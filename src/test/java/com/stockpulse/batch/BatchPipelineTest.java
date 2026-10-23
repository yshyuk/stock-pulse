package com.stockpulse.batch;

import com.stockpulse.analysis.AnalysisResult;
import com.stockpulse.analysis.ReportAnalyzer;
import com.stockpulse.collector.CollectionResult;
import com.stockpulse.collector.CollectorService;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.credential.CredentialExpiryChecker;
import com.stockpulse.domain.Report;
import com.stockpulse.domain.ReportFormat;
import com.stockpulse.domain.StockMetric;
import com.stockpulse.intraday.review.IntradayReviewReporter;
import com.stockpulse.market.MarketStage;
import com.stockpulse.notification.NotificationMessage;
import com.stockpulse.notification.NotificationService;
import com.stockpulse.plan.PlanJsonSerializer;
import com.stockpulse.plan.PlanStage;
import com.stockpulse.plan.TradingPlan;
import com.stockpulse.plan.dispatch.DispatchResult;
import com.stockpulse.plan.dispatch.PlanDispatcher;
import com.stockpulse.plan.rule.PlanRule;
import com.stockpulse.plan.rule.RuleCondition;
import com.stockpulse.plan.rule.RuleEvaluator;
import com.stockpulse.processor.DisclosureProcessor;
import com.stockpulse.processor.MetricProcessor;
import com.stockpulse.report.ReportService;
import com.stockpulse.report.dispatch.ReportDispatchResult;
import com.stockpulse.report.dispatch.ReportDispatcher;
import com.stockpulse.report.dispatch.ReportPayload;
import com.stockpulse.report.dispatch.ReportPayloadBuilder;
import com.stockpulse.screening.ScreeningStage;
import com.stockpulse.storage.ReportStore;
import com.stockpulse.timeseries.DailyStockSnapshot;
import com.stockpulse.timeseries.DerivedMetrics;
import com.stockpulse.timeseries.SnapshotService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pipeline-level wiring, as opposed to the stage unit tests: that screening actually narrows
 * what reaches the report WITHOUT narrowing what reaches the plan, and that a run which collected
 * nothing is loud on a trading day and quiet on a weekend.
 *
 * <p>{@link ScreeningStage} and {@link EmptyRunPolicy} are REAL here on purpose — they are the
 * behaviour under test, and mocking them would leave the wiring itself unverified.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatchPipelineTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 9);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 9, 12);

    @Mock private CollectorService collectorService;
    @Mock private MetricProcessor metricProcessor;
    @Mock private DisclosureProcessor disclosureProcessor;
    @Mock private SnapshotService snapshotService;
    @Mock private MarketStage marketStage;
    @Mock private PlanStage planStage;
    @Mock private PlanDispatcher planDispatcher;
    @Mock private IntradayReviewReporter intradayReviewReporter;
    @Mock private ReportService reportService;
    @Mock private ReportAnalyzer reportAnalyzer;
    @Mock private ReportStore reportStore;
    @Mock private NotificationService notificationService;

    /** 2026-09-09 15:00 KST — "today" for the rerun guard is WEDNESDAY. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T06:00:00Z"), ZoneOffset.UTC);
    /** The session KRX answered for: the trading day before WEDNESDAY. */
    private static final LocalDate SESSION = LocalDate.of(2026, 9, 8);

    private StockPulseProperties properties;
    private ReportPayloadBuilder payloadBuilder;
    private MockWebServer reportServer;
    private BatchPipeline pipeline;

    @BeforeEach
    void setUp() throws IOException {
        properties = new StockPulseProperties();
        payloadBuilder = new ReportPayloadBuilder(CLOCK);
        reportServer = new MockWebServer();
        reportServer.start();
        StockPulseProperties.ReportDispatch cfg = properties.getReportDispatch();
        cfg.setUrl(reportServer.url("/v1/signals/reports").toString());
        cfg.setApiKey("test-report-key");
        cfg.setMaxAttempts(2);
        cfg.setRetryDelayMs(0);
        cfg.setTimeoutSeconds(5);
        // enabled stays false (the default) unless a test turns it on

        pipeline = pipelineWith(planDispatcher,
                new ReportDispatcher(properties, payloadBuilder, WebClient.builder().build()));

        when(collectorService.collectAll(any())).thenReturn(new CollectionResult(List.of(), List.of()));
        when(disclosureProcessor.process(anyList())).thenReturn(List.of());
        when(marketStage.recordFrom(any(), anyList())).thenReturn(List.of());
        when(reportAnalyzer.analyze(any())).thenReturn(AnalysisResult.none());
        when(reportService.generate(anyList(), anyList(), any())).thenReturn(report());
    }

    @AfterEach
    void tearDown() throws IOException {
        reportServer.shutdown();
    }

    private BatchPipeline pipelineWith(PlanDispatcher plans, ReportDispatcher reports) {
        ScreeningStage screeningStage = new ScreeningStage(properties, new RuleEvaluator());
        return new BatchPipeline(new CredentialExpiryChecker(properties),
                collectorService, metricProcessor, disclosureProcessor,
                snapshotService, new EmptyRunPolicy(), marketStage, planStage, plans,
                intradayReviewReporter, screeningStage, reportService, reportAnalyzer,
                payloadBuilder, reports,
                List.of(reportStore), notificationService, properties, CLOCK);
    }

    @Test
    void screeningNarrowsTheReportButThePlanStillSeesEveryStock() throws Exception {
        enableScreening();
        // Only 000002 surges; the other two are quiet.
        when(metricProcessor.process(anyList()))
                .thenReturn(List.of(metric("000001"), metric("000002"), metric("000003")));
        when(snapshotService.record(any(), anyList())).thenReturn(List.of(
                snapshot("000001", 1.0), snapshot("000002", 6.0), snapshot("000003", 0.5)));

        pipeline.run(WEDNESDAY);

        ArgumentCaptor<List<StockMetric>> reported = ArgumentCaptor.forClass(List.class);
        verify(reportService).generate(reported.capture(), anyList(), any());
        assertThat(reported.getValue()).extracting(StockMetric::getSymbol).containsExactly("000002");

        // The plan must NOT inherit the report's cap — screening is a cost control for the
        // token-billed path only, and a capped report silently dropping plan candidates would be
        // extremely hard to notice.
        ArgumentCaptor<List<DailyStockSnapshot>> planned = ArgumentCaptor.forClass(List.class);
        verify(planStage).generateAndStore(any(), planned.capture(), any());
        assertThat(planned.getValue()).hasSize(3);
    }

    @Test
    void collectingNoPricesOnATradingDayFailsAndNotifies() {
        when(metricProcessor.process(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> pipeline.run(WEDNESDAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No price data collected");

        ArgumentCaptor<NotificationMessage> sent = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationService).broadcast(sent.capture());
        assertThat(sent.getValue().getSeverity()).isEqualTo(NotificationMessage.Severity.FAILURE);
        verify(reportStore, never()).save(any());
    }

    @Test
    void collectingNoPricesOnAWeekendExitsQuietly() throws Exception {
        when(metricProcessor.process(anyList())).thenReturn(List.of());

        pipeline.run(SATURDAY);

        // Expected on a non-trading day: no report, no plan, and above all no failure alert —
        // weekly false alarms are how operators learn to ignore real ones.
        verify(reportStore, never()).save(any());
        verify(notificationService, never()).broadcast(any());
        verify(planStage, never()).generateAndStore(any(), anyList(), any());
    }

    // ── report dispatch (5c) ────────────────────────────────────────────────

    @Test
    void reportIsDispatchedAfterStoringWithTheStoredContent() throws Exception {
        properties.getReportDispatch().setEnabled(true);
        tradingDayWithSession(SESSION);
        // the analysis seam changes the report; the dispatched body must be the FINAL one
        when(reportAnalyzer.analyze(any())).thenReturn(AnalysisResult.builder().performed(true).analysis("2차 분석 문단").build());
        ReportDispatcher reports = mock(ReportDispatcher.class);
        when(reports.isEnabled()).thenReturn(true);
        when(reports.dispatch(any())).thenReturn(new ReportDispatchResult(ReportDispatchResult.Status.CREATED, 1, null));

        pipelineWith(planDispatcher, reports).run(WEDNESDAY);

        ArgumentCaptor<Report> stored = ArgumentCaptor.forClass(Report.class);
        ArgumentCaptor<ReportPayload> sent = ArgumentCaptor.forClass(ReportPayload.class);
        InOrder order = inOrder(reportStore, reports, notificationService);
        order.verify(reportStore).save(stored.capture());
        order.verify(reports).dispatch(sent.capture());
        order.verify(notificationService).broadcast(any());

        assertThat(stored.getValue().getContent()).contains("2차 분석 문단");
        assertThat(sent.getValue().body()).isEqualTo(stored.getValue().getContent());
        assertThat(sent.getValue().tradeDate()).isEqualTo(WEDNESDAY);
        // dataAsOf comes from the collected session (09-08 15:30 KST), not the run date
        assertThat(sent.getValue().dataAsOf()).isEqualTo(Instant.parse("2026-09-08T06:30:00Z"));
    }

    @Test
    void createdReportIsShownInSuccessMessage() throws Exception {
        properties.getReportDispatch().setEnabled(true);
        tradingDayWithSession(SESSION);
        reportServer.enqueue(new MockResponse().setResponseCode(202));

        pipeline.run(WEDNESDAY);

        RecordedRequest sent = reportServer.takeRequest();
        assertThat(sent.getBody().readString(StandardCharsets.UTF_8))
                .contains("\"reportId\":\"pulse-2026-09-09\"", "\"sessionDate\":\"2026-09-08\"");
        assertThat(successBody()).contains("리포트 전송", "CREATED");
    }

    @Test
    void conflictIsShownInSuccessMessage() throws Exception {
        // Review Focus 5: same-date re-run with a changed body → api 409. Not retried, batch succeeds,
        // and the operator sees CONFLICT in the success message.
        properties.getReportDispatch().setEnabled(true);
        tradingDayWithSession(SESSION);
        reportServer.enqueue(new MockResponse().setResponseCode(409));
        reportServer.enqueue(new MockResponse().setResponseCode(202));

        pipeline.run(WEDNESDAY);

        assertThat(reportServer.getRequestCount()).isEqualTo(1);
        assertThat(successBody()).contains("CONFLICT");
    }

    @Test
    void reportDispatchFailureDoesNotFailBatch() throws Exception {
        properties.getReportDispatch().setEnabled(true);
        tradingDayWithSession(SESSION);
        reportServer.enqueue(new MockResponse().setResponseCode(503));
        reportServer.enqueue(new MockResponse().setResponseCode(503));

        pipeline.run(WEDNESDAY); // must not throw

        assertThat(reportServer.getRequestCount()).isEqualTo(2);
        assertThat(successBody()).contains("FAILED");
        verify(reportStore).save(any());
    }

    @Test
    void reportDispatcherThrowingDoesNotFailBatch() throws Exception {
        tradingDayWithSession(SESSION);
        ReportDispatcher reports = mock(ReportDispatcher.class);
        when(reports.isEnabled()).thenReturn(true);
        when(reports.dispatch(any())).thenThrow(new IllegalStateException("boom"));

        pipelineWith(planDispatcher, reports).run(WEDNESDAY);

        assertThat(successBody()).contains("FAILED", "boom");
    }

    @Test
    void degradedRunSkipsReportDispatch() throws Exception {
        properties.getReportDispatch().setEnabled(true);
        stubTradingDay();
        when(collectorService.collectAll(any()))
                .thenReturn(new CollectionResult(List.of(), List.of("krx-all"), SESSION));

        pipeline.run(WEDNESDAY);

        assertThat(reportServer.getRequestCount()).isZero();
        assertThat(successBody()).contains("SKIPPED", "degraded");
    }

    @Test
    void missingSessionDateSkipsReportDispatch() throws Exception {
        // e.g. KRX off and only the realtime Naver feed ran — the session is unknown, never guessed
        properties.getReportDispatch().setEnabled(true);
        tradingDayWithSession(null);

        pipeline.run(WEDNESDAY);

        assertThat(reportServer.getRequestCount()).isZero();
        assertThat(successBody()).contains("SKIPPED", "session date unavailable");
    }

    @Test
    void rerunOfPastDateSkipsReportDispatch() throws Exception {
        // --stockpulse.run-date=<past>: the payload would carry TODAY's reportId with old data
        properties.getReportDispatch().setEnabled(true);
        tradingDayWithSession(LocalDate.of(2026, 9, 7));

        pipeline.run(LocalDate.of(2026, 9, 8));

        assertThat(reportServer.getRequestCount()).isZero();
        assertThat(successBody()).contains("SKIPPED", "rerun of past date");
    }

    @Test
    void disabledReportDispatchAddsNoLine() throws Exception {
        tradingDayWithSession(SESSION);

        pipeline.run(WEDNESDAY);

        assertThat(reportServer.getRequestCount()).isZero();
        assertThat(successBody()).doesNotContain("리포트 전송");
    }

    @Test
    void v2DispatchUnaffected() throws Exception {
        // Only report-dispatch is on: the v2 plan hand-off must still be skipped (its own block is off)
        properties.getReportDispatch().setEnabled(true);
        properties.getPlan().setMode(TradingPlan.MODE_SIGNAL);
        tradingDayWithSession(SESSION);
        TradingPlan plan = TradingPlan.builder().planDate(WEDNESDAY).mode(TradingPlan.MODE_SIGNAL)
                .candidates(List.of()).warnings(List.of()).build();
        when(planStage.generateAndStore(any(), anyList(), any())).thenReturn(plan);
        reportServer.enqueue(new MockResponse().setResponseCode(202));
        PlanDispatcher plans = spy(new PlanDispatcher(properties, new PlanJsonSerializer(), WebClient.builder().build()));
        ReportDispatcher reports = new ReportDispatcher(properties, payloadBuilder, WebClient.builder().build());

        pipelineWith(plans, reports).run(WEDNESDAY);

        assertThat(plans.isEnabled()).isFalse();
        ArgumentCaptor<TradingPlan> handed = ArgumentCaptor.forClass(TradingPlan.class);
        verify(plans).dispatch(handed.capture());
        assertThat(plans.dispatch(handed.getValue()).status()).isEqualTo(DispatchResult.Status.SKIPPED);
        // exactly one request went out, and it was the report — not a plan
        assertThat(reportServer.getRequestCount()).isEqualTo(1);
        assertThat(reportServer.takeRequest().getPath()).isEqualTo("/v1/signals/reports");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A normal weekday run whose collector reported {@code session} (null = no source reported one). */
    private void tradingDayWithSession(LocalDate session) {
        stubTradingDay();
        when(collectorService.collectAll(any()))
                .thenReturn(new CollectionResult(List.of(), List.of(), session));
    }

    private void stubTradingDay() {
        when(metricProcessor.process(anyList())).thenReturn(List.of(metric("000001")));
        when(snapshotService.record(any(), anyList())).thenReturn(List.of(snapshot("000001", 1.0)));
    }

    /** Body of the single SUCCESS broadcast. */
    private String successBody() {
        ArgumentCaptor<NotificationMessage> sent = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationService).broadcast(sent.capture());
        assertThat(sent.getValue().getSeverity()).isEqualTo(NotificationMessage.Severity.SUCCESS);
        return sent.getValue().getBody();
    }

    private void enableScreening() {
        RuleCondition c = new RuleCondition();
        c.setMetric("changeRate1d");
        c.setOp(RuleCondition.Op.GTE);
        c.setValue(new BigDecimal("5.0"));
        PlanRule rule = new PlanRule();
        rule.setId("surge-up");
        rule.setConditions(List.of(c));

        properties.getScreening().setEnabled(true);
        properties.getScreening().setRules(List.of(rule));
    }

    private StockMetric metric(String symbol) {
        return StockMetric.builder().symbol(symbol).name("종목" + symbol)
                .price(new BigDecimal("10000")).volume(200_000L).build();
    }

    private DailyStockSnapshot snapshot(String symbol, double changeRate1d) {
        return DailyStockSnapshot.builder()
                .symbol(symbol).name("종목" + symbol).tradeDate(WEDNESDAY)
                .price(new BigDecimal("10000")).volume(200_000L).source("test")
                .derived(DerivedMetrics.builder()
                        .changeRate1d(BigDecimal.valueOf(changeRate1d)).build())
                .build();
    }

    private Report report() {
        return Report.builder().reportDate(WEDNESDAY).format(ReportFormat.MARKDOWN)
                .content("# report").generatedAt(Instant.parse("2026-09-09T06:00:00Z")).build();
    }
}

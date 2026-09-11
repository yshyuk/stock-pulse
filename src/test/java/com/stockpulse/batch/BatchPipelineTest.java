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
import com.stockpulse.plan.PlanStage;
import com.stockpulse.plan.dispatch.PlanDispatcher;
import com.stockpulse.plan.rule.PlanRule;
import com.stockpulse.plan.rule.RuleCondition;
import com.stockpulse.plan.rule.RuleEvaluator;
import com.stockpulse.processor.DisclosureProcessor;
import com.stockpulse.processor.MetricProcessor;
import com.stockpulse.report.ReportService;
import com.stockpulse.screening.ScreeningStage;
import com.stockpulse.storage.ReportStore;
import com.stockpulse.timeseries.DailyStockSnapshot;
import com.stockpulse.timeseries.DerivedMetrics;
import com.stockpulse.timeseries.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
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

    private StockPulseProperties properties;
    private BatchPipeline pipeline;

    @BeforeEach
    void setUp() {
        properties = new StockPulseProperties();
        ScreeningStage screeningStage = new ScreeningStage(properties, new RuleEvaluator());

        pipeline = new BatchPipeline(new CredentialExpiryChecker(properties),
                collectorService, metricProcessor, disclosureProcessor,
                snapshotService, new EmptyRunPolicy(), marketStage, planStage, planDispatcher,
                intradayReviewReporter, screeningStage, reportService, reportAnalyzer,
                List.of(reportStore), notificationService, properties,
                Clock.fixed(Instant.parse("2026-09-09T06:00:00Z"), ZoneOffset.UTC));

        when(collectorService.collectAll(any())).thenReturn(new CollectionResult(List.of(), List.of()));
        when(disclosureProcessor.process(anyList())).thenReturn(List.of());
        when(marketStage.recordFrom(any(), anyList())).thenReturn(List.of());
        when(reportAnalyzer.analyze(any())).thenReturn(AnalysisResult.none());
        when(reportService.generate(anyList(), anyList(), any())).thenReturn(report());
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

    // ── helpers ─────────────────────────────────────────────────────────────

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

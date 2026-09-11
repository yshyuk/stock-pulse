package com.stockpulse.batch;

import com.stockpulse.analysis.AnalysisResult;
import com.stockpulse.analysis.ReportAnalyzer;
import com.stockpulse.collector.CollectionResult;
import com.stockpulse.collector.CollectorService;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.credential.CredentialExpiryChecker;
import com.stockpulse.domain.Disclosure;
import com.stockpulse.domain.RawData;
import com.stockpulse.domain.Report;
import com.stockpulse.domain.StockMetric;
import com.stockpulse.market.DailyMarketSnapshot;
import com.stockpulse.market.MarketStage;
import com.stockpulse.notification.NotificationMessage;
import com.stockpulse.notification.NotificationService;
import com.stockpulse.processor.DisclosureProcessor;
import com.stockpulse.processor.MetricProcessor;
import com.stockpulse.intraday.review.IntradayReviewReporter;
import com.stockpulse.plan.MarketContext;
import com.stockpulse.plan.PlanStage;
import com.stockpulse.plan.TradingPlan;
import com.stockpulse.plan.dispatch.DispatchResult;
import com.stockpulse.plan.dispatch.PlanDispatcher;
import com.stockpulse.report.ReportService;
import com.stockpulse.screening.ScreeningResult;
import com.stockpulse.screening.ScreeningStage;
import com.stockpulse.storage.ReportStore;
import com.stockpulse.timeseries.DailyStockSnapshot;
import com.stockpulse.timeseries.SnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Orchestrates the dawn pipeline as a single pass:
 *
 * <pre>
 *   collect -> process -> [empty-run guard] -> render report -> (analyze) -> store -> notify
 * </pre>
 *
 * <p>The empty-run guard is what stops a misconfigured run from succeeding quietly: no prices on
 * a trading day fails the batch, no prices on a weekend exits without a report.
 *
 * <p>On success it broadcasts a SUCCESS notification with the report; on any failure it
 * broadcasts a FAILURE notification (no silent failures) and rethrows so the caller can
 * exit with a non-zero code.
 */
@Slf4j
@Component
public class BatchPipeline {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CredentialExpiryChecker credentialExpiryChecker;
    private final CollectorService collectorService;
    private final MetricProcessor metricProcessor;
    private final DisclosureProcessor disclosureProcessor;
    private final SnapshotService snapshotService;
    private final EmptyRunPolicy emptyRunPolicy;
    private final MarketStage marketStage;
    private final PlanStage planStage;
    private final PlanDispatcher planDispatcher;
    private final IntradayReviewReporter intradayReviewReporter;
    private final ScreeningStage screeningStage;
    private final ReportService reportService;
    private final ReportAnalyzer reportAnalyzer;
    private final List<ReportStore> reportStores;
    private final NotificationService notificationService;
    private final StockPulseProperties properties;
    private final Clock clock;

    public BatchPipeline(CredentialExpiryChecker credentialExpiryChecker,
                         CollectorService collectorService,
                         MetricProcessor metricProcessor,
                         DisclosureProcessor disclosureProcessor,
                         SnapshotService snapshotService,
                         EmptyRunPolicy emptyRunPolicy,
                         MarketStage marketStage,
                         PlanStage planStage,
                         PlanDispatcher planDispatcher,
                         IntradayReviewReporter intradayReviewReporter,
                         ScreeningStage screeningStage,
                         ReportService reportService,
                         ReportAnalyzer reportAnalyzer,
                         List<ReportStore> reportStores,
                         NotificationService notificationService,
                         StockPulseProperties properties,
                         Clock clock) {
        this.credentialExpiryChecker = credentialExpiryChecker;
        this.collectorService = collectorService;
        this.metricProcessor = metricProcessor;
        this.disclosureProcessor = disclosureProcessor;
        this.snapshotService = snapshotService;
        this.emptyRunPolicy = emptyRunPolicy;
        this.marketStage = marketStage;
        this.planStage = planStage;
        this.planDispatcher = planDispatcher;
        this.intradayReviewReporter = intradayReviewReporter;
        this.screeningStage = screeningStage;
        this.reportService = reportService;
        this.reportAnalyzer = reportAnalyzer;
        this.reportStores = reportStores;
        this.notificationService = notificationService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs the whole pipeline once.
     *
     * @throws Exception if any stage fails (after a FAILURE notification has been sent)
     */
    public void run() throws Exception {
        run(LocalDate.now(clock));
    }

    /**
     * Runs the whole pipeline once for an explicit run date. A re-run for a past date
     * (via {@code --stockpulse.run-date}) re-computes and upserts that day's snapshots
     * and report idempotently.
     *
     * @throws Exception if any stage fails (after a FAILURE notification has been sent)
     */
    public void run(LocalDate runDate) throws Exception {
        Instant start = Instant.now();
        log.info("==== StockPulse batch pipeline START (runDate={}) ====", runDate);
        try {
            // 0) credentials: advance notice, before anything that could fail because of one.
            //    Runs on every day including weekends — a key expiring on a Saturday still needs
            //    the same lead time, and this has nothing to do with whether the market opened.
            notifyCredentialWarnings(runDate);

            // 1) collect
            CollectionResult collected = collectorService.collectAll(runDate);
            List<RawData> raw = collected.items();
            boolean degraded = collected.hasRequiredFailure();

            // 2) process (objective metrics + disclosures)
            List<StockMetric> metrics = metricProcessor.process(raw);
            List<Disclosure> disclosures = disclosureProcessor.process(raw);

            // 2a) no prices at all? Decide BEFORE doing any further work. A run that collected
            //     nothing must never look like a normal run — that is exactly how hard-coded
            //     sample data once reached a production report unnoticed.
            switch (emptyRunPolicy.decide(runDate, metrics.size())) {
                case SKIP -> {
                    log.info("==== StockPulse batch SKIPPED: {} is a non-trading day and no prices "
                            + "were collected (expected) ====", runDate);
                    return;
                }
                case FAIL -> throw new IllegalStateException(
                        "No price data collected for trading day " + runDate
                                + " — check that a price source is enabled (NAVER_ENABLED / KRX_ALL_ENABLED). "
                                + "Collected " + raw.size() + " raw item(s) from all sources.");
                case PROCEED -> { /* normal path */ }
            }

            // 2b) time-series: upsert daily snapshots + derived metrics (idempotent on runDate)
            List<DailyStockSnapshot> snapshots = snapshotService.record(runDate, metrics);

            // 2b') market indicators: upsert index/FX/supply-demand snapshots + build plan context
            List<DailyMarketSnapshot> marketSnapshots = marketStage.recordFrom(runDate, raw);
            MarketContext marketContext = MarketContext.from(marketSnapshots);

            // 2c) plan: deterministic rule-based signal candidates (plan-only, no orders).
            //     Skipped when a required source failed (F-13) — no plan on untrustworthy data.
            //     Other plan failures are non-fatal (enrichment): the batch still succeeds.
            TradingPlan plan = degraded ? null : planStage.generateAndStore(runDate, snapshots, marketContext);
            if (degraded) {
                log.warn("[pipeline] degraded run (failed required sources: {}) — plan skipped",
                        collected.failedRequiredSources());
            }

            // 2d) screening: narrow what reaches the (token-billed) report and second-stage
            //      analysis. Cost control only — the plan stage above deliberately still sees
            //      every snapshot, so a capped report never silently drops a plan candidate.
            ScreeningResult screened = screeningStage.select(snapshots, metrics);

            // 3) render report (Markdown), then append market context / plan / intraday-review sections
            Report report = reportService.generate(screened.metrics(), disclosures, runDate);
            report = appendSection(report, screeningStage.summaryMarkdown(screened));
            report = appendSection(report, marketStage.summaryMarkdown(marketSnapshots));
            // M4 feedback loop: yesterday's intraday execution results in this morning's report.
            report = appendSection(report, intradayReviewReporter.morningSection(runDate));
            if (degraded) {
                report = withDegradedWarning(report, collected.failedRequiredSources());
            }
            if (plan != null) {
                report = withPlanSummary(report, planStage.summaryMarkdown(plan));
            }

            // 4) second-stage analysis seam (NoOp by default; Claude API when enabled).
            //    If analysis was performed, fold its text into the report so storage and
            //    notification carry it too.
            AnalysisResult analysis = reportAnalyzer.analyze(report);
            log.info("[pipeline] analysis performed={}", analysis.isPerformed());
            if (analysis.isPerformed()) {
                report = withAnalysis(report, analysis);
                // Attach the same analysis to the plan as an advisory (reference only; the
                // deterministic execution fields stay untouched). Enrichment — never fatal.
                if (plan != null) {
                    plan = planStage.attachAdvisory(plan, "claude", analysis.getAnalysis(), Instant.now(clock));
                }
            }

            // 5) store to all sinks (file + db)
            for (ReportStore store : reportStores) {
                store.save(report);
            }

            // 5b) hand the finished plan (advisory included, for audit) to the external execution
            //     consumer. Best-effort by design: a delivery failure means "no agent signals
            //     downstream today", never a failed batch and never a forced order.
            DispatchResult dispatch = planDispatcher.dispatch(plan);

            // 6) notify success — surfacing a failed hand-off, which is otherwise invisible
            notificationService.broadcast(successMessage(report, screened, start, dispatch));

            log.info("==== StockPulse batch pipeline SUCCESS in {} ====", elapsed(start));
        } catch (Exception e) {
            log.error("==== StockPulse batch pipeline FAILED: {} ====", e.getMessage(), e);
            // Never fail silently.
            safeNotifyFailure(e, start);
            throw e;
        }
    }

    /**
     * Sends credential expiry warnings as their own message.
     *
     * <p>Deliberately not folded into the success notification: that one carries the whole report
     * and a line about a key would be lost in it. Never fatal — failing the batch because we
     * could not warn about a future problem would be its own bug.
     */
    private void notifyCredentialWarnings(LocalDate runDate) {
        try {
            List<String> warnings = credentialExpiryChecker.warnings(runDate);
            if (warnings.isEmpty()) {
                return;
            }
            notificationService.broadcast(NotificationMessage.builder()
                    .severity(NotificationMessage.Severity.WARNING)
                    .title("🔑 StockPulse 인증키 만료 예정")
                    .body(String.join("\n\n", warnings)
                            + "\n\n재발급: https://openapi.krx.co.kr (마이페이지 > API 인증키 신청)\n"
                            + "인증키 발급 후 '서비스 이용 > API 이용신청'까지 해야 실제로 호출됩니다.")
                    .build());
        } catch (Exception e) {
            log.error("[pipeline] credential expiry check failed: {}", e.getMessage(), e);
        }
    }

    /** Returns a copy of the report with an extra section appended (no-op for blank text). */
    private Report appendSection(Report report, String section) {
        if (section == null || section.isBlank()) {
            return report;
        }
        return Report.builder()
                .reportDate(report.getReportDate())
                .format(report.getFormat())
                .content(report.getContent() + section)
                .generatedAt(report.getGeneratedAt())
                .build();
    }

    /** Returns a copy of the report with a degraded-run warning section appended. */
    private Report withDegradedWarning(Report report, List<String> failedSources) {
        String warning = "\n\n---\n## ⚠️ 데이터 경고 (일부 소스 실패)\n\n"
                + "필수 데이터 소스가 실패해 이번 실행은 **degraded** 상태입니다. "
                + "신뢰할 수 없는 데이터로 플랜을 만들지 않도록 **오늘의 플랜은 생성되지 않았습니다**.\n\n"
                + "- 실패한 필수 소스: " + String.join(", ", failedSources) + "\n";
        return Report.builder()
                .reportDate(report.getReportDate())
                .format(report.getFormat())
                .content(report.getContent() + warning)
                .generatedAt(report.getGeneratedAt())
                .build();
    }

    /** Returns a copy of the report with the plan summary section appended. */
    private Report withPlanSummary(Report report, String summaryMarkdown) {
        String enriched = report.getContent() + summaryMarkdown;
        return Report.builder()
                .reportDate(report.getReportDate())
                .format(report.getFormat())
                .content(enriched)
                .generatedAt(report.getGeneratedAt())
                .build();
    }

    /** Returns a copy of the report with the second-stage analysis appended as a section. */
    private Report withAnalysis(Report report, AnalysisResult analysis) {
        String enriched = report.getContent()
                + "\n\n---\n## 2차 분석 (Claude)\n\n" + analysis.getAnalysis() + "\n";
        return Report.builder()
                .reportDate(report.getReportDate())
                .format(report.getFormat())
                .content(enriched)
                .generatedAt(report.getGeneratedAt())
                .build();
    }

    private NotificationMessage successMessage(Report report, ScreeningResult screened, Instant start,
                                               DispatchResult dispatch) {
        Path reportFile = Path.of(properties.getReportDir())
                .resolve(report.getReportDate().format(DATE) + report.getFormat().fileExtension());
        String title = "✅ StockPulse 리포트 생성 완료 (" + report.getReportDate().format(DATE) + ")";
        String body = "종목 " + stockCountNotice(screened) + ", 소요 " + elapsed(start)
                + dispatchNotice(dispatch) + "\n\n" + report.getContent();
        return NotificationMessage.builder()
                .severity(NotificationMessage.Severity.SUCCESS)
                .title(title)
                .body(body)
                .attachmentPath(reportFile.toAbsolutePath().toString())
                .build();
    }

    /** "N건" normally; "전체 N건 중 M건" once screening is actually narrowing the list. */
    private String stockCountNotice(ScreeningResult screened) {
        int shown = screened.metrics().size();
        if (shown == screened.totalEvaluated()) {
            return shown + "건";
        }
        return "전체 " + screened.totalEvaluated() + "건 중 " + shown + "건";
    }

    /**
     * One line about the plan hand-off, but only when it FAILED. A successful or skipped
     * dispatch is the expected state and does not need to compete for attention in the report.
     */
    private String dispatchNotice(DispatchResult dispatch) {
        if (dispatch == null || !dispatch.isFailed()) {
            return "";
        }
        return "\n\n⚠️ 플랜 전송 실패 (" + dispatch.attempts() + "회 시도) — 오늘 실행 시스템에 "
                + "agent 신호가 전달되지 않았습니다. 원인: " + dispatch.reason();
    }

    private void safeNotifyFailure(Exception e, Instant start) {
        try {
            String title = "❌ StockPulse 배치 실패";
            String body = "소요 " + elapsed(start) + "\n원인: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            notificationService.broadcast(NotificationMessage.builder()
                    .severity(NotificationMessage.Severity.FAILURE)
                    .title(title)
                    .body(body)
                    .build());
        } catch (Exception notifyError) {
            log.error("[pipeline] failed to send FAILURE notification: {}", notifyError.getMessage(), notifyError);
        }
    }

    private String elapsed(Instant start) {
        Duration d = Duration.between(start, Instant.now());
        return d.toMillis() + "ms";
    }
}

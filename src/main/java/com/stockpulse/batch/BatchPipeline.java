package com.stockpulse.batch;

import com.stockpulse.analysis.AnalysisResult;
import com.stockpulse.analysis.ReportAnalyzer;
import com.stockpulse.collector.CollectorService;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.RawData;
import com.stockpulse.domain.Report;
import com.stockpulse.domain.StockMetric;
import com.stockpulse.notification.NotificationMessage;
import com.stockpulse.notification.NotificationService;
import com.stockpulse.processor.MetricProcessor;
import com.stockpulse.report.ReportService;
import com.stockpulse.storage.ReportStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Orchestrates the dawn pipeline as a single pass:
 *
 * <pre>
 *   collect -> process -> render report -> (analyze: NoOp) -> store(file+db) -> notify
 * </pre>
 *
 * <p>On success it broadcasts a SUCCESS notification with the report; on any failure it
 * broadcasts a FAILURE notification (no silent failures) and rethrows so the caller can
 * exit with a non-zero code.
 */
@Slf4j
@Component
public class BatchPipeline {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CollectorService collectorService;
    private final MetricProcessor metricProcessor;
    private final ReportService reportService;
    private final ReportAnalyzer reportAnalyzer;
    private final List<ReportStore> reportStores;
    private final NotificationService notificationService;
    private final StockPulseProperties properties;

    public BatchPipeline(CollectorService collectorService,
                         MetricProcessor metricProcessor,
                         ReportService reportService,
                         ReportAnalyzer reportAnalyzer,
                         List<ReportStore> reportStores,
                         NotificationService notificationService,
                         StockPulseProperties properties) {
        this.collectorService = collectorService;
        this.metricProcessor = metricProcessor;
        this.reportService = reportService;
        this.reportAnalyzer = reportAnalyzer;
        this.reportStores = reportStores;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    /**
     * Runs the whole pipeline once.
     *
     * @throws Exception if any stage fails (after a FAILURE notification has been sent)
     */
    public void run() throws Exception {
        Instant start = Instant.now();
        log.info("==== StockPulse batch pipeline START ====");
        try {
            // 1) collect
            List<RawData> raw = collectorService.collectAll();

            // 2) process (objective metrics only)
            List<StockMetric> metrics = metricProcessor.process(raw);

            // 3) render report (Markdown)
            Report report = reportService.generate(metrics);

            // 4) second-stage analysis seam (NoOp today; future: Claude API)
            AnalysisResult analysis = reportAnalyzer.analyze(report);
            log.info("[pipeline] analysis performed={}", analysis.isPerformed());

            // 5) store to all sinks (file + db)
            for (ReportStore store : reportStores) {
                store.save(report);
            }

            // 6) notify success
            notificationService.broadcast(successMessage(report, metrics.size(), start));

            log.info("==== StockPulse batch pipeline SUCCESS in {} ====", elapsed(start));
        } catch (Exception e) {
            log.error("==== StockPulse batch pipeline FAILED: {} ====", e.getMessage(), e);
            // Never fail silently.
            safeNotifyFailure(e, start);
            throw e;
        }
    }

    private NotificationMessage successMessage(Report report, int stockCount, Instant start) {
        Path reportFile = Path.of(properties.getReportDir())
                .resolve(report.getReportDate().format(DATE) + report.getFormat().fileExtension());
        String title = "✅ StockPulse 리포트 생성 완료 (" + report.getReportDate().format(DATE) + ")";
        String body = "종목 " + stockCount + "건, 소요 " + elapsed(start) + "\n\n" + report.getContent();
        return NotificationMessage.builder()
                .severity(NotificationMessage.Severity.SUCCESS)
                .title(title)
                .body(body)
                .attachmentPath(reportFile.toAbsolutePath().toString())
                .build();
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

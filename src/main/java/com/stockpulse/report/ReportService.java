package com.stockpulse.report;

import com.stockpulse.domain.Report;
import com.stockpulse.domain.ReportFormat;
import com.stockpulse.domain.ReportModel;
import com.stockpulse.domain.StockMetric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Report stage: builds a {@link ReportModel} from metrics and renders it via the
 * appropriate {@link ReportRenderer}.
 *
 * <p>Renderers are looked up by {@link ReportFormat}, so adding a JsonRenderer later
 * needs no change here. Today only MARKDOWN is wired.
 */
@Slf4j
@Service
public class ReportService {

    private final Map<ReportFormat, ReportRenderer> renderers = new EnumMap<>(ReportFormat.class);
    private final Clock clock;

    public ReportService(List<ReportRenderer> rendererList, Clock clock) {
        this.clock = clock;
        for (ReportRenderer r : rendererList) {
            renderers.put(r.format(), r);
        }
        log.info("[report] registered renderers: {}", renderers.keySet());
    }

    /** Generate a report in the default (Markdown) format. */
    public Report generate(List<StockMetric> metrics) {
        return generate(metrics, ReportFormat.MARKDOWN);
    }

    public Report generate(List<StockMetric> metrics, ReportFormat format) {
        ReportRenderer renderer = renderers.get(format);
        if (renderer == null) {
            throw new IllegalStateException("No ReportRenderer registered for format " + format);
        }

        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);

        ReportModel model = ReportModel.builder()
                .reportDate(today)
                .generatedAt(now)
                .metrics(metrics)
                .build();

        String content = renderer.render(model);
        log.info("[report] rendered {} report ({} chars) for {}", format, content.length(), today);

        return Report.builder()
                .reportDate(today)
                .format(format)
                .content(content)
                .generatedAt(now)
                .build();
    }
}

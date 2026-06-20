package com.stockpulse.analysis;

import com.stockpulse.domain.Report;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Default {@link ReportAnalyzer}: does nothing and passes the report through unchanged.
 *
 * <p>This keeps the current workflow semi-automatic (human pastes the report into Claude).
 *
 * <p>TODO: to automate second-stage analysis, add an {@code AnthropicReportAnalyzer} that
 * calls api.anthropic.com (use ANTHROPIC_API_KEY + the shared WebClient) and mark it
 * {@code @Primary} so it replaces this NoOp without touching the pipeline.
 */
@Slf4j
@Component
public class NoOpReportAnalyzer implements ReportAnalyzer {

    @Override
    public AnalysisResult analyze(Report report) {
        log.info("[analysis] NoOp analyzer — second-stage analysis is performed manually for now");
        return AnalysisResult.none();
    }
}

package com.stockpulse.analysis;

import com.stockpulse.domain.Report;

/**
 * ★ Extension point for SECOND-STAGE analysis, inserted right after report generation.
 *
 * <p>Today the workflow is semi-automatic: a human takes the rendered report and pastes it
 * into Claude by hand. There is intentionally no Claude API call in this codebase.
 *
 * <p>This seam exists so that, in the future, the batch can call the Claude API
 * (api.anthropic.com) to produce the second-stage analysis automatically — just provide a
 * new implementation and register it as the primary bean. The default
 * {@link NoOpReportAnalyzer} changes nothing.
 */
public interface ReportAnalyzer {

    /**
     * Analyze a freshly generated report.
     *
     * @return the analysis outcome (the NoOp implementation returns {@link AnalysisResult#none()})
     */
    AnalysisResult analyze(Report report);
}

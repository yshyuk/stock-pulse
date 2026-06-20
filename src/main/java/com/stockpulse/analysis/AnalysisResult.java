package com.stockpulse.analysis;

import lombok.Builder;
import lombok.Value;

/**
 * Outcome of a {@link ReportAnalyzer} run.
 *
 * <p>Kept deliberately small for now. When real (Claude-backed) analysis is added, this can
 * grow to carry the model's narrative, structured findings, token usage, etc.
 */
@Value
@Builder
public class AnalysisResult {

    /** Whether any analysis was actually performed. */
    boolean performed;

    /** Free-form analysis text (empty for NoOp). */
    String analysis;

    public static AnalysisResult none() {
        return AnalysisResult.builder()
                .performed(false)
                .analysis("")
                .build();
    }
}

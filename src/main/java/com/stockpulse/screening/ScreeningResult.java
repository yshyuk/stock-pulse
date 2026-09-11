package com.stockpulse.screening;

import com.stockpulse.domain.StockMetric;

import java.util.List;
import java.util.Map;

/**
 * Outcome of the screening stage: which stocks are worth putting in front of the
 * (token-billed) report and second-stage analysis, and the counts needed to say so honestly.
 *
 * @param metrics        the stocks that passed, already ranked and capped
 * @param totalEvaluated how many stocks entered screening
 * @param matchedCount   how many matched at least one rule BEFORE the cap was applied
 * @param ruleHits       symbol -> ids of the rules it matched (empty when screening is off)
 */
public record ScreeningResult(List<StockMetric> metrics,
                              int totalEvaluated,
                              int matchedCount,
                              Map<String, List<String>> ruleHits) {

    /** Screening disabled / not applicable: everything passes, nothing is attributed to a rule. */
    public static ScreeningResult passthrough(List<StockMetric> metrics) {
        return new ScreeningResult(metrics, metrics.size(), metrics.size(), Map.of());
    }
}

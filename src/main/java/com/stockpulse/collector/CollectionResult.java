package com.stockpulse.collector;

import com.stockpulse.domain.RawData;

import java.util.List;

/**
 * Outcome of a collection pass: the aggregated raw items plus the names of any REQUIRED
 * sources that failed. A required-source failure means the run's price data is untrustworthy,
 * so downstream plan generation is skipped (F-13) rather than emitting a plan on bad data.
 */
public record CollectionResult(List<RawData> items, List<String> failedRequiredSources) {

    /** True when at least one required source failed — the run is degraded. */
    public boolean hasRequiredFailure() {
        return failedRequiredSources != null && !failedRequiredSources.isEmpty();
    }
}

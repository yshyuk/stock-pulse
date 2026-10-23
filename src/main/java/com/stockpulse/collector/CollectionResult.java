package com.stockpulse.collector;

import com.stockpulse.domain.RawData;

import java.time.LocalDate;
import java.util.List;

/**
 * Outcome of a collection pass: the aggregated raw items plus the names of any REQUIRED
 * sources that failed. A required-source failure means the run's price data is untrustworthy,
 * so downstream plan generation is skipped (F-13) rather than emitting a plan on bad data.
 *
 * @param sessionDate the market session the collected prices belong to, as REPORTED by a source
 *                    ({@link RawData#getSessionDate()} — today only KRX knows it). Null when no
 *                    source reported one (KRX disabled/failed, realtime sources only) or when
 *                    sources disagreed. It is never derived from the run date: after a holiday the
 *                    previous weekday is not the previous session.
 */
public record CollectionResult(List<RawData> items, List<String> failedRequiredSources, LocalDate sessionDate) {

    /** No session date reported. */
    public CollectionResult(List<RawData> items, List<String> failedRequiredSources) {
        this(items, failedRequiredSources, null);
    }

    /** True when at least one required source failed — the run is degraded. */
    public boolean hasRequiredFailure() {
        return failedRequiredSources != null && !failedRequiredSources.isEmpty();
    }
}

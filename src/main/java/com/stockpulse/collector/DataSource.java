package com.stockpulse.collector;

import com.stockpulse.domain.RawData;

import java.time.LocalDate;
import java.util.List;

/**
 * ★ Core abstraction for a single data source.
 *
 * <p>Add a new source (DART disclosures, Naver finance, a news API, ...) by implementing
 * this interface and registering it as a Spring bean — {@link CollectorService} will pick
 * it up automatically. Only one dummy implementation exists today.
 */
public interface DataSource {

    /** Stable identifier, e.g. "dummy", "dart", "naver-finance". */
    String sourceName();

    /** Whether this source should run. Lets sources be toggled via config without code changes. */
    boolean isEnabled();

    /**
     * Whether this source is REQUIRED for a trustworthy run. If a required source fails, the
     * run is treated as degraded and plan generation is skipped (F-13). Defaults to false so
     * optional/enrichment sources never block the plan.
     */
    default boolean isRequired() {
        return false;
    }

    /**
     * Fetch the raw items this source provides for {@code runDate}.
     *
     * <p>Implementations should be self-contained (own error handling for partial failures)
     * and return an empty list rather than throwing for "nothing today" cases.
     *
     * <p>{@code runDate} is the date the pipeline is building a report FOR, which is not always
     * today: a re-run backfills a past date. Sources that can honour it (KRX serves history back
     * to 2010) should; realtime-only sources may ignore it.
     */
    List<RawData> collect(LocalDate runDate);
}

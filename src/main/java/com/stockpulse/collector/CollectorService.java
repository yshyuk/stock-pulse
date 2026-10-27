package com.stockpulse.collector;

import com.stockpulse.domain.RawData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Collector stage: runs every enabled {@link DataSource} and aggregates their raw items.
 *
 * <p>All registered {@code DataSource} beans are injected automatically, so adding a source
 * is just adding a bean. A failure in one source is logged and skipped so a single flaky
 * source does not abort the whole run.
 */
@Slf4j
@Service
public class CollectorService {

    private final List<DataSource> dataSources;

    public CollectorService(List<DataSource> dataSources) {
        this.dataSources = dataSources;
    }

    public CollectionResult collectAll(LocalDate runDate) {
        List<RawData> aggregated = new ArrayList<>();
        List<String> failedRequired = new ArrayList<>();
        for (DataSource source : dataSources) {
            if (!source.isEnabled()) {
                log.info("[collector] source '{}' disabled, skipping", source.sourceName());
                continue;
            }
            try {
                List<RawData> items = source.collect(runDate);
                log.info("[collector] source '{}' returned {} item(s)", source.sourceName(), items.size());
                aggregated.addAll(items);
            } catch (Exception e) {
                // Partial failure: keep going so other sources still contribute. A required
                // source failing marks the whole run degraded (plan is skipped downstream).
                log.error("[collector] source '{}' failed{}: {}",
                        source.sourceName(), source.isRequired() ? " (REQUIRED)" : "", e.getMessage(), e);
                if (source.isRequired()) {
                    failedRequired.add(source.sourceName());
                }
            }
        }
        log.info("[collector] aggregated {} raw item(s) from {} source(s){}",
                aggregated.size(), dataSources.size(),
                failedRequired.isEmpty() ? "" : " — DEGRADED, failed required: " + failedRequired);
        return new CollectionResult(aggregated, failedRequired, sessionDateOf(aggregated));
    }

    /**
     * The one session the sources reported, or null. Two different sessions in one run means we
     * do not know which close the prices are — say so instead of picking one.
     */
    private LocalDate sessionDateOf(List<RawData> items) {
        Set<LocalDate> sessions = new TreeSet<>();
        items.stream().map(RawData::getSessionDate).filter(Objects::nonNull).forEach(sessions::add);
        if (sessions.size() > 1) {
            log.warn("[collector] sources reported different sessions {} — session date left unknown",
                    sessions);
            return null;
        }
        return sessions.isEmpty() ? null : sessions.iterator().next();
    }
}

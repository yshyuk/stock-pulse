package com.stockpulse.collector;

import com.stockpulse.domain.RawData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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

    public List<RawData> collectAll() {
        List<RawData> aggregated = new ArrayList<>();
        for (DataSource source : dataSources) {
            if (!source.isEnabled()) {
                log.info("[collector] source '{}' disabled, skipping", source.sourceName());
                continue;
            }
            try {
                List<RawData> items = source.collect();
                log.info("[collector] source '{}' returned {} item(s)", source.sourceName(), items.size());
                aggregated.addAll(items);
            } catch (Exception e) {
                // Partial failure: keep going so other sources still contribute.
                log.error("[collector] source '{}' failed: {}", source.sourceName(), e.getMessage(), e);
            }
        }
        log.info("[collector] aggregated {} raw item(s) from {} source(s)", aggregated.size(), dataSources.size());
        return aggregated;
    }
}

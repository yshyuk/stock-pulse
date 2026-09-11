package com.stockpulse.collector.source;

import com.stockpulse.collector.DataSource;
import com.stockpulse.domain.RawData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The single placeholder {@link DataSource} so the pipeline runs end-to-end today.
 *
 * <p>It returns a couple of hard-coded rows. OPT-IN ONLY
 * ({@code stockpulse.collector.dummy.enabled=true}): with it on by default a run with no real
 * source configured still exits 0 and produces a plausible-looking report of invented prices —
 * a silent failure that reached production once. Keep it for smoke tests, never for a real run.
 *
 * <p>TODO: real sources should use the shared {@code WebClient}
 * ({@link com.stockpulse.config.WebClientConfig}) to call external APIs.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "stockpulse.collector.dummy", name = "enabled", havingValue = "true")
public class DummyDataSource implements DataSource {

    @Override
    public String sourceName() {
        return "dummy";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public List<RawData> collect(LocalDate runDate) {
        log.info("[collector] DummyDataSource producing sample rows (replace with real sources)");
        Instant now = Instant.now();
        return List.of(
                RawData.builder()
                        .sourceName(sourceName())
                        .symbol("005930")
                        .name("삼성전자")
                        .fetchedAt(now)
                        .payload(Map.of(
                                "price", 78900,
                                "previousPrice", 77000,
                                "volume", 15_200_000L,
                                "previousVolume", 12_800_000L))
                        .build(),
                RawData.builder()
                        .sourceName(sourceName())
                        .symbol("000660")
                        .name("SK하이닉스")
                        .fetchedAt(now)
                        .payload(Map.of(
                                "price", 201500,
                                "previousPrice", 205000,
                                "volume", 4_100_000L,
                                "previousVolume", 5_300_000L))
                        .build());
    }
}

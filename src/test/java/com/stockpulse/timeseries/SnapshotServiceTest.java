package com.stockpulse.timeseries;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.StockMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class SnapshotServiceTest {

    @Autowired
    private DailyStockSnapshotRepository repository;

    private final StockPulseProperties properties = new StockPulseProperties();
    private final DerivedMetricCalculator calculator = new DerivedMetricCalculator();
    private SnapshotService service;

    private final LocalDate day = LocalDate.of(2026, 7, 15);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T06:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new SnapshotService(repository, calculator, properties, clock);
    }

    private StockMetric metric(String symbol, double price, long volume) {
        return StockMetric.builder()
                .symbol(symbol)
                .name("삼성전자")
                .source("naver")
                .price(BigDecimal.valueOf(price))
                .volume(volume)
                .build();
    }

    @Test
    void record_persistsSnapshotWithSource() {
        service.record(day, List.of(metric("005930", 61000, 1000)));

        DailyStockSnapshot s = repository.findBySymbolAndTradeDate("005930", day).orElseThrow();
        assertThat(s.getPrice()).isEqualByComparingTo("61000");
        assertThat(s.getSource()).isEqualTo("naver");
        assertThat(s.getDerived()).isNotNull();
        // No prior history -> 1-day change is null, not zero.
        assertThat(s.getDerived().getChangeRate1d()).isNull();
    }

    @Test
    void rerunSameDate_isIdempotentUpsert() {
        service.record(day, List.of(metric("005930", 61000, 1000)));
        service.record(day, List.of(metric("005930", 62000, 2000)));

        List<DailyStockSnapshot> all = repository.findAll();
        assertThat(all).hasSize(1); // upsert, not a duplicate insert
        assertThat(all.get(0).getPrice()).isEqualByComparingTo("62000");
        assertThat(all.get(0).getVolume()).isEqualTo(2000);
    }

    @Test
    void derivedMetricsUsePriorSnapshots() {
        service.record(LocalDate.of(2026, 7, 14), List.of(metric("005930", 60000, 1000)));
        service.record(day, List.of(metric("005930", 66000, 1000)));

        DailyStockSnapshot today = repository.findBySymbolAndTradeDate("005930", day).orElseThrow();
        // (66000 - 60000) / 60000 * 100 = 10%
        assertThat(today.getDerived().getChangeRate1d()).isEqualByComparingTo("10.0000");
        assertThat(today.getDerived().getStreakDays()).isEqualTo(1);
    }
}

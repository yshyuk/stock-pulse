package com.stockpulse.market;

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
class MarketSnapshotServiceTest {

    @Autowired
    private DailyMarketSnapshotRepository repository;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-16T06:00:00Z"), ZoneOffset.UTC);
    private final LocalDate day = LocalDate.of(2026, 7, 16);
    private MarketSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new MarketSnapshotService(repository, clock);
    }

    private MarketIndicator ind(String code, double value) {
        return MarketIndicator.builder().code(code).name(code).value(BigDecimal.valueOf(value))
                .source("test").build();
    }

    @Test
    void recordsIndicatorWithNullChangeRateOnFirstDay() {
        service.record(day, List.of(ind(MarketIndicatorCodes.KOSPI, 2650)));

        DailyMarketSnapshot s = repository.findByIndicatorCodeAndTradeDate(MarketIndicatorCodes.KOSPI, day)
                .orElseThrow();
        assertThat(s.getValue()).isEqualByComparingTo("2650");
        assertThat(s.getChangeRate()).isNull();
    }

    @Test
    void computesChangeRateFromPriorDay() {
        service.record(day.minusDays(1), List.of(ind(MarketIndicatorCodes.KOSPI, 2600)));
        service.record(day, List.of(ind(MarketIndicatorCodes.KOSPI, 2652)));

        DailyMarketSnapshot today = repository.findByIndicatorCodeAndTradeDate(MarketIndicatorCodes.KOSPI, day)
                .orElseThrow();
        // (2652 - 2600) / 2600 * 100 = 2.0%
        assertThat(today.getChangeRate()).isEqualByComparingTo("2.0000");
    }

    @Test
    void rerunSameDateIsIdempotent() {
        service.record(day, List.of(ind(MarketIndicatorCodes.USDKRW, 1350)));
        service.record(day, List.of(ind(MarketIndicatorCodes.USDKRW, 1360)));

        List<DailyMarketSnapshot> all = repository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getValue()).isEqualByComparingTo("1360");
    }
}

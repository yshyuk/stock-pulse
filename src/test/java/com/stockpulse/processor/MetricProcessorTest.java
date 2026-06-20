package com.stockpulse.processor;

import com.stockpulse.domain.RawData;
import com.stockpulse.domain.StockMetric;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricProcessorTest {

    private final MetricProcessor processor = new MetricProcessor();

    @Test
    void computesObjectiveChangeRates() {
        RawData raw = RawData.builder()
                .sourceName("dummy")
                .symbol("005930")
                .name("삼성전자")
                .fetchedAt(Instant.now())
                .payload(Map.of(
                        "price", 110,
                        "previousPrice", 100,
                        "volume", 150L,
                        "previousVolume", 100L))
                .build();

        List<StockMetric> metrics = processor.process(List.of(raw));

        assertThat(metrics).hasSize(1);
        StockMetric m = metrics.get(0);
        assertThat(m.getChangeRate().doubleValue()).isEqualTo(10.00);
        assertThat(m.getVolumeChangeRate().doubleValue()).isEqualTo(50.00);
    }

    @Test
    void skipsItemsWithoutSymbol() {
        RawData newsItem = RawData.builder()
                .sourceName("news")
                .fetchedAt(Instant.now())
                .payload(Map.of("headline", "something"))
                .build();

        assertThat(processor.process(List.of(newsItem))).isEmpty();
    }
}

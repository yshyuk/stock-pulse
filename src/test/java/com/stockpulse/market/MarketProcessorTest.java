package com.stockpulse.market;

import com.stockpulse.domain.RawData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarketProcessorTest {

    private final MarketProcessor processor = new MarketProcessor();

    @Test
    void extractsMarketItemsByPayloadMarker() {
        RawData index = RawData.builder()
                .sourceName("naver-index").symbol("KOSPI").name("코스피")
                .fetchedAt(Instant.now())
                .payload(Map.of(MarketProcessor.PAYLOAD_VALUE, "2650.34"))
                .build();

        List<MarketIndicator> out = processor.process(List.of(index));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getCode()).isEqualTo("KOSPI");
        assertThat(out.get(0).getValue()).isEqualByComparingTo("2650.34");
        assertThat(out.get(0).getSource()).isEqualTo("naver-index");
    }

    @Test
    void ignoresStockPriceItems() {
        RawData stock = RawData.builder()
                .sourceName("naver-finance").symbol("005930")
                .fetchedAt(Instant.now())
                .payload(Map.of("price", 61000)) // no marketValue marker
                .build();

        assertThat(processor.process(List.of(stock))).isEmpty();
    }
}

package com.stockpulse.processor;

import com.stockpulse.domain.Disclosure;
import com.stockpulse.domain.RawData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DisclosureProcessorTest {

    private final DisclosureProcessor processor = new DisclosureProcessor();

    @Test
    void extractsDisclosureItemsAndIgnoresPriceItems() {
        RawData disclosure = RawData.builder()
                .sourceName("dart")
                .name("삼성전자")
                .fetchedAt(Instant.now())
                .payload(Map.of(
                        "type", "disclosure",
                        "corpName", "삼성전자",
                        "stockCode", "005930",
                        "reportName", "주요사항보고서",
                        "receiptNo", "20260621000001",
                        "receiptDate", "20260621",
                        "filer", "삼성전자"))
                .build();
        RawData priceItem = RawData.builder()
                .sourceName("dummy")
                .symbol("000660")
                .fetchedAt(Instant.now())
                .payload(Map.of("price", 200000))
                .build();

        List<Disclosure> result = processor.process(List.of(disclosure, priceItem));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockCode()).isEqualTo("005930");
        assertThat(result.get(0).getReportName()).isEqualTo("주요사항보고서");
    }
}

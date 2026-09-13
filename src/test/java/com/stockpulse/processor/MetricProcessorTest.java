package com.stockpulse.processor;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.RawData;
import com.stockpulse.domain.StockMetric;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricProcessorTest {

    private final StockPulseProperties properties = new StockPulseProperties();
    private final MetricProcessor processor = new MetricProcessor(properties);

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

    // ── 소스 간 중복 제거 ────────────────────────────────────────────────────

    @Test
    void keepsTheAuthoritativeSourceWhenTwoReportTheSameStock() {
        // KRX covers every listed stock and Naver covers a watchlist, so running both means
        // the watchlist symbols arrive twice. daily_stock_snapshot is unique on
        // (symbol, trade_date), so letting both through fails the whole run.
        List<StockMetric> metrics = processor.process(List.of(
                quote("naver-finance", "005930", 111),
                quote("krx-all", "005930", 222)));

        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).getSource()).isEqualTo("krx-all");
        assertThat(metrics.get(0).getPrice().intValue()).isEqualTo(222);
    }

    @Test
    void priorityWinsRegardlessOfArrivalOrder() {
        List<StockMetric> metrics = processor.process(List.of(
                quote("krx-all", "005930", 222),
                quote("naver-finance", "005930", 111)));

        assertThat(metrics).singleElement()
                .extracting(StockMetric::getSource).isEqualTo("krx-all");
    }

    @Test
    void anUnlistedSourceRanksBelowEveryListedOne() {
        List<StockMetric> metrics = processor.process(List.of(
                quote("some-new-feed", "005930", 999),
                quote("dummy", "005930", 111)));

        // "dummy" is last in the default priority list but still beats an unknown source.
        assertThat(metrics).singleElement()
                .extracting(StockMetric::getSource).isEqualTo("dummy");
    }

    @Test
    void keepsTheFirstWhenPrioritiesAreEqualSoRerunsAgree() {
        List<StockMetric> metrics = processor.process(List.of(
                quote("feed-a", "005930", 111),
                quote("feed-b", "005930", 222)));

        assertThat(metrics).singleElement()
                .extracting(StockMetric::getSource).isEqualTo("feed-a");
    }

    @Test
    void leavesDistinctSymbolsAlone() {
        List<StockMetric> metrics = processor.process(List.of(
                quote("krx-all", "005930", 111),
                quote("krx-all", "000660", 222)));

        assertThat(metrics).hasSize(2)
                .extracting(StockMetric::getSymbol)
                .containsExactly("005930", "000660");
    }

    private RawData quote(String source, String symbol, int price) {
        return RawData.builder()
                .sourceName(source)
                .symbol(symbol)
                .name("종목" + symbol)
                .fetchedAt(Instant.now())
                .payload(Map.of("price", price, "previousPrice", 100, "volume", 10L))
                .build();
    }
}

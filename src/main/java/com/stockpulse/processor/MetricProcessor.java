package com.stockpulse.processor;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.RawData;
import com.stockpulse.domain.StockMetric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Processor stage: turns raw collected items into OBJECTIVE per-stock metrics.
 *
 * <p>Scope boundary (intentional): this computes only factual numbers such as price
 * change rate and volume change rate, and tidies per-stock data. It does NOT decide
 * whether a stock is "good", assign a recommendation score, or rank tickers — that
 * interpretation belongs to the second-stage Claude analysis performed on the report.
 */
@Slf4j
@Service
public class MetricProcessor {

    private static final int SCALE = 2;

    private final StockPulseProperties properties;

    public MetricProcessor(StockPulseProperties properties) {
        this.properties = properties;
    }

    public List<StockMetric> process(List<RawData> rawData) {
        List<StockMetric> metrics = new ArrayList<>();
        for (RawData raw : rawData) {
            if (raw.getSymbol() == null) {
                // Source-level items (e.g. news) have no per-stock metric.
                continue;
            }
            if (raw.getPayload() == null || !raw.getPayload().containsKey("price")) {
                // Non-price items (e.g. DART disclosures) are handled by DisclosureProcessor,
                // not turned into empty price metrics.
                continue;
            }
            try {
                metrics.add(toMetric(raw));
            } catch (Exception e) {
                log.warn("[processor] could not compute metrics for '{}': {}", raw.getSymbol(), e.getMessage());
            }
        }
        List<StockMetric> deduplicated = deduplicateBySymbol(metrics);
        log.info("[processor] computed {} stock metric(s){}", deduplicated.size(),
                metrics.size() == deduplicated.size()
                        ? "" : " (" + (metrics.size() - deduplicated.size()) + " duplicate(s) dropped)");
        return deduplicated;
    }

    /**
     * One metric per symbol, keeping the most authoritative source.
     *
     * <p>Sources overlap by design — KRX covers every listed stock while Naver covers a
     * watchlist — so running both makes the watchlist symbols arrive twice. {@code
     * daily_stock_snapshot} is unique on {@code (symbol, trade_date)}, so passing duplicates on
     * does not merely produce a messy report: the upsert violates the constraint and the whole
     * run dies. Resolving it here keeps every downstream stage working on one row per stock.
     *
     * <p>Order of arrival is not used as the tiebreak, because it depends on Spring's bean
     * ordering and would make the winner effectively arbitrary. Configured priority decides;
     * arrival order only breaks ties between equally ranked sources, so a re-run agrees with
     * the original.
     */
    private List<StockMetric> deduplicateBySymbol(List<StockMetric> metrics) {
        Map<String, StockMetric> bySymbol = new LinkedHashMap<>();
        for (StockMetric candidate : metrics) {
            StockMetric existing = bySymbol.get(candidate.getSymbol());
            if (existing == null) {
                bySymbol.put(candidate.getSymbol(), candidate);
                continue;
            }
            if (rank(candidate) < rank(existing)) {
                log.warn("[processor] '{}' reported by both '{}' and '{}' — keeping '{}'",
                        candidate.getSymbol(), existing.getSource(), candidate.getSource(),
                        candidate.getSource());
                bySymbol.put(candidate.getSymbol(), candidate);
            } else {
                log.warn("[processor] '{}' reported by both '{}' and '{}' — keeping '{}'",
                        candidate.getSymbol(), existing.getSource(), candidate.getSource(),
                        existing.getSource());
            }
        }
        return new ArrayList<>(bySymbol.values());
    }

    /** Position in the configured priority list; unlisted sources sort after every listed one. */
    private int rank(StockMetric metric) {
        List<String> priority = properties.getCollector().getSourcePriority();
        int index = metric.getSource() == null ? -1 : priority.indexOf(metric.getSource());
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private StockMetric toMetric(RawData raw) {
        BigDecimal price = number(raw, "price");
        BigDecimal previousPrice = number(raw, "previousPrice");
        Long volume = longValue(raw, "volume");
        Long previousVolume = longValue(raw, "previousVolume");

        return StockMetric.builder()
                .symbol(raw.getSymbol())
                .name(raw.getName())
                .source(raw.getSourceName())
                .price(price)
                .previousPrice(previousPrice)
                .changeRate(percentChange(previousPrice, price))
                .volume(volume)
                .previousVolume(previousVolume)
                .volumeChangeRate(percentChange(toBigDecimal(previousVolume), toBigDecimal(volume)))
                .build();
    }

    /** (current - base) / base * 100, rounded to 2 decimals. Null-safe. */
    private BigDecimal percentChange(BigDecimal base, BigDecimal current) {
        if (base == null || current == null || base.signum() == 0) {
            return null;
        }
        return current.subtract(base)
                .divide(base, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal number(RawData raw, String key) {
        Object v = raw.getPayload().get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    private Long longValue(RawData raw, String key) {
        Object v = raw.getPayload().get(key);
        return v == null ? null : Long.valueOf(v.toString());
    }

    private BigDecimal toBigDecimal(Long v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }
}

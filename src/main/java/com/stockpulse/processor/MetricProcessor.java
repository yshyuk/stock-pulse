package com.stockpulse.processor;

import com.stockpulse.domain.RawData;
import com.stockpulse.domain.StockMetric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

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

    public List<StockMetric> process(List<RawData> rawData) {
        List<StockMetric> metrics = new ArrayList<>();
        for (RawData raw : rawData) {
            if (raw.getSymbol() == null) {
                // Source-level items (e.g. news) have no per-stock metric yet.
                // TODO: route these into a separate news/disclosure section of the report.
                continue;
            }
            try {
                metrics.add(toMetric(raw));
            } catch (Exception e) {
                log.warn("[processor] could not compute metrics for '{}': {}", raw.getSymbol(), e.getMessage());
            }
        }
        log.info("[processor] computed {} stock metric(s)", metrics.size());
        return metrics;
    }

    private StockMetric toMetric(RawData raw) {
        BigDecimal price = number(raw, "price");
        BigDecimal previousPrice = number(raw, "previousPrice");
        Long volume = longValue(raw, "volume");
        Long previousVolume = longValue(raw, "previousVolume");

        return StockMetric.builder()
                .symbol(raw.getSymbol())
                .name(raw.getName())
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

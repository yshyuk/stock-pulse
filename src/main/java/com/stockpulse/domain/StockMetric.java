package com.stockpulse.domain;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Per-stock OBJECTIVE metrics produced by the processor stage.
 *
 * <p>Important boundary: this object carries only factual, computable numbers
 * (change rate, volume change rate, ...). It deliberately contains NO judgement,
 * recommendation, "is this a good stock" score, or ranking. That kind of
 * interpretation is the job of the downstream (second-stage) Claude analysis,
 * which a human currently runs by hand on the rendered report.
 */
@Value
@Builder
public class StockMetric {

    String symbol;
    String name;

    /** Latest close/price. */
    BigDecimal price;

    /** Previous reference price used to compute {@link #changeRate}. */
    BigDecimal previousPrice;

    /** Day-over-day price change rate, in percent. (price - prev) / prev * 100 */
    BigDecimal changeRate;

    /** Trading volume for the period. */
    Long volume;

    /** Reference (e.g. average/previous) volume used to compute {@link #volumeChangeRate}. */
    Long previousVolume;

    /** Volume change rate vs. reference, in percent. */
    BigDecimal volumeChangeRate;

    // TODO: add further OBJECTIVE indicators only (moving averages, 52w high/low gap, etc.).
    //       Do NOT add subjective scores or buy/sell signals here.
}

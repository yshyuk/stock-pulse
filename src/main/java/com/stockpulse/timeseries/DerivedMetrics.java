package com.stockpulse.timeseries;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * OBJECTIVE time-series derived indicators for one {@link DailyStockSnapshot}, computed at
 * ingest time from the accumulated history (see {@link DerivedMetricCalculator}).
 *
 * <p>Grouped as an {@code @Embeddable} value object (per the architect's entity-bloat rule)
 * so the snapshot entity stays small while these columns still live in the same table row.
 *
 * <p>Every field is nullable on purpose: when there is not enough accumulated history to
 * compute an indicator (e.g. a 20-day metric on day 5), the value is {@code null} — never 0
 * or an approximation, so downstream plan generation is never fed a polluted number.
 */
@Embeddable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DerivedMetrics {

    /** Change rate vs. the previous trading day's snapshot, in percent. */
    @Column(name = "change_rate_1d", precision = 9, scale = 4)
    private BigDecimal changeRate1d;

    /** Change rate vs. the snapshot 5 trading days ago, in percent. */
    @Column(name = "change_rate_5d", precision = 9, scale = 4)
    private BigDecimal changeRate5d;

    /** Change rate vs. the snapshot 20 trading days ago, in percent. */
    @Column(name = "change_rate_20d", precision = 9, scale = 4)
    private BigDecimal changeRate20d;

    /** Today's volume divided by the trailing 20-day average volume. */
    @Column(name = "volume_ma20_ratio", precision = 9, scale = 4)
    private BigDecimal volumeMa20Ratio;

    /** Consecutive up (positive) / down (negative) day count, including today. */
    @Column(name = "streak_days")
    private Integer streakDays;

    /** Standard deviation of the last 20 daily returns, in percent. */
    @Column(name = "volatility_20d", precision = 9, scale = 4)
    private BigDecimal volatility20d;

    /** Position within accumulated high/low range: 0 (at low) .. 1 (at high). */
    @Column(name = "range_position", precision = 9, scale = 4)
    private BigDecimal rangePosition;

    /** All-null derived metrics (used when there is no prior history at all). */
    public static DerivedMetrics empty() {
        return DerivedMetrics.builder().build();
    }
}

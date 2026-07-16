package com.stockpulse.timeseries;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One day's OBJECTIVE snapshot for a single stock — the time-series row that accumulates
 * across dawn runs and backs both plan generation and (future, Part 2) the intraday engine.
 *
 * <p>Natural key {@code (symbol, trade_date)} is UNIQUE so a re-run for the same date is an
 * upsert (see {@link SnapshotService}) rather than a duplicate insert — this is what makes
 * {@code --stockpulse.run-date=YYYY-MM-DD} idempotent.
 *
 * <p>Raw fields (price/volume) are what we collected; {@link #derived} holds the computed
 * indicators. Like {@link com.stockpulse.domain.StockMetric}, this object carries only facts —
 * no judgement, score, or buy/sell signal.
 */
@Entity
@Table(
        name = "daily_stock_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_symbol_date",
                columnNames = {"symbol", "trade_date"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DailyStockSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Column(nullable = false)
    private String name;

    /** Business/trading date this snapshot describes. */
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** Latest close/price collected for {@link #tradeDate}. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal price;

    /** Reference price as collected (source-provided previous close), for traceability. */
    @Column(name = "previous_price", precision = 18, scale = 2)
    private BigDecimal previousPrice;

    /** Trading volume for {@link #tradeDate}. */
    private Long volume;

    /** Time-series derived indicators (nullable when history is insufficient). */
    @Embedded
    private DerivedMetrics derived;

    /** Which DataSource produced the raw values, e.g. naver / dummy / (future) kis. */
    @Column(nullable = false, length = 32)
    private String source;

    /** When the raw values were collected. */
    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;
}

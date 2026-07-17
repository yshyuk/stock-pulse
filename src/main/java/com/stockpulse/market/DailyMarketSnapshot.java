package com.stockpulse.market;

import jakarta.persistence.Column;
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
 * One day's OBJECTIVE snapshot for a market-level indicator (index / FX / supply-demand).
 *
 * <p>Natural key {@code (indicator_code, trade_date)} is UNIQUE so a re-run upserts (ADR-001).
 * The value is raw; {@link #changeRate} is computed vs. the prior day's stored value at ingest
 * time (null when there is no prior).
 */
@Entity
@Table(
        name = "daily_market_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_indicator_date",
                columnNames = {"indicator_code", "trade_date"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DailyMarketSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "indicator_code", nullable = false, length = 32)
    private String indicatorCode;

    @Column(nullable = false)
    private String name;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** Raw reading (index points / won / net-buy KRW). Wide enough for large KRW amounts. */
    // Column is 'indicator_value' — plain 'value' is a reserved word in H2/MySQL.
    @Column(name = "indicator_value", nullable = false, precision = 24, scale = 4)
    private BigDecimal value;

    /** Day-over-day change rate vs the prior snapshot, in percent (null when no prior). */
    @Column(name = "change_rate", precision = 9, scale = 4)
    private BigDecimal changeRate;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;
}

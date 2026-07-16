package com.stockpulse.intraday.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * A position the engine holds. Crucially, it OWNS its exit criteria ({@link #targetPriceKrw} /
 * {@link #stopLossPriceKrw}) — copied from the originating plan at entry time — because a
 * position can outlive its one-day plan (design ADR-007/008). The next day's engine loads a new
 * plan but can still manage yesterday's holdings from these persisted targets.
 */
@Entity
@Table(name = "intraday_position")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PositionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "avg_price_krw", nullable = false, precision = 18, scale = 2)
    private BigDecimal avgPriceKrw;

    /** Plan date that opened this position (provenance; not the exit authority). */
    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    /** Take-profit price, copied from the plan — the position's own exit authority. */
    @Column(name = "target_price_krw", nullable = false, precision = 18, scale = 2)
    private BigDecimal targetPriceKrw;

    /** Stop-loss price, copied from the plan. */
    @Column(name = "stop_loss_price_krw", nullable = false, precision = 18, scale = 2)
    private BigDecimal stopLossPriceKrw;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private PositionStatus status;

    /** Realized P&L in KRW, set when the position is closed (null while open). */
    @Column(name = "realized_pnl_krw", precision = 18, scale = 2)
    private BigDecimal realizedPnlKrw;

    @Column(name = "closed_at")
    private Instant closedAt;
}

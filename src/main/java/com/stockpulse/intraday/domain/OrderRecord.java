package com.stockpulse.intraday.domain;

import com.stockpulse.broker.OrderSide;
import com.stockpulse.broker.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Persisted record of an order the engine issued (or would have, in dry-run).
 *
 * <p>{@code clientOrderId} is UNIQUE — this is the on-DB half of idempotency: before placing an
 * order the engine checks for an existing record with the same key, so a restart or retry never
 * double-orders. Named {@code OrderRecord} to avoid clashing with the broker's transient
 * {@link com.stockpulse.broker.OrderRequest}/{@code OrderResult} DTOs.
 */
@Entity
@Table(
        name = "intraday_order",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_order_id", columnNames = "client_order_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class OrderRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_order_id", nullable = false, length = 64)
    private String clientOrderId;

    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private OrderSide side;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "price_krw", nullable = false, precision = 18, scale = 2)
    private BigDecimal priceKrw;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "broker_order_id", length = 64)
    private String brokerOrderId;

    @Column(name = "filled_quantity", nullable = false)
    private int filledQuantity;

    @Column(name = "avg_fill_price_krw", precision = 18, scale = 2)
    private BigDecimal avgFillPriceKrw;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

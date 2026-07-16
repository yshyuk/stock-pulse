package com.stockpulse.broker;

import java.math.BigDecimal;

/**
 * A request to place an order. {@code clientOrderId} is the deterministic idempotency key
 * (e.g. {@code 2026-07-16-005930-BUY}); the broker/engine must never place two orders with the
 * same key.
 */
public record OrderRequest(
        String clientOrderId,
        String symbol,
        OrderSide side,
        OrderType type,
        int quantity,
        BigDecimal priceKrw) {
}

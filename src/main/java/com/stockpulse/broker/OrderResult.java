package com.stockpulse.broker;

import java.math.BigDecimal;

/**
 * Outcome of a {@link BrokerClient#placeOrder} / {@code cancelOrder} call.
 *
 * @param clientOrderId the idempotency key echoed back
 * @param brokerOrderId broker-assigned id (null if not accepted)
 * @param status        resulting status
 * @param filledQuantity quantity filled so far (0 if none)
 * @param avgFillPriceKrw average fill price (null if nothing filled)
 * @param message       human-readable detail (rejection reason, etc.)
 */
public record OrderResult(
        String clientOrderId,
        String brokerOrderId,
        OrderStatus status,
        int filledQuantity,
        BigDecimal avgFillPriceKrw,
        String message) {

    public static OrderResult rejected(String clientOrderId, String message) {
        return new OrderResult(clientOrderId, null, OrderStatus.REJECTED, 0, null, message);
    }

    public static OrderResult dryRun(String clientOrderId) {
        return new OrderResult(clientOrderId, null, OrderStatus.DRY_RUN, 0, null, "dry-run: not sent");
    }
}

package com.stockpulse.broker;

import java.math.BigDecimal;

/**
 * A holding as reported by the broker account (used for start-up reconciliation).
 * Named {@code BrokerPosition} to distinguish it from the engine's own persisted position.
 */
public record BrokerPosition(String symbol, int quantity, BigDecimal avgPrice) {
}

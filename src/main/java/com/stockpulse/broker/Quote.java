package com.stockpulse.broker;

import java.math.BigDecimal;
import java.time.Instant;

/** A current-price reading for a symbol from the broker. */
public record Quote(String symbol, BigDecimal price, Instant asOf) {
}

package com.stockpulse.broker;

import java.math.BigDecimal;

/** Account cash and total evaluated value from the broker. */
public record AccountBalance(BigDecimal cashKrw, BigDecimal totalEvalKrw) {
}

package com.stockpulse.broker;

/** Order pricing type. M1 uses LIMIT (from the plan's entry); MARKET reserved for later. */
public enum OrderType {
    LIMIT,
    MARKET
}

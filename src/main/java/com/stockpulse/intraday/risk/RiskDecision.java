package com.stockpulse.intraday.risk;

/** Outcome of a pre-trade risk check. */
public record RiskDecision(boolean allowed, String reason) {

    public static RiskDecision allow() {
        return new RiskDecision(true, "ok");
    }

    public static RiskDecision deny(String reason) {
        return new RiskDecision(false, reason);
    }
}

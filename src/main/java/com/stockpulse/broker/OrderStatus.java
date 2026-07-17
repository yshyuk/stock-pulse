package com.stockpulse.broker;

/**
 * Lifecycle status of an order, as reported by the broker (or by the engine for dry-run).
 */
public enum OrderStatus {
    /** Recorded but not sent (dry-run mode). */
    DRY_RUN,
    /** Accepted by the broker, not yet filled. */
    ACCEPTED,
    /** Rejected by the broker or by a pre-trade risk guard. */
    REJECTED,
    /** Fully filled. */
    FILLED,
    /** Partially filled. */
    PARTIAL,
    /** Cancelled (e.g. EOD cleanup of an unfilled order). */
    CANCELLED;

    public boolean isFilled() {
        return this == FILLED;
    }

    /** Terminal states that need no further polling/cancellation. */
    public boolean isTerminal() {
        return this == FILLED || this == REJECTED || this == CANCELLED || this == DRY_RUN;
    }
}

package com.stockpulse.intraday.order;

/** Result of an {@link OrderService} submit attempt (for logging and tests). */
public enum SubmitOutcome {
    /** Order sent and filled. */
    FILLED,
    /** Order sent, accepted but not (yet) filled. */
    ACCEPTED,
    /** Recorded only (dry-run mode) — nothing sent. */
    DRY_RUN,
    /** An order with the same idempotency key already exists — skipped. */
    DUPLICATE,
    /** A risk guard denied the order. */
    RISK_DENIED,
    /** Budget can't afford a single share. */
    NO_QUANTITY,
    /** Broker rejected the order. */
    REJECTED,
    /** An unexpected error occurred (logged; batch continues). */
    ERROR
}

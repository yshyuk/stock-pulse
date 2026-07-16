package com.stockpulse.intraday.domain;

/** Why a position is being exited — part of the sell order's idempotency key. */
public enum ExitReason {
    /** Price reached the plan's take-profit target. */
    TARGET,
    /** Price hit the plan's stop-loss. */
    STOP,
    /** End-of-day flatten (reserved; M1 keeps positions unless target/stop hit). */
    EOD
}

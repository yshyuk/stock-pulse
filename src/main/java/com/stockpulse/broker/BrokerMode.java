package com.stockpulse.broker;

/**
 * Order execution mode (see design ADR-006). Default is the safest, {@link #DRY_RUN}.
 *
 * <ul>
 *   <li>{@link #DRY_RUN} — quotes are real, but orders are recorded/logged only and NEVER sent.
 *       The mandatory shadow-validation mode before any real order.</li>
 *   <li>{@link #PAPER} — orders are actually placed against the broker's paper/simulated venue.</li>
 *   <li>{@link #LIVE} — real-money trading. Requires an explicit confirmation flag to boot.</li>
 * </ul>
 */
public enum BrokerMode {
    DRY_RUN,
    PAPER,
    LIVE;

    /** Whether orders should actually be transmitted to the broker in this mode. */
    public boolean sendsOrders() {
        return this != DRY_RUN;
    }
}

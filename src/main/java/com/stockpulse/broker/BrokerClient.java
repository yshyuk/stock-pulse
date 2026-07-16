package com.stockpulse.broker;

import java.util.List;

/**
 * ★ Core abstraction for the brokerage the intraday engine trades through.
 *
 * <p>Keeping this an interface lets the engine be developed and tested against a
 * {@link FakeBrokerClient} with deterministic quotes/fills, then run for real against a KIS
 * implementation by swapping the bean — the engine code does not change. Paper vs. live is a
 * concern of the concrete implementation's configuration, not this contract.
 */
public interface BrokerClient {

    /** Short id for logging, e.g. "fake", "kis". */
    String name();

    /** Current price for a symbol. */
    Quote getQuote(String symbol);

    /** Place an order. Implementations must treat {@code clientOrderId} as an idempotency key. */
    OrderResult placeOrder(OrderRequest request);

    /** Cancel a previously placed order (used for EOD cleanup of unfilled orders). */
    OrderResult cancelOrder(String clientOrderId, String brokerOrderId);

    /** Current holdings in the account (for start-up reconciliation). */
    List<BrokerPosition> getPositions();

    /** Account cash / evaluated value. */
    AccountBalance getBalance();
}

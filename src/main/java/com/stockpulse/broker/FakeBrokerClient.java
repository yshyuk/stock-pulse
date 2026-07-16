package com.stockpulse.broker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link BrokerClient} used for local runs and tests: deterministic quotes and
 * immediate simulated fills at the requested price. Lets the whole intraday engine run
 * end-to-end (in dry-run or paper mode) without a live KIS account.
 *
 * <p>Active by default ({@code stockpulse.broker.impl=fake}); a real KIS client will register
 * under {@code impl=kis}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "stockpulse.broker", name = "impl", havingValue = "fake", matchIfMissing = true)
public class FakeBrokerClient implements BrokerClient {

    private final Clock clock;

    private final Map<String, BigDecimal> quotes = new ConcurrentHashMap<>();
    private final Map<String, BrokerPosition> positions = new ConcurrentHashMap<>();
    private final AtomicInteger orderSeq = new AtomicInteger();
    private volatile BigDecimal cashKrw = new BigDecimal("10000000"); // 1천만원 시드

    public FakeBrokerClient(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "fake";
    }

    // ── Test/demo controls ─────────────────────────────────────────────
    public void setQuote(String symbol, BigDecimal price) {
        quotes.put(symbol, price);
    }

    public void reset() {
        quotes.clear();
        positions.clear();
        orderSeq.set(0);
        cashKrw = new BigDecimal("10000000");
    }

    // ── BrokerClient ───────────────────────────────────────────────────
    @Override
    public Quote getQuote(String symbol) {
        BigDecimal price = quotes.get(symbol);
        if (price == null) {
            throw new IllegalStateException("fake broker has no quote for " + symbol);
        }
        return new Quote(symbol, price, Instant.now(clock));
    }

    @Override
    public OrderResult placeOrder(OrderRequest req) {
        String brokerOrderId = "FAKE-" + orderSeq.incrementAndGet();
        BigDecimal fillPrice = req.priceKrw();
        BigDecimal notional = fillPrice.multiply(BigDecimal.valueOf(req.quantity()));

        if (req.side() == OrderSide.BUY) {
            positions.merge(req.symbol(),
                    new BrokerPosition(req.symbol(), req.quantity(), fillPrice),
                    (existing, add) -> mergeBuy(existing, add.quantity(), fillPrice));
            cashKrw = cashKrw.subtract(notional);
        } else {
            BrokerPosition held = positions.get(req.symbol());
            int remaining = (held == null ? 0 : held.quantity()) - req.quantity();
            if (remaining <= 0) {
                positions.remove(req.symbol());
            } else {
                positions.put(req.symbol(),
                        new BrokerPosition(req.symbol(), remaining, held.avgPrice()));
            }
            cashKrw = cashKrw.add(notional);
        }

        log.info("[broker:fake] {} {} x{} @ {} -> FILLED ({})",
                req.side(), req.symbol(), req.quantity(), fillPrice, brokerOrderId);
        return new OrderResult(req.clientOrderId(), brokerOrderId, OrderStatus.FILLED,
                req.quantity(), fillPrice, "fake fill");
    }

    private BrokerPosition mergeBuy(BrokerPosition existing, int addQty, BigDecimal addPrice) {
        int totalQty = existing.quantity() + addQty;
        BigDecimal totalCost = existing.avgPrice().multiply(BigDecimal.valueOf(existing.quantity()))
                .add(addPrice.multiply(BigDecimal.valueOf(addQty)));
        BigDecimal avg = totalCost.divide(BigDecimal.valueOf(totalQty), 2, java.math.RoundingMode.HALF_UP);
        return new BrokerPosition(existing.symbol(), totalQty, avg);
    }

    @Override
    public OrderResult cancelOrder(String clientOrderId, String brokerOrderId) {
        log.info("[broker:fake] cancel {} ({})", clientOrderId, brokerOrderId);
        return new OrderResult(clientOrderId, brokerOrderId, OrderStatus.CANCELLED, 0, null, "fake cancel");
    }

    @Override
    public List<BrokerPosition> getPositions() {
        return new ArrayList<>(positions.values());
    }

    @Override
    public AccountBalance getBalance() {
        return new AccountBalance(cashKrw, cashKrw);
    }
}

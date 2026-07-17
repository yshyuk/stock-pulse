package com.stockpulse.intraday.order;

import com.stockpulse.broker.BrokerClient;
import com.stockpulse.broker.OrderRequest;
import com.stockpulse.broker.OrderResult;
import com.stockpulse.broker.OrderSide;
import com.stockpulse.broker.OrderStatus;
import com.stockpulse.broker.OrderType;
import com.stockpulse.broker.Quote;
import com.stockpulse.config.BrokerProperties;
import com.stockpulse.intraday.MarketClock;
import com.stockpulse.intraday.domain.ExitReason;
import com.stockpulse.intraday.domain.OrderRecord;
import com.stockpulse.intraday.domain.OrderRecordRepository;
import com.stockpulse.intraday.domain.PositionRecord;
import com.stockpulse.intraday.domain.PositionRecordRepository;
import com.stockpulse.intraday.domain.PositionStatus;
import com.stockpulse.intraday.risk.RiskCheckInput;
import com.stockpulse.intraday.risk.RiskDecision;
import com.stockpulse.intraday.risk.RiskGuard;
import com.stockpulse.intraday.signal.SignalEvaluator;
import com.stockpulse.notification.NotificationMessage;
import com.stockpulse.notification.NotificationService;
import com.stockpulse.plan.PlanCandidate;
import com.stockpulse.plan.TradingPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Places entry (BUY) and exit (SELL) orders — the only path to the broker. Every order passes
 * the {@link RiskGuard} and a deterministic idempotency key, and honors the broker mode
 * (dry-run records only, never sends). Fills update the persisted {@link PositionRecord}, which
 * carries its own exit targets so it survives past the plan's day. Nothing here fails silently.
 */
@Slf4j
@Service
public class OrderService {

    private final BrokerClient broker;
    private final OrderRecordRepository orderRepository;
    private final PositionRecordRepository positionRepository;
    private final RiskGuard riskGuard;
    private final SignalEvaluator signalEvaluator;
    private final BrokerProperties brokerProperties;
    private final NotificationService notificationService;
    private final Clock clock;

    public OrderService(BrokerClient broker,
                        OrderRecordRepository orderRepository,
                        PositionRecordRepository positionRepository,
                        RiskGuard riskGuard,
                        SignalEvaluator signalEvaluator,
                        BrokerProperties brokerProperties,
                        NotificationService notificationService,
                        Clock clock) {
        this.broker = broker;
        this.orderRepository = orderRepository;
        this.positionRepository = positionRepository;
        this.riskGuard = riskGuard;
        this.signalEvaluator = signalEvaluator;
        this.brokerProperties = brokerProperties;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /** Attempts to enter {@code candidate} at the current quote. Idempotent per (planDate, symbol). */
    @Transactional
    public SubmitOutcome submitBuy(TradingPlan plan, PlanCandidate candidate, Quote quote) {
        LocalDate planDate = plan.getPlanDate();
        String symbol = candidate.getSymbol();
        String clientOrderId = planDate + "-" + symbol + "-BUY";

        if (orderRepository.existsByClientOrderId(clientOrderId)) {
            return SubmitOutcome.DUPLICATE;
        }

        BigDecimal entryPrice = candidate.getEntry().getPriceKrw();
        BigDecimal budget = candidate.getSizing().getMaxBudgetKrw();
        int qty = signalEvaluator.quantityFor(budget, entryPrice);
        if (qty <= 0) {
            log.warn("[order] {} budget {} can't afford entry price {} — skipping", symbol, budget, entryPrice);
            return SubmitOutcome.NO_QUANTITY;
        }
        BigDecimal orderCost = entryPrice.multiply(BigDecimal.valueOf(qty));

        RiskDecision decision = riskGuard.check(new RiskCheckInput(
                symbol,
                orderCost,
                plan.getConstraints().getMaxBudgetPerSymbolKrw(),
                plan.getConstraints().getMaxTotalBudgetKrw(),
                currentTotalInvested(),
                openPositionCount(),
                dailyRealizedPnl(),
                false));
        if (!decision.allowed()) {
            log.warn("[order] BUY {} denied by risk guard: {}", symbol, decision.reason());
            if (decision.reason().contains("daily loss")) {
                notify(NotificationMessage.Severity.FAILURE, "⛔ 일 손실 한도 — 신규 주문 중단",
                        "리스크 가드가 신규 진입을 차단했습니다: " + decision.reason());
            }
            return SubmitOutcome.RISK_DENIED;
        }

        OrderRequest request = new OrderRequest(
                clientOrderId, symbol, OrderSide.BUY, OrderType.LIMIT, qty, entryPrice);

        if (!brokerProperties.getMode().sendsOrders()) {
            saveOrder(clientOrderId, planDate, symbol, OrderSide.BUY, qty, entryPrice,
                    OrderResult.dryRun(clientOrderId));
            log.info("[order] DRY-RUN BUY {} x{} @ {} (not sent)", symbol, qty, entryPrice);
            return SubmitOutcome.DRY_RUN;
        }

        OrderResult result = broker.placeOrder(request);
        saveOrder(clientOrderId, planDate, symbol, OrderSide.BUY, qty, entryPrice, result);

        if (result.status().isFilled()) {
            openPosition(candidate, planDate, qty, result.avgFillPriceKrw());
            notify(NotificationMessage.Severity.SUCCESS, "🟢 매수 체결 " + symbol,
                    symbol + " " + qty + "주 @ " + result.avgFillPriceKrw());
            return SubmitOutcome.FILLED;
        }
        if (result.status() == OrderStatus.REJECTED) {
            return SubmitOutcome.REJECTED;
        }
        return SubmitOutcome.ACCEPTED;
    }

    /** Attempts to exit an open position. Idempotent per (tradeDate, symbol, reason). */
    @Transactional
    public SubmitOutcome submitSell(PositionRecord position, Quote quote, ExitReason reason, LocalDate tradeDate) {
        String symbol = position.getSymbol();
        String clientOrderId = tradeDate + "-" + symbol + "-SELL-" + reason.name();

        if (orderRepository.existsByClientOrderId(clientOrderId)) {
            return SubmitOutcome.DUPLICATE;
        }

        int qty = position.getQuantity();
        // Exits deliberately bypass the RiskGuard (closing risk must never be blocked, even under
        // the kill switch), so this is the sell path's only sanity check: never send a nonsensical
        // quantity from a corrupted position record.
        if (qty <= 0) {
            log.error("[order] refusing SELL {} — invalid position quantity {}", symbol, qty);
            return SubmitOutcome.NO_QUANTITY;
        }
        BigDecimal price = quote.price();
        OrderRequest request = new OrderRequest(
                clientOrderId, symbol, OrderSide.SELL, OrderType.LIMIT, qty, price);

        if (!brokerProperties.getMode().sendsOrders()) {
            saveOrder(clientOrderId, tradeDate, symbol, OrderSide.SELL, qty, price,
                    OrderResult.dryRun(clientOrderId));
            log.info("[order] DRY-RUN SELL {} x{} @ {} ({}) (not sent)", symbol, qty, price, reason);
            return SubmitOutcome.DRY_RUN;
        }

        OrderResult result = broker.placeOrder(request);
        saveOrder(clientOrderId, tradeDate, symbol, OrderSide.SELL, qty, price, result);

        if (result.status().isFilled()) {
            closePosition(position, result.avgFillPriceKrw());
            BigDecimal pnl = result.avgFillPriceKrw().subtract(position.getAvgPriceKrw())
                    .multiply(BigDecimal.valueOf(qty));
            notify(NotificationMessage.Severity.SUCCESS,
                    "🔴 매도 체결 " + symbol + " (" + reason + ")",
                    symbol + " " + qty + "주 @ " + result.avgFillPriceKrw() + " · 손익 " + pnl + "원");
            return SubmitOutcome.FILLED;
        }
        if (result.status() == OrderStatus.REJECTED) {
            return SubmitOutcome.REJECTED;
        }
        return SubmitOutcome.ACCEPTED;
    }

    /**
     * Cancels every non-terminal order (ACCEPTED/PARTIAL) — the EOD cleanup that prevents an
     * unfilled order from carrying into the next day. Returns the count cancelled.
     */
    @Transactional
    public int cancelUnfilledOrders() {
        List<OrderRecord> unfilled = orderRepository.findByStatusIn(
                List.of(OrderStatus.ACCEPTED, OrderStatus.PARTIAL));
        for (OrderRecord order : unfilled) {
            try {
                broker.cancelOrder(order.getClientOrderId(), order.getBrokerOrderId());
                order.setStatus(OrderStatus.CANCELLED);
                orderRepository.save(order);
            } catch (Exception e) {
                log.warn("[order] cancel failed for {} (will re-check on next boot): {}",
                        order.getClientOrderId(), e.getMessage());
            }
        }
        if (!unfilled.isEmpty()) {
            log.info("[order] EOD cleanup cancelled {} unfilled order(s)", unfilled.size());
        }
        return unfilled.size();
    }

    /** One-line end-of-day summary for the shutdown notification. */
    public String dailySummary(LocalDate tradeDate) {
        List<OrderRecord> today = orderRepository.findByPlanDate(tradeDate);
        long buys = today.stream().filter(o -> o.getSide() == OrderSide.BUY).count();
        long sells = today.stream().filter(o -> o.getSide() == OrderSide.SELL).count();
        int open = openPositionCount();
        return String.format("주문 매수 %d · 매도 %d · 보유 %d · 당일 실현손익 %s원",
                buys, sells, open, dailyRealizedPnl().toPlainString());
    }

    // ── persistence helpers ────────────────────────────────────────────
    private void saveOrder(String clientOrderId, LocalDate planDate, String symbol, OrderSide side,
                           int qty, BigDecimal price, OrderResult result) {
        orderRepository.save(OrderRecord.builder()
                .clientOrderId(clientOrderId)
                .planDate(planDate)
                .symbol(symbol)
                .side(side)
                .quantity(qty)
                .priceKrw(price)
                .status(result.status())
                .brokerOrderId(result.brokerOrderId())
                .filledQuantity(result.filledQuantity())
                .avgFillPriceKrw(result.avgFillPriceKrw())
                .createdAt(Instant.now(clock))
                .build());
    }

    private void openPosition(PlanCandidate candidate, LocalDate planDate, int qty, BigDecimal fillPrice) {
        positionRepository.save(PositionRecord.builder()
                .symbol(candidate.getSymbol())
                .quantity(qty)
                .avgPriceKrw(fillPrice)
                .planDate(planDate)
                .targetPriceKrw(candidate.getExit().getTargetPriceKrw())
                .stopLossPriceKrw(candidate.getExit().getStopLossPriceKrw())
                .openedAt(Instant.now(clock))
                .status(PositionStatus.OPEN)
                .build());
    }

    private void closePosition(PositionRecord position, BigDecimal fillPrice) {
        BigDecimal pnl = fillPrice.subtract(position.getAvgPriceKrw())
                .multiply(BigDecimal.valueOf(position.getQuantity()));
        position.setStatus(PositionStatus.CLOSED);
        position.setRealizedPnlKrw(pnl);
        position.setClosedAt(Instant.now(clock));
        positionRepository.save(position);
    }

    // ── risk-context helpers ───────────────────────────────────────────
    private BigDecimal currentTotalInvested() {
        return positionRepository.findByStatus(PositionStatus.OPEN).stream()
                .map(p -> p.getAvgPriceKrw().multiply(BigDecimal.valueOf(p.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int openPositionCount() {
        return positionRepository.findByStatus(PositionStatus.OPEN).size();
    }

    /** Realized P&L for positions closed today (KST), the daily-loss guard input. */
    private BigDecimal dailyRealizedPnl() {
        LocalDate today = LocalDate.now(clock.withZone(MarketClock.KST));
        List<PositionRecord> closed = positionRepository.findByStatus(PositionStatus.CLOSED);
        return closed.stream()
                .filter(p -> p.getClosedAt() != null
                        && p.getClosedAt().atZone(MarketClock.KST).toLocalDate().equals(today))
                .map(p -> p.getRealizedPnlKrw() == null ? BigDecimal.ZERO : p.getRealizedPnlKrw())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void notify(NotificationMessage.Severity severity, String title, String body) {
        try {
            notificationService.broadcast(NotificationMessage.builder()
                    .severity(severity).title(title).body(body).build());
        } catch (Exception e) {
            log.warn("[order] notification failed (ignored): {}", e.getMessage());
        }
    }
}

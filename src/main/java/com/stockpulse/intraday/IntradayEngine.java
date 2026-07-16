package com.stockpulse.intraday;

import com.stockpulse.broker.BrokerClient;
import com.stockpulse.broker.Quote;
import com.stockpulse.config.BrokerProperties;
import com.stockpulse.intraday.order.OrderService;
import com.stockpulse.intraday.position.PositionManager;
import com.stockpulse.intraday.signal.SignalEvaluator;
import com.stockpulse.notification.NotificationMessage;
import com.stockpulse.notification.NotificationService;
import com.stockpulse.plan.PlanCandidate;
import com.stockpulse.plan.TradingPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The intraday engine's brain: holds the day's plan and performs one poll cycle (tick). It does
 * NOT own scheduling or process lifecycle — {@link com.stockpulse.intraday.poll.QuotePoller}
 * drives ticks and shutdown. Exits are monitored every tick (even under kill switch, to let risk
 * close); new entries stop under the kill switch or during the EOD cleanup window.
 */
@Slf4j
@Component
public class IntradayEngine {

    private final PlanLoader planLoader;
    private final OrderService orderService;
    private final PositionManager positionManager;
    private final BrokerClient broker;
    private final SignalEvaluator signalEvaluator;
    private final MarketClock marketClock;
    private final KillSwitch killSwitch;
    private final BrokerProperties brokerProperties;
    private final NotificationService notificationService;

    private volatile boolean active;
    private volatile LocalDate activeDate;
    private volatile TradingPlan activePlan; // null => exit-only mode

    public IntradayEngine(PlanLoader planLoader,
                          OrderService orderService,
                          PositionManager positionManager,
                          BrokerClient broker,
                          SignalEvaluator signalEvaluator,
                          MarketClock marketClock,
                          KillSwitch killSwitch,
                          BrokerProperties brokerProperties,
                          NotificationService notificationService) {
        this.planLoader = planLoader;
        this.orderService = orderService;
        this.positionManager = positionManager;
        this.broker = broker;
        this.signalEvaluator = signalEvaluator;
        this.marketClock = marketClock;
        this.killSwitch = killSwitch;
        this.brokerProperties = brokerProperties;
        this.notificationService = notificationService;
    }

    /** Boot-time setup: pick the trading date, load the plan (or exit-only), announce mode. */
    public void initialize() {
        activeDate = marketClock.today();
        if (!marketClock.isTradingDay(activeDate)) {
            active = false;
            log.info("[engine] {} is not a trading day — engine idle", activeDate);
            return;
        }
        activePlan = planLoader.load(activeDate).orElse(null);
        active = true;

        String mode = brokerProperties.getMode().name();
        if (activePlan == null) {
            log.warn("[engine] no usable plan for {} — EXIT-ONLY mode (broker={}, mode={})",
                    activeDate, broker.name(), mode);
            notify(NotificationMessage.Severity.FAILURE, "⚠️ 장중 엔진: 플랜 없음 (청산 전용)",
                    activeDate + " 플랜이 없어 신규 진입 없이 보유 포지션 청산만 수행합니다.");
        } else {
            int n = activePlan.getCandidates() == null ? 0 : activePlan.getCandidates().size();
            log.info("[engine] initialized for {} — {} candidate(s), broker={}, mode={}",
                    activeDate, n, broker.name(), mode);
            notify(NotificationMessage.Severity.SUCCESS, "▶️ 장중 엔진 시작 (" + mode + ")",
                    activeDate + " 후보 " + n + "건 · broker=" + broker.name());
        }
    }

    public boolean isActive() {
        return active;
    }

    /** One poll cycle. {@code allowEntries} is false during the EOD cleanup window. */
    public void tick(Instant now, boolean allowEntries) {
        // Exits are always monitored — closing risk must never be blocked.
        positionManager.monitorExits(activeDate);

        if (allowEntries && activePlan != null && !killSwitch.isEngaged()) {
            attemptEntries(now);
        }
    }

    private void attemptEntries(Instant now) {
        for (PlanCandidate candidate : activePlan.getCandidates()) {
            try {
                Quote quote = broker.getQuote(candidate.getSymbol());
                if (signalEvaluator.shouldEnter(candidate, quote)) {
                    orderService.submitBuy(activePlan, candidate, quote);
                }
            } catch (Exception e) {
                log.warn("[engine] entry check failed for {} (skipped this tick): {}",
                        candidate.getSymbol(), e.getMessage());
            }
        }
    }

    /** Cancel unfilled orders (EOD). Returns count cancelled. */
    public int eodCleanup() {
        return orderService.cancelUnfilledOrders();
    }

    /** Final summary for the shutdown notification. */
    public void notifyDailySummary() {
        String summary = orderService.dailySummary(activeDate);
        log.info("[engine] daily summary ({}): {}", activeDate, summary);
        notify(NotificationMessage.Severity.SUCCESS, "⏹️ 장중 엔진 종료 (" + activeDate + ")", summary);
    }

    private void notify(NotificationMessage.Severity severity, String title, String body) {
        try {
            notificationService.broadcast(NotificationMessage.builder()
                    .severity(severity).title(title).body(body).build());
        } catch (Exception e) {
            log.warn("[engine] notification failed (ignored): {}", e.getMessage());
        }
    }
}

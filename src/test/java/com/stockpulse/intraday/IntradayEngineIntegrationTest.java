package com.stockpulse.intraday;

import com.stockpulse.broker.BrokerMode;
import com.stockpulse.broker.FakeBrokerClient;
import com.stockpulse.config.BrokerProperties;
import com.stockpulse.config.IntradayProperties;
import com.stockpulse.intraday.domain.OrderRecordRepository;
import com.stockpulse.intraday.domain.PositionRecordRepository;
import com.stockpulse.intraday.domain.PositionStatus;
import com.stockpulse.intraday.order.OrderService;
import com.stockpulse.intraday.position.PositionManager;
import com.stockpulse.intraday.risk.RiskGuard;
import com.stockpulse.intraday.signal.ExitEvaluator;
import com.stockpulse.intraday.signal.SignalEvaluator;
import com.stockpulse.notification.NotificationService;
import com.stockpulse.plan.Entry;
import com.stockpulse.plan.Exit;
import com.stockpulse.plan.MarketContext;
import com.stockpulse.plan.PlanCandidate;
import com.stockpulse.plan.PlanConstraints;
import com.stockpulse.plan.PlanJsonSerializer;
import com.stockpulse.plan.Sizing;
import com.stockpulse.plan.TradingPlan;
import com.stockpulse.plan.TradingPlanEntity;
import com.stockpulse.plan.TradingPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tick of the intraday engine wired against the {@link FakeBrokerClient}: load a plan
 * from the DB, enter on a matching quote, then exit at target — verifying the whole vertical
 * slice (PlanLoader → SignalEvaluator → OrderService → PositionManager → ExitEvaluator) in paper
 * mode without a live broker.
 */
@DataJpaTest
class IntradayEngineIntegrationTest {

    @Autowired
    private TradingPlanRepository planRepository;
    @Autowired
    private OrderRecordRepository orderRepository;
    @Autowired
    private PositionRecordRepository positionRepository;

    // 2026-07-16 11:00 KST (Thursday, mid-session)
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-16T02:00:00Z"), ZoneOffset.UTC);
    private final LocalDate day = LocalDate.of(2026, 7, 16);

    private FakeBrokerClient broker;
    private IntradayEngine engine;

    @BeforeEach
    void setUp() {
        broker = new FakeBrokerClient(clock);
        BrokerProperties brokerProps = new BrokerProperties();
        brokerProps.setMode(BrokerMode.PAPER);
        IntradayProperties intradayProps = new IntradayProperties();
        KillSwitch killSwitch = new KillSwitch(intradayProps);
        RiskGuard riskGuard = new RiskGuard(intradayProps, killSwitch);
        SignalEvaluator signalEvaluator = new SignalEvaluator();
        NotificationService notifier = new NotificationService(List.of());

        OrderService orderService = new OrderService(broker, orderRepository, positionRepository,
                riskGuard, signalEvaluator, brokerProps, notifier, clock);
        PositionManager positionManager = new PositionManager(broker, positionRepository,
                new ExitEvaluator(), orderService);
        com.stockpulse.intraday.PlanLoader planLoader =
                new com.stockpulse.intraday.PlanLoader(planRepository, new PlanJsonSerializer());
        MarketClock marketClock = new MarketClock(intradayProps, clock);

        engine = new IntradayEngine(planLoader, orderService, positionManager, broker,
                signalEvaluator, marketClock, killSwitch, brokerProps, notifier);

        persistPlan();
        engine.initialize();
    }

    private void persistPlan() {
        PlanCandidate candidate = PlanCandidate.builder()
                .symbol("005930").name("삼성전자").matchedRules(List.of("momentum"))
                .entry(Entry.builder().type("limit").priceKrw(new BigDecimal("61000")).build())
                .exit(Exit.builder().targetPriceKrw(new BigDecimal("64000"))
                        .stopLossPriceKrw(new BigDecimal("59000")).build())
                .sizing(Sizing.builder().maxBudgetKrw(new BigDecimal("500000")).build())
                .build();
        TradingPlan plan = TradingPlan.builder()
                .schemaVersion(TradingPlan.SCHEMA_VERSION).planDate(day)
                .generatedAt(Instant.now(clock)).mode(TradingPlan.MODE_PLAN_ONLY)
                .constraints(PlanConstraints.builder()
                        .maxBudgetPerSymbolKrw(new BigDecimal("500000"))
                        .maxTotalBudgetKrw(new BigDecimal("2000000")).build())
                .marketContext(MarketContext.empty())
                .candidates(List.of(candidate)).warnings(List.of()).build();

        String json = new PlanJsonSerializer().toJson(plan);
        planRepository.save(TradingPlanEntity.builder()
                .planDate(day).schemaVersion(TradingPlan.SCHEMA_VERSION)
                .content(json).generatedAt(Instant.now(clock)).build());
    }

    @Test
    void entersOnMatchingQuoteThenExitsAtTarget() {
        // 1) price at/below entry limit → enter
        broker.setQuote("005930", new BigDecimal("60000"));
        engine.tick(Instant.now(clock), true);

        assertThat(positionRepository.findByStatus(PositionStatus.OPEN)).hasSize(1);
        assertThat(orderRepository.existsByClientOrderId("2026-07-16-005930-BUY")).isTrue();

        // 2) price reaches target → exit, position closed with realized profit
        broker.setQuote("005930", new BigDecimal("64000"));
        engine.tick(Instant.now(clock), true);

        assertThat(positionRepository.findByStatus(PositionStatus.OPEN)).isEmpty();
        assertThat(orderRepository.existsByClientOrderId("2026-07-16-005930-SELL-TARGET")).isTrue();
        // entry 61000 × 8, exit 64000 → +24000
        assertThat(positionRepository.findAll().get(0).getRealizedPnlKrw()).isEqualByComparingTo("24000");
    }
}

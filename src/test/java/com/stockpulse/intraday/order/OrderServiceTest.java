package com.stockpulse.intraday.order;

import com.stockpulse.broker.BrokerMode;
import com.stockpulse.broker.FakeBrokerClient;
import com.stockpulse.broker.Quote;
import com.stockpulse.config.BrokerProperties;
import com.stockpulse.config.IntradayProperties;
import com.stockpulse.intraday.KillSwitch;
import com.stockpulse.intraday.domain.OrderRecordRepository;
import com.stockpulse.intraday.domain.PositionRecord;
import com.stockpulse.intraday.domain.PositionRecordRepository;
import com.stockpulse.intraday.domain.PositionStatus;
import com.stockpulse.intraday.risk.RiskGuard;
import com.stockpulse.intraday.signal.SignalEvaluator;
import com.stockpulse.notification.NotificationService;
import com.stockpulse.plan.Entry;
import com.stockpulse.plan.Exit;
import com.stockpulse.plan.PlanCandidate;
import com.stockpulse.plan.PlanConstraints;
import com.stockpulse.plan.Sizing;
import com.stockpulse.plan.TradingPlan;
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

@DataJpaTest
class OrderServiceTest {

    @Autowired
    private OrderRecordRepository orderRepository;
    @Autowired
    private PositionRecordRepository positionRepository;

    private final LocalDate day = LocalDate.of(2026, 7, 16);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-16T02:00:00Z"), ZoneOffset.UTC);

    private FakeBrokerClient broker;
    private BrokerProperties brokerProps;
    private OrderService service;

    @BeforeEach
    void setUp() {
        broker = new FakeBrokerClient(clock);
        brokerProps = new BrokerProperties();
        IntradayProperties intradayProps = new IntradayProperties();
        RiskGuard riskGuard = new RiskGuard(intradayProps, new KillSwitch(intradayProps, clock));
        service = new OrderService(broker, orderRepository, positionRepository, riskGuard,
                new SignalEvaluator(), brokerProps, new NotificationService(List.of()), clock);
    }

    private TradingPlan planWith(PlanCandidate candidate) {
        return TradingPlan.builder()
                .schemaVersion(TradingPlan.SCHEMA_VERSION).planDate(day).mode(TradingPlan.MODE_PLAN_ONLY)
                .constraints(PlanConstraints.builder()
                        .maxBudgetPerSymbolKrw(new BigDecimal("500000"))
                        .maxTotalBudgetKrw(new BigDecimal("2000000")).build())
                .candidates(List.of(candidate)).build();
    }

    private PlanCandidate candidate() {
        return PlanCandidate.builder()
                .symbol("005930").name("삼성전자")
                .matchedRules(List.of("r1"))
                .entry(Entry.builder().type("limit").priceKrw(new BigDecimal("61000")).build())
                .exit(Exit.builder().targetPriceKrw(new BigDecimal("64000"))
                        .stopLossPriceKrw(new BigDecimal("59000")).build())
                .sizing(Sizing.builder().maxBudgetKrw(new BigDecimal("500000")).build())
                .build();
    }

    private Quote quote(String price) {
        return new Quote("005930", new BigDecimal(price), Instant.now(clock));
    }

    @Test
    void dryRunRecordsButDoesNotOpenPosition() {
        brokerProps.setMode(BrokerMode.DRY_RUN);

        SubmitOutcome outcome = service.submitBuy(planWith(candidate()), candidate(), quote("60000"));

        assertThat(outcome).isEqualTo(SubmitOutcome.DRY_RUN);
        assertThat(orderRepository.findByClientOrderId("2026-07-16-005930-BUY")).isPresent();
        assertThat(positionRepository.findByStatus(PositionStatus.OPEN)).isEmpty(); // nothing really bought
    }

    @Test
    void paperBuyFillsAndOpensPositionWithCopiedExits() {
        brokerProps.setMode(BrokerMode.PAPER);

        SubmitOutcome outcome = service.submitBuy(planWith(candidate()), candidate(), quote("60000"));

        assertThat(outcome).isEqualTo(SubmitOutcome.FILLED);
        PositionRecord pos = positionRepository.findBySymbolAndStatus("005930", PositionStatus.OPEN).orElseThrow();
        // 500000 budget / 61000 entry = 8 shares
        assertThat(pos.getQuantity()).isEqualTo(8);
        // Exit targets are copied from the plan onto the position (survives past plan day).
        assertThat(pos.getTargetPriceKrw()).isEqualByComparingTo("64000");
        assertThat(pos.getStopLossPriceKrw()).isEqualByComparingTo("59000");
    }

    @Test
    void duplicateBuyIsSkipped() {
        brokerProps.setMode(BrokerMode.PAPER);
        service.submitBuy(planWith(candidate()), candidate(), quote("60000"));

        SubmitOutcome second = service.submitBuy(planWith(candidate()), candidate(), quote("60000"));

        assertThat(second).isEqualTo(SubmitOutcome.DUPLICATE);
        assertThat(orderRepository.findAll()).hasSize(1); // idempotent — no second order
    }

    @Test
    void sellClosesPositionAndRealizesPnl() {
        brokerProps.setMode(BrokerMode.PAPER);
        service.submitBuy(planWith(candidate()), candidate(), quote("60000"));
        PositionRecord pos = positionRepository.findBySymbolAndStatus("005930", PositionStatus.OPEN).orElseThrow();

        SubmitOutcome outcome = service.submitSell(pos, quote("64000"),
                com.stockpulse.intraday.domain.ExitReason.TARGET, day);

        assertThat(outcome).isEqualTo(SubmitOutcome.FILLED);
        PositionRecord closed = positionRepository.findById(pos.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(PositionStatus.CLOSED);
        // (64000 - 61000) * 8 = 24000
        assertThat(closed.getRealizedPnlKrw()).isEqualByComparingTo("24000");
    }
}

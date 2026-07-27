package com.stockpulse.plan;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.plan.rule.PlanRule;
import com.stockpulse.plan.rule.RuleCondition;
import com.stockpulse.plan.rule.RuleEvaluator;
import com.stockpulse.timeseries.DailyStockSnapshot;
import com.stockpulse.timeseries.DailyStockSnapshotRepository;
import com.stockpulse.timeseries.DerivedMetrics;
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
class PlanServiceTest {

    @Autowired
    private DailyStockSnapshotRepository repository;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T06:00:00Z"), ZoneOffset.UTC);
    private final LocalDate day = LocalDate.of(2026, 7, 15);

    private StockPulseProperties properties;
    private PlanService service;

    @BeforeEach
    void setUp() {
        properties = new StockPulseProperties();
        PlanRule rule = new PlanRule();
        rule.setId("momentum-up-5pct");
        RuleCondition c = new RuleCondition();
        c.setMetric("changeRate1d");
        c.setOp(RuleCondition.Op.GTE);
        c.setValue(new BigDecimal("5.0"));
        rule.setConditions(List.of(c));
        properties.getPlan().setRules(List.of(rule));

        service = new PlanService(new RuleEvaluator(), repository, properties, clock);
    }

    private DailyStockSnapshot snap(String symbol, double price, DerivedMetrics d) {
        return DailyStockSnapshot.builder()
                .symbol(symbol).name("삼성전자").tradeDate(day)
                .price(BigDecimal.valueOf(price)).source("dummy")
                .collectedAt(Instant.parse("2026-07-15T06:00:00Z"))
                .derived(d).build();
    }

    @Test
    void matchingSnapshotBecomesCandidateWithComputedTargets() {
        DailyStockSnapshot s = snap("005930", 10000,
                DerivedMetrics.builder().changeRate1d(new BigDecimal("6.0")).build());

        TradingPlan plan = service.generate(day, List.of(s), MarketContext.empty());

        assertThat(plan.getSchemaVersion()).isEqualTo(TradingPlan.SCHEMA_VERSION);
        assertThat(plan.getMode()).isEqualTo(TradingPlan.MODE_PLAN_ONLY);
        assertThat(plan.getCandidates()).hasSize(1);

        PlanCandidate c = plan.getCandidates().get(0);
        assertThat(c.getMatchedRules()).containsExactly("momentum-up-5pct");
        assertThat(c.getEntry().getPriceKrw()).isEqualByComparingTo("10000");   // offset 0%
        assertThat(c.getExit().getTargetPriceKrw()).isEqualByComparingTo("10500"); // +5%
        assertThat(c.getExit().getStopLossPriceKrw()).isEqualByComparingTo("9700"); // -3%
        assertThat(c.getSizing().getMaxBudgetKrw()).isEqualByComparingTo("500000");
        assertThat(c.getEvidence().getChangeRate1d()).isEqualByComparingTo("6.0");
    }

    @Test
    void noMatchYieldsEmptyButValidPlan() {
        DailyStockSnapshot s = snap("005930", 10000,
                DerivedMetrics.builder().changeRate1d(new BigDecimal("1.0")).build());

        TradingPlan plan = service.generate(day, List.of(s), MarketContext.empty());

        assertThat(plan.getCandidates()).isEmpty();
        assertThat(plan.getPlanDate()).isEqualTo(day);
    }

    @Test
    void staleDataProducesWarning() {
        // A prior snapshot 10 days before the run date (> default maxDataGapDays=4).
        repository.save(DailyStockSnapshot.builder()
                .symbol("005930").name("삼성전자").tradeDate(day.minusDays(10))
                .price(BigDecimal.valueOf(9000)).source("dummy")
                .collectedAt(Instant.parse("2026-07-05T06:00:00Z"))
                .derived(DerivedMetrics.empty()).build());

        DailyStockSnapshot today = snap("005930", 10000, DerivedMetrics.empty());
        TradingPlan plan = service.generate(day, List.of(today), MarketContext.empty());

        assertThat(plan.getWarnings()).anyMatch(w -> w.startsWith("stale-data:005930"));
    }
}

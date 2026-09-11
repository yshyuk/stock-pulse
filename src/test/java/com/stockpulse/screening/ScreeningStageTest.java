package com.stockpulse.screening;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.StockMetric;
import com.stockpulse.plan.rule.PlanRule;
import com.stockpulse.plan.rule.RuleCondition;
import com.stockpulse.plan.rule.RuleEvaluator;
import com.stockpulse.timeseries.DailyStockSnapshot;
import com.stockpulse.timeseries.DerivedMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScreeningStageTest {

    private static final LocalDate RUN_DATE = LocalDate.of(2026, 9, 9);
    /** price x volume = 2,000,000,000 — comfortably over the default 1e9 liquidity floor. */
    private static final long LIQUID_VOLUME = 200_000L;
    private static final double LIQUID_PRICE = 10_000;

    private StockPulseProperties properties;
    private ScreeningStage stage;

    @BeforeEach
    void setUp() {
        properties = new StockPulseProperties();
        properties.getScreening().setEnabled(true);
        stage = new ScreeningStage(properties, new RuleEvaluator());
    }

    @Test
    void onlyStocksMatchingARulePass() {
        properties.getScreening().setRules(List.of(
                rule("surge-up", "changeRate1d", RuleCondition.Op.GTE, 5.0)));

        List<DailyStockSnapshot> snapshots = List.of(
                snapshot("000001", changeRate1d(6.0)),
                snapshot("000002", changeRate1d(1.0)));

        ScreeningResult result = stage.select(snapshots, List.of(metric("000001"), metric("000002")));

        assertThat(result.metrics()).extracting(StockMetric::getSymbol).containsExactly("000001");
        assertThat(result.totalEvaluated()).isEqualTo(2);
        assertThat(result.matchedCount()).isEqualTo(1);
    }

    @Test
    void stocksBelowTheLiquidityFloorNeverMatch() {
        properties.getScreening().setRules(List.of(
                rule("surge-up", "changeRate1d", RuleCondition.Op.GTE, 5.0)));

        // Both surge 6%, but the second trades 100,000 KRW/day — far under the 1e9 floor.
        List<DailyStockSnapshot> snapshots = List.of(
                snapshot("000001", changeRate1d(6.0)),
                snapshot("000002", 1_000, 100L, changeRate1d(6.0)));

        ScreeningResult result = stage.select(snapshots, List.of(metric("000001"), metric("000002")));

        assertThat(result.metrics()).extracting(StockMetric::getSymbol).containsExactly("000001");
    }

    @Test
    void stocksWithNoPriceOrVolumeNeverMatch() {
        properties.getScreening().setRules(List.of(
                rule("surge-up", "changeRate1d", RuleCondition.Op.GTE, 5.0)));

        List<DailyStockSnapshot> snapshots = List.of(
                snapshot("000001", LIQUID_PRICE, null, changeRate1d(6.0)));

        ScreeningResult result = stage.select(snapshots, List.of(metric("000001")));

        assertThat(result.metrics()).isEmpty();
    }

    @Test
    void ranksStocksMatchingMoreRulesFirst() {
        properties.getScreening().setRules(surgeAndVolumeRules());

        List<DailyStockSnapshot> snapshots = List.of(
                snapshot("000001", derived(6.0, 1.0)),   // surge only        -> 1 rule
                snapshot("000002", derived(6.0, 4.0)));  // surge + volume    -> 2 rules

        ScreeningResult result = stage.select(snapshots, List.of(metric("000001"), metric("000002")));

        assertThat(result.metrics()).extracting(StockMetric::getSymbol)
                .containsExactly("000002", "000001");
    }

    @Test
    void breaksRuleCountTiesByVolumeRatioDescending() {
        properties.getScreening().setRules(surgeAndVolumeRules());

        // Both match surge-up only (volume ratio under 3.0), so the volume ratio orders them.
        List<DailyStockSnapshot> snapshots = List.of(
                snapshot("000001", derived(6.0, 1.0)),
                snapshot("000002", derived(6.0, 2.5)));

        ScreeningResult result = stage.select(snapshots, List.of(metric("000001"), metric("000002")));

        assertThat(result.metrics()).extracting(StockMetric::getSymbol)
                .containsExactly("000002", "000001");
    }

    @Test
    void breaksVolumeTiesByHowFarThePriceMoved() {
        properties.getScreening().setRules(surgeAndVolumeRules());

        // Cold start: no history means no volume ratio, so without this tiebreak the "top 30"
        // would just be the 30 lowest stock codes rather than the 30 most unusual stocks.
        List<DailyStockSnapshot> snapshots = List.of(
                snapshot("000001", derived(6.0, null)),
                snapshot("000002", derived(21.0, null)));

        ScreeningResult result = stage.select(snapshots, List.of(metric("000001"), metric("000002")));

        assertThat(result.metrics()).extracting(StockMetric::getSymbol)
                .containsExactly("000002", "000001");
    }

    @Test
    void ranksBigDropsAsHighlyAsBigRises() {
        properties.getScreening().setRules(surgeAndVolumeRules());

        // Magnitude only — a -20% day is exactly as noteworthy as a +20% one.
        List<DailyStockSnapshot> snapshots = List.of(
                snapshot("000001", derived(6.0, null)),
                snapshot("000002", derived(-20.0, null)));

        ScreeningResult result = stage.select(snapshots, List.of(metric("000001"), metric("000002")));

        assertThat(result.metrics()).extracting(StockMetric::getSymbol)
                .containsExactly("000002", "000001");
    }

    @Test
    void breaksRemainingTiesBySymbolSoRerunsAreIdentical() {
        properties.getScreening().setRules(surgeAndVolumeRules());

        // Identical on every ranking key -> only the symbol can order them, deterministically.
        List<DailyStockSnapshot> snapshots = List.of(
                snapshot("000009", derived(6.0, 1.0)),
                snapshot("000001", derived(6.0, 1.0)));

        ScreeningResult result = stage.select(snapshots, List.of(metric("000009"), metric("000001")));

        assertThat(result.metrics()).extracting(StockMetric::getSymbol)
                .containsExactly("000001", "000009");
    }

    @Test
    void capsTheListAtMaxCandidatesKeepingTheStrongest() {
        properties.getScreening().setRules(surgeAndVolumeRules());
        properties.getScreening().setMaxCandidates(2);

        List<DailyStockSnapshot> snapshots = List.of(
                snapshot("000001", derived(6.0, 1.0)),   // 1 rule
                snapshot("000002", derived(6.0, 4.0)),   // 2 rules
                snapshot("000003", derived(6.0, 5.0)));  // 2 rules, bigger volume anomaly

        ScreeningResult result = stage.select(snapshots,
                List.of(metric("000001"), metric("000002"), metric("000003")));

        assertThat(result.metrics()).extracting(StockMetric::getSymbol)
                .containsExactly("000003", "000002");
        // matchedCount reports what matched BEFORE the cap, so the report can say "3 of which 2 shown".
        assertThat(result.matchedCount()).isEqualTo(3);
        assertThat(result.totalEvaluated()).isEqualTo(3);
    }

    @Test
    void noRulesConfiguredYieldsNothingRatherThanEverything() {
        properties.getScreening().setRules(List.of());

        ScreeningResult result = stage.select(
                List.of(snapshot("000001", changeRate1d(6.0))), List.of(metric("000001")));

        // Fail closed: passing everything through would silently uncap the Claude bill.
        assertThat(result.metrics()).isEmpty();
    }

    @Test
    void disabledScreeningPassesEveryStockThrough() {
        properties.getScreening().setEnabled(false);
        properties.getScreening().setRules(surgeAndVolumeRules());

        // Would fail every rule and the liquidity floor if screening were on.
        List<DailyStockSnapshot> snapshots = List.of(snapshot("000001", 10, 1L, changeRate1d(0.1)));

        ScreeningResult result = stage.select(snapshots, List.of(metric("000001")));

        assertThat(result.metrics()).extracting(StockMetric::getSymbol).containsExactly("000001");
    }

    @Test
    void summaryMarkdownStatesHowMuchWasNotShown() {
        properties.getScreening().setRules(surgeAndVolumeRules());
        properties.getScreening().setMaxCandidates(1);

        List<DailyStockSnapshot> snapshots = List.of(
                snapshot("000001", derived(6.0, 1.0)),
                snapshot("000002", derived(6.0, 4.0)));

        String md = stage.summaryMarkdown(
                stage.select(snapshots, List.of(metric("000001"), metric("000002"))));

        assertThat(md).contains("전체 2종목", "조건 매칭 2종목", "리포트 표시 1종목");
        // The kept stock's rules are attributed, so a reader knows WHY it is here.
        assertThat(md).contains("000002").contains("surge-up").contains("volume-spike");
    }

    @Test
    void summaryMarkdownIsEmptyWhenScreeningIsOff() {
        properties.getScreening().setEnabled(false);

        String md = stage.summaryMarkdown(
                stage.select(List.of(snapshot("000001", changeRate1d(1.0))), List.of(metric("000001"))));

        assertThat(md).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private PlanRule rule(String id, String metric, RuleCondition.Op op, double value) {
        RuleCondition c = new RuleCondition();
        c.setMetric(metric);
        c.setOp(op);
        c.setValue(BigDecimal.valueOf(value));
        PlanRule r = new PlanRule();
        r.setId(id);
        r.setConditions(List.of(c));
        return r;
    }

    private List<PlanRule> surgeAndVolumeRules() {
        return List.of(
                rule("surge-up", "changeRate1d", RuleCondition.Op.GTE, 5.0),
                rule("surge-down", "changeRate1d", RuleCondition.Op.LTE, -5.0),
                rule("volume-spike", "volumeMa20Ratio", RuleCondition.Op.GTE, 3.0));
    }

    private DerivedMetrics derived(Double changeRate1d, Double volumeMa20Ratio) {
        return DerivedMetrics.builder()
                .changeRate1d(changeRate1d == null ? null : BigDecimal.valueOf(changeRate1d))
                .volumeMa20Ratio(volumeMa20Ratio == null ? null : BigDecimal.valueOf(volumeMa20Ratio))
                .build();
    }

    private DerivedMetrics changeRate1d(double pct) {
        return DerivedMetrics.builder().changeRate1d(BigDecimal.valueOf(pct)).build();
    }

    private DailyStockSnapshot snapshot(String symbol, DerivedMetrics derived) {
        return snapshot(symbol, LIQUID_PRICE, LIQUID_VOLUME, derived);
    }

    private DailyStockSnapshot snapshot(String symbol, double price, Long volume, DerivedMetrics derived) {
        return DailyStockSnapshot.builder()
                .symbol(symbol)
                .name("종목" + symbol)
                .tradeDate(RUN_DATE)
                .price(BigDecimal.valueOf(price))
                .volume(volume)
                .derived(derived)
                .source("test")
                .build();
    }

    private StockMetric metric(String symbol) {
        return StockMetric.builder()
                .symbol(symbol)
                .name("종목" + symbol)
                .price(BigDecimal.valueOf(LIQUID_PRICE))
                .volume(LIQUID_VOLUME)
                .build();
    }
}

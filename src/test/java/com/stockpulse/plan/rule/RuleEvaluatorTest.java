package com.stockpulse.plan.rule;

import com.stockpulse.timeseries.DailyStockSnapshot;
import com.stockpulse.timeseries.DerivedMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEvaluatorTest {

    private final RuleEvaluator evaluator = new RuleEvaluator();

    private RuleCondition cond(String metric, RuleCondition.Op op, String value) {
        RuleCondition c = new RuleCondition();
        c.setMetric(metric);
        c.setOp(op);
        c.setValue(new BigDecimal(value));
        return c;
    }

    private PlanRule rule(String id, RuleCondition... conditions) {
        PlanRule r = new PlanRule();
        r.setId(id);
        r.setConditions(List.of(conditions));
        return r;
    }

    private DailyStockSnapshot snapshot(DerivedMetrics derived) {
        return DailyStockSnapshot.builder().symbol("005930").derived(derived).build();
    }

    @Test
    void matchesRuleWhenAllConditionsHold() {
        DailyStockSnapshot s = snapshot(DerivedMetrics.builder()
                .volumeMa20Ratio(new BigDecimal("3.5"))
                .changeRate1d(new BigDecimal("6.0"))
                .build());

        PlanRule r = rule("surge-and-up",
                cond("volumeMa20Ratio", RuleCondition.Op.GTE, "3.0"),
                cond("changeRate1d", RuleCondition.Op.GTE, "5.0"));

        assertThat(evaluator.matchedRuleIds(s, List.of(r))).containsExactly("surge-and-up");
    }

    @Test
    void doesNotMatchWhenOneConditionFails() {
        DailyStockSnapshot s = snapshot(DerivedMetrics.builder()
                .volumeMa20Ratio(new BigDecimal("3.5"))
                .changeRate1d(new BigDecimal("2.0")) // below 5
                .build());

        PlanRule r = rule("surge-and-up",
                cond("volumeMa20Ratio", RuleCondition.Op.GTE, "3.0"),
                cond("changeRate1d", RuleCondition.Op.GTE, "5.0"));

        assertThat(evaluator.matchedRuleIds(s, List.of(r))).isEmpty();
    }

    @Test
    void nullMetricNeverMatches() {
        // volumeMa20Ratio null (insufficient history) must NOT satisfy the condition
        DailyStockSnapshot s = snapshot(DerivedMetrics.builder().volumeMa20Ratio(null).build());
        PlanRule r = rule("surge", cond("volumeMa20Ratio", RuleCondition.Op.GTE, "3.0"));

        assertThat(evaluator.matchedRuleIds(s, List.of(r))).isEmpty();
    }

    @Test
    void rulesAreOred_multipleMatchesReported() {
        DailyStockSnapshot s = snapshot(DerivedMetrics.builder()
                .volumeMa20Ratio(new BigDecimal("4.0"))
                .changeRate1d(new BigDecimal("7.0"))
                .build());

        PlanRule volume = rule("volume", cond("volumeMa20Ratio", RuleCondition.Op.GTE, "3.0"));
        PlanRule momentum = rule("momentum", cond("changeRate1d", RuleCondition.Op.GTE, "5.0"));

        assertThat(evaluator.matchedRuleIds(s, List.of(volume, momentum)))
                .containsExactly("volume", "momentum");
    }

    @Test
    void streakDaysConditionSupported() {
        DailyStockSnapshot s = snapshot(DerivedMetrics.builder().streakDays(3).build());
        PlanRule r = rule("three-up", cond("streakDays", RuleCondition.Op.GTE, "3"));
        assertThat(evaluator.matchedRuleIds(s, List.of(r))).containsExactly("three-up");
    }

    @Test
    void noRules_noMatch() {
        DailyStockSnapshot s = snapshot(DerivedMetrics.builder().changeRate1d(BigDecimal.TEN).build());
        assertThat(evaluator.matchedRuleIds(s, List.of())).isEmpty();
    }
}

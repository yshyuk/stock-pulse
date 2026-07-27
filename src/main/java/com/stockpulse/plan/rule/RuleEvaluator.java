package com.stockpulse.plan.rule;

import com.stockpulse.timeseries.DailyStockSnapshot;
import com.stockpulse.timeseries.DerivedMetrics;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, deterministic matching of a snapshot against the configured plan rules.
 *
 * <p>Semantics: conditions within a rule are ANDed; a stock matches a rule only if every
 * condition holds. A condition whose metric is {@code null} (insufficient history) does NOT
 * hold — so a lack of data never produces a false signal. Returns the ids of all matched rules
 * (empty means "not a candidate").
 */
@Component
public class RuleEvaluator {

    public List<String> matchedRuleIds(DailyStockSnapshot snapshot, List<PlanRule> rules) {
        List<String> matched = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            return matched;
        }
        for (PlanRule rule : rules) {
            if (rule.getConditions() != null && !rule.getConditions().isEmpty() && allHold(snapshot, rule)) {
                matched.add(rule.getId());
            }
        }
        return matched;
    }

    private boolean allHold(DailyStockSnapshot snapshot, PlanRule rule) {
        for (RuleCondition c : rule.getConditions()) {
            if (!holds(snapshot, c)) {
                return false;
            }
        }
        return true;
    }

    private boolean holds(DailyStockSnapshot snapshot, RuleCondition c) {
        BigDecimal actual = metricValue(snapshot, c.getMetric());
        if (actual == null || c.getValue() == null || c.getOp() == null) {
            return false;
        }
        int cmp = actual.compareTo(c.getValue());
        return switch (c.getOp()) {
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
        };
    }

    /** Resolves a supported metric name to its value on the snapshot, or null if unavailable. */
    private BigDecimal metricValue(DailyStockSnapshot snapshot, String metric) {
        DerivedMetrics d = snapshot.getDerived();
        if (metric == null || d == null) {
            return null;
        }
        return switch (metric) {
            case "changeRate1d" -> d.getChangeRate1d();
            case "changeRate5d" -> d.getChangeRate5d();
            case "changeRate20d" -> d.getChangeRate20d();
            case "volumeMa20Ratio" -> d.getVolumeMa20Ratio();
            case "streakDays" -> d.getStreakDays() == null ? null : BigDecimal.valueOf(d.getStreakDays());
            case "volatility20d" -> d.getVolatility20d();
            case "rangePosition" -> d.getRangePosition();
            default -> null;
        };
    }
}

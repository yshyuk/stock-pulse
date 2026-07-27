package com.stockpulse.plan.rule;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A single threshold condition on one derived indicator, e.g. {@code volumeMa20Ratio >= 3.0}.
 * Bound from {@code stockpulse.plan.rules[].conditions[]} in yml.
 */
@Getter
@Setter
public class RuleCondition {

    /** Comparison operators supported in v1. */
    public enum Op {
        GT, GTE, LT, LTE
    }

    /**
     * Derived-metric name to test. Supported: changeRate1d, changeRate5d, changeRate20d,
     * volumeMa20Ratio, streakDays, volatility20d, rangePosition.
     */
    private String metric;

    private Op op;

    private BigDecimal value;
}

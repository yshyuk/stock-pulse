package com.stockpulse.plan.rule;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * One externally-configured plan rule (bound from {@code stockpulse.plan.rules[]}).
 *
 * <p>v1 expressiveness: a rule matches a stock when ALL of its {@link #conditions} hold
 * (logical AND). A stock becomes a candidate if it matches AT LEAST ONE rule (rules are ORed).
 */
@Getter
@Setter
public class PlanRule {

    private String id;

    private String description;

    private List<RuleCondition> conditions = new ArrayList<>();
}

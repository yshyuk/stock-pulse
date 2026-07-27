package com.stockpulse.plan;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;

/** Position sizing limit for a {@link PlanCandidate}. */
@Value
@Builder
@Jacksonized
public class Sizing {

    /** Max budget to allocate to this symbol (KRW), bounded by the plan constraints. */
    BigDecimal maxBudgetKrw;
}

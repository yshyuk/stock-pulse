package com.stockpulse.plan;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.time.Instant;

/** Plan-wide risk limits (a value-object group on {@link TradingPlan}). */
@Value
@Builder
@Jacksonized
public class PlanConstraints {

    /** Max budget to allocate to any single symbol. */
    BigDecimal maxBudgetPerSymbolKrw;

    /** Max total budget across all candidates. */
    BigDecimal maxTotalBudgetKrw;

    /** The plan is valid until this instant (e.g. market close of {@code planDate}). */
    Instant validUntil;
}

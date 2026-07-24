package com.stockpulse.plan;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;

/** Deterministic exit targets for a {@link PlanCandidate}. */
@Value
@Builder
@Jacksonized
public class Exit {

    /** Take-profit price (KRW). */
    BigDecimal targetPriceKrw;

    /** Stop-loss price (KRW). */
    BigDecimal stopLossPriceKrw;
}

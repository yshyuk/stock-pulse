package com.stockpulse.plan;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;

/** Deterministic entry instruction for a {@link PlanCandidate}. */
@Value
@Builder
@Jacksonized
public class Entry {

    /** Order type, e.g. "limit" or "market". */
    String type;

    /** Limit price (KRW) computed from the rule's entry offset. */
    BigDecimal priceKrw;
}

package com.stockpulse.plan;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

/**
 * AI (Claude) reference opinion attached to a plan. This is advisory ONLY — the Part 2
 * execution engine must never use it to drive orders. Populated in Sprint 3 (F-11); null now.
 */
@Value
@Builder
@Jacksonized
public class PlanAdvisory {

    String source;
    String text;
    Instant generatedAt;
}

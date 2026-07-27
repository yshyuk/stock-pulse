package com.stockpulse.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The machine-readable trading plan produced by the dawn batch — the CONTRACT that the
 * future (Part 2) intraday execution engine will consume.
 *
 * <p>Design invariants (see docs/design-part1-night-batch.md, ADR-003):
 * <ul>
 *   <li>{@code schemaVersion} is always set; a consumer that does not support it must refuse.</li>
 *   <li>{@code mode} declares what the plan is FOR: {@code plan-only} (reference, triggers
 *       nothing) or {@code signal} (may be dispatched to an external execution system).
 *       Neither mode places an order from inside this process.</li>
 *   <li>Everything under {@code candidates} is the DETERMINISTIC output of the rule engine:
 *       same snapshots in → same plan out.</li>
 *   <li>{@code advisory} is an append-only, nullable REFERENCE field for AI opinion (consumers
 *       must not use it for execution). Left null unless second-stage analysis ran.</li>
 * </ul>
 *
 * <p>Schema v2 (stock-api signal integration, see stock-api ADR-002) adds the {@code signal}
 * mode and {@link PlanCandidate#getPriority() candidate priority}. v1 readers can still parse a
 * v2 plan — the added field is optional — but must refuse it on the version check, because the
 * v2 semantics (this plan may drive real conditions) are not what a v1 reader assumed.
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TradingPlan {

    public static final int SCHEMA_VERSION = 2;
    public static final String MODE_PLAN_ONLY = "plan-only";

    /**
     * Plan is intended for an external execution consumer (stock-api SignalIngest). Still no
     * order is placed here — the consumer applies its own guardrails and deterministic execution.
     */
    public static final String MODE_SIGNAL = "signal";

    int schemaVersion;
    LocalDate planDate;
    Instant generatedAt;
    String mode;

    PlanConstraints constraints;
    MarketContext marketContext;
    List<PlanCandidate> candidates;

    /** AI reference opinion; null in Part 1 (wired in Sprint 3, F-11). Never drives execution. */
    PlanAdvisory advisory;

    /** Non-fatal issues detected while building the plan (e.g. missing/stale data). */
    List<String> warnings;
}

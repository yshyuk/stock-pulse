package com.stockpulse.plan;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * One signal candidate: a stock that matched at least one plan rule, with the deterministic
 * entry/exit/sizing the rules produced and the evidence snapshot behind it.
 *
 * <p>No judgement here — a candidate is "these objective rules fired", not "buy this".
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class PlanCandidate {

    String symbol;
    String name;

    /** Ids of the rules this stock matched (for traceability). */
    List<String> matchedRules;

    /**
     * 1-based rank within the plan (1 = strongest). Deterministic: more matched rules first,
     * ties broken by symbol. A consumer that caps how many candidates it acts on truncates from
     * the bottom, so the cap drops the weakest signals rather than an arbitrary subset.
     *
     * <p>Nullable for schema v1 plans read back from storage.
     */
    Integer priority;

    Entry entry;
    Exit exit;
    Sizing sizing;

    /** The indicator values that led to the match, captured for reproducibility. */
    Evidence evidence;
}

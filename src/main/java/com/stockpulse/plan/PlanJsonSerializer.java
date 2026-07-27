package com.stockpulse.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Serializes a {@link TradingPlan} to its canonical JSON form, validating the contract
 * invariants FIRST. If validation fails the plan is not serialized (and, upstream, not stored)
 * — a polluted plan must never reach the Part 2 execution engine.
 *
 * <p>Uses a dedicated Jackson mapper (ISO-8601 dates, not numeric timestamps) so the plan
 * contract is stable regardless of the ambient Spring ObjectMapper config. No external
 * JSON-schema library is introduced; the schema is small enough to enforce in code.
 */
@Component
public class PlanJsonSerializer {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /**
     * Schema versions this serializer can still read. Plans written before the v2 bump remain
     * on disk and in the DB, and re-reading them (Part 2, re-runs) must not fail.
     */
    private static final Set<Integer> SUPPORTED_SCHEMA_VERSIONS = Set.of(1, TradingPlan.SCHEMA_VERSION);

    private static final Set<String> SUPPORTED_MODES =
            Set.of(TradingPlan.MODE_PLAN_ONLY, TradingPlan.MODE_SIGNAL);

    /** @throws PlanValidationException if the plan violates a schema invariant */
    public void validate(TradingPlan plan) {
        if (plan == null) {
            throw new PlanValidationException("plan is null");
        }
        if (!SUPPORTED_SCHEMA_VERSIONS.contains(plan.getSchemaVersion())) {
            throw new PlanValidationException("unsupported schemaVersion: " + plan.getSchemaVersion());
        }
        if (plan.getPlanDate() == null) {
            throw new PlanValidationException("planDate is required");
        }
        if (!SUPPORTED_MODES.contains(plan.getMode())) {
            throw new PlanValidationException("mode must be one of " + SUPPORTED_MODES
                    + " but was '" + plan.getMode() + "'");
        }
        if (plan.getCandidates() == null) {
            throw new PlanValidationException("candidates must be present (may be empty)");
        }
        for (PlanCandidate c : plan.getCandidates()) {
            validateCandidate(c, plan.getSchemaVersion());
        }
    }

    private void validateCandidate(PlanCandidate c, int schemaVersion) {
        if (c.getSymbol() == null || c.getSymbol().isBlank()) {
            throw new PlanValidationException("candidate symbol is required");
        }
        if (c.getMatchedRules() == null || c.getMatchedRules().isEmpty()) {
            throw new PlanValidationException("candidate " + c.getSymbol() + " has no matched rules");
        }
        if (c.getEntry() == null || c.getEntry().getPriceKrw() == null) {
            throw new PlanValidationException("candidate " + c.getSymbol() + " missing entry price");
        }
        if (c.getExit() == null
                || c.getExit().getTargetPriceKrw() == null
                || c.getExit().getStopLossPriceKrw() == null) {
            throw new PlanValidationException("candidate " + c.getSymbol() + " missing exit targets");
        }
        if (c.getSizing() == null || c.getSizing().getMaxBudgetKrw() == null) {
            throw new PlanValidationException("candidate " + c.getSymbol() + " missing sizing");
        }
        // v2 consumers truncate by priority when they cap the day's candidates; a missing rank
        // would make that truncation arbitrary, so require it from v2 on.
        if (schemaVersion >= 2 && c.getPriority() == null) {
            throw new PlanValidationException("candidate " + c.getSymbol() + " missing priority");
        }
    }

    /** Validates then serializes to pretty JSON. */
    public String toJson(TradingPlan plan) {
        validate(plan);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan);
        } catch (JsonProcessingException e) {
            throw new PlanValidationException("failed to serialize plan: " + e.getMessage(), e);
        }
    }

    /** Deserializes a plan from its JSON form and validates the contract invariants. */
    public TradingPlan fromJson(String json) {
        TradingPlan plan;
        try {
            plan = objectMapper.readValue(json, TradingPlan.class);
        } catch (JsonProcessingException e) {
            throw new PlanValidationException("failed to parse plan JSON: " + e.getMessage(), e);
        }
        validate(plan);
        return plan;
    }

    /** Thrown when a plan fails validation or serialization. */
    public static class PlanValidationException extends RuntimeException {
        public PlanValidationException(String message) {
            super(message);
        }

        public PlanValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

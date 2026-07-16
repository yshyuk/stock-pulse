package com.stockpulse.intraday;

import com.stockpulse.plan.PlanJsonSerializer;
import com.stockpulse.plan.TradingPlan;
import com.stockpulse.plan.TradingPlanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Loads the day's plan (the Part 1 → Part 2 contract) from the DB and validates it.
 *
 * <p>A missing plan is a valid, expected state (Part 1 may have skipped it on a degraded run,
 * F-13) — the engine then runs in exit-only mode. An unparseable or unsupported-schema plan is
 * NOT valid and surfaces as an empty result with an error log (the engine treats it like a
 * missing plan rather than trading on a plan it can't trust). Design ADR-008.
 */
@Slf4j
@Component
public class PlanLoader {

    private final TradingPlanRepository repository;
    private final PlanJsonSerializer serializer;

    public PlanLoader(TradingPlanRepository repository, PlanJsonSerializer serializer) {
        this.repository = repository;
        this.serializer = serializer;
    }

    /** The validated plan for {@code date}, or empty if absent/invalid (→ exit-only mode). */
    public Optional<TradingPlan> load(LocalDate date) {
        return repository.findByPlanDate(date)
                .flatMap(entity -> parse(entity.getContent(), date));
    }

    private Optional<TradingPlan> parse(String json, LocalDate date) {
        try {
            TradingPlan plan = serializer.fromJson(json); // validates schemaVersion + mode=plan-only
            log.info("[plan-loader] loaded plan for {} — {} candidate(s)",
                    date, plan.getCandidates() == null ? 0 : plan.getCandidates().size());
            return Optional.of(plan);
        } catch (Exception e) {
            log.error("[plan-loader] plan for {} is unusable ({}): running exit-only",
                    date, e.getMessage(), e);
            return Optional.empty();
        }
    }
}

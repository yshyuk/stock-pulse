package com.stockpulse.plan;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.timeseries.DailyStockSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Cohesive plan step for the pipeline: generate → validate → store to all sinks, and render the
 * human-readable summary. Extracting this from {@link com.stockpulse.batch.BatchPipeline} keeps
 * that orchestrator's dependency list small and groups the plan collaborators behind one seam.
 *
 * <p>Plan generation is an ENRICHMENT, not a gate: if it is disabled or fails validation, this
 * returns {@code null} (logged) and the batch continues.
 */
@Slf4j
@Component
public class PlanStage {

    private final PlanService planService;
    private final PlanJsonSerializer planJsonSerializer;
    private final List<PlanStore> planStores;
    private final PlanSummaryFormatter planSummaryFormatter;
    private final StockPulseProperties properties;

    public PlanStage(PlanService planService,
                     PlanJsonSerializer planJsonSerializer,
                     List<PlanStore> planStores,
                     PlanSummaryFormatter planSummaryFormatter,
                     StockPulseProperties properties) {
        this.planService = planService;
        this.planJsonSerializer = planJsonSerializer;
        this.planStores = planStores;
        this.planSummaryFormatter = planSummaryFormatter;
        this.properties = properties;
    }

    /**
     * Generates the plan for {@code runDate} and writes it to all plan stores.
     *
     * @return the plan (for the report summary), or {@code null} when generation is disabled
     *         or fails validation
     */
    public TradingPlan generateAndStore(LocalDate runDate, List<DailyStockSnapshot> snapshots,
                                        MarketContext marketContext) {
        if (!properties.getPlan().isEnabled()) {
            log.info("[plan] generation disabled — skipping");
            return null;
        }
        try {
            TradingPlan plan = planService.generate(runDate, snapshots, marketContext);
            return validateAndStore(plan);
        } catch (Exception e) {
            log.warn("[plan] plan generation/storage skipped: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Attaches a second-stage AI opinion to an existing plan as its {@code advisory} field and
     * re-stores it. This is a REFERENCE annotation only — the deterministic candidate/entry/exit
     * fields are untouched, so execution logic (Part 2) is never influenced by the AI (F-11).
     *
     * @return the plan with advisory attached, or the original plan if re-storage fails
     */
    public TradingPlan attachAdvisory(TradingPlan plan, String source, String text, Instant generatedAt) {
        if (plan == null || text == null || text.isBlank()) {
            return plan;
        }
        try {
            TradingPlan enriched = plan.toBuilder()
                    .advisory(PlanAdvisory.builder()
                            .source(source)
                            .text(text)
                            .generatedAt(generatedAt)
                            .build())
                    .build();
            TradingPlan stored = validateAndStore(enriched);
            log.info("[plan] advisory ({}) attached and plan re-stored", source);
            return stored;
        } catch (Exception e) {
            log.warn("[plan] advisory attach skipped: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return plan;
        }
    }

    /** Validates (via serialization) then writes the plan to every sink. */
    private TradingPlan validateAndStore(TradingPlan plan) {
        String json = planJsonSerializer.toJson(plan); // validates before writing
        for (PlanStore store : planStores) {
            store.save(plan, json);
        }
        return plan;
    }

    /** Markdown summary section for the report (empty string for a null plan). */
    public String summaryMarkdown(TradingPlan plan) {
        return plan == null ? "" : planSummaryFormatter.toMarkdown(plan);
    }
}

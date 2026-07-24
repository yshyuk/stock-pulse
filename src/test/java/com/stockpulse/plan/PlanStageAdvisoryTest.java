package com.stockpulse.plan;

import com.stockpulse.config.StockPulseProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanStageAdvisoryTest {

    /** Captures the JSON written to a plan sink. */
    private static class CapturingPlanStore implements PlanStore {
        final List<String> saved = new ArrayList<>();

        public String storeName() {
            return "capture";
        }

        public void save(TradingPlan plan, String json) {
            saved.add(json);
        }
    }

    private TradingPlan basePlan() {
        return TradingPlan.builder()
                .schemaVersion(TradingPlan.SCHEMA_VERSION)
                .planDate(LocalDate.of(2026, 7, 16))
                .generatedAt(Instant.parse("2026-07-16T06:00:00Z"))
                .mode(TradingPlan.MODE_PLAN_ONLY)
                .constraints(PlanConstraints.builder()
                        .maxBudgetPerSymbolKrw(new BigDecimal("500000"))
                        .maxTotalBudgetKrw(new BigDecimal("2000000"))
                        .build())
                .marketContext(MarketContext.empty())
                .candidates(List.of())
                .warnings(List.of())
                .build();
    }

    private PlanStage stageWith(CapturingPlanStore store) {
        return new PlanStage(null, new PlanJsonSerializer(), List.of(store),
                new PlanSummaryFormatter(), new StockPulseProperties());
    }

    @Test
    void attachAdvisorySetsFieldAndRestores() {
        CapturingPlanStore store = new CapturingPlanStore();
        PlanStage stage = stageWith(store);

        TradingPlan result = stage.attachAdvisory(basePlan(), "claude", "관심 종목 없음",
                Instant.parse("2026-07-16T06:05:00Z"));

        assertThat(result.getAdvisory()).isNotNull();
        assertThat(result.getAdvisory().getSource()).isEqualTo("claude");
        assertThat(result.getAdvisory().getText()).isEqualTo("관심 종목 없음");
        assertThat(store.saved).hasSize(1);
        assertThat(store.saved.get(0)).contains("advisory").contains("관심 종목 없음");
    }

    @Test
    void blankAdvisoryLeavesPlanUnchangedAndDoesNotStore() {
        CapturingPlanStore store = new CapturingPlanStore();
        PlanStage stage = stageWith(store);

        TradingPlan result = stage.attachAdvisory(basePlan(), "claude", "  ", Instant.now(java.time.Clock.systemUTC()));

        assertThat(result.getAdvisory()).isNull();
        assertThat(store.saved).isEmpty();
    }

    @Test
    void deterministicFieldsUntouchedByAdvisory() {
        PlanStage stage = stageWith(new CapturingPlanStore());
        TradingPlan before = basePlan();

        TradingPlan after = stage.attachAdvisory(before, "claude", "opinion",
                Instant.parse("2026-07-16T06:05:00Z"));

        // Advisory must never alter the execution contract fields.
        assertThat(after.getMode()).isEqualTo(before.getMode());
        assertThat(after.getSchemaVersion()).isEqualTo(before.getSchemaVersion());
        assertThat(after.getCandidates()).isEqualTo(before.getCandidates());
        assertThat(after.getConstraints()).isEqualTo(before.getConstraints());
    }
}

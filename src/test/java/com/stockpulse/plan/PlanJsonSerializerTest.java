package com.stockpulse.plan;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanJsonSerializerTest {

    private final PlanJsonSerializer serializer = new PlanJsonSerializer();

    private final LocalDate day = LocalDate.of(2026, 7, 15);

    private TradingPlan.TradingPlanBuilder validPlanBuilder() {
        return TradingPlan.builder()
                .schemaVersion(TradingPlan.SCHEMA_VERSION)
                .planDate(day)
                .generatedAt(Instant.parse("2026-07-15T06:00:00Z"))
                .mode(TradingPlan.MODE_PLAN_ONLY)
                .constraints(PlanConstraints.builder()
                        .maxBudgetPerSymbolKrw(new BigDecimal("500000"))
                        .maxTotalBudgetKrw(new BigDecimal("2000000"))
                        .build())
                .marketContext(MarketContext.empty())
                .candidates(List.of())
                .warnings(List.of());
    }

    private PlanCandidate validCandidate() {
        return PlanCandidate.builder()
                .symbol("005930").name("삼성전자")
                .matchedRules(List.of("momentum-up-5pct"))
                .priority(1)
                .entry(Entry.builder().type("limit").priceKrw(new BigDecimal("10000")).build())
                .exit(Exit.builder().targetPriceKrw(new BigDecimal("10500"))
                        .stopLossPriceKrw(new BigDecimal("9700")).build())
                .sizing(Sizing.builder().maxBudgetKrw(new BigDecimal("500000")).build())
                .evidence(Evidence.builder().price(new BigDecimal("10000")).build())
                .build();
    }

    @Test
    void serializesValidPlanToJson() {
        String json = serializer.toJson(validPlanBuilder().candidates(List.of(validCandidate())).build());
        String compact = json.replaceAll("\\s", "");

        assertThat(compact).contains("\"schemaVersion\":2");
        assertThat(compact).contains("\"planDate\":\"2026-07-15\"");
        assertThat(compact).contains("\"mode\":\"plan-only\"");
        assertThat(compact).contains("\"priority\":1");
        assertThat(json).contains("005930");
    }

    @Test
    void roundTripsThroughJson() {
        // The Part 2 engine loads plans via fromJson — verify the whole immutable tree survives.
        TradingPlan original = validPlanBuilder().candidates(List.of(validCandidate())).build();

        TradingPlan parsed = serializer.fromJson(serializer.toJson(original));

        assertThat(parsed.getSchemaVersion()).isEqualTo(2);
        assertThat(parsed.getPlanDate()).isEqualTo(day);
        assertThat(parsed.getMode()).isEqualTo(TradingPlan.MODE_PLAN_ONLY);
        assertThat(parsed.getCandidates()).hasSize(1);
        PlanCandidate c = parsed.getCandidates().get(0);
        assertThat(c.getSymbol()).isEqualTo("005930");
        assertThat(c.getEntry().getPriceKrw()).isEqualByComparingTo("10000");
        assertThat(c.getExit().getTargetPriceKrw()).isEqualByComparingTo("10500");
        assertThat(c.getSizing().getMaxBudgetKrw()).isEqualByComparingTo("500000");
    }

    @Test
    void rejectsWrongMode() {
        TradingPlan bad = validPlanBuilder().mode("live").build();
        assertThatThrownBy(() -> serializer.toJson(bad))
                .isInstanceOf(PlanJsonSerializer.PlanValidationException.class)
                .hasMessageContaining("mode");
    }

    @Test
    void rejectsCandidateMissingExit() {
        PlanCandidate broken = PlanCandidate.builder()
                .symbol("005930")
                .matchedRules(List.of("r1"))
                .entry(Entry.builder().type("limit").priceKrw(new BigDecimal("10000")).build())
                .sizing(Sizing.builder().maxBudgetKrw(new BigDecimal("500000")).build())
                .build();

        TradingPlan bad = validPlanBuilder().candidates(List.of(broken)).build();
        assertThatThrownBy(() -> serializer.toJson(bad))
                .isInstanceOf(PlanJsonSerializer.PlanValidationException.class)
                .hasMessageContaining("exit");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        TradingPlan bad = validPlanBuilder().schemaVersion(99).build();
        assertThatThrownBy(() -> serializer.toJson(bad))
                .isInstanceOf(PlanJsonSerializer.PlanValidationException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void acceptsSignalMode() {
        String json = serializer.toJson(validPlanBuilder()
                .mode(TradingPlan.MODE_SIGNAL)
                .candidates(List.of(validCandidate()))
                .build());

        assertThat(json.replaceAll("\\s", "")).contains("\"mode\":\"signal\"");
    }

    @Test
    void stillReadsSchemaV1PlansWrittenBeforeTheBump() {
        // Plans written before v2 are on disk and in the DB; re-reading one (Part 2, re-run)
        // must not fail just because a newer optional field is absent.
        TradingPlan v1 = validPlanBuilder()
                .schemaVersion(1)
                .candidates(List.of(validCandidate().toBuilder().priority(null).build()))
                .build();

        TradingPlan parsed = serializer.fromJson(serializer.toJson(v1));

        assertThat(parsed.getSchemaVersion()).isEqualTo(1);
        assertThat(parsed.getCandidates().get(0).getPriority()).isNull();
    }

    @Test
    void rejectsV2CandidateWithoutPriority() {
        // Consumers truncate by priority when capping the day's candidates — an unranked
        // candidate would make that truncation arbitrary.
        TradingPlan bad = validPlanBuilder()
                .candidates(List.of(validCandidate().toBuilder().priority(null).build()))
                .build();

        assertThatThrownBy(() -> serializer.toJson(bad))
                .isInstanceOf(PlanJsonSerializer.PlanValidationException.class)
                .hasMessageContaining("priority");
    }
}

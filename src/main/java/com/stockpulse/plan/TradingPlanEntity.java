package com.stockpulse.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * DB record of a generated {@link TradingPlan}. The JSON string ({@link #content}) is the
 * source of truth (the same bytes written to {@code plan/YYYY-MM-DD.json}); the columns are
 * for lookup/history. Natural key {@code plan_date} is UNIQUE so re-runs upsert.
 */
@Entity
@Table(
        name = "trading_plan",
        uniqueConstraints = @UniqueConstraint(name = "uq_plan_date", columnNames = "plan_date"))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TradingPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}

package com.stockpulse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A generated report for a given dawn run.
 *
 * <p>Persisted by {@link com.stockpulse.storage.DbReportStore} and also written to disk
 * (reports/YYYY-MM-DD.md) by {@link com.stockpulse.storage.FileReportStore}.
 */
@Entity
@Table(name = "report")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business date of the report (one report per day in the normal case). */
    @Column(nullable = false)
    private LocalDate reportDate;

    /** Output format of {@link #content} (MARKDOWN today, JSON reserved for later). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportFormat format;

    /** Fully rendered report body. */
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    /** When the report was generated. */
    @Column(nullable = false)
    private Instant generatedAt;
}

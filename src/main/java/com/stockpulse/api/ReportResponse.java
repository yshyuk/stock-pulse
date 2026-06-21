package com.stockpulse.api;

import com.stockpulse.domain.Report;
import com.stockpulse.domain.ReportFormat;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Read-API view of a {@link Report}.
 */
public record ReportResponse(
        Long id,
        LocalDate reportDate,
        ReportFormat format,
        String content,
        Instant generatedAt) {

    public static ReportResponse from(Report report) {
        return new ReportResponse(
                report.getId(),
                report.getReportDate(),
                report.getFormat(),
                report.getContent(),
                report.getGeneratedAt());
    }
}

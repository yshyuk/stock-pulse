package com.stockpulse.domain;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The structured input handed to a {@link com.stockpulse.report.ReportRenderer}.
 *
 * <p>It holds only objective {@link StockMetric}s plus run metadata; the renderer turns
 * this into a per-stock, sectioned, table-friendly document that a human can paste
 * straight into Claude for second-stage analysis.
 */
@Value
@Builder
public class ReportModel {

    LocalDate reportDate;
    Instant generatedAt;
    List<StockMetric> metrics;

    /** Disclosure items (e.g. from OpenDART); may be empty. */
    List<Disclosure> disclosures;
}

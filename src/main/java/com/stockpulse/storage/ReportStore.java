package com.stockpulse.storage;

import com.stockpulse.domain.Report;

/**
 * ★ Core abstraction for persisting a generated {@link Report}.
 *
 * <p>Two implementations run side by side: {@link FileReportStore} writes
 * reports/YYYY-MM-DD.md to disk, and {@link DbReportStore} persists to the database.
 * The pipeline saves to all registered stores.
 */
public interface ReportStore {

    /** A short id for logging, e.g. "file", "db". */
    String storeName();

    /** Persist the report. Implementations should be idempotent for re-runs of the same day. */
    void save(Report report);
}

package com.stockpulse.plan;

/**
 * ★ Core abstraction for persisting a generated {@link TradingPlan}.
 *
 * <p>Mirrors {@link com.stockpulse.storage.ReportStore}: a file store writes
 * plan/YYYY-MM-DD.json and a db store persists to {@code trading_plan}. The pipeline saves
 * to all registered stores. Implementations receive the already-validated JSON so every sink
 * writes identical bytes.
 */
public interface PlanStore {

    /** A short id for logging, e.g. "file", "db". */
    String storeName();

    /** Persist the plan (as validated JSON). Implementations must be idempotent per plan date. */
    void save(TradingPlan plan, String json);
}

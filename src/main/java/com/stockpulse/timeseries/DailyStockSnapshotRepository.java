package com.stockpulse.timeseries;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link DailyStockSnapshot}.
 *
 * <p>Two workloads only: (1) look up an existing row by natural key for upsert, and
 * (2) read a symbol's recent prior history to compute derived metrics. Both are covered
 * by the {@code (symbol, trade_date)} unique index.
 */
public interface DailyStockSnapshotRepository extends JpaRepository<DailyStockSnapshot, Long> {

    /** Existing row for the natural key, if any (drives idempotent upsert). */
    Optional<DailyStockSnapshot> findBySymbolAndTradeDate(String symbol, LocalDate tradeDate);

    /**
     * Prior snapshots for a symbol strictly before {@code tradeDate}, most recent first.
     * Callers pass a {@link Pageable} to bound how far back to read (e.g. ~52 weeks).
     */
    List<DailyStockSnapshot> findBySymbolAndTradeDateLessThanOrderByTradeDateDesc(
            String symbol, LocalDate tradeDate, Pageable pageable);
}

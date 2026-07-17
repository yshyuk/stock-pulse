package com.stockpulse.market;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Spring Data JPA repository for {@link DailyMarketSnapshot}. */
public interface DailyMarketSnapshotRepository extends JpaRepository<DailyMarketSnapshot, Long> {

    /** Existing row for the natural key, if any (drives idempotent upsert). */
    Optional<DailyMarketSnapshot> findByIndicatorCodeAndTradeDate(String indicatorCode, LocalDate tradeDate);

    /** Most recent snapshot for this indicator strictly before {@code tradeDate} (for change rate). */
    Optional<DailyMarketSnapshot> findFirstByIndicatorCodeAndTradeDateLessThanOrderByTradeDateDesc(
            String indicatorCode, LocalDate tradeDate);

    /** All indicators recorded for a date (for report/context lookups). */
    List<DailyMarketSnapshot> findByTradeDate(LocalDate tradeDate);
}

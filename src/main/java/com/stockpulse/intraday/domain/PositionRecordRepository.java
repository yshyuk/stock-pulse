package com.stockpulse.intraday.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Spring Data JPA repository for {@link PositionRecord}. */
public interface PositionRecordRepository extends JpaRepository<PositionRecord, Long> {

    /** All currently-held positions (drives exit monitoring and reconciliation). */
    List<PositionRecord> findByStatus(PositionStatus status);

    /** An open position for a symbol, if any. */
    Optional<PositionRecord> findBySymbolAndStatus(String symbol, PositionStatus status);

    /** Positions closed on a given plan date (drives daily realized-P&L). */
    List<PositionRecord> findByPlanDateAndStatus(LocalDate planDate, PositionStatus status);
}

package com.stockpulse.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/** Spring Data JPA repository for {@link TradingPlanEntity}. */
public interface TradingPlanRepository extends JpaRepository<TradingPlanEntity, Long> {

    /** Existing plan for a date, if any (drives idempotent upsert). */
    Optional<TradingPlanEntity> findByPlanDate(LocalDate planDate);
}

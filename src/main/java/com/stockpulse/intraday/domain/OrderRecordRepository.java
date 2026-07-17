package com.stockpulse.intraday.domain;

import com.stockpulse.broker.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Spring Data JPA repository for {@link OrderRecord}. */
public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {

    /** Idempotency lookup: does an order already exist for this key? */
    Optional<OrderRecord> findByClientOrderId(String clientOrderId);

    boolean existsByClientOrderId(String clientOrderId);

    List<OrderRecord> findByPlanDate(LocalDate planDate);

    /** Non-terminal orders (for EOD cleanup / reconciliation). */
    List<OrderRecord> findByStatusIn(List<OrderStatus> statuses);
}

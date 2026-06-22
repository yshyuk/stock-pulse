package com.stockpulse.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Report}.
 */
public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findFirstByReportDateOrderByGeneratedAtDesc(LocalDate reportDate);

    /** Most recently generated report across all dates. */
    Optional<Report> findFirstByOrderByReportDateDescGeneratedAtDesc();
}

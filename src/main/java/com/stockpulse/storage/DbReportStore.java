package com.stockpulse.storage;

import com.stockpulse.domain.Report;
import com.stockpulse.domain.ReportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the report to the database via {@link ReportRepository}.
 */
@Slf4j
@Component
public class DbReportStore implements ReportStore {

    private final ReportRepository reportRepository;

    public DbReportStore(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    @Override
    public String storeName() {
        return "db";
    }

    @Override
    @Transactional
    public void save(Report report) {
        Report saved = reportRepository.save(report);
        log.info("[storage:db] saved report id={} for {}", saved.getId(), saved.getReportDate());
    }
}

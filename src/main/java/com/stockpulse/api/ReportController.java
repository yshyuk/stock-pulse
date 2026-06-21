package com.stockpulse.api;

import com.stockpulse.domain.ReportRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

/**
 * Read-only report lookup API.
 *
 * <p>The app is batch-style and normally exits right after a run, so these endpoints are
 * reachable only when the process is started for inspection (e.g. with
 * {@code --stockpulse.batch.auto-run=false}). They read persisted reports from the DB.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportRepository reportRepository;

    public ReportController(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /** Report for a given date (latest generation that day). */
    @GetMapping("/{date}")
    public ReportResponse getByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportRepository.findFirstByReportDateOrderByGeneratedAtDesc(date)
                .map(ReportResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No report for " + date));
    }

    /** Most recently generated report. */
    @GetMapping("/latest")
    public ReportResponse getLatest() {
        return reportRepository.findFirstByOrderByReportDateDescGeneratedAtDesc()
                .map(ReportResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No reports yet"));
    }
}

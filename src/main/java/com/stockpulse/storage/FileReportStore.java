package com.stockpulse.storage;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.Report;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * Writes the report to the local filesystem as {@code <reportDir>/YYYY-MM-DD.<ext>}.
 *
 * <p>The report directory is configurable (default {@code ./reports}, overridable via the
 * {@code STOCKPULSE_REPORT_DIR} env var). Re-running the same day overwrites the file.
 */
@Slf4j
@Component
public class FileReportStore implements ReportStore {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StockPulseProperties properties;

    public FileReportStore(StockPulseProperties properties) {
        this.properties = properties;
    }

    @Override
    public String storeName() {
        return "file";
    }

    @Override
    public void save(Report report) {
        Path dir = Path.of(properties.getReportDir());
        String fileName = report.getReportDate().format(DATE) + report.getFormat().fileExtension();
        Path target = dir.resolve(fileName);
        try {
            Files.createDirectories(dir);
            Files.writeString(target, report.getContent());
            log.info("[storage:file] wrote report to {}", target.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write report file: " + target, e);
        }
    }
}

package com.stockpulse.plan.store;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.plan.PlanStore;
import com.stockpulse.plan.TradingPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * Writes the plan JSON to {@code <planDir>/YYYY-MM-DD.json}. Re-running the same day overwrites.
 */
@Slf4j
@Component
public class FilePlanStore implements PlanStore {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StockPulseProperties properties;

    public FilePlanStore(StockPulseProperties properties) {
        this.properties = properties;
    }

    @Override
    public String storeName() {
        return "file";
    }

    @Override
    public void save(TradingPlan plan, String json) {
        Path dir = Path.of(properties.getPlanDir());
        Path target = dir.resolve(plan.getPlanDate().format(DATE) + ".json");
        try {
            Files.createDirectories(dir);
            Files.writeString(target, json);
            log.info("[plan:file] wrote plan to {}", target.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write plan file: " + target, e);
        }
    }
}

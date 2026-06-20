package com.stockpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Batch-style entry point.
 *
 * <p>This is NOT a long-running server. The OS scheduler (launchd on the Mac Mini)
 * starts the jar once at a fixed time each dawn; {@link com.stockpulse.batch.BatchRunner}
 * runs the pipeline a single time and then terminates the JVM. There is intentionally
 * no {@code @Scheduled} / {@code @EnableScheduling} here — scheduling is the OS's job.
 *
 * <p>The web context is still started (kept minimal) so a future read-only report API
 * can be added without restructuring, and so a health endpoint exists.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StockPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockPulseApplication.class, args);
    }
}

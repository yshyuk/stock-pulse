package com.stockpulse.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Drives the batch: as soon as the Spring context is ready, run the pipeline once and exit.
 *
 * <p>This is what makes the app "batch-style" rather than a server. The OS scheduler
 * (launchd) starts the jar each dawn; this runner executes a single pass and then calls
 * {@link System#exit(int)} — 0 on success, 1 on failure — so the JVM terminates and the
 * machine can go back to idle/sleep.
 *
 * <p>{@code @Order} is set high so this runs after any other initialization runners.
 */
@Slf4j
@Component
@Order(Integer.MAX_VALUE)
// On by default; tests set stockpulse.batch.auto-run=false so the context can load
// without the runner calling System.exit and killing the test JVM.
@ConditionalOnProperty(prefix = "stockpulse.batch", name = "auto-run", havingValue = "true", matchIfMissing = true)
public class BatchRunner implements ApplicationRunner {

    /** CLI option that overrides the run date, e.g. --stockpulse.run-date=2026-07-14. */
    private static final String RUN_DATE_OPTION = "stockpulse.run-date";

    private final BatchPipeline pipeline;
    private final HeartbeatPinger heartbeatPinger;
    private final ApplicationContext applicationContext;
    private final Clock clock;

    public BatchRunner(BatchPipeline pipeline,
                       HeartbeatPinger heartbeatPinger,
                       ApplicationContext applicationContext,
                       Clock clock) {
        this.pipeline = pipeline;
        this.heartbeatPinger = heartbeatPinger;
        this.applicationContext = applicationContext;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            pipeline.run(resolveRunDate(args));
            // External heartbeat on a fully successful run — lets a monitor detect a MISSED run.
            heartbeatPinger.pingSuccess();
        } catch (Exception e) {
            // The pipeline already sent a FAILURE notification; just translate to an exit code.
            exitCode = 1;
        } finally {
            // Gracefully close the Spring context, then force JVM exit so we never linger.
            int springExit = SpringApplication.exit(applicationContext, () -> 0);
            int finalCode = exitCode != 0 ? exitCode : springExit;
            log.info("[runner] batch finished, exiting with code {}", finalCode);
            System.exit(finalCode);
        }
    }

    /**
     * Run date from {@code --stockpulse.run-date=YYYY-MM-DD} if present and valid,
     * otherwise today per the injected {@link Clock}. An unparseable value falls back
     * to today (logged) rather than aborting the batch.
     */
    private LocalDate resolveRunDate(ApplicationArguments args) {
        List<String> values = args.getOptionValues(RUN_DATE_OPTION);
        if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            return LocalDate.now(clock);
        }
        String raw = values.get(0).trim();
        try {
            LocalDate parsed = LocalDate.parse(raw);
            log.info("[runner] using overridden run date {}", parsed);
            return parsed;
        } catch (DateTimeParseException e) {
            log.warn("[runner] invalid --{}='{}' (expected YYYY-MM-DD); using today", RUN_DATE_OPTION, raw);
            return LocalDate.now(clock);
        }
    }
}

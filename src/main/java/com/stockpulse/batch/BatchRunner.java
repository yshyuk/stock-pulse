package com.stockpulse.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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

    private final BatchPipeline pipeline;
    private final ApplicationContext applicationContext;

    public BatchRunner(BatchPipeline pipeline, ApplicationContext applicationContext) {
        this.pipeline = pipeline;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            pipeline.run();
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
}

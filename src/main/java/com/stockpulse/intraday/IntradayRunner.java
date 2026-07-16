package com.stockpulse.intraday;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Boots the intraday engine (design ADR-005). Only active under the {@code intraday} profile.
 * Unlike {@link com.stockpulse.batch.BatchRunner}, this does NOT exit — it initializes the engine
 * and returns; the web context keeps the JVM alive and {@link com.stockpulse.intraday.poll.QuotePoller}
 * drives ticks until it shuts the process down at session end.
 */
@Slf4j
@Component
@Profile("intraday")
public class IntradayRunner implements ApplicationRunner {

    private final IntradayEngine engine;

    public IntradayRunner(IntradayEngine engine) {
        this.engine = engine;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("==== StockPulse intraday engine STARTING ====");
        engine.initialize();
    }
}

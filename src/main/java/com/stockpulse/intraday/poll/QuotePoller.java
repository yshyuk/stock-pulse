package com.stockpulse.intraday.poll;

import com.stockpulse.intraday.IntradayEngine;
import com.stockpulse.intraday.MarketClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives the engine on a fixed interval during market hours (design ADR-005). Only active under
 * the {@code intraday} profile. Each fire: shut down after session end, run the one-time EOD
 * cleanup at the cleanup boundary, otherwise tick (entries suppressed during the cleanup window).
 */
@Slf4j
@Component
@Profile("intraday")
public class QuotePoller {

    private final IntradayEngine engine;
    private final MarketClock marketClock;
    private final ApplicationContext applicationContext;
    private final Clock clock;

    private final AtomicBoolean cleanupDone = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public QuotePoller(IntradayEngine engine,
                       MarketClock marketClock,
                       ApplicationContext applicationContext,
                       Clock clock) {
        this.engine = engine;
        this.marketClock = marketClock;
        this.applicationContext = applicationContext;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${stockpulse.intraday.poll-interval-ms:90000}")
    public void poll() {
        if (!engine.isActive()) {
            return; // not a trading day / not initialized
        }
        Instant now = clock.instant();

        if (marketClock.isPastSessionEnd(now)) {
            shutdown();
            return;
        }
        if (!marketClock.isWithinSession(now)) {
            return; // before open (or in the post-close gap before end)
        }

        boolean cleanupWindow = marketClock.isCleanupTime(now);
        if (cleanupWindow && cleanupDone.compareAndSet(false, true)) {
            engine.eodCleanup();
        }

        try {
            engine.tick(now, !cleanupWindow); // no new entries during the cleanup window
        } catch (Exception e) {
            log.error("[poller] tick failed: {}", e.getMessage(), e);
        }
    }

    private void shutdown() {
        if (shuttingDown.compareAndSet(false, true)) {
            log.info("[poller] session end — shutting down");
            try {
                engine.notifyDailySummary();
            } finally {
                int code = SpringApplication.exit(applicationContext, () -> 0);
                System.exit(code);
            }
        }
    }
}

package com.stockpulse.intraday;

import com.stockpulse.config.IntradayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Halts all NEW orders immediately (design ADR-007). Engaged either by an in-memory trip
 * (e.g. the daily-loss guard) or by the presence of a configured kill-switch file — so a human
 * can stop the engine out-of-band by touching a file, without redeploying.
 *
 * <p>M1 policy: engaging blocks new entries; it does NOT auto-liquidate existing positions
 * (auto-flatten in a crash could lock in losses — deferred to a separate policy, debt #P2-3).
 */
@Slf4j
@Component
public class KillSwitch {

    private final IntradayProperties properties;
    private final Clock clock;
    private final AtomicBoolean tripped = new AtomicBoolean(false);

    public KillSwitch(IntradayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** True if new orders must be blocked (in-memory trip OR kill file present). */
    public boolean isEngaged() {
        if (tripped.get()) {
            return true;
        }
        String file = properties.getKillSwitchFile();
        if (file != null && !file.isBlank() && Files.exists(Path.of(file))) {
            log.warn("[killswitch] kill file present at {} — new orders blocked", file);
            return true;
        }
        return false;
    }

    /**
     * Trip the switch (idempotent). Persists to the kill file when configured so the trip
     * SURVIVES a crash/restart — an in-memory-only trip would silently reset when launchd
     * restarts the engine, resuming trading after a breached loss limit.
     */
    public void engage(String reason) {
        if (!tripped.compareAndSet(false, true)) {
            return; // already engaged
        }
        log.error("[killswitch] ENGAGED: {} — no further new orders", reason);
        persist(reason);
    }

    /** Writes the kill file so the trip outlives this process. */
    private void persist(String reason) {
        String file = properties.getKillSwitchFile();
        if (file == null || file.isBlank()) {
            log.error("[killswitch] no kill-switch file configured — the trip will NOT survive a "
                    + "restart. Set stockpulse.intraday.kill-switch-file for durable protection.");
            return;
        }
        Path path = Path.of(file);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, Instant.now(clock) + " " + reason + System.lineSeparator());
            log.error("[killswitch] persisted to {} — remove this file to re-enable trading", path);
        } catch (IOException e) {
            log.error("[killswitch] FAILED to persist kill file {} — trip is memory-only: {}",
                    path, e.getMessage(), e);
        }
    }
}

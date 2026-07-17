package com.stockpulse.intraday;

import com.stockpulse.config.IntradayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
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
    private final AtomicBoolean tripped = new AtomicBoolean(false);

    public KillSwitch(IntradayProperties properties) {
        this.properties = properties;
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

    /** Trip the switch in-memory (idempotent). New orders are blocked until process restart. */
    public void engage(String reason) {
        if (tripped.compareAndSet(false, true)) {
            log.error("[killswitch] ENGAGED: {} — no further new orders this session", reason);
        }
    }
}

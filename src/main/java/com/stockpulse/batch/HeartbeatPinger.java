package com.stockpulse.batch;

import com.stockpulse.config.StockPulseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Pings an external heartbeat URL (e.g. healthchecks.io) after a successful run, so a MISSED
 * batch — launchd skipped it, the Mac was asleep — is detected by the external monitor when the
 * expected ping never arrives. A batch cannot alert on its own failure to start; this closes
 * that gap (F-12).
 *
 * <p>Best-effort: any error is swallowed (logged) so the heartbeat never affects the batch's
 * exit status. Disabled when no URL is configured.
 */
@Slf4j
@Component
public class HeartbeatPinger {

    private final WebClient webClient;
    private final StockPulseProperties properties;

    public HeartbeatPinger(WebClient webClient, StockPulseProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    /** Fire the success ping if a URL is configured. Never throws. */
    public void pingSuccess() {
        String url = properties.getHeartbeat().getUrl();
        if (url == null || url.isBlank()) {
            log.debug("[heartbeat] no url configured — skipping");
            return;
        }
        try {
            webClient.get()
                    .uri(url)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));
            log.info("[heartbeat] success ping sent");
        } catch (Exception e) {
            log.warn("[heartbeat] ping failed (ignored): {}", e.getMessage());
        }
    }
}

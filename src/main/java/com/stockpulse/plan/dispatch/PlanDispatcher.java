package com.stockpulse.plan.dispatch;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.plan.PlanJsonSerializer;
import com.stockpulse.plan.TradingPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Pushes the finished plan to the external execution consumer (stock-api SignalIngest).
 *
 * <p>Delivery is best-effort and NEVER fatal: a plan that cannot be delivered simply means the
 * consumer has no agent signals today. That is the correct fail-safe — the alternative (retry
 * forever, or let the batch fail) either delays the report or, worse, invites a manual workaround
 * that pushes an unvalidated plan. Callers get a {@link DispatchResult} so the failure is
 * reported to the operator rather than swallowed.
 *
 * <p>Refuses to send a plan whose {@code mode} is not {@link TradingPlan#MODE_SIGNAL}: marking a
 * plan as executable is an explicit configuration decision (see
 * {@code stockpulse.plan.mode}), and a mis-set config must not silently start driving orders.
 */
@Slf4j
@Component
public class PlanDispatcher {

    /** Shared-secret header the consumer authenticates the ingest request with. */
    public static final String API_KEY_HEADER = "X-Agent-Signal-Key";

    private final StockPulseProperties properties;
    private final PlanJsonSerializer planJsonSerializer;
    private final WebClient webClient;

    public PlanDispatcher(StockPulseProperties properties,
                          PlanJsonSerializer planJsonSerializer,
                          WebClient webClient) {
        this.properties = properties;
        this.planJsonSerializer = planJsonSerializer;
        this.webClient = webClient;
    }

    /** True when dispatch is switched on AND fully configured (URL + key). */
    public boolean isEnabled() {
        StockPulseProperties.Dispatch cfg = properties.getDispatch();
        return cfg.isEnabled()
                && StringUtils.hasText(cfg.getUrl())
                && StringUtils.hasText(cfg.getApiKey());
    }

    /**
     * Delivers {@code plan} downstream, retrying up to the configured attempt count.
     *
     * @return the outcome; {@link DispatchResult#skipped(String)} when dispatch is off, the plan
     *         is absent, or the plan is not marked as a signal
     */
    public DispatchResult dispatch(TradingPlan plan) {
        if (plan == null) {
            return DispatchResult.skipped("no plan to dispatch");
        }
        if (!isEnabled()) {
            return DispatchResult.skipped("dispatch disabled or not configured");
        }
        if (!TradingPlan.MODE_SIGNAL.equals(plan.getMode())) {
            log.warn("[dispatch] plan mode is '{}' (not '{}') — refusing to send. "
                            + "Set stockpulse.plan.mode=signal to enable agent execution.",
                    plan.getMode(), TradingPlan.MODE_SIGNAL);
            return DispatchResult.skipped("plan mode is not '" + TradingPlan.MODE_SIGNAL + "'");
        }

        String json;
        try {
            json = planJsonSerializer.toJson(plan); // validates the contract before it leaves
        } catch (RuntimeException e) {
            log.error("[dispatch] plan failed validation — not sent: {}", e.getMessage());
            return DispatchResult.failed(0, "invalid plan: " + e.getMessage());
        }

        StockPulseProperties.Dispatch cfg = properties.getDispatch();
        int maxAttempts = Math.max(1, cfg.getMaxAttempts());
        String lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                webClient.post()
                        .uri(cfg.getUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(API_KEY_HEADER, cfg.getApiKey())
                        .bodyValue(json)
                        .retrieve()
                        .toBodilessEntity()
                        .block(Duration.ofSeconds(cfg.getTimeoutSeconds()));

                log.info("[dispatch] plan {} delivered on attempt {}/{} ({} candidate(s))",
                        plan.getPlanDate(), attempt, maxAttempts, plan.getCandidates().size());
                return DispatchResult.delivered(attempt);
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("[dispatch] attempt {}/{} failed: {}", attempt, maxAttempts, lastError);
                if (attempt < maxAttempts) {
                    sleepBetweenAttempts(cfg.getRetryDelayMs());
                }
            }
        }

        log.error("[dispatch] plan {} NOT delivered after {} attempt(s) — no agent signals "
                + "downstream today. Last error: {}", plan.getPlanDate(), maxAttempts, lastError);
        return DispatchResult.failed(maxAttempts, lastError);
    }

    /** Restores the interrupt flag so a shutdown during the backoff is not swallowed. */
    private void sleepBetweenAttempts(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

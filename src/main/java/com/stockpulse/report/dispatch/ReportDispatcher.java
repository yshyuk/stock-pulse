package com.stockpulse.report.dispatch;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.plan.dispatch.PlanDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends the stored report to stock-api {@code POST /v1/signals/reports} and reports what the api
 * decided. Configured by {@code stockpulse.report-dispatch.*}, independent of the v2 plan
 * hand-off ({@code stockpulse.dispatch.*}, {@link PlanDispatcher}).
 *
 * <p>Status mapping (pinned against stock-api {@code StrategyReportController}, which answers a
 * NEW report with 202): 201/202 → CREATED, 200 → DUPLICATE, 409 → CONFLICT, other 4xx → INVALID —
 * none of these retried, because the api has already given its answer. Only connection errors,
 * timeouts and 5xx retry, each time with the SAME bytes so the api's hash check sees one report.
 *
 * <p>Never throws for an HTTP outcome and never logs the API key or the body (the body is the
 * report itself, already stored; it does not belong in a log line).
 */
@Slf4j
@Component
public class ReportDispatcher {

    /** Longest slice of the api's response body kept in a reason (it is the api's JSON verdict). */
    private static final int MAX_REASON_BODY = 200;

    private final StockPulseProperties properties;
    private final ReportPayloadBuilder payloadBuilder;
    private final WebClient webClient;

    public ReportDispatcher(StockPulseProperties properties,
                            ReportPayloadBuilder payloadBuilder,
                            WebClient webClient) {
        this.properties = properties;
        this.payloadBuilder = payloadBuilder;
        this.webClient = webClient;
    }

    /** True when report dispatch is switched on AND fully configured (URL + key). */
    public boolean isEnabled() {
        StockPulseProperties.ReportDispatch cfg = properties.getReportDispatch();
        return cfg.isEnabled()
                && StringUtils.hasText(cfg.getUrl())
                && StringUtils.hasText(cfg.getApiKey());
    }

    public ReportDispatchResult dispatch(ReportPayload payload) {
        if (!isEnabled()) {
            return ReportDispatchResult.skipped(ReportDispatchResult.DISABLED);
        }
        if (payload == null) {
            return ReportDispatchResult.skipped("no report payload");
        }
        StockPulseProperties.ReportDispatch cfg = properties.getReportDispatch();
        // Serialized once: every attempt sends exactly these bytes.
        byte[] bytes = payloadBuilder.toJson(payload).getBytes(StandardCharsets.UTF_8);
        int maxAttempts = Math.max(1, cfg.getMaxAttempts());
        String lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Response response;
            try {
                response = webClient.post()
                        .uri(cfg.getUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(PlanDispatcher.API_KEY_HEADER, cfg.getApiKey())
                        .bodyValue(bytes)
                        .exchangeToMono(r -> r.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(b -> new Response(r.statusCode().value(), b)))
                        .block(Duration.ofSeconds(cfg.getTimeoutSeconds()));
            } catch (Exception e) {
                // connection refused / reset / timeout — the api never answered, so retrying is safe
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("[report-dispatch] {} attempt {}/{} failed: {}",
                        payload.reportId(), attempt, maxAttempts, lastError);
                sleepIfMore(attempt, maxAttempts, cfg.getRetryDelayMs());
                continue;
            }
            int code = response == null ? -1 : response.status();
            ReportDispatchResult.Status status = classify(code);
            if (status != null) {
                String reason = status == ReportDispatchResult.Status.CREATED
                        || status == ReportDispatchResult.Status.DUPLICATE
                        ? null : "HTTP " + code + describe(response.body());
                log.info("[report-dispatch] {} → {} (HTTP {}, attempt {}/{})",
                        payload.reportId(), status, code, attempt, maxAttempts);
                return new ReportDispatchResult(status, attempt, reason);
            }
            lastError = "HTTP " + code + describe(response == null ? null : response.body());
            log.warn("[report-dispatch] {} attempt {}/{} failed: {}",
                    payload.reportId(), attempt, maxAttempts, lastError);
            sleepIfMore(attempt, maxAttempts, cfg.getRetryDelayMs());
        }

        log.error("[report-dispatch] {} NOT delivered after {} attempt(s). Last error: {}",
                payload.reportId(), maxAttempts, lastError);
        return ReportDispatchResult.failed(maxAttempts, lastError);
    }

    /** Final verdict for a status, or null when the status is retryable (5xx / no status). */
    static ReportDispatchResult.Status classify(int code) {
        if (code == 201 || code == 202) {
            return ReportDispatchResult.Status.CREATED;
        }
        if (code == 200) {
            return ReportDispatchResult.Status.DUPLICATE;
        }
        if (code == 409) {
            return ReportDispatchResult.Status.CONFLICT;
        }
        if (code >= 500 || code < 0) {
            return null;
        }
        // other 4xx — and any status the api does not use (204, 3xx): it answered, retrying the
        // same bytes will not change that, and treating it as success would hide a contract drift
        return ReportDispatchResult.Status.INVALID;
    }

    private static String describe(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return " " + (oneLine.length() > MAX_REASON_BODY ? oneLine.substring(0, MAX_REASON_BODY) + "…" : oneLine);
    }

    private static void sleepIfMore(int attempt, int maxAttempts, long delayMs) {
        if (attempt >= maxAttempts || delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private record Response(int status, String body) {
    }
}

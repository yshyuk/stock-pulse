package com.stockpulse.collector.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpulse.collector.DataSource;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.RawData;
import com.stockpulse.market.MarketIndicatorCodes;
import com.stockpulse.market.MarketProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * USD/KRW exchange rate from the Bank of Korea ECOS open API (official, documented, free key).
 *
 * <p>Active only when {@code stockpulse.collector.ecos.enabled=true} and an API key is set.
 * Queries the last {@code lookbackDays} of the USD/KRW statistic and emits the most recent row.
 * Optional/enrichment — not required, so a failure never degrades the run.
 *
 * <p>URL shape: {@code {base}/StatisticSearch/{key}/json/kr/1/{n}/{stat}/{cycle}/{from}/{to}/{item}}.
 * Response: {@code {StatisticSearch:{row:[{TIME,DATA_VALUE}]}}}; errors come back under {@code RESULT}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "stockpulse.collector.ecos", name = "enabled", havingValue = "true")
public class EcosSource implements DataSource {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockPulseProperties properties;
    private final WebClient webClient;
    private final Clock clock;

    public EcosSource(StockPulseProperties properties, WebClient webClient, Clock clock) {
        this.properties = properties;
        this.webClient = webClient;
        this.clock = clock;
    }

    @Override
    public String sourceName() {
        return "ecos";
    }

    @Override
    public boolean isEnabled() {
        String key = properties.getCollector().getEcos().getApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public List<RawData> collect() {
        StockPulseProperties.Ecos cfg = properties.getCollector().getEcos();
        LocalDate today = LocalDate.now(clock);
        String from = today.minusDays(cfg.getLookbackDays()).format(YMD);
        String to = today.format(YMD);

        String uri = String.join("/", cfg.getBaseUrl(), "StatisticSearch", cfg.getApiKey(),
                "json", "kr", "1", "100", cfg.getStatCode(), cfg.getCycle(), from, to, cfg.getItemCode());

        JsonNode body;
        try {
            body = webClient.get().uri(uri)
                    .retrieve().bodyToMono(JsonNode.class).block();
        } catch (Exception e) {
            // The API key is in the URL path — never let a raw exception (whose message echoes the
            // URI) reach the logs. Optional source: degrade to empty rather than failing the run.
            log.error("[collector:ecos] request failed: {}",
                    SecretMasker.mask(e.getMessage(), cfg.getApiKey()));
            return List.of();
        }

        if (body == null) {
            log.warn("[collector:ecos] empty response");
            return List.of();
        }
        if (body.has("RESULT")) {
            log.warn("[collector:ecos] API error: {}", body.path("RESULT").path("MESSAGE").asText(""));
            return List.of();
        }

        JsonNode rows = body.path("StatisticSearch").path("row");
        if (!rows.isArray() || rows.isEmpty()) {
            log.warn("[collector:ecos] no rows returned");
            return List.of();
        }

        // Rows are chronological; take the most recent valid DATA_VALUE.
        JsonNode latest = null;
        for (JsonNode row : rows) {
            JsonNode v = row.path("DATA_VALUE");
            if (!v.isMissingNode() && !v.isNull() && !v.asText().isBlank()) {
                latest = row;
            }
        }
        if (latest == null) {
            log.warn("[collector:ecos] no usable DATA_VALUE in rows");
            return List.of();
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put(MarketProcessor.PAYLOAD_VALUE, latest.path("DATA_VALUE").asText());
        RawData item = RawData.builder()
                .sourceName(sourceName())
                .symbol(MarketIndicatorCodes.USDKRW)
                .name("원/달러")
                .fetchedAt(Instant.now(clock))
                .payload(payload)
                .build();
        log.info("[collector:ecos] USD/KRW = {} (time={})",
                latest.path("DATA_VALUE").asText(), latest.path("TIME").asText());
        return List.of(item);
    }
}

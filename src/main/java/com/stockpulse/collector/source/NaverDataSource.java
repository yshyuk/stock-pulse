package com.stockpulse.collector.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpulse.collector.DataSource;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.RawData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real price source backed by Naver Finance's (unofficial) realtime polling endpoint.
 *
 * <p>Active only when {@code stockpulse.collector.naver.enabled=true} and at least one symbol
 * is configured. It fetches {@code .../stock/<code1>,<code2>,...} and emits one
 * {@link RawData} per quote with price/previous-price/volume, which {@link
 * com.stockpulse.processor.MetricProcessor} turns into objective metrics.
 *
 * <p>Note: the snapshot endpoint exposes today's volume but not the prior day's, so
 * {@code volumeChangeRate} will be blank for Naver-sourced rows (price change rate still
 * computes from the previous close).
 *
 * <p>This is an undocumented endpoint; treat it as best-effort and expect occasional shape
 * changes. Parsing is defensive so a single bad field doesn't abort the run.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "stockpulse.collector.naver", name = "enabled", havingValue = "true")
public class NaverDataSource implements DataSource {

    private final StockPulseProperties properties;
    private final WebClient webClient;
    private final Clock clock;

    public NaverDataSource(StockPulseProperties properties, WebClient webClient, Clock clock) {
        this.properties = properties;
        this.webClient = webClient;
        this.clock = clock;
    }

    @Override
    public String sourceName() {
        return "naver-finance";
    }

    @Override
    public boolean isEnabled() {
        return !properties.getCollector().getNaver().getSymbols().isEmpty();
    }

    @Override
    public List<RawData> collect() {
        StockPulseProperties.Naver cfg = properties.getCollector().getNaver();
        String codes = String.join(",", cfg.getSymbols());
        String uri = cfg.getBaseUrl() + "/" + codes;

        JsonNode body = webClient.get()
                .uri(uri)
                // The endpoint is browser-facing; set a Referer/UA so it doesn't reject us.
                .header(HttpHeaders.REFERER, "https://finance.naver.com/")
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (stock-pulse batch)")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (body == null) {
            log.warn("[collector:naver] empty response");
            return List.of();
        }

        List<JsonNode> quotes = extractQuotes(body);
        if (quotes.isEmpty()) {
            log.warn("[collector:naver] no quotes parsed (resultCode={})",
                    body.path("resultCode").asText(""));
            return List.of();
        }

        List<RawData> items = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (JsonNode q : quotes) {
            try {
                items.add(toRawData(q, now));
            } catch (Exception e) {
                log.warn("[collector:naver] skipping malformed quote '{}': {}",
                        q.path("cd").asText("?"), e.getMessage());
            }
        }
        log.info("[collector:naver] fetched {} quote(s)", items.size());
        return items;
    }

    /** Handles both the flat {@code {datas:[...]}} and nested {@code {result:{areas:[{datas:[...]}]}}} shapes. */
    private List<JsonNode> extractQuotes(JsonNode body) {
        List<JsonNode> quotes = new ArrayList<>();
        JsonNode flat = body.path("datas");
        if (flat.isArray()) {
            flat.forEach(quotes::add);
            return quotes;
        }
        for (JsonNode area : body.path("result").path("areas")) {
            area.path("datas").forEach(quotes::add);
        }
        return quotes;
    }

    private RawData toRawData(JsonNode q, Instant now) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("price", num(q, "nv"));          // 현재가
        payload.put("previousPrice", num(q, "pcv")); // 전일 종가
        payload.put("volume", num(q, "aq"));         // 누적 거래량
        // previousVolume not available from this endpoint → volumeChangeRate stays blank.
        return RawData.builder()
                .sourceName(sourceName())
                .symbol(q.path("cd").asText(null))
                .name(q.path("nm").asText(null))
                .fetchedAt(now)
                .payload(payload)
                .build();
    }

    private Long num(JsonNode q, String field) {
        JsonNode n = q.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asLong();
    }
}

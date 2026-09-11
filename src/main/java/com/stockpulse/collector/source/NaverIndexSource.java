package com.stockpulse.collector.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpulse.collector.DataSource;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.RawData;
import com.stockpulse.market.MarketProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Market index source backed by Naver Finance's (unofficial) realtime index polling endpoint.
 * Emits one market indicator per index code (e.g. KOSPI, KOSDAQ).
 *
 * <p>Active only when {@code stockpulse.collector.naver-index.enabled=true}. This is an
 * undocumented, browser-facing endpoint — treated as best-effort (like {@link NaverDataSource}),
 * so it is NOT required: if it fails, the run is not degraded and plan generation continues.
 *
 * <p>Response-shape assumption: {@code {datas:[{cd,nm,nv}]}} where {@code nv} is the index level.
 * Parsing is defensive; enable it and check logs to confirm the real shape.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "stockpulse.collector.naver-index", name = "enabled", havingValue = "true")
public class NaverIndexSource implements DataSource {

    private final StockPulseProperties properties;
    private final WebClient webClient;
    private final Clock clock;

    public NaverIndexSource(StockPulseProperties properties, WebClient webClient, Clock clock) {
        this.properties = properties;
        this.webClient = webClient;
        this.clock = clock;
    }

    @Override
    public String sourceName() {
        return "naver-index";
    }

    @Override
    public boolean isEnabled() {
        return !properties.getCollector().getNaverIndex().getCodes().isEmpty();
    }

    @Override
    public List<RawData> collect(LocalDate runDate) {
        StockPulseProperties.NaverIndex cfg = properties.getCollector().getNaverIndex();
        String codes = String.join(",", cfg.getCodes());
        String uri = cfg.getBaseUrl() + "/" + codes;

        JsonNode body = webClient.get()
                .uri(uri)
                .header(HttpHeaders.REFERER, "https://finance.naver.com/")
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (stock-pulse batch)")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (body == null) {
            log.warn("[collector:naver-index] empty response");
            return List.of();
        }

        List<RawData> items = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (JsonNode d : extractDatas(body)) {
            try {
                String code = d.path("cd").asText(null);
                JsonNode nv = d.path("nv");
                if (code == null || nv.isMissingNode() || nv.isNull()) {
                    continue;
                }
                Map<String, Object> payload = new HashMap<>();
                payload.put(MarketProcessor.PAYLOAD_VALUE, nv.asText());
                items.add(RawData.builder()
                        .sourceName(sourceName())
                        .symbol(code)
                        .name(d.path("nm").asText(code))
                        .fetchedAt(now)
                        .payload(payload)
                        .build());
            } catch (Exception e) {
                log.warn("[collector:naver-index] skipping malformed index item: {}", e.getMessage());
            }
        }
        log.info("[collector:naver-index] fetched {} index value(s)", items.size());
        return items;
    }

    /** Handles both {@code {datas:[...]}} and {@code {result:{areas:[{datas:[...]}]}}} shapes. */
    private List<JsonNode> extractDatas(JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode flat = body.path("datas");
        if (flat.isArray()) {
            flat.forEach(out::add);
            return out;
        }
        for (JsonNode area : body.path("result").path("areas")) {
            area.path("datas").forEach(out::add);
        }
        return out;
    }
}

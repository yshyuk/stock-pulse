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
import java.time.LocalDate;
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
 * computes from the previous close, which is derived as {@code close - change}).
 *
 * <p>Field names here were re-verified against the live endpoint on 2026-09-09. Naver had
 * renamed every field this parser previously read ({@code cd/nm/nv/pcv/aq/cr}), and because the
 * test fixture was hand-written to match the old names, the source returned nothing while its
 * test stayed green. Capture fixtures from the real endpoint, never from the parser.
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

    /** Naver is the real price feed — if it fails, the run is degraded and no plan is emitted. */
    @Override
    public boolean isRequired() {
        return true;
    }

    @Override
    public List<RawData> collect(LocalDate runDate) {
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
        int skipped = 0;
        for (JsonNode q : quotes) {
            RawData item = toRawData(q, now);
            if (item == null) {
                skipped++;
                continue;
            }
            items.add(item);
        }
        log.info("[collector:naver] fetched {} quote(s), skipped {}", items.size(), skipped);
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

    /** One quote, or null when it carries no usable price (e.g. a suspended ticker). */
    private RawData toRawData(JsonNode q, Instant now) {
        String symbol = text(q, "itemCode", "symbolCode");
        Long price = num(q, "closePriceRaw", "closePrice");
        if (symbol == null || price == null) {
            return null;
        }
        // Naver reports the day-over-day CHANGE, not the previous close. The value is signed,
        // so subtracting it works in both directions (a falling stock's change is negative).
        Long change = num(q, "compareToPreviousClosePriceRaw", "compareToPreviousClosePrice");

        Map<String, Object> payload = new HashMap<>();
        payload.put("price", price);
        payload.put("volume", num(q, "accumulatedTradingVolumeRaw", "accumulatedTradingVolume"));
        if (change != null) {
            payload.put("previousPrice", price - change);
        }
        // previousVolume is not exposed by this endpoint → volumeChangeRate stays blank.
        String name = text(q, "stockName");
        return RawData.builder()
                .sourceName(sourceName())
                .symbol(symbol)
                .name(name == null ? symbol : name)
                .fetchedAt(now)
                .payload(payload)
                .build();
    }

    private String text(JsonNode q, String... fields) {
        for (String f : fields) {
            JsonNode n = q.path(f);
            if (!n.isMissingNode() && !n.isNull() && !n.asText().isBlank()) {
                return n.asText().trim();
            }
        }
        return null;
    }

    /**
     * Reads the first present field as a whole number. The {@code *Raw} variants are unformatted;
     * the display variants are grouped ("13,958,538") and are only a fallback.
     */
    private Long num(JsonNode q, String... fields) {
        String raw = text(q, fields);
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace(",", "").replace("+", "").trim();
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return null;
        }
        try {
            return Long.valueOf(cleaned);
        } catch (NumberFormatException e) {
            log.warn("[collector:naver] unparseable number '{}'", raw);
            return null;
        }
    }
}

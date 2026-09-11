package com.stockpulse.collector.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpulse.collector.DataSource;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.RawData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily quotes for every listed stock, from the official KRX Open API (data-dbg.krx.co.kr).
 *
 * <p>This replaces an earlier attempt against the data.krx.co.kr portal endpoint, which now
 * rejects programmatic access outright ({@code 400 LOGOUT}). The Open API needs a free auth key
 * sent as the {@code AUTH_KEY} header, plus a per-service subscription made on the portal.
 *
 * <p>There is no all-market endpoint: KOSPI ({@code stk_bydd_trd}) and KOSDAQ
 * ({@code ksq_bydd_trd}) are fetched separately and merged — one request each, which is what
 * makes whole-market collection cheap. Passing all ~2,765 rows on to the report and the Claude
 * analysis is NOT cheap, so enable {@code stockpulse.screening.enabled} alongside this source.
 *
 * <p><b>Dates.</b> KRX publishes after the close, so asking for today at a 06:00 run returns zero
 * rows — as do weekends and holidays. The source therefore walks back from the run date until a
 * session answers with data, which handles holidays without a market calendar. This matches what
 * the Naver source reports at dawn (the previous session's close), so both sources agree.
 *
 * <p>Required source: it IS the price feed, so a failure marks the run degraded and the plan is
 * skipped rather than built on nothing (F-13).
 *
 * <p>Field names verified against the live API on 2026-09-09.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "stockpulse.collector.krx-all", name = "enabled", havingValue = "true")
public class KrxAllStocksSource implements DataSource {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockPulseProperties properties;
    private final WebClient webClient;
    private final Clock clock;

    public KrxAllStocksSource(StockPulseProperties properties, WebClient webClient, Clock clock) {
        this.properties = properties;
        this.webClient = webClient;
        this.clock = clock;
    }

    @Override
    public String sourceName() {
        return "krx-all";
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.hasText(properties.getCollector().getKrxAll().getApiKey());
    }

    /** This source IS the price feed — losing it means the day's data cannot be trusted. */
    @Override
    public boolean isRequired() {
        return true;
    }

    @Override
    public List<RawData> collect(LocalDate runDate) {
        StockPulseProperties.KrxAll cfg = properties.getCollector().getKrxAll();

        for (int back = 1; back <= cfg.getMaxLookbackDays(); back++) {
            LocalDate candidate = runDate.minusDays(back);
            if (isWeekend(candidate)) {
                continue;
            }
            List<RawData> items = fetchSession(cfg, candidate);
            if (!items.isEmpty()) {
                log.info("[collector:krx-all] {} quote(s) for session {}", items.size(), candidate);
                return items;
            }
            log.info("[collector:krx-all] no rows for {} (holiday?), looking further back", candidate);
        }
        log.warn("[collector:krx-all] no session with data in the {} days before {}",
                cfg.getMaxLookbackDays(), runDate);
        return List.of();
    }

    /** All markets for one session date, merged. Empty means "this day had no trading". */
    private List<RawData> fetchSession(StockPulseProperties.KrxAll cfg, LocalDate sessionDate) {
        String basDd = sessionDate.format(YMD);
        Instant now = Instant.now(clock);
        List<RawData> items = new ArrayList<>();

        for (String endpoint : cfg.getEndpoints()) {
            JsonNode body;
            try {
                body = webClient.get()
                        .uri(cfg.getBaseUrl() + "/" + endpoint + "?basDd=" + basDd)
                        .header("AUTH_KEY", cfg.getApiKey())
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();
            } catch (WebClientResponseException.Unauthorized e) {
                // 401 has two very different causes and the fix differs, so say both rather than
                // letting the operator guess from a bare status code. Keys also expire yearly.
                throw new IllegalStateException(
                        "KRX Open API rejected the key (401) for '" + endpoint + "'. Either the key "
                                + "expired/is wrong, or this API has no active subscription — "
                                + "openapi.krx.co.kr requires BOTH an auth key and a per-service "
                                + "'API 이용신청'. Response: " + e.getResponseBodyAsString(), e);
            }

            JsonNode rows = body == null ? null : body.path("OutBlock_1");
            if (rows == null || !rows.isArray()) {
                log.warn("[collector:krx-all] unexpected response from '{}' for {}: {}",
                        endpoint, basDd, body == null ? "null" : body.toString().substring(0,
                                Math.min(200, body.toString().length())));
                continue;
            }
            for (JsonNode row : rows) {
                RawData item = toRawData(row, now);
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    /** One quote, or null when a required field is missing/unparseable (e.g. a halted ticker). */
    private RawData toRawData(JsonNode row, Instant fetchedAt) {
        String symbol = text(row, "ISU_CD");
        Long close = num(row, "TDD_CLSPRC");
        Long volume = num(row, "ACC_TRDVOL");
        if (symbol == null || close == null || volume == null) {
            return null;
        }
        // KRX reports the day-over-day CHANGE, not the previous close. It is signed, so
        // subtracting works in both directions (a falling stock's change is negative).
        Long change = num(row, "CMPPREVDD_PRC");

        Map<String, Object> payload = new HashMap<>();
        payload.put("price", close);
        payload.put("volume", volume);
        if (change != null) {
            payload.put("previousPrice", close - change);
        }
        String name = text(row, "ISU_NM");
        return RawData.builder()
                .sourceName(sourceName())
                .symbol(symbol)
                .name(name == null ? symbol : name)
                .fetchedAt(fetchedAt)
                .payload(payload)
                .build();
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private String text(JsonNode row, String field) {
        JsonNode n = row.path(field);
        return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText().trim();
    }

    /** Open API numbers arrive as plain strings ("1856000"); "-" marks no value. */
    private Long num(JsonNode row, String field) {
        String raw = text(row, field);
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
            return null;
        }
    }
}

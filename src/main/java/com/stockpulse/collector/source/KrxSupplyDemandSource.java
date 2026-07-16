package com.stockpulse.collector.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpulse.collector.DataSource;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.RawData;
import com.stockpulse.market.MarketIndicatorCodes;
import com.stockpulse.market.MarketProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Investor supply/demand (foreign & institution net buy) from KRX's data portal
 * (data.krx.co.kr {@code getJsonData.cmd}). Emits FOREIGN_NET_KOSPI / INSTITUTION_NET_KOSPI.
 *
 * <p>Active only when {@code stockpulse.collector.krx.enabled=true}. This is a form-POST portal
 * endpoint whose row/field shape is not officially documented — parsing is deliberately tolerant
 * (it probes several candidate field names and matches investor rows by the 외국인/기관 labels)
 * and best-effort. Not required: a failure never degrades the run. Enable it and check the logs
 * to confirm the shape against the live response, then tune {@code bld}/{@code marketId} as needed.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "stockpulse.collector.krx", name = "enabled", havingValue = "true")
public class KrxSupplyDemandSource implements DataSource {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> NAME_KEYS = List.of("INVST_NM", "INVSTR_NM", "invstNm", "INVST_TP_NM");
    private static final List<String> NET_KEYS =
            List.of("NETBYD_TRDVAL", "NET_BYD_TRDVAL", "netBydTrdval", "SETL_NETBYD_TRDVAL");

    private final StockPulseProperties properties;
    private final WebClient webClient;
    private final Clock clock;

    public KrxSupplyDemandSource(StockPulseProperties properties, WebClient webClient, Clock clock) {
        this.properties = properties;
        this.webClient = webClient;
        this.clock = clock;
    }

    @Override
    public String sourceName() {
        return "krx";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public List<RawData> collect() {
        StockPulseProperties.Krx cfg = properties.getCollector().getKrx();
        String trdDd = LocalDate.now(clock).format(YMD);

        JsonNode body = webClient.post()
                .uri(cfg.getBaseUrl())
                .header(HttpHeaders.REFERER, "http://data.krx.co.kr/")
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (stock-pulse batch)")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("bld", cfg.getBld())
                        .with("locale", "ko_KR")
                        .with("mktId", cfg.getMarketId())
                        .with("trdDd", trdDd))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (body == null) {
            log.warn("[collector:krx] empty response");
            return List.of();
        }

        JsonNode rows = findRows(body);
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            log.warn("[collector:krx] no rows found in response (keys={})", fieldNames(body));
            return List.of();
        }

        List<RawData> items = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (JsonNode row : rows) {
            String name = firstText(row, NAME_KEYS);
            String net = firstText(row, NET_KEYS);
            if (name == null || net == null) {
                continue;
            }
            String code = classify(name);
            if (code == null) {
                continue;
            }
            Map<String, Object> payload = new HashMap<>();
            // KRX amounts are grouped with commas, e.g. "1,234,567" — strip separators.
            payload.put(MarketProcessor.PAYLOAD_VALUE, net.replace(",", "").trim());
            items.add(RawData.builder()
                    .sourceName(sourceName())
                    .symbol(code)
                    .name(name)
                    .fetchedAt(now)
                    .payload(payload)
                    .build());
        }
        log.info("[collector:krx] extracted {} supply/demand item(s)", items.size());
        return items;
    }

    /** Foreign/institution investor row → indicator code, or null for other investor types. */
    private String classify(String investorName) {
        if (investorName.contains("외국인")) {
            return MarketIndicatorCodes.FOREIGN_NET_KOSPI;
        }
        if (investorName.contains("기관")) {
            return MarketIndicatorCodes.INSTITUTION_NET_KOSPI;
        }
        return null;
    }

    /** Finds the first array field in the response (KRX uses output / OutBlock_1 / block1). */
    private JsonNode findRows(JsonNode body) {
        for (String key : List.of("output", "OutBlock_1", "block1", "list")) {
            if (body.path(key).isArray()) {
                return body.path(key);
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
        while (fields.hasNext()) {
            JsonNode v = fields.next().getValue();
            if (v.isArray()) {
                return v;
            }
        }
        return null;
    }

    private String firstText(JsonNode row, List<String> keys) {
        for (String k : keys) {
            JsonNode n = row.path(k);
            if (!n.isMissingNode() && !n.isNull() && !n.asText().isBlank()) {
                return n.asText();
            }
        }
        return null;
    }

    private String fieldNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        return String.join(",", names);
    }
}

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
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real disclosure source backed by OpenDART (dart.fss.or.kr).
 *
 * <p>Active only when {@code stockpulse.collector.dart.enabled=true} and an API key is set.
 * Calls the OpenDART "공시검색" (list.json) endpoint for the configured look-back window and
 * emits one {@link RawData} per disclosure (payload {@code type == "disclosure"}), which
 * {@link com.stockpulse.processor.DisclosureProcessor} turns into report rows.
 *
 * <p>OpenDART returns price-free disclosure metadata, so these items are intentionally not
 * fed into price-metric calculation.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "stockpulse.collector.dart", name = "enabled", havingValue = "true")
public class DartDataSource implements DataSource {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockPulseProperties properties;
    private final WebClient webClient;
    private final Clock clock;

    public DartDataSource(StockPulseProperties properties, WebClient webClient, Clock clock) {
        this.properties = properties;
        this.webClient = webClient;
        this.clock = clock;
    }

    @Override
    public String sourceName() {
        return "dart";
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.hasText(properties.getCollector().getDart().getApiKey());
    }

    @Override
    public List<RawData> collect() {
        StockPulseProperties.Dart cfg = properties.getCollector().getDart();
        LocalDate today = LocalDate.now(clock);
        LocalDate begin = today.minusDays(Math.max(0, cfg.getLookbackDays() - 1));

        String uri = UriComponentsBuilder.fromHttpUrl(cfg.getBaseUrl() + "/list.json")
                .queryParam("crtfc_key", cfg.getApiKey())
                .queryParam("bgn_de", begin.format(YMD))
                .queryParam("end_de", today.format(YMD))
                .queryParam("page_no", 1)
                .queryParam("page_count", Math.min(100, Math.max(1, cfg.getMaxItems())))
                .build()
                .toUriString();

        JsonNode body = webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (body == null) {
            log.warn("[collector:dart] empty response");
            return List.of();
        }
        String status = body.path("status").asText("");
        if ("013".equals(status)) {
            log.info("[collector:dart] no disclosures for {}..{}", begin.format(YMD), today.format(YMD));
            return List.of();
        }
        if (!"000".equals(status)) {
            // Surface OpenDART error (e.g. 010 invalid key, 020 quota) without aborting the run.
            log.warn("[collector:dart] OpenDART error status={} message={}",
                    status, body.path("message").asText(""));
            return List.of();
        }

        List<RawData> items = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (JsonNode node : body.path("list")) {
            String stockCode = blankToNull(node.path("stock_code").asText(""));
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "disclosure");
            payload.put("corpName", node.path("corp_name").asText(""));
            payload.put("stockCode", stockCode);
            payload.put("reportName", node.path("report_nm").asText(""));
            payload.put("receiptNo", node.path("rcept_no").asText(""));
            payload.put("receiptDate", node.path("rcept_dt").asText(""));
            payload.put("filer", node.path("flr_nm").asText(""));

            items.add(RawData.builder()
                    .sourceName(sourceName())
                    // No symbol: disclosures are not price metrics. Stock code lives in payload.
                    .symbol(null)
                    .name(node.path("corp_name").asText(""))
                    .fetchedAt(now)
                    .payload(payload)
                    .build());
        }
        log.info("[collector:dart] fetched {} disclosure(s)", items.size());
        return items;
    }

    private String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }
}

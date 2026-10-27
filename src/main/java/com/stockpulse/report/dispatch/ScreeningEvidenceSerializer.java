package com.stockpulse.report.dispatch;

import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockpulse.domain.StockMetric;
import com.stockpulse.screening.ScreeningResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link ScreeningResult} into the screening JSON contract that stock-api's daily
 * strategy generator reads ({@code ScreeningCatalog} on the api side). Schema version 1:
 *
 * <pre>
 * {"schemaVersion":1,"sessionDate":"YYYY-MM-DD","totalEvaluated":N,"matchedCount":N,
 *  "items":[{"evidenceId":"&lt;reportId&gt;:&lt;symbol&gt;","symbol":"005930","name":"…",
 *            "price":…,"previousPrice":…,"changeRate":…,"volume":…,"previousVolume":…,
 *            "volumeChangeRate":…,"ruleHits":["…"],"missing":["volume"]}]}
 * </pre>
 *
 * <ul>
 *   <li>{@code price}/{@code previousPrice} are KRW; {@code volume}/{@code previousVolume} are shares.</li>
 *   <li>{@code changeRate} and {@code volumeChangeRate} are <b>percent</b> (4.41 means +4.41%),
 *       exactly as {@link StockMetric} carries them. The api prompt labels them %.</li>
 *   <li>A metric with no value is <b>omitted</b> and its name listed in {@code missing}. It is never
 *       written as 0 — a 0 volume reads as "nobody traded", which is a claim the data does not make.</li>
 *   <li>{@code evidenceId} is what a generated plan may cite; anything else is rejected downstream.</li>
 *   <li>Deterministic: fixed field order, items in {@link ScreeningResult#metrics()} order (already
 *       ranked), {@code ruleHits} sorted, numbers written plain (no scientific notation). Same input,
 *       same bytes — the report hash depends on it.</li>
 * </ul>
 *
 * <p>No market judgement is added here: the batch carries objective metrics only.
 */
public final class ScreeningEvidenceSerializer {

    public static final int SCHEMA_VERSION = 1;

    /** Jackson 2.15+ default factory keeps each BigDecimal's own scale (no stripTrailingZeros). */
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Compact, plain-number writer used for the hash input AND the wire bytes. */
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .nodeFactory(NODES)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private ScreeningEvidenceSerializer() {
    }

    static ObjectNode newObject() {
        return NODES.objectNode();
    }

    public static ObjectNode toNode(String reportId, LocalDate sessionDate, ScreeningResult screened) {
        ObjectNode root = NODES.objectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("sessionDate", sessionDate.toString());
        root.put("totalEvaluated", screened.totalEvaluated());
        root.put("matchedCount", screened.matchedCount());
        ArrayNode items = root.putArray("items");
        Map<String, List<String>> ruleHits = screened.ruleHits() == null ? Map.of() : screened.ruleHits();
        for (StockMetric m : screened.metrics()) {
            if (m.getSymbol() == null || m.getSymbol().isBlank()) {
                // "<reportId>:null" would be a citable evidence ID that refers to nothing
                throw new IllegalArgumentException("screened metric without a symbol cannot become evidence (name="
                        + m.getName() + ")");
            }
            items.add(item(reportId, m, ruleHits.getOrDefault(m.getSymbol(), List.of())));
        }
        return root;
    }

    /** Compact JSON text of a node, as it will be hashed and sent. */
    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize report payload JSON: " + e.getMessage(), e);
        }
    }

    private static ObjectNode item(String reportId, StockMetric m, List<String> hits) {
        ObjectNode item = NODES.objectNode();
        List<String> missing = new ArrayList<>();
        item.put("evidenceId", reportId + ":" + m.getSymbol());
        item.put("symbol", m.getSymbol());
        if (m.getName() != null) {
            item.put("name", m.getName());
        }
        decimal(item, missing, "price", m.getPrice());
        decimal(item, missing, "previousPrice", m.getPreviousPrice());
        decimal(item, missing, "changeRate", m.getChangeRate());
        integer(item, missing, "volume", m.getVolume());
        integer(item, missing, "previousVolume", m.getPreviousVolume());
        decimal(item, missing, "volumeChangeRate", m.getVolumeChangeRate());
        ArrayNode ruleArray = item.putArray("ruleHits");
        hits.stream().sorted().forEach(ruleArray::add);
        ArrayNode missingArray = item.putArray("missing");
        missing.forEach(missingArray::add);
        return item;
    }

    private static void decimal(ObjectNode item, List<String> missing, String field, BigDecimal value) {
        if (value == null) {
            missing.add(field);
        } else {
            item.put(field, value);
        }
    }

    private static void integer(ObjectNode item, List<String> missing, String field, Long value) {
        if (value == null) {
            missing.add(field);
        } else {
            item.put(field, value);
        }
    }
}

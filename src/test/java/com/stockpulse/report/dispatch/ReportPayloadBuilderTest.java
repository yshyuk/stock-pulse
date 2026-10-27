package com.stockpulse.report.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpulse.domain.Report;
import com.stockpulse.domain.ReportFormat;
import com.stockpulse.domain.StockMetric;
import com.stockpulse.screening.ScreeningResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리포트 전송 페이로드(api {@code POST /v1/signals/reports}) 계약 테스트.
 *
 * <p><b>runDate 와 sessionDate 는 다르다(Step 1 확인 결과).</b>
 * <ul>
 *   <li>{@code runDate} 는 배치 <i>실행일</i>이다 — {@code BatchRunner#resolveRunDate} 가
 *       {@code LocalDate.now(clock)} 를 쓰고(재실행 시 {@code --stockpulse.run-date}),
 *       {@code EmptyRunPolicy} 는 그 날짜가 주말이면 SKIP 한다.</li>
 *   <li>실제 수집 세션일은 {@code KrxAllStocksSource#collect} 가 {@code runDate - 1} 부터 거슬러
 *       올라가며 데이터가 나온 첫 평일({@code candidate})이다. 이 값은 로그에만 찍히고
 *       {@code RawData}·{@code CollectionResult} 로 전달되지 않는다. 네이버 소스는 runDate 를 무시하는
 *       실시간 소스라 날짜를 주지 않는다.</li>
 * </ul>
 * 따라서 이 빌더는 sessionDate 를 추측하지 않고 호출자에게서 받는다. 배선(Task 14)은 KRX 소스가 찾은
 * 세션일을 파이프라인으로 올려야 한다 — "runDate 전 평일" 로 대신하면 공휴일 다음 날 틀린다.
 */
class ReportPayloadBuilderTest {

    /** 2026-10-23 06:00 KST. 시계 존을 UTC 로 둬서 UTC 날짜(10-22)가 아니라 KST 날짜를 쓰는지 본다. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T21:00:00Z"), ZoneOffset.UTC);
    private final ReportPayloadBuilder builder = new ReportPayloadBuilder(clock);

    private final LocalDate sessionDate = LocalDate.of(2026, 10, 22);

    private Report report(String content) {
        return Report.builder()
                .reportDate(LocalDate.of(2026, 10, 23))
                .format(ReportFormat.MARKDOWN)
                .content(content)
                .generatedAt(Instant.parse("2026-10-22T21:01:00Z"))
                .build();
    }

    private StockMetric full(String symbol, String name) {
        return StockMetric.builder()
                .symbol(symbol).name(name).source("krx-all")
                .price(new BigDecimal("71000")).previousPrice(new BigDecimal("68000"))
                .changeRate(new BigDecimal("4.41"))
                .volume(2_000_000L).previousVolume(800_000L)
                .volumeChangeRate(new BigDecimal("150.00"))
                .build();
    }

    private StockMetric withoutVolume(String symbol, String name) {
        return StockMetric.builder()
                .symbol(symbol).name(name).source("krx-all")
                .price(new BigDecimal("190000")).previousPrice(new BigDecimal("200000"))
                .changeRate(new BigDecimal("-5.00"))
                .volume(null).previousVolume(1_000_000L)
                .volumeChangeRate(null)
                .build();
    }

    /** 순서를 일부러 심볼 역순으로 둔다 — items 는 metrics 순서를 따라야 한다. */
    private ScreeningResult screened() {
        return new ScreeningResult(
                List.of(withoutVolume("000660", "SK하이닉스"), full("005930", "삼성전자")),
                2765, 41,
                Map.of("005930", List.of("volume-spike", "gap-up"),
                        "000660", List.of("drop-5pct")));
    }

    @Test
    void evidenceIdsAndFields() {
        ReportPayload payload = builder.build(report("# 리포트\n본문"), screened(), sessionDate);

        assertThat(payload.reportId()).isEqualTo("pulse-2026-10-23");
        assertThat(payload.tradeDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(payload.body()).isEqualTo("# 리포트\n본문");

        JsonNode s = payload.screening();
        assertThat(s.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(s.get("sessionDate").asText()).isEqualTo("2026-10-22");
        assertThat(s.get("totalEvaluated").intValue()).isEqualTo(2765);
        assertThat(s.get("matchedCount").intValue()).isEqualTo(41);
        assertThat(s.get("items")).hasSize(2);

        JsonNode first = s.get("items").get(0);
        assertThat(first.get("evidenceId").asText()).isEqualTo("pulse-2026-10-23:000660");
        assertThat(first.get("symbol").asText()).isEqualTo("000660");

        JsonNode samsung = s.get("items").get(1);
        assertThat(samsung.get("evidenceId").asText()).isEqualTo("pulse-2026-10-23:005930");
        assertThat(samsung.get("name").asText()).isEqualTo("삼성전자");
        assertThat(samsung.get("price").decimalValue()).isEqualByComparingTo("71000");
        assertThat(samsung.get("previousPrice").decimalValue()).isEqualByComparingTo("68000");
        assertThat(samsung.get("changeRate").decimalValue()).isEqualByComparingTo("4.41");
        assertThat(samsung.get("volume").longValue()).isEqualTo(2_000_000L);
        assertThat(samsung.get("previousVolume").longValue()).isEqualTo(800_000L);
        assertThat(samsung.get("volumeChangeRate").decimalValue()).isEqualByComparingTo("150");
        // 정렬된 ruleHits
        assertThat(samsung.get("ruleHits")).extracting(JsonNode::asText).containsExactly("gap-up", "volume-spike");
        // 원천 표시(source)는 계약에 없다
        assertThat(samsung.has("source")).isFalse();
    }

    @Test
    void explicitTradeDateOverridesClock() {
        ReportPayload payload = builder.build(report("본문"), screened(), sessionDate, LocalDate.of(2026, 10, 24));

        assertThat(payload.reportId()).isEqualTo("pulse-2026-10-24");
        assertThat(payload.screening().get("items").get(0).get("evidenceId").asText())
                .isEqualTo("pulse-2026-10-24:000660");
    }

    @Test
    void nullMetricGoesToMissing() {
        ReportPayload payload = builder.build(report("본문"), screened(), sessionDate);

        JsonNode hynix = payload.screening().get("items").get(0);
        assertThat(hynix.has("volume")).isFalse();
        assertThat(hynix.has("volumeChangeRate")).isFalse();
        assertThat(hynix.get("missing")).extracting(JsonNode::asText)
                .containsExactly("volume", "volumeChangeRate");
        assertThat(hynix.get("previousVolume").longValue()).isEqualTo(1_000_000L);

        JsonNode samsung = payload.screening().get("items").get(1);
        assertThat(samsung.get("missing")).isEmpty();
        assertThat(builder.toJson(payload)).doesNotContain("\"volume\":0");
    }

    @Test
    void symbolWithoutRuleHitsGetsEmptyList() {
        ScreeningResult passthrough = ScreeningResult.passthrough(List.of(full("005930", "삼성전자")));

        ReportPayload payload = builder.build(report("본문"), passthrough, sessionDate);

        assertThat(payload.screening().get("items").get(0).get("ruleHits")).isEmpty();
    }

    @Test
    void hashIsStableAndChangesWithBody() throws Exception {
        ReportPayload a = builder.build(report("본문 A"), screened(), sessionDate);
        ReportPayload again = builder.build(report("본문 A"), screened(), sessionDate);
        ReportPayload b = builder.build(report("본문 B"), screened(), sessionDate);
        ReportPayload otherScreening = builder.build(report("본문 A"),
                new ScreeningResult(List.of(full("005930", "삼성전자")), 2765, 41, Map.of()), sessionDate);

        assertThat(a.reportHash()).matches("[0-9a-f]{64}");
        assertThat(again.reportHash()).isEqualTo(a.reportHash());
        assertThat(builder.toJson(again)).isEqualTo(builder.toJson(a));
        assertThat(b.reportHash()).isNotEqualTo(a.reportHash());
        assertThat(otherScreening.reportHash()).isNotEqualTo(a.reportHash());

        // 정본: SHA-256 hex(body + "\n" + 전송되는 screening JSON 문자열), UTF-8
        String json = builder.toJson(a);
        String marker = "\"screening\":";
        String screeningJson = json.substring(json.indexOf(marker) + marker.length(), json.length() - 1);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("본문 A" + "\n" + screeningJson).getBytes(StandardCharsets.UTF_8));
        assertThat(a.reportHash()).isEqualTo(HexFormat.of().formatHex(digest));
    }

    @Test
    void dataAsOfIsSessionClose() {
        ReportPayload payload = builder.build(report("본문"), screened(), sessionDate);

        // sessionDate 15:30 +09:00
        assertThat(payload.dataAsOf()).isEqualTo(Instant.parse("2026-10-22T06:30:00Z"));
        assertThat(builder.toJson(payload)).contains("\"dataAsOf\":\"2026-10-22T06:30:00Z\"");
    }

    @Test
    void sessionDateIsRequired() {
        assertThatThrownBy(() -> builder.build(report("본문"), screened(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionDate");
    }

    /** 심볼 없는 지표는 evidenceId 가 "pulse-…:null" 이 되어 인용 가능한 가짜 근거가 된다 — 조용히 내보내지 않는다. */
    @Test
    void nullMetricSymbolFailsFast() {
        StockMetric noSymbol = StockMetric.builder().name("이름만").price(new BigDecimal("1000")).build();
        ScreeningResult bad = new ScreeningResult(List.of(noSymbol), 1, 1, Map.of());

        assertThatThrownBy(() -> builder.build(report("본문"), bad, sessionDate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol");
    }

    @Test
    void jsonFieldOrderFixed() {
        ScreeningResult one = new ScreeningResult(
                List.of(withoutVolume("000660", "SK하이닉스")), 10, 1,
                Map.of("000660", List.of("drop-5pct", "a-rule")));

        String json = builder.toJson(builder.build(report("# 제목\n\"인용\""), one, sessionDate));

        String expectedScreening = "{\"schemaVersion\":1,\"sessionDate\":\"2026-10-22\",\"totalEvaluated\":10,"
                + "\"matchedCount\":1,\"items\":[{\"evidenceId\":\"pulse-2026-10-23:000660\",\"symbol\":\"000660\","
                + "\"name\":\"SK하이닉스\",\"price\":190000,\"previousPrice\":200000,\"changeRate\":-5.00,"
                + "\"previousVolume\":1000000,\"ruleHits\":[\"a-rule\",\"drop-5pct\"],"
                + "\"missing\":[\"volume\",\"volumeChangeRate\"]}]}";
        assertThat(json).startsWith("{\"reportId\":\"pulse-2026-10-23\",\"reportHash\":\"");
        assertThat(json).endsWith("\",\"tradeDate\":\"2026-10-23\",\"dataAsOf\":\"2026-10-22T06:30:00Z\","
                + "\"body\":\"# 제목\\n\\\"인용\\\"\",\"screening\":" + expectedScreening + "}");
    }

    @Test
    void bigDecimalWrittenPlain() {
        StockMetric sci = StockMetric.builder()
                .symbol("005930").name("삼성전자")
                .price(new BigDecimal("7.1E+4")).previousPrice(new BigDecimal("6.8E+4"))
                .changeRate(new BigDecimal("1E-7"))
                .volume(1L).previousVolume(1L).volumeChangeRate(new BigDecimal("0E-2"))
                .build();

        String json = builder.toJson(builder.build(report("본문"),
                new ScreeningResult(List.of(sci), 1, 1, Map.of()), sessionDate));

        assertThat(json).contains("\"price\":71000,").contains("\"previousPrice\":68000,")
                .contains("\"changeRate\":0.0000001,").contains("\"volumeChangeRate\":0.00,");
        assertThat(json).doesNotContain("E+").doesNotContain("E-");
    }
}

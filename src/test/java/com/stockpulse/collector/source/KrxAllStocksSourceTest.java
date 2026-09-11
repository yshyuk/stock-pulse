package com.stockpulse.collector.source;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.RawData;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixtures are VERBATIM rows captured from the live KRX Open API on 2026-09-09 (basDd=20260909),
 * not hand-written — the previous version of this source was built against a guessed schema and
 * had to be thrown away.
 */
class KrxAllStocksSourceTest {

    /** Real KOSPI rows: one falling (-20) and one rising (+63,000). */
    private static final String KOSPI = """
            {"OutBlock_1":[
              {"BAS_DD":"20260909","ISU_CD":"095570","ISU_NM":"AJ네트웍스","MKT_NM":"KOSPI",
               "TDD_CLSPRC":"4170","CMPPREVDD_PRC":"-20","FLUC_RT":"-0.48",
               "ACC_TRDVOL":"61612","ACC_TRDVAL":"257346867"},
              {"BAS_DD":"20260909","ISU_CD":"000660","ISU_NM":"SK하이닉스","MKT_NM":"KOSPI",
               "TDD_CLSPRC":"1856000","CMPPREVDD_PRC":"63000","FLUC_RT":"3.51",
               "ACC_TRDVOL":"3526484","ACC_TRDVAL":"6518811756305"}
            ]}""";

    /** Real KOSDAQ row. */
    private static final String KOSDAQ = """
            {"OutBlock_1":[
              {"BAS_DD":"20260909","ISU_CD":"060310","ISU_NM":"3S","MKT_NM":"KOSDAQ",
               "TDD_CLSPRC":"1159","CMPPREVDD_PRC":"-12","ACC_TRDVOL":"62267"}
            ]}""";

    private static final String EMPTY = "{\"OutBlock_1\":[]}";

    private MockWebServer server;
    private StockPulseProperties props;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        props = new StockPulseProperties();
        StockPulseProperties.KrxAll cfg = props.getCollector().getKrxAll();
        cfg.setEnabled(true);
        cfg.setApiKey("test-key");
        cfg.setBaseUrl(server.url("/svc/apis/sto").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    /**
     * The run date is now an argument rather than wall-clock time, so a backfill of a past date
     * asks KRX for the session before THAT date — which is the point of the change.
     */
    private KrxAllStocksSource source() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T06:00:00Z"), ZoneOffset.UTC);
        return new KrxAllStocksSource(props, WebClient.builder().build(), clock);
    }

    @Test
    void mergesBothMarketsIntoOneList() throws InterruptedException {
        server.enqueue(json(KOSPI));
        server.enqueue(json(KOSDAQ));

        List<RawData> items = source().collect(LocalDate.parse("2026-09-10"));

        assertThat(items).extracting(RawData::getSymbol)
                .containsExactlyInAnyOrder("095570", "000660", "060310");
        assertThat(items).allMatch(i -> "krx-all".equals(i.getSourceName()));

        RecordedRequest first = server.takeRequest();
        assertThat(first.getHeader("AUTH_KEY")).isEqualTo("test-key");
        // A dawn run must ask for the PRIOR session — KRX publishes after the close, so
        // asking for today returns zero rows.
        assertThat(first.getPath()).contains("basDd=20260909");
    }

    @Test
    void derivesPreviousCloseFromTheSignedChange() {
        server.enqueue(json(KOSPI));
        server.enqueue(json(EMPTY));

        List<RawData> items = source().collect(LocalDate.parse("2026-09-10"));

        RawData falling = items.stream().filter(i -> i.getSymbol().equals("095570")).findFirst().orElseThrow();
        // Falling: 4170 - (-20) = 4190.
        assertThat(falling.getPayload()).containsEntry("price", 4170L)
                .containsEntry("previousPrice", 4190L)
                .containsEntry("volume", 61612L);

        RawData rising = items.stream().filter(i -> i.getSymbol().equals("000660")).findFirst().orElseThrow();
        // Rising: 1856000 - 63000 = 1793000.
        assertThat(rising.getPayload()).containsEntry("previousPrice", 1793000L);
    }

    @Test
    void walksBackPastSessionlessDaysToFindTheLastRealOne() throws InterruptedException {
        // Monday 2026-09-14: Sunday and Saturday are skipped without a call, and Friday answers.
        server.enqueue(json(KOSPI));
        server.enqueue(json(KOSDAQ));

        List<RawData> items = source().collect(LocalDate.parse("2026-09-14"));

        assertThat(items).isNotEmpty();
        assertThat(server.takeRequest().getPath()).contains("basDd=20260911");
    }

    @Test
    void keepsLookingWhenASessionReturnsNoRows() throws InterruptedException {
        // A public holiday answers 200 with zero rows; the source must try the day before.
        // NOTE: every session costs TWO requests (KOSPI then KOSDAQ).
        server.enqueue(json(EMPTY));   // 09-09 KOSPI  (pretend holiday)
        server.enqueue(json(EMPTY));   // 09-09 KOSDAQ
        server.enqueue(json(KOSPI));   // 09-08 KOSPI
        server.enqueue(json(KOSDAQ));  // 09-08 KOSDAQ

        List<RawData> items = source().collect(LocalDate.parse("2026-09-10"));

        assertThat(items).isNotEmpty();
        assertThat(server.takeRequest().getPath()).contains("basDd=20260909");
        server.takeRequest();  // 09-09 KOSDAQ
        assertThat(server.takeRequest().getPath()).contains("basDd=20260908");
    }

    @Test
    void givesUpAfterTheLookbackWindowInsteadOfLoopingForever() {
        props.getCollector().getKrxAll().setMaxLookbackDays(2);
        // Two sessions (09-09, 09-08) x two markets = four empty answers, then it must stop.
        for (int i = 0; i < 4; i++) {
            server.enqueue(json(EMPTY));
        }

        assertThat(source().collect(LocalDate.parse("2026-09-10"))).isEmpty();
    }

    @Test
    void aRejectedKeyExplainsBothCausesRatherThanJustTheStatus() {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"respMsg\":\"Unauthorized API Call\",\"respCode\":\"401\"}"));

        // 401 means either a bad/expired key or a missing per-service subscription, and the fix
        // is different for each. A bare status code sends the operator hunting.
        assertThatThrownBy(() -> source().collect(LocalDate.parse("2026-09-10")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired")
                .hasMessageContaining("이용신청");
    }

    @Test
    void isRequiredSoThatAFailedPriceFeedDegradesTheRun() {
        assertThat(source().isRequired()).isTrue();
    }

    @Test
    void isDisabledWithoutAnApiKey() {
        props.getCollector().getKrxAll().setApiKey("  ");
        assertThat(source().isEnabled()).isFalse();
    }

    private MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}

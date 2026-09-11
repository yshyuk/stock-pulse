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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures here are VERBATIM captures from the live endpoint (2026-09-09), not hand-written.
 *
 * <p>This matters: the previous fixture was invented from the field names the code already used
 * ({@code cd/nm/nv/pcv/aq/cr}). When Naver renamed every one of them the test stayed green and the
 * source silently returned nothing. A hand-written fixture can only ever confirm the assumptions
 * that produced it. Re-capture from the real endpoint when updating these.
 */
class NaverDataSourceTest {

    /** Verbatim response for 005930 (rising) and 035420 (falling), trimmed to the fields we read. */
    private static final String LIVE_RESPONSE = """
            {"pollingInterval":7000,"datas":[
              {"itemCode":"005930","stockName":"삼성전자","closePrice":"270,000",
               "compareToPreviousClosePrice":"500","fluctuationsRatio":"0.19",
               "accumulatedTradingVolume":"13,958,538","marketStatus":"OPEN",
               "closePriceRaw":"270000","compareToPreviousClosePriceRaw":"500",
               "fluctuationsRatioRaw":"0.19","accumulatedTradingVolumeRaw":"13958538",
               "compareToPreviousPrice":{"code":"2","text":"상승","name":"RISING"}},
              {"itemCode":"035420","stockName":"NAVER","closePrice":"210,000",
               "compareToPreviousClosePrice":"-4,500","fluctuationsRatio":"-2.10",
               "accumulatedTradingVolume":"592,090","marketStatus":"OPEN",
               "closePriceRaw":"210000","compareToPreviousClosePriceRaw":"-4500",
               "fluctuationsRatioRaw":"-2.10","accumulatedTradingVolumeRaw":"592090",
               "compareToPreviousPrice":{"code":"5","text":"하락","name":"FALLING"}}
            ],"time":"20260909145423"}""";

    private MockWebServer server;
    private NaverDataSource source;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        StockPulseProperties props = new StockPulseProperties();
        StockPulseProperties.Naver naver = props.getCollector().getNaver();
        naver.setEnabled(true);
        naver.setBaseUrl(server.url("/api/realtime/domestic/stock").toString());
        naver.setSymbols(List.of("005930", "035420"));

        source = new NaverDataSource(props, WebClient.builder().build(), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void mapsLiveQuotesToRawData() throws InterruptedException {
        server.enqueue(json(LIVE_RESPONSE));

        List<RawData> items = source.collect(LocalDate.of(2026, 9, 10));

        assertThat(items).hasSize(2);
        RawData samsung = items.get(0);
        assertThat(samsung.getSymbol()).isEqualTo("005930");
        assertThat(samsung.getName()).isEqualTo("삼성전자");
        assertThat(samsung.getSourceName()).isEqualTo("naver-finance");
        assertThat(samsung.getPayload()).containsEntry("price", 270000L)
                .containsEntry("volume", 13_958_538L);

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getPath()).contains("005930,035420");
    }

    @Test
    void derivesPreviousCloseFromTheSignedChange() {
        server.enqueue(json(LIVE_RESPONSE));

        List<RawData> items = source.collect(LocalDate.of(2026, 9, 10));

        // Naver reports the change, not the previous close. Rising: 270000 - 500 = 269500.
        assertThat(items.get(0).getPayload()).containsEntry("previousPrice", 269500L);
        // Falling stocks carry a NEGATIVE change, so subtracting adds back: 210000 - (-4500).
        assertThat(items.get(1).getPayload()).containsEntry("previousPrice", 214500L);
    }

    @Test
    void skipsQuotesMissingAPriceRatherThanFailingTheRun() {
        server.enqueue(json("""
                {"datas":[
                  {"itemCode":"005930","stockName":"삼성전자","closePriceRaw":"270000",
                   "compareToPreviousClosePriceRaw":"500","accumulatedTradingVolumeRaw":"13958538"},
                  {"itemCode":"999999","stockName":"거래정지","tradableStatus":"suspended"}
                ]}"""));

        assertThat(source.collect(LocalDate.of(2026, 9, 10))).extracting(RawData::getSymbol).containsExactly("005930");
    }

    @Test
    void emptyPayloadYieldsNoItems() {
        server.enqueue(json("{\"datas\":[]}"));

        assertThat(source.collect(LocalDate.of(2026, 9, 10))).isEmpty();
    }

    private MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}

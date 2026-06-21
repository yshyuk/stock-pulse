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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NaverDataSourceTest {

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
        naver.setSymbols(List.of("005930", "000660"));

        source = new NaverDataSource(props, WebClient.builder().build(), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void mapsRealtimeQuotesToRawData() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"resultCode":"success","datas":[
                          {"cd":"005930","nm":"삼성전자","nv":78900,"pcv":77000,"aq":15200000,"cr":2.47},
                          {"cd":"000660","nm":"SK하이닉스","nv":201500,"pcv":205000,"aq":4100000,"cr":-1.71}
                        ]}"""));

        List<RawData> result = source.collect();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/realtime/domestic/stock/005930,000660");

        assertThat(result).hasSize(2);
        RawData first = result.get(0);
        assertThat(first.getSymbol()).isEqualTo("005930");
        assertThat(first.getName()).isEqualTo("삼성전자");
        assertThat(first.getPayload().get("price")).isEqualTo(78900L);
        assertThat(first.getPayload().get("previousPrice")).isEqualTo(77000L);
        assertThat(first.getPayload().get("volume")).isEqualTo(15200000L);
    }

    @Test
    void parsesNestedAreasShape() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"result":{"areas":[{"datas":[
                          {"cd":"005930","nm":"삼성전자","nv":78900,"pcv":77000,"aq":15200000}
                        ]}]}}"""));

        List<RawData> result = source.collect();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSymbol()).isEqualTo("005930");
    }
}

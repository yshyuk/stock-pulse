package com.stockpulse.batch;

import com.stockpulse.config.StockPulseProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatPingerTest {

    private MockWebServer server;
    private final WebClient webClient = WebClient.builder().build();
    private StockPulseProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        properties = new StockPulseProperties();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void pingsWhenUrlConfigured() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200));
        properties.getHeartbeat().setUrl(server.url("/ping").toString());

        new HeartbeatPinger(webClient, properties).pingSuccess();

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(server.takeRequest().getPath()).isEqualTo("/ping");
    }

    @Test
    void noPingWhenUrlBlank() {
        properties.getHeartbeat().setUrl("");

        new HeartbeatPinger(webClient, properties).pingSuccess();

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void pingFailureIsSwallowed() {
        // Point at the running server but make it drop the connection.
        server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));
        properties.getHeartbeat().setUrl(server.url("/ping").toString());

        // Should not throw despite the disconnect.
        new HeartbeatPinger(webClient, properties).pingSuccess();
    }
}

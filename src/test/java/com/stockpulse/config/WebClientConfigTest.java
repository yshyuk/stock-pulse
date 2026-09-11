package com.stockpulse.config;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared {@link WebClient} must cope with whole-market responses.
 *
 * <p>Spring's default in-memory buffer is 256 KB. A single KRX market response is larger than
 * that (KOSPI ~293 KB, KOSDAQ ~595 KB as of 2026-09), so the default silently turned a healthy
 * {@code 200 OK} into a {@code DataBufferLimitException} and failed the whole run. Unit tests
 * with small fixtures cannot catch this — only a realistically sized body can.
 */
class WebClientConfigTest {

    private MockWebServer server;
    private WebClient webClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        webClient = new WebClientConfig().webClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void readsAResponseLargerThanTheDefaultBufferLimit() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(jsonOfRoughly(700_000)));

        JsonNode body = webClient.get().uri(server.url("/big").toString())
                .retrieve().bodyToMono(JsonNode.class).block();

        assertThat(body).isNotNull();
        assertThat(body.path("OutBlock_1").isArray()).isTrue();
        assertThat(body.path("OutBlock_1")).isNotEmpty();
    }

    /** A KRX-shaped payload of at least {@code targetBytes}, so the size is what is under test. */
    private String jsonOfRoughly(int targetBytes) {
        StringBuilder sb = new StringBuilder("{\"OutBlock_1\":[");
        for (int i = 0; sb.length() < targetBytes; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"ISU_CD\":\"%06d\",\"ISU_NM\":\"종목%d\",\"TDD_CLSPRC\":\"1000\","
                            .formatted(i % 1_000_000, i))
                    .append("\"CMPPREVDD_PRC\":\"-10\",\"ACC_TRDVOL\":\"12345\"}");
        }
        return sb.append("]}").toString();
    }
}

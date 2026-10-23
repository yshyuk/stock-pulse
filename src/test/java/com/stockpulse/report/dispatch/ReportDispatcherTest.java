package com.stockpulse.report.dispatch;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.Report;
import com.stockpulse.domain.ReportFormat;
import com.stockpulse.domain.StockMetric;
import com.stockpulse.plan.dispatch.PlanDispatcher;
import com.stockpulse.screening.ScreeningResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Status mapping is pinned against stock-api's {@code StrategyReportController}: a new report is
 * answered with <b>202</b> (CREATED → ACCEPTED there), a byte-identical re-send with 200, a
 * different body for the same reportId with 409, a rejected payload with 422. 201 is accepted too
 * because it is the conventional "created" status and the brief names it.
 */
class ReportDispatcherTest {

    private MockWebServer server;
    private StockPulseProperties properties;
    private ReportPayloadBuilder builder;
    private ReportDispatcher dispatcher;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        properties = new StockPulseProperties();
        StockPulseProperties.ReportDispatch cfg = properties.getReportDispatch();
        cfg.setEnabled(true);
        cfg.setUrl(server.url("/v1/signals/reports").toString());
        cfg.setApiKey("test-report-key");
        cfg.setMaxAttempts(3);
        cfg.setRetryDelayMs(0); // retry timing is not under test
        cfg.setTimeoutSeconds(5);

        builder = new ReportPayloadBuilder(Clock.fixed(Instant.parse("2026-10-22T21:00:00Z"), ZoneOffset.UTC));
        dispatcher = new ReportDispatcher(properties, builder, WebClient.builder().build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void createdOnFirstAttempt() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(202)); // what stock-api actually returns

        ReportDispatchResult result = dispatcher.dispatch(payload("# 본문"));

        assertThat(result.status()).isEqualTo(ReportDispatchResult.Status.CREATED);
        assertThat(result.attempts()).isEqualTo(1);
        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getMethod()).isEqualTo("POST");
        assertThat(sent.getPath()).isEqualTo("/v1/signals/reports");
    }

    @Test
    void status201IsAlsoCreated() {
        server.enqueue(new MockResponse().setResponseCode(201));

        assertThat(dispatcher.dispatch(payload("# 본문")).status())
                .isEqualTo(ReportDispatchResult.Status.CREATED);
    }

    @Test
    void duplicateIsSuccessNoRetry() {
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(202)); // must never be consumed

        ReportDispatchResult result = dispatcher.dispatch(payload("# 본문"));

        assertThat(result.status()).isEqualTo(ReportDispatchResult.Status.DUPLICATE);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void conflictIsNotRetriedAndReported() {
        // Review Focus 5: same-day re-run with a changed body. Retrying cannot change the answer.
        server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("{\"outcome\":\"CONFLICT\",\"reportRowId\":7,\"message\":\"hash mismatch\"}"));
        server.enqueue(new MockResponse().setResponseCode(202));

        ReportDispatchResult result = dispatcher.dispatch(payload("# 바뀐 본문"));

        assertThat(result.status()).isEqualTo(ReportDispatchResult.Status.CONFLICT);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.reason()).contains("409");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void otherClientErrorIsInvalidNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(422));
        server.enqueue(new MockResponse().setResponseCode(202));

        ReportDispatchResult result = dispatcher.dispatch(payload("# 본문"));

        assertThat(result.status()).isEqualTo(ReportDispatchResult.Status.INVALID);
        assertThat(result.reason()).contains("422");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void unauthorizedIsInvalidNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(401));

        ReportDispatchResult result = dispatcher.dispatch(payload("# 본문"));

        assertThat(result.status()).isEqualTo(ReportDispatchResult.Status.INVALID);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void serverErrorRetriesSameBytes() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(201));

        ReportPayload payload = payload("# 본문 — 한글과 기호 ✓");
        ReportDispatchResult result = dispatcher.dispatch(payload);

        assertThat(result.status()).isEqualTo(ReportDispatchResult.Status.CREATED);
        assertThat(result.attempts()).isEqualTo(3);
        byte[] expected = builder.toJson(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            assertThat(server.takeRequest().getBody().readByteArray()).isEqualTo(expected);
        }
    }

    @Test
    void exhaustedRetriesFailWithoutThrowing() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }

        ReportDispatchResult result = dispatcher.dispatch(payload("# 본문"));

        assertThat(result.status()).isEqualTo(ReportDispatchResult.Status.FAILED);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.reason()).contains("500");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void timeoutIsRetried() {
        properties.getReportDispatch().setTimeoutSeconds(1);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setResponseCode(202));

        ReportDispatchResult result = dispatcher.dispatch(payload("# 본문"));

        assertThat(result.status()).isEqualTo(ReportDispatchResult.Status.CREATED);
        assertThat(result.attempts()).isEqualTo(2);
    }

    @Test
    void disabledSkipsWithoutRequest() {
        properties.getReportDispatch().setEnabled(false);
        ReportDispatchResult off = dispatcher.dispatch(payload("# 본문"));
        assertThat(off.status()).isEqualTo(ReportDispatchResult.Status.SKIPPED);
        assertThat(off.isDisabled()).isTrue();

        // enabled but not configured is also off — never POST to an empty URL / without a key
        properties.getReportDispatch().setEnabled(true);
        properties.getReportDispatch().setApiKey("");
        assertThat(dispatcher.isEnabled()).isFalse();
        assertThat(dispatcher.dispatch(payload("# 본문")).status())
                .isEqualTo(ReportDispatchResult.Status.SKIPPED);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void apiKeyHeaderSent() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(202));

        dispatcher.dispatch(payload("# 본문"));

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(sent).isNotNull();
        // same header name as the v2 plan ingest — one agent key filter on the api side
        assertThat(sent.getHeader(PlanDispatcher.API_KEY_HEADER)).isEqualTo("test-report-key");
        assertThat(sent.getHeader("Content-Type")).startsWith("application/json");
    }

    @Test
    void usesItsOwnConfigNotTheV2PlanDispatchBlock() {
        properties.getDispatch().setEnabled(true);
        properties.getDispatch().setUrl("http://127.0.0.1:1/never");
        properties.getDispatch().setApiKey("v2-key");
        properties.getReportDispatch().setEnabled(false);

        assertThat(dispatcher.isEnabled()).isFalse();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ReportPayload payload(String body) {
        Report report = Report.builder().reportDate(LocalDate.of(2026, 10, 23)).format(ReportFormat.MARKDOWN)
                .content(body).generatedAt(Instant.parse("2026-10-22T21:00:00Z")).build();
        StockMetric m = StockMetric.builder().symbol("000001").name("가상종목")
                .price(new BigDecimal("10000")).previousPrice(new BigDecimal("9800"))
                .changeRate(new BigDecimal("2.04")).volume(150_000L).build();
        ScreeningResult screened = new ScreeningResult(List.of(m), 10, 1, Map.of("000001", List.of("surge-up")));
        return builder.build(report, screened, LocalDate.of(2026, 10, 22));
    }
}

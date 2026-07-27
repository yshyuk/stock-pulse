package com.stockpulse.plan.dispatch;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.plan.Entry;
import com.stockpulse.plan.Exit;
import com.stockpulse.plan.MarketContext;
import com.stockpulse.plan.PlanCandidate;
import com.stockpulse.plan.PlanConstraints;
import com.stockpulse.plan.PlanJsonSerializer;
import com.stockpulse.plan.Sizing;
import com.stockpulse.plan.TradingPlan;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanDispatcherTest {

    private MockWebServer server;
    private StockPulseProperties properties;
    private PlanDispatcher dispatcher;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        properties = new StockPulseProperties();
        StockPulseProperties.Dispatch cfg = properties.getDispatch();
        cfg.setEnabled(true);
        cfg.setUrl(server.url("/v1/signals/plans").toString());
        cfg.setApiKey("test-key");
        cfg.setMaxAttempts(3);
        cfg.setRetryDelayMs(0); // keep the test fast; retry timing is not under test
        cfg.setTimeoutSeconds(5);

        dispatcher = new PlanDispatcher(properties, new PlanJsonSerializer(), WebClient.builder().build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private TradingPlan planWithMode(String mode) {
        return TradingPlan.builder()
                .schemaVersion(TradingPlan.SCHEMA_VERSION)
                .planDate(LocalDate.of(2026, 7, 24))
                .generatedAt(Instant.parse("2026-07-24T06:00:00Z"))
                .mode(mode)
                .constraints(PlanConstraints.builder()
                        .maxBudgetPerSymbolKrw(new BigDecimal("500000"))
                        .maxTotalBudgetKrw(new BigDecimal("2000000"))
                        .build())
                .marketContext(MarketContext.empty())
                .candidates(List.of(PlanCandidate.builder()
                        .symbol("005930").name("삼성전자")
                        .matchedRules(List.of("momentum-up"))
                        .priority(1)
                        .entry(Entry.builder().type("limit").priceKrw(new BigDecimal("61000")).build())
                        .exit(Exit.builder().targetPriceKrw(new BigDecimal("64000"))
                                .stopLossPriceKrw(new BigDecimal("59200")).build())
                        .sizing(Sizing.builder().maxBudgetKrw(new BigDecimal("500000")).build())
                        .build()))
                .warnings(List.of())
                .build();
    }

    @Test
    void deliversSignalPlanWithApiKeyHeader() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(202));

        DispatchResult result = dispatcher.dispatch(planWithMode(TradingPlan.MODE_SIGNAL));

        assertThat(result.isDelivered()).isTrue();
        assertThat(result.attempts()).isEqualTo(1);

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getMethod()).isEqualTo("POST");
        assertThat(sent.getHeader(PlanDispatcher.API_KEY_HEADER)).isEqualTo("test-key");
        assertThat(sent.getBody().readUtf8()).contains("\"mode\" : \"signal\"", "005930");
    }

    @Test
    void refusesToSendPlanNotMarkedAsSignal() {
        // A plan-only plan reaching the wire would mean a config slip silently started driving
        // real conditions — the dispatcher must stop it, not the consumer.
        DispatchResult result = dispatcher.dispatch(planWithMode(TradingPlan.MODE_PLAN_ONLY));

        assertThat(result.status()).isEqualTo(DispatchResult.Status.SKIPPED);
        assertThat(result.reason()).contains("signal");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void retriesThenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(202));

        DispatchResult result = dispatcher.dispatch(planWithMode(TradingPlan.MODE_SIGNAL));

        assertThat(result.isDelivered()).isTrue();
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void reportsFailureAfterExhaustingAttemptsWithoutThrowing() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));

        DispatchResult result = dispatcher.dispatch(planWithMode(TradingPlan.MODE_SIGNAL));

        // Fail-safe: the batch must survive an unreachable consumer — no orders are forced.
        assertThat(result.isFailed()).isTrue();
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void skipsWhenDisabledOrUnconfigured() {
        properties.getDispatch().setEnabled(false);
        assertThat(dispatcher.dispatch(planWithMode(TradingPlan.MODE_SIGNAL)).status())
                .isEqualTo(DispatchResult.Status.SKIPPED);

        properties.getDispatch().setEnabled(true);
        properties.getDispatch().setApiKey("");
        assertThat(dispatcher.isEnabled()).isFalse();
        assertThat(dispatcher.dispatch(planWithMode(TradingPlan.MODE_SIGNAL)).status())
                .isEqualTo(DispatchResult.Status.SKIPPED);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void skipsNullPlan() {
        assertThat(dispatcher.dispatch(null).status()).isEqualTo(DispatchResult.Status.SKIPPED);
    }
}

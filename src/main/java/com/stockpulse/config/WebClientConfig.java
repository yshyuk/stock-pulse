package com.stockpulse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;

/**
 * Shared infrastructure beans.
 *
 * <p>{@link WebClient} is the single HTTP client for all outbound API calls (data sources,
 * notification webhooks, and a future Claude API call). {@link Clock} is provided so time
 * is injectable and testable.
 */
@Configuration
public class WebClientConfig {

    /**
     * Buffer ceiling for a single response body.
     *
     * <p>Spring's default is 256 KB, which is smaller than one whole-market KRX response
     * (KOSPI ~293 KB, KOSDAQ ~595 KB as of 2026-09). Under the default a perfectly healthy
     * {@code 200 OK} surfaced as a {@code DataBufferLimitException} and failed the run. 8 MB
     * leaves room for the market to grow while still bounding memory for a batch process.
     */
    private static final int MAX_IN_MEMORY_BYTES = 8 * 1024 * 1024;

    @Bean
    public WebClient webClient() {
        // TODO: tune timeouts / connection pool when wiring real external calls.
        return WebClient.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}

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

    @Bean
    public WebClient webClient() {
        // TODO: tune timeouts / connection pool when wiring real external calls.
        return WebClient.builder().build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}

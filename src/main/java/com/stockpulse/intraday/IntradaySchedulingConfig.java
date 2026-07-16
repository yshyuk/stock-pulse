package com.stockpulse.intraday;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduling ONLY under the {@code intraday} profile — so the batch-mode app keeps its
 * "no @Scheduled" property (scheduling is the OS's job there) while the intraday engine gets its
 * polling loop. Design ADR-005.
 */
@Configuration
@Profile("intraday")
@EnableScheduling
public class IntradaySchedulingConfig {
}

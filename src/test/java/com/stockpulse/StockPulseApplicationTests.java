package com.stockpulse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: the Spring context wires together (collector/processor/report/storage/
 * notification/analysis beans) under the local profile.
 *
 * <p>{@code stockpulse.batch.auto-run=false} disables {@link com.stockpulse.batch.BatchRunner}
 * so loading the context does not call System.exit and kill the test JVM.
 */
@SpringBootTest(properties = "stockpulse.batch.auto-run=false")
@ActiveProfiles("local")
class StockPulseApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: context startup is the assertion.
    }
}

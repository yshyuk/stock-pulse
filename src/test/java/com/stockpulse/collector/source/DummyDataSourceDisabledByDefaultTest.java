package com.stockpulse.collector.source;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dummy source emits hard-coded prices. When it is live by accident the batch still exits 0
 * and the report looks plausible — a silent failure that shipped once already. It must be opt-in,
 * so the default configuration must not register it at all.
 */
@SpringBootTest(properties = "stockpulse.batch.auto-run=false")
@ActiveProfiles("local")
class DummyDataSourceDisabledByDefaultTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    void dummySourceIsNotRegisteredByDefault() {
        assertThat(ctx.getBeansOfType(DummyDataSource.class)).isEmpty();
    }
}

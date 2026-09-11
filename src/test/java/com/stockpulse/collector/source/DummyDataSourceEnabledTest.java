package com.stockpulse.collector.source;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly opting in (smoke-testing the pipeline) still works. */
@SpringBootTest(properties = {"stockpulse.batch.auto-run=false",
        "stockpulse.collector.dummy.enabled=true"})
@ActiveProfiles("local")
class DummyDataSourceEnabledTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    void dummySourceIsRegisteredWhenExplicitlyEnabled() {
        assertThat(ctx.getBeansOfType(DummyDataSource.class)).hasSize(1);
    }
}

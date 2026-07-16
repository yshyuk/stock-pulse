package com.stockpulse.collector;

import com.stockpulse.domain.RawData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorServiceTest {

    /** Minimal configurable DataSource for testing collection outcomes. */
    private static class FakeSource implements DataSource {
        private final String name;
        private final boolean enabled;
        private final boolean required;
        private final boolean throwing;

        FakeSource(String name, boolean enabled, boolean required, boolean throwing) {
            this.name = name;
            this.enabled = enabled;
            this.required = required;
            this.throwing = throwing;
        }

        public String sourceName() {
            return name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isRequired() {
            return required;
        }

        public List<RawData> collect() {
            if (throwing) {
                throw new RuntimeException("boom");
            }
            return List.of(RawData.builder().sourceName(name).symbol("005930")
                    .payload(Map.of("price", 100)).fetchedAt(Instant.now()).build());
        }
    }

    @Test
    void requiredSourceFailureMarksRunDegraded() {
        CollectorService service = new CollectorService(List.of(
                new FakeSource("naver", true, true, true)));

        CollectionResult result = service.collectAll();

        assertThat(result.hasRequiredFailure()).isTrue();
        assertThat(result.failedRequiredSources()).containsExactly("naver");
    }

    @Test
    void optionalSourceFailureDoesNotDegrade() {
        CollectorService service = new CollectorService(List.of(
                new FakeSource("news", true, false, true),      // optional, fails
                new FakeSource("dummy", true, false, false)));   // optional, ok

        CollectionResult result = service.collectAll();

        assertThat(result.hasRequiredFailure()).isFalse();
        assertThat(result.items()).hasSize(1); // dummy still contributed
    }

    @Test
    void disabledSourcesAreSkipped() {
        CollectorService service = new CollectorService(List.of(
                new FakeSource("naver", false, true, true))); // disabled — not run, not counted

        CollectionResult result = service.collectAll();

        assertThat(result.hasRequiredFailure()).isFalse();
        assertThat(result.items()).isEmpty();
    }
}

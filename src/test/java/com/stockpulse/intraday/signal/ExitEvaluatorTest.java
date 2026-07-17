package com.stockpulse.intraday.signal;

import com.stockpulse.broker.Quote;
import com.stockpulse.intraday.domain.ExitReason;
import com.stockpulse.intraday.domain.PositionRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExitEvaluatorTest {

    private final ExitEvaluator evaluator = new ExitEvaluator();

    private PositionRecord position(String target, String stop) {
        return PositionRecord.builder()
                .symbol("005930")
                .targetPriceKrw(new BigDecimal(target))
                .stopLossPriceKrw(new BigDecimal(stop))
                .build();
    }

    private Quote quote(String price) {
        return new Quote("005930", new BigDecimal(price), Instant.now());
    }

    @Test
    void exitsAtTarget() {
        assertThat(evaluator.evaluate(position("64000", "59000"), quote("64000")))
                .contains(ExitReason.TARGET);
        assertThat(evaluator.evaluate(position("64000", "59000"), quote("64500")))
                .contains(ExitReason.TARGET);
    }

    @Test
    void exitsAtStop() {
        assertThat(evaluator.evaluate(position("64000", "59000"), quote("59000")))
                .contains(ExitReason.STOP);
        assertThat(evaluator.evaluate(position("64000", "59000"), quote("58000")))
                .contains(ExitReason.STOP);
    }

    @Test
    void holdsBetweenTargetAndStop() {
        assertThat(evaluator.evaluate(position("64000", "59000"), quote("61000"))).isEmpty();
    }
}

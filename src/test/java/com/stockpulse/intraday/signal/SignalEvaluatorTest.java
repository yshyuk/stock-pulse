package com.stockpulse.intraday.signal;

import com.stockpulse.broker.Quote;
import com.stockpulse.plan.Entry;
import com.stockpulse.plan.PlanCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SignalEvaluatorTest {

    private final SignalEvaluator evaluator = new SignalEvaluator();

    private PlanCandidate limitAt(String price) {
        return PlanCandidate.builder()
                .symbol("005930")
                .entry(Entry.builder().type("limit").priceKrw(new BigDecimal(price)).build())
                .build();
    }

    private Quote quoteAt(String price) {
        return new Quote("005930", new BigDecimal(price), Instant.now());
    }

    @Test
    void limitEntersWhenPriceAtOrBelowLimit() {
        assertThat(evaluator.shouldEnter(limitAt("61000"), quoteAt("61000"))).isTrue();  // equal
        assertThat(evaluator.shouldEnter(limitAt("61000"), quoteAt("60500"))).isTrue();  // below
        assertThat(evaluator.shouldEnter(limitAt("61000"), quoteAt("61500"))).isFalse(); // above
    }

    @Test
    void quantityFloorsToBudget() {
        assertThat(evaluator.quantityFor(new BigDecimal("500000"), new BigDecimal("61000"))).isEqualTo(8);
        assertThat(evaluator.quantityFor(new BigDecimal("50000"), new BigDecimal("61000"))).isZero();
    }

    @Test
    void nullsAreSafe() {
        assertThat(evaluator.shouldEnter(limitAt("61000"), null)).isFalse();
        assertThat(evaluator.quantityFor(null, new BigDecimal("61000"))).isZero();
        assertThat(evaluator.quantityFor(new BigDecimal("500000"), BigDecimal.ZERO)).isZero();
    }
}

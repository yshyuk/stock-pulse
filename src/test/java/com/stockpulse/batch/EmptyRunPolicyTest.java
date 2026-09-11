package com.stockpulse.batch;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batch used to exit 0 and send a SUCCESS notification even when it had collected no real
 * prices at all — the failure mode that let hard-coded sample data reach a production report.
 * This policy is what makes "we got nothing" loud, without crying wolf every weekend.
 */
class EmptyRunPolicyTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 9);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 9, 12);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 9, 13);

    private final EmptyRunPolicy policy = new EmptyRunPolicy();

    @Test
    void noPricesOnATradingDayIsAFailure() {
        assertThat(policy.decide(WEDNESDAY, 0)).isEqualTo(EmptyRunPolicy.Decision.FAIL);
    }

    @Test
    void noPricesOnAWeekendIsExpectedAndSkipsQuietly() {
        assertThat(policy.decide(SATURDAY, 0)).isEqualTo(EmptyRunPolicy.Decision.SKIP);
        assertThat(policy.decide(SUNDAY, 0)).isEqualTo(EmptyRunPolicy.Decision.SKIP);
    }

    @Test
    void anyPricesMeansProceedRegardlessOfTheDay() {
        assertThat(policy.decide(WEDNESDAY, 1)).isEqualTo(EmptyRunPolicy.Decision.PROCEED);
        // A re-run for a past date can legitimately produce data on a weekend.
        assertThat(policy.decide(SATURDAY, 1)).isEqualTo(EmptyRunPolicy.Decision.PROCEED);
    }

    @Test
    void usesTheRunDateNotToday() {
        // Re-running a missed Wednesday must still demand data, even if executed on a Sunday.
        assertThat(policy.decide(WEDNESDAY, 0)).isEqualTo(EmptyRunPolicy.Decision.FAIL);
    }
}

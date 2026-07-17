package com.stockpulse.intraday;

import com.stockpulse.config.IntradayProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MarketClockTest {

    private final IntradayProperties props = new IntradayProperties();
    // Injected clock only drives today(); the session methods take explicit instants.
    private final MarketClock clock = new MarketClock(props,
            Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC));

    /** Instant at the given KST wall-clock time on 2026-07-16 (a Thursday). */
    private Instant at(String kstTime) {
        return LocalDate.of(2026, 7, 16).atTime(LocalTime.parse(kstTime))
                .atZone(MarketClock.KST).toInstant();
    }

    @Test
    void weekendIsNotTradingDay() {
        assertThat(clock.isTradingDay(LocalDate.of(2026, 7, 18))).isFalse(); // Saturday
        assertThat(clock.isTradingDay(LocalDate.of(2026, 7, 19))).isFalse(); // Sunday
        assertThat(clock.isTradingDay(LocalDate.of(2026, 7, 16))).isTrue();  // Thursday
    }

    @Test
    void withinSessionOnlyBetweenOpenAndClose() {
        assertThat(clock.isWithinSession(at("08:59"))).isFalse();
        assertThat(clock.isWithinSession(at("09:00"))).isTrue();
        assertThat(clock.isWithinSession(at("12:00"))).isTrue();
        assertThat(clock.isWithinSession(at("15:30"))).isTrue();
        assertThat(clock.isWithinSession(at("15:31"))).isFalse();
    }

    @Test
    void cleanupAndSessionEndBoundaries() {
        assertThat(clock.isCleanupTime(at("15:24"))).isFalse();
        assertThat(clock.isCleanupTime(at("15:25"))).isTrue();
        assertThat(clock.isPastSessionEnd(at("15:34"))).isFalse();
        assertThat(clock.isPastSessionEnd(at("15:35"))).isTrue();
    }
}

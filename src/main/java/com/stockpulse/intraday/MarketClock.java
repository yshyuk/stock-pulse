package com.stockpulse.intraday;

import com.stockpulse.config.IntradayProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Korean market session/clock helper (Asia/Seoul, fixed). Decides trading-day, in-session,
 * cleanup, and shutdown moments from the injected {@link Clock} and configured session times.
 *
 * <p>M1 trading-day check is weekend-only. Public-holiday handling (KIS holiday API) is deferred
 * (debt: launchd fires on holidays too, so the engine would boot and idle — noisy but not
 * dangerous). Time is never read via {@code now()} directly — always through the injected clock.
 */
@Component
public class MarketClock {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final IntradayProperties properties;
    private final Clock clock;

    public MarketClock(IntradayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(KST));
    }

    /** Weekday check (M1). TODO: incorporate the KRW market holiday calendar. */
    public boolean isTradingDay(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
    }

    /** True while the market is open on a trading day: [open, close] in KST. */
    public boolean isWithinSession(Instant instant) {
        if (!isTradingDay(dateAt(instant))) {
            return false;
        }
        LocalTime t = timeAt(instant);
        IntradayProperties.Session s = properties.getSession();
        return !t.isBefore(s.getOpen()) && !t.isAfter(s.getClose());
    }

    /** True at/after the unfilled-order cleanup time on a trading day. */
    public boolean isCleanupTime(Instant instant) {
        return isTradingDay(dateAt(instant)) && !timeAt(instant).isBefore(properties.getSession().getCleanup());
    }

    /** True at/after the session-end shutdown time. */
    public boolean isPastSessionEnd(Instant instant) {
        return !timeAt(instant).isBefore(properties.getSession().getEnd());
    }

    private LocalDate dateAt(Instant instant) {
        return instant.atZone(KST).toLocalDate();
    }

    private LocalTime timeAt(Instant instant) {
        return instant.atZone(KST).toLocalTime();
    }
}

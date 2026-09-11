package com.stockpulse.batch;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Decides what a run with no collected prices means.
 *
 * <p>Why this exists: the batch previously treated "collected nothing" exactly like a normal run —
 * exit 0, SUCCESS notification, a report saying there was no data. A misconfiguration (no price
 * source enabled) was therefore indistinguishable from a quiet day, and went unnoticed until
 * someone read the numbers closely. Silence is the one outcome this batch must never produce.
 *
 * <p>Against that, launchd fires every calendar day, so ~115 days a year legitimately have no
 * data. Failing on all of them would train the operator to ignore failure alerts, which is the
 * same bug wearing a different hat. The compromise: weekends are expected and skip quietly,
 * a weekday with nothing is a real failure.
 *
 * <p>The decision is made on the RUN DATE, not on today, so re-running a missed weekday still
 * demands data even when executed on a Sunday.
 *
 * <p>Known gap: public holidays (~11/year) still look like weekday failures. That is a deliberate
 * trade — a handful of self-explanatory false alarms beats a market-calendar dependency, and
 * matches {@link com.stockpulse.intraday.MarketClock}, which is also weekend-only today.
 */
@Component
public class EmptyRunPolicy {

    public enum Decision {
        /** Data present — run normally. */
        PROCEED,
        /** No data, and none was expected (weekend). Exit 0 without a report or notification. */
        SKIP,
        /** No data on a trading day. Something is broken — fail loudly. */
        FAIL
    }

    public Decision decide(LocalDate runDate, int priceMetricCount) {
        if (priceMetricCount > 0) {
            return Decision.PROCEED;
        }
        return isWeekend(runDate) ? Decision.SKIP : Decision.FAIL;
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}

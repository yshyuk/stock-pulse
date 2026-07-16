package com.stockpulse.intraday.signal;

import com.stockpulse.broker.OrderType;
import com.stockpulse.broker.Quote;
import com.stockpulse.plan.Entry;
import com.stockpulse.plan.PlanCandidate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, deterministic entry-signal evaluation: given a plan candidate and a live quote, decides
 * whether to enter and how many shares to buy. No side effects, no external calls — same inputs
 * always yield the same decision (unit-testable).
 */
@Component
public class SignalEvaluator {

    /**
     * Whether the entry condition is met now. For a LIMIT entry, buy when the market price is at
     * or below the plan's entry price. (MARKET entries always trigger.)
     */
    public boolean shouldEnter(PlanCandidate candidate, Quote quote) {
        Entry entry = candidate.getEntry();
        if (entry == null || entry.getPriceKrw() == null || quote == null || quote.price() == null) {
            return false;
        }
        boolean isMarket = OrderType.MARKET.name().equalsIgnoreCase(entry.getType());
        if (isMarket) {
            return true;
        }
        // LIMIT: price crossed down to/through the entry limit.
        return quote.price().compareTo(entry.getPriceKrw()) <= 0;
    }

    /**
     * Shares to buy for a candidate at {@code entryPriceKrw}, bounded by its budget.
     * Returns 0 when the budget cannot afford a single share.
     */
    public int quantityFor(BigDecimal budgetKrw, BigDecimal entryPriceKrw) {
        if (budgetKrw == null || entryPriceKrw == null || entryPriceKrw.signum() <= 0) {
            return 0;
        }
        return budgetKrw.divide(entryPriceKrw, 0, RoundingMode.DOWN).intValue();
    }
}

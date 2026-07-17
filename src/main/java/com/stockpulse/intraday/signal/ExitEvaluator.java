package com.stockpulse.intraday.signal;

import com.stockpulse.broker.Quote;
import com.stockpulse.intraday.domain.ExitReason;
import com.stockpulse.intraday.domain.PositionRecord;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Pure exit evaluation: given an open position and a live quote, decides whether to exit and why.
 * Uses the position's OWN target/stop (persisted at entry) so it works even after the originating
 * plan has expired. Target is checked before stop; both use inclusive comparisons.
 */
@Component
public class ExitEvaluator {

    public Optional<ExitReason> evaluate(PositionRecord position, Quote quote) {
        if (position == null || quote == null || quote.price() == null) {
            return Optional.empty();
        }
        if (position.getTargetPriceKrw() != null
                && quote.price().compareTo(position.getTargetPriceKrw()) >= 0) {
            return Optional.of(ExitReason.TARGET);
        }
        if (position.getStopLossPriceKrw() != null
                && quote.price().compareTo(position.getStopLossPriceKrw()) <= 0) {
            return Optional.of(ExitReason.STOP);
        }
        return Optional.empty();
    }
}

package com.stockpulse.plan;

import com.stockpulse.market.DailyMarketSnapshot;
import com.stockpulse.market.MarketIndicatorCodes;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.util.List;

/**
 * Market-level context for the plan. Fields are nullable — any indicator not collected on a
 * given day stays null rather than defaulting to a misleading value.
 */
@Value
@Builder
@Jacksonized
public class MarketContext {

    BigDecimal kospiChangeRate;
    BigDecimal kosdaqChangeRate;
    BigDecimal usdkrw;

    /** An all-null context (used when no market indicators are available). */
    public static MarketContext empty() {
        return MarketContext.builder().build();
    }

    /** Builds a context from the day's recorded market snapshots (null for absent indicators). */
    public static MarketContext from(List<DailyMarketSnapshot> snapshots) {
        MarketContextBuilder b = MarketContext.builder();
        for (DailyMarketSnapshot s : snapshots) {
            switch (s.getIndicatorCode()) {
                case MarketIndicatorCodes.KOSPI -> b.kospiChangeRate(s.getChangeRate());
                case MarketIndicatorCodes.KOSDAQ -> b.kosdaqChangeRate(s.getChangeRate());
                case MarketIndicatorCodes.USDKRW -> b.usdkrw(s.getValue());
                default -> { /* other indicators (e.g. supply/demand) are not in the plan context */ }
            }
        }
        return b.build();
    }
}

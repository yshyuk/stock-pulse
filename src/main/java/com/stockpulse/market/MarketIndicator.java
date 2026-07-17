package com.stockpulse.market;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * A single OBJECTIVE market-level reading produced by {@link MarketProcessor} from a market
 * data source (index level, FX rate, investor net-buy, ...).
 *
 * <p>Carries only the raw value + provenance; the day-over-day change rate is computed later
 * at snapshot time (from the prior day's stored value), mirroring how stock derived metrics
 * are handled — so results are reproducible and DB-only.
 */
@Value
@Builder
public class MarketIndicator {

    /** Canonical code, e.g. KOSPI / USDKRW (see {@link MarketIndicatorCodes}). */
    String code;

    /** Human-readable name, e.g. "코스피". */
    String name;

    /** The reading: index points, won per USD, or net-buy amount in KRW. */
    BigDecimal value;

    /** Originating source, e.g. naver-index / ecos / krx. */
    String source;
}

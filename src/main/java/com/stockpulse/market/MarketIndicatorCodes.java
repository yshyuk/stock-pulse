package com.stockpulse.market;

/**
 * Canonical codes for market-level indicators stored in {@code daily_market_snapshot}.
 * Sources emit these as the {@code indicatorCode} so the plan and report can look them up
 * regardless of which source produced them.
 */
public final class MarketIndicatorCodes {

    private MarketIndicatorCodes() {
    }

    public static final String KOSPI = "KOSPI";
    public static final String KOSDAQ = "KOSDAQ";
    public static final String USDKRW = "USDKRW";

    /** Net buy (KRW) by foreigners on KOSPI (positive = net buy). */
    public static final String FOREIGN_NET_KOSPI = "FOREIGN_NET_KOSPI";
    /** Net buy (KRW) by institutions on KOSPI. */
    public static final String INSTITUTION_NET_KOSPI = "INSTITUTION_NET_KOSPI";
}

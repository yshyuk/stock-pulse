package com.stockpulse.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Binding for {@code stockpulse.intraday.*} — engine-level risk limits and market-session
 * timing that are NOT part of the per-day plan (the plan carries per-symbol/total budgets;
 * these are engine-wide guards). See design ADR-007.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "stockpulse.intraday")
public class IntradayProperties {

    /** Poll interval in milliseconds (default 90s). */
    private long pollIntervalMs = 90_000;

    /** Max number of concurrently-open positions. */
    private int maxPositions = 5;

    /** Daily realized-loss limit (KRW, positive number). Breaching it engages the kill switch. */
    private BigDecimal dailyLossLimitKrw = new BigDecimal("100000");

    /** File whose existence engages the kill switch (halt new orders). Empty disables the file check. */
    private String killSwitchFile = "";

    @org.springframework.boot.context.properties.NestedConfigurationProperty
    private Session session = new Session();

    /** Korean market session boundaries (Asia/Seoul). */
    @Getter
    @Setter
    public static class Session {
        private LocalTime open = LocalTime.of(9, 0);
        private LocalTime close = LocalTime.of(15, 30);
        /** Unfilled-order cleanup time (before close). */
        private LocalTime cleanup = LocalTime.of(15, 25);
        /** Engine shutdown time (after close). */
        private LocalTime end = LocalTime.of(15, 35);
    }
}

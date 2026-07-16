package com.stockpulse.intraday.risk;

import com.stockpulse.config.IntradayProperties;
import com.stockpulse.intraday.KillSwitch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RiskGuardTest {

    private IntradayProperties props;
    private KillSwitch killSwitch;
    private RiskGuard guard;

    @BeforeEach
    void setUp() {
        props = new IntradayProperties();
        props.setMaxPositions(3);
        props.setDailyLossLimitKrw(new BigDecimal("100000"));
        killSwitch = new KillSwitch(props);
        guard = new RiskGuard(props, killSwitch);
    }

    private RiskCheckInput input(BigDecimal cost, BigDecimal perSymbol, BigDecimal total,
                                 BigDecimal invested, int openCount, BigDecimal dailyPnl, boolean already) {
        return new RiskCheckInput("005930", cost, perSymbol, total, invested, openCount, dailyPnl, already);
    }

    @Test
    void allowsWithinAllLimits() {
        RiskDecision d = guard.check(input(
                new BigDecimal("400000"), new BigDecimal("500000"), new BigDecimal("2000000"),
                new BigDecimal("500000"), 1, BigDecimal.ZERO, false));
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void deniesPerSymbolBudgetExceeded() {
        RiskDecision d = guard.check(input(
                new BigDecimal("600000"), new BigDecimal("500000"), new BigDecimal("2000000"),
                BigDecimal.ZERO, 0, BigDecimal.ZERO, false));
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("per-symbol");
    }

    @Test
    void deniesTotalBudgetExceeded() {
        RiskDecision d = guard.check(input(
                new BigDecimal("400000"), new BigDecimal("500000"), new BigDecimal("2000000"),
                new BigDecimal("1800000"), 2, BigDecimal.ZERO, false)); // 1.8M + 0.4M > 2M, under maxPositions
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("total budget");
    }

    @Test
    void deniesMaxPositions() {
        RiskDecision d = guard.check(input(
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("2000000"),
                new BigDecimal("300000"), 3, BigDecimal.ZERO, false)); // openCount == maxPositions
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("max positions");
    }

    @Test
    void deniesAndEngagesKillOnDailyLossLimit() {
        RiskDecision d = guard.check(input(
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("2000000"),
                BigDecimal.ZERO, 0, new BigDecimal("-100000"), false)); // realized loss == limit
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("daily loss");
        assertThat(killSwitch.isEngaged()).isTrue(); // breach trips the switch
    }

    @Test
    void deniesWhenKillSwitchAlreadyEngaged() {
        killSwitch.engage("manual");
        RiskDecision d = guard.check(input(
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("2000000"),
                BigDecimal.ZERO, 0, BigDecimal.ZERO, false));
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("kill switch");
    }

    @Test
    void deniesSymbolAlreadyOrderedToday() {
        RiskDecision d = guard.check(input(
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("2000000"),
                BigDecimal.ZERO, 0, BigDecimal.ZERO, true));
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("already ordered");
    }
}

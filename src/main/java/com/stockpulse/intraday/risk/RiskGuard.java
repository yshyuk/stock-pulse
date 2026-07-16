package com.stockpulse.intraday.risk;

import com.stockpulse.config.IntradayProperties;
import com.stockpulse.intraday.KillSwitch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The single pre-trade gate every BUY order must pass (design ADR-007). Deterministic: given the
 * same input it returns the same decision. Any violation denies the order — and a breached
 * daily-loss limit also engages the kill switch so the whole session stops opening new risk.
 *
 * <p>Ordering matters: the kill switch and daily-loss checks come first so a stopped session
 * denies fast regardless of the per-order economics.
 */
@Slf4j
@Component
public class RiskGuard {

    private final IntradayProperties properties;
    private final KillSwitch killSwitch;

    public RiskGuard(IntradayProperties properties, KillSwitch killSwitch) {
        this.properties = properties;
        this.killSwitch = killSwitch;
    }

    public RiskDecision check(RiskCheckInput in) {
        if (killSwitch.isEngaged()) {
            return RiskDecision.deny("kill switch engaged");
        }

        // Daily loss limit — realized loss beyond the limit stops all new orders (and trips the switch).
        BigDecimal limit = properties.getDailyLossLimitKrw();
        if (limit != null && in.dailyRealizedPnlKrw() != null
                && in.dailyRealizedPnlKrw().compareTo(limit.negate()) <= 0) {
            killSwitch.engage("daily loss limit breached: realized=" + in.dailyRealizedPnlKrw());
            return RiskDecision.deny("daily loss limit breached");
        }

        if (in.alreadyOrderedSymbolToday()) {
            return RiskDecision.deny("symbol already ordered today");
        }

        if (in.openPositionsCount() >= properties.getMaxPositions()) {
            return RiskDecision.deny("max positions reached (" + properties.getMaxPositions() + ")");
        }

        if (exceeds(in.orderCostKrw(), in.perSymbolBudgetKrw())) {
            return RiskDecision.deny("per-symbol budget exceeded");
        }

        BigDecimal projectedTotal = nz(in.currentTotalInvestedKrw()).add(nz(in.orderCostKrw()));
        if (in.totalBudgetKrw() != null && projectedTotal.compareTo(in.totalBudgetKrw()) > 0) {
            return RiskDecision.deny("total budget exceeded");
        }

        return RiskDecision.allow();
    }

    private boolean exceeds(BigDecimal cost, BigDecimal cap) {
        return cap != null && nz(cost).compareTo(cap) > 0;
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

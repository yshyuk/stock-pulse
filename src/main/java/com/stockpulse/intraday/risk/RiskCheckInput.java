package com.stockpulse.intraday.risk;

import java.math.BigDecimal;

/**
 * Runtime state a {@link RiskGuard} needs to vet one BUY order. Config-derived limits
 * (max positions, daily-loss limit) are read by the guard itself; this record carries only the
 * per-order and current-portfolio state the caller must supply.
 *
 * @param symbol                    target symbol
 * @param orderCostKrw              cost of this order (qty × price)
 * @param perSymbolBudgetKrw        plan's per-symbol budget cap
 * @param totalBudgetKrw            plan's total budget cap
 * @param currentTotalInvestedKrw   sum of open positions' cost
 * @param openPositionsCount        number of currently-open positions
 * @param dailyRealizedPnlKrw       today's realized P&L (negative = loss)
 * @param alreadyOrderedSymbolToday whether this symbol already has a BUY today
 */
public record RiskCheckInput(
        String symbol,
        BigDecimal orderCostKrw,
        BigDecimal perSymbolBudgetKrw,
        BigDecimal totalBudgetKrw,
        BigDecimal currentTotalInvestedKrw,
        int openPositionsCount,
        BigDecimal dailyRealizedPnlKrw,
        boolean alreadyOrderedSymbolToday) {
}

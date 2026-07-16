package com.stockpulse.market;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Renders a "시장 컨텍스트" Markdown section from the day's recorded market snapshots, appended
 * to the report so the operator sees the market backdrop alongside the per-stock data.
 * Objective only — values and day-over-day change, no interpretation.
 */
@Component
public class MarketSummaryFormatter {

    public String toMarkdown(List<DailyMarketSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n## 시장 컨텍스트\n\n");
        sb.append("| 지표 | 값 | 전일 대비 | 출처 |\n");
        sb.append("|------|---:|---------:|------|\n");
        for (DailyMarketSnapshot s : snapshots) {
            sb.append("| ").append(nz(s.getName() == null ? s.getIndicatorCode() : s.getName()))
                    .append(" | ").append(num(s.getValue()))
                    .append(" | ").append(pct(s.getChangeRate()))
                    .append(" | ").append(nz(s.getSource()))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private String nz(Object v) {
        return v == null ? "-" : v.toString();
    }

    private String num(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }

    private String pct(BigDecimal v) {
        if (v == null) {
            return "-";
        }
        return (v.signum() > 0 ? "+" : "") + v.toPlainString() + "%";
    }
}

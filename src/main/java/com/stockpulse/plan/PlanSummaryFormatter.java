package com.stockpulse.plan;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Renders a human-readable "오늘의 플랜" Markdown section from a {@link TradingPlan}, appended
 * to the report so the operator (persona P1) sees the same plan the machine consumes.
 *
 * <p>Objective only: it lists which rules fired and the computed entry/exit — it states no
 * opinion on whether to act.
 */
@Component
public class PlanSummaryFormatter {

    public String toMarkdown(TradingPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n## 오늘의 플랜 (규칙 기반 · plan-only)\n\n");
        sb.append("> 이 섹션은 설정된 규칙에 **기계적으로 매칭**된 시그널 후보입니다. ")
                .append("매수/매도 판단이 아니며, 어떤 실주문도 발생시키지 않습니다.\n\n");

        List<PlanCandidate> candidates = plan.getCandidates();
        if (candidates == null || candidates.isEmpty()) {
            sb.append("_규칙에 매칭된 후보가 없습니다._\n");
        } else {
            sb.append("| 종목 | 코드 | 매칭 규칙 | 진입가 | 목표가 | 손절가 | 종목당 한도 |\n");
            sb.append("|------|------|----------|-------:|-------:|-------:|-----------:|\n");
            for (PlanCandidate c : candidates) {
                sb.append("| ").append(nz(c.getName()))
                        .append(" | ").append(nz(c.getSymbol()))
                        .append(" | ").append(String.join(", ", c.getMatchedRules()))
                        .append(" | ").append(num(c.getEntry().getPriceKrw()))
                        .append(" | ").append(num(c.getExit().getTargetPriceKrw()))
                        .append(" | ").append(num(c.getExit().getStopLossPriceKrw()))
                        .append(" | ").append(num(c.getSizing().getMaxBudgetKrw()))
                        .append(" |\n");
            }
        }

        List<String> warnings = plan.getWarnings();
        if (warnings != null && !warnings.isEmpty()) {
            sb.append("\n**데이터 경고**\n");
            for (String w : warnings) {
                sb.append("- ").append(w).append("\n");
            }
        }
        return sb.toString();
    }

    private String nz(Object v) {
        return v == null ? "-" : v.toString();
    }

    private String num(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }
}

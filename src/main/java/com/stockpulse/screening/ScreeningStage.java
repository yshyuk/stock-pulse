package com.stockpulse.screening;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.StockMetric;
import com.stockpulse.plan.rule.RuleEvaluator;
import com.stockpulse.timeseries.DailyStockSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ScreeningStage {

    private final StockPulseProperties properties;
    private final RuleEvaluator ruleEvaluator;

    public ScreeningStage(StockPulseProperties properties, RuleEvaluator ruleEvaluator) {
        this.properties = properties;
        this.ruleEvaluator = ruleEvaluator;
    }

    public ScreeningResult select(List<DailyStockSnapshot> snapshots, List<StockMetric> metrics) {
        StockPulseProperties.Screening cfg = properties.getScreening();
        if (!cfg.isEnabled()) {
            return ScreeningResult.passthrough(metrics);
        }

        Map<String, DailyStockSnapshot> bySymbol = new HashMap<>();
        for (DailyStockSnapshot s : snapshots) {
            bySymbol.put(s.getSymbol(), s);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (StockMetric m : metrics) {
            DailyStockSnapshot snapshot = bySymbol.get(m.getSymbol());
            if (snapshot == null || !isLiquidEnough(snapshot, cfg.getMinTradingValue())) {
                continue;
            }
            List<String> matched = ruleEvaluator.matchedRuleIds(snapshot, cfg.getRules());
            if (!matched.isEmpty()) {
                candidates.add(new Candidate(m, snapshot, matched));
            }
        }

        candidates.sort(BY_ANOMALY_STRENGTH);

        // The cap is what actually bounds the bill; matchedCount below still reports the
        // pre-cap total so the report can say how much it is not showing.
        List<Candidate> kept = candidates.size() > cfg.getMaxCandidates()
                ? candidates.subList(0, cfg.getMaxCandidates())
                : candidates;

        List<StockMetric> passed = new ArrayList<>();
        Map<String, List<String>> hits = new LinkedHashMap<>();
        for (Candidate c : kept) {
            passed.add(c.metric());
            hits.put(c.metric().getSymbol(), c.matchedRules());
        }
        log.info("[screening] {} evaluated -> {} matched -> {} kept (cap {})",
                metrics.size(), candidates.size(), passed.size(), cfg.getMaxCandidates());
        return new ScreeningResult(passed, metrics.size(), candidates.size(), hits);
    }

    /**
     * A section stating what the report is NOT showing, plus why each shown stock is here.
     *
     * <p>Without this the reader cannot tell a quiet market from a broken filter — the report
     * would look identical either way. Empty when screening is off (nothing was filtered).
     */
    public String summaryMarkdown(ScreeningResult result) {
        if (!properties.getScreening().isEnabled() || result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n## 스크리닝 요약\n\n");
        sb.append("> 전체 ").append(result.totalEvaluated()).append("종목 중 ")
                .append("조건 매칭 ").append(result.matchedCount()).append("종목, ")
                .append("리포트 표시 ").append(result.metrics().size()).append("종목")
                .append(" (상한 ").append(properties.getScreening().getMaxCandidates()).append(").\n>\n");
        sb.append("> 이 목록은 **이상징후 탐지 결과**이며 매수/매도 후보가 아닙니다. ")
                .append("정렬은 '얼마나 특이한가'(매칭 룰 수 → 거래량비) 기준이며 ")
                .append("상승/하락 선호가 없습니다.\n\n");

        if (result.ruleHits().isEmpty()) {
            sb.append("_조건에 매칭된 종목이 없습니다._\n");
            return sb.toString();
        }
        sb.append("| 종목 | 매칭된 조건 |\n");
        sb.append("|------|------------|\n");
        for (Map.Entry<String, List<String>> e : result.ruleHits().entrySet()) {
            sb.append("| ").append(e.getKey())
                    .append(" | ").append(String.join(", ", e.getValue()))
                    .append(" |\n");
        }
        return sb.toString();
    }

    /** A stock that cleared the liquidity floor and matched at least one rule. */
    private record Candidate(StockMetric metric, DailyStockSnapshot snapshot, List<String> matchedRules) {

        /** Null (not enough history) sorts last rather than being treated as zero. */
        BigDecimal volumeMa20Ratio() {
            return snapshot.getDerived() == null ? null : snapshot.getDerived().getVolumeMa20Ratio();
        }

        /**
         * How far the price moved, direction discarded. A -20% day is exactly as noteworthy as
         * a +20% one, so taking the absolute value keeps the ranking free of any up/down bias.
         */
        BigDecimal priceMoveMagnitude() {
            if (snapshot.getDerived() == null || snapshot.getDerived().getChangeRate1d() == null) {
                return null;
            }
            return snapshot.getDerived().getChangeRate1d().abs();
        }
    }

    /**
     * Ranking, most anomalous first: more matched rules, then a bigger volume anomaly, then
     * symbol so a re-run of the same day produces the identical list.
     *
     * <p>The price-move tiebreak matters most on a cold start: until 20 days of history exist
     * there is no volume ratio at all, and without it the "top N" would be the N lowest stock
     * codes rather than the N most unusual stocks.
     *
     * <p>Note what is NOT here: no preference for rising over falling stocks. This orders by
     * "how unusual", never by "how promising" — the judgement stays in the second-stage analysis.
     */
    private static final Comparator<Candidate> BY_ANOMALY_STRENGTH =
            Comparator.comparingInt((Candidate c) -> c.matchedRules().size()).reversed()
                    .thenComparing(Candidate::volumeMa20Ratio,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Candidate::priceMoveMagnitude,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(c -> c.metric().getSymbol());

    /**
     * Trading value (price x volume) floor, applied to every stock BEFORE rule matching.
     *
     * <p>Deliberately a precondition rather than a rule condition: it must hold for every rule,
     * and duplicating it across rules would make it easy to forget on the next rule added.
     * A stock whose price or volume is missing cannot be shown to clear the floor, so it does
     * not — same null semantics as {@code RuleEvaluator}.
     */
    private boolean isLiquidEnough(DailyStockSnapshot snapshot, BigDecimal floor) {
        if (floor == null) {
            return true;
        }
        if (snapshot.getPrice() == null || snapshot.getVolume() == null) {
            return false;
        }
        BigDecimal tradingValue = snapshot.getPrice().multiply(BigDecimal.valueOf(snapshot.getVolume()));
        return tradingValue.compareTo(floor) >= 0;
    }
}

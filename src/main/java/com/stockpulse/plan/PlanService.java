package com.stockpulse.plan;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.plan.rule.RuleEvaluator;
import com.stockpulse.timeseries.DailyStockSnapshot;
import com.stockpulse.timeseries.DailyStockSnapshotRepository;
import com.stockpulse.timeseries.DerivedMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Plan stage: turns today's snapshots into a deterministic {@link TradingPlan}.
 *
 * <p>A stock becomes a candidate when it matches at least one configured rule (see
 * {@link RuleEvaluator}); entry/exit/sizing are computed from the current price using the
 * configured offsets. Everything here is deterministic — no AI, no judgement — so the same
 * snapshots always yield the same plan. Data gaps are surfaced as {@code warnings} (F-04),
 * never as silent zeros.
 */
@Slf4j
@Service
public class PlanService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final RuleEvaluator ruleEvaluator;
    private final DailyStockSnapshotRepository snapshotRepository;
    private final StockPulseProperties properties;
    private final Clock clock;

    public PlanService(RuleEvaluator ruleEvaluator,
                       DailyStockSnapshotRepository snapshotRepository,
                       StockPulseProperties properties,
                       Clock clock) {
        this.ruleEvaluator = ruleEvaluator;
        this.snapshotRepository = snapshotRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Builds the plan for {@code runDate} from the just-recorded {@code snapshots}. */
    public TradingPlan generate(LocalDate runDate, List<DailyStockSnapshot> snapshots,
                                MarketContext marketContext) {
        StockPulseProperties.Plan cfg = properties.getPlan();
        Instant now = Instant.now(clock);

        List<PlanCandidate> candidates = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (DailyStockSnapshot s : snapshots) {
            collectGapWarning(runDate, s, cfg.getMaxDataGapDays(), warnings);

            List<String> matched = ruleEvaluator.matchedRuleIds(s, cfg.getRules());
            if (!matched.isEmpty()) {
                candidates.add(toCandidate(s, matched, cfg));
            }
        }
        candidates = rankByPriority(candidates);

        TradingPlan plan = TradingPlan.builder()
                .schemaVersion(TradingPlan.SCHEMA_VERSION)
                .planDate(runDate)
                .generatedAt(now)
                .mode(cfg.getMode())
                .constraints(PlanConstraints.builder()
                        .maxBudgetPerSymbolKrw(cfg.getMaxBudgetPerSymbolKrw())
                        .maxTotalBudgetKrw(cfg.getMaxTotalBudgetKrw())
                        .validUntil(runDate.atTime(MARKET_CLOSE).atZone(KST).toInstant())
                        .build())
                .marketContext(marketContext == null ? MarketContext.empty() : marketContext)
                .candidates(candidates)
                .advisory(null)
                .warnings(warnings)
                .build();

        log.info("[plan] generated plan for {} — {} candidate(s), {} warning(s)",
                runDate, candidates.size(), warnings.size());
        return plan;
    }

    /**
     * Orders candidates strongest-first and stamps a 1-based {@code priority} on each.
     *
     * <p>Strength is "how many independent rules agreed", with the symbol as tiebreaker so the
     * ordering is total and reproducible. A consumer that only acts on the first N candidates
     * therefore drops the weakest signals, and drops the same ones on a re-run.
     */
    private List<PlanCandidate> rankByPriority(List<PlanCandidate> candidates) {
        List<PlanCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
                .comparingInt((PlanCandidate c) -> c.getMatchedRules().size()).reversed()
                .thenComparing(PlanCandidate::getSymbol));

        List<PlanCandidate> ranked = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ranked.add(sorted.get(i).toBuilder().priority(i + 1).build());
        }
        return ranked;
    }

    private PlanCandidate toCandidate(DailyStockSnapshot s, List<String> matched,
                                      StockPulseProperties.Plan cfg) {
        BigDecimal price = s.getPrice();
        BigDecimal entryPrice = applyPct(price, cfg.getEntry().getOffsetPct());
        BigDecimal targetPrice = applyPct(price, cfg.getExit().getTargetPct());
        BigDecimal stopPrice = applyPct(price, cfg.getExit().getStopLossPct());

        DerivedMetrics d = s.getDerived();
        Evidence evidence = Evidence.builder()
                .price(price)
                .changeRate1d(d == null ? null : d.getChangeRate1d())
                .changeRate5d(d == null ? null : d.getChangeRate5d())
                .changeRate20d(d == null ? null : d.getChangeRate20d())
                .volumeMa20Ratio(d == null ? null : d.getVolumeMa20Ratio())
                .streakDays(d == null ? null : d.getStreakDays())
                .volatility20d(d == null ? null : d.getVolatility20d())
                .rangePosition(d == null ? null : d.getRangePosition())
                .build();

        return PlanCandidate.builder()
                .symbol(s.getSymbol())
                .name(s.getName())
                .matchedRules(matched)
                .entry(Entry.builder().type(cfg.getEntry().getType()).priceKrw(entryPrice).build())
                .exit(Exit.builder().targetPriceKrw(targetPrice).stopLossPriceKrw(stopPrice).build())
                .sizing(Sizing.builder().maxBudgetKrw(cfg.getMaxBudgetPerSymbolKrw()).build())
                .evidence(evidence)
                .build();
    }

    /** price * (1 + pct/100), rounded to whole KRW. */
    private BigDecimal applyPct(BigDecimal price, BigDecimal pct) {
        BigDecimal factor = BigDecimal.ONE.add(pct.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
        return price.multiply(factor).setScale(0, RoundingMode.HALF_UP);
    }

    /** Warns if the most recent prior snapshot for this symbol is older than the allowed gap. */
    private void collectGapWarning(LocalDate runDate, DailyStockSnapshot s, int maxGapDays,
                                   List<String> warnings) {
        List<DailyStockSnapshot> prior = snapshotRepository
                .findBySymbolAndTradeDateLessThanOrderByTradeDateDesc(
                        s.getSymbol(), runDate, PageRequest.of(0, 1));
        if (prior.isEmpty()) {
            return; // first observation for this symbol — nothing to compare against
        }
        long gap = ChronoUnit.DAYS.between(prior.get(0).getTradeDate(), runDate);
        if (gap > maxGapDays) {
            warnings.add("stale-data:" + s.getSymbol() + ":last=" + prior.get(0).getTradeDate() + ":gap=" + gap + "d");
        }
    }
}

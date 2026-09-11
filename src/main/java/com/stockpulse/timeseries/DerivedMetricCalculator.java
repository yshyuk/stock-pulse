package com.stockpulse.timeseries;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure computation of {@link DerivedMetrics} from a stock's accumulated history.
 *
 * <p>Deliberately has NO database access: it receives today's price/volume plus the list of
 * prior snapshots (already loaded, most-recent-first) and returns the derived indicators.
 * This keeps it a pure function — fully unit-testable and deterministic (a plan-reproducibility
 * requirement). Any indicator lacking enough history is returned as {@code null}, never 0.
 */
@Component
public class DerivedMetricCalculator {

    private static final int SCALE = 4;
    private static final int VOL_WINDOW = 20;   // trading days for volume MA and volatility

    /**
     * @param price          today's price (must be non-null)
     * @param previousPrice  the previous close as reported BY THE SOURCE (nullable). Preferred
     *                       over our own history for the 1-day change: it is always the real
     *                       prior session, whereas {@code priors.get(0)} is only the previous
     *                       session if no run was ever missed. It also lets the 1-day rules work
     *                       on a cold start, before any history exists.
     * @param volume  today's volume (nullable)
     * @param priors  prior snapshots for the same symbol, strictly before today,
     *                ordered most-recent-first (index 0 = previous trading day)
     */
    public DerivedMetrics compute(BigDecimal price, BigDecimal previousPrice, Long volume,
                                  List<DailyStockSnapshot> priors) {
        if (price == null) {
            return DerivedMetrics.empty();
        }
        List<BigDecimal> priorPrices = new ArrayList<>();
        for (DailyStockSnapshot s : priors) {
            priorPrices.add(s.getPrice());
        }
        return DerivedMetrics.builder()
                .changeRate1d(changeRate1d(price, previousPrice, priorPrices))
                .changeRate5d(changeOver(price, priorPrices, 5))
                .changeRate20d(changeOver(price, priorPrices, 20))
                .volumeMa20Ratio(volumeMaRatio(volume, priors))
                .streakDays(streak(price, priorPrices))
                .volatility20d(volatility(price, priorPrices))
                .rangePosition(rangePosition(price, priorPrices))
                .build();
    }

    /**
     * Day-over-day change, from the source's own previous close when it gave one, otherwise from
     * the most recent prior snapshot. Only the 1-day metric gets this treatment — the 5/20-day
     * ones have no source-reported equivalent.
     */
    private BigDecimal changeRate1d(BigDecimal price, BigDecimal previousPrice,
                                    List<BigDecimal> priorPrices) {
        if (previousPrice != null && previousPrice.signum() != 0) {
            return percent(price, previousPrice);
        }
        return changeOver(price, priorPrices, 1);
    }

    /** (price - price N trading days ago) / that price * 100. Null when history < N. */
    private BigDecimal changeOver(BigDecimal price, List<BigDecimal> priorPrices, int daysBack) {
        if (priorPrices.size() < daysBack) {
            return null;
        }
        return percent(price, priorPrices.get(daysBack - 1));
    }

    /** (price - base) / base * 100, or null when base is missing or zero. */
    private BigDecimal percent(BigDecimal price, BigDecimal base) {
        if (base == null || base.signum() == 0) {
            return null;
        }
        return price.subtract(base)
                .divide(base, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** today's volume / trailing 20-day average volume. Null when insufficient/invalid. */
    private BigDecimal volumeMaRatio(Long volume, List<DailyStockSnapshot> priors) {
        if (volume == null || priors.size() < VOL_WINDOW) {
            return null;
        }
        long sum = 0;
        for (int i = 0; i < VOL_WINDOW; i++) {
            Long v = priors.get(i).getVolume();
            if (v == null) {
                return null;
            }
            sum += v;
        }
        if (sum == 0) {
            return null;
        }
        BigDecimal avg = BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(VOL_WINDOW), 8, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(volume).divide(avg, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Consecutive same-direction day-over-day moves ending today.
     * Positive = up streak, negative = down streak. Null when no prior day exists;
     * 0 when today is unchanged from the previous day.
     */
    private Integer streak(BigDecimal price, List<BigDecimal> priorPrices) {
        if (priorPrices.isEmpty()) {
            return null;
        }
        // Descending price series: [today, prior0, prior1, ...]
        List<BigDecimal> series = new ArrayList<>();
        series.add(price);
        series.addAll(priorPrices);

        int firstDir = Integer.compare(series.get(0).compareTo(series.get(1)), 0);
        if (firstDir == 0) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i + 1 < series.size(); i++) {
            BigDecimal a = series.get(i);
            BigDecimal b = series.get(i + 1);
            if (a == null || b == null) {
                break;
            }
            int dir = Integer.compare(a.compareTo(b), 0);
            if (dir != firstDir) {
                break;
            }
            count++;
        }
        return firstDir > 0 ? count : -count;
    }

    /** Sample standard deviation of the last 20 daily returns, in percent. Null when insufficient. */
    private BigDecimal volatility(BigDecimal price, List<BigDecimal> priorPrices) {
        if (priorPrices.size() < VOL_WINDOW) {
            return null;
        }
        // Need 21 price points (today + 20 priors) to form 20 daily returns.
        List<BigDecimal> series = new ArrayList<>();
        series.add(price);
        for (int i = 0; i < VOL_WINDOW; i++) {
            BigDecimal p = priorPrices.get(i);
            if (p == null || p.signum() == 0) {
                return null;
            }
            series.add(p);
        }
        double[] returns = new double[VOL_WINDOW];
        for (int i = 0; i < VOL_WINDOW; i++) {
            double cur = series.get(i).doubleValue();
            double prev = series.get(i + 1).doubleValue();
            returns[i] = (cur - prev) / prev * 100.0;
        }
        double mean = 0;
        for (double r : returns) {
            mean += r;
        }
        mean /= VOL_WINDOW;
        double sq = 0;
        for (double r : returns) {
            sq += (r - mean) * (r - mean);
        }
        double std = Math.sqrt(sq / (VOL_WINDOW - 1));
        return BigDecimal.valueOf(std).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Position within the accumulated high/low range including today: 0 at the low, 1 at the high.
     * Null when there is no prior history or the range is flat.
     */
    private BigDecimal rangePosition(BigDecimal price, List<BigDecimal> priorPrices) {
        if (priorPrices.isEmpty()) {
            return null;
        }
        BigDecimal high = price;
        BigDecimal low = price;
        for (BigDecimal p : priorPrices) {
            if (p == null) {
                continue;
            }
            if (p.compareTo(high) > 0) {
                high = p;
            }
            if (p.compareTo(low) < 0) {
                low = p;
            }
        }
        BigDecimal range = high.subtract(low);
        if (range.signum() == 0) {
            return null;
        }
        return price.subtract(low).divide(range, SCALE, RoundingMode.HALF_UP);
    }
}

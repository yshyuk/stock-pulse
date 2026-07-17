package com.stockpulse.timeseries;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DerivedMetricCalculatorTest {

    private final DerivedMetricCalculator calc = new DerivedMetricCalculator();

    /** Prior snapshots, most-recent-first, with the given descending-by-date prices. */
    private List<DailyStockSnapshot> priors(double... pricesMostRecentFirst) {
        List<DailyStockSnapshot> list = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < pricesMostRecentFirst.length; i++) {
            list.add(DailyStockSnapshot.builder()
                    .symbol("005930")
                    .tradeDate(d.minusDays(i + 1))
                    .price(BigDecimal.valueOf(pricesMostRecentFirst[i]))
                    .volume(100L)
                    .build());
        }
        return list;
    }

    @Test
    void noPriorHistory_allDerivedNull() {
        DerivedMetrics m = calc.compute(BigDecimal.valueOf(100), 100L, List.of());

        assertThat(m.getChangeRate1d()).isNull();
        assertThat(m.getStreakDays()).isNull();
        assertThat(m.getRangePosition()).isNull();
        assertThat(m.getVolatility20d()).isNull();
        assertThat(m.getVolumeMa20Ratio()).isNull();
    }

    @Test
    void changeRate1d_usesPreviousDaySnapshot() {
        DerivedMetrics m = calc.compute(BigDecimal.valueOf(110), 100L, priors(100));
        assertThat(m.getChangeRate1d()).isEqualByComparingTo("10.0000");
    }

    @Test
    void changeRate5d_nullUntilFivePriorsExist() {
        assertThat(calc.compute(BigDecimal.valueOf(110), 100L, priors(108, 106, 104, 102)).getChangeRate5d())
                .isNull();
        // 5 priors: 5-days-ago price is priors[4] = 100 -> (110-100)/100 = 10%
        assertThat(calc.compute(BigDecimal.valueOf(110), 100L, priors(108, 106, 104, 102, 100)).getChangeRate5d())
                .isEqualByComparingTo("10.0000");
    }

    @Test
    void streak_countsConsecutiveUpMovesIncludingToday() {
        // prices desc: today=104, then 103,102,101,100 -> 4 consecutive up moves
        DerivedMetrics m = calc.compute(BigDecimal.valueOf(104), 100L, priors(103, 102, 101, 100));
        assertThat(m.getStreakDays()).isEqualTo(4);
    }

    @Test
    void streak_negativeForDownMovesAndStopsAtReversal() {
        // today=100, 101 (down move), 100 (up move -> reversal) => streak of -1
        DerivedMetrics m = calc.compute(BigDecimal.valueOf(100), 100L, priors(101, 100));
        assertThat(m.getStreakDays()).isEqualTo(-1);
    }

    @Test
    void streak_zeroWhenUnchangedFromPreviousDay() {
        DerivedMetrics m = calc.compute(BigDecimal.valueOf(100), 100L, priors(100, 99));
        assertThat(m.getStreakDays()).isZero();
    }

    @Test
    void rangePosition_atHighIsOne_atLowIsZero() {
        // priors span 90..110, today=110 (the high) -> position 1
        assertThat(calc.compute(BigDecimal.valueOf(110), 100L, priors(90, 100)).getRangePosition())
                .isEqualByComparingTo("1.0000");
        // today=90 (the low) -> position 0
        assertThat(calc.compute(BigDecimal.valueOf(90), 100L, priors(110, 100)).getRangePosition())
                .isEqualByComparingTo("0.0000");
    }

    @Test
    void volumeMa20Ratio_nullBelow20Priors_andComputedAt20() {
        assertThat(calc.compute(BigDecimal.valueOf(100), 200L, priors(100)).getVolumeMa20Ratio()).isNull();

        double[] twenty = new double[20];
        for (int i = 0; i < 20; i++) {
            twenty[i] = 100; // each prior volume defaults to 100 in the helper
        }
        // today volume 200, avg prior volume 100 -> ratio 2.0
        DerivedMetrics m = calc.compute(BigDecimal.valueOf(100), 200L, priors(twenty));
        assertThat(m.getVolumeMa20Ratio()).isEqualByComparingTo("2.0000");
    }

    @Test
    void volatility_nonNullWith20Priors() {
        double[] p = new double[20];
        double base = 100;
        for (int i = 0; i < 20; i++) {
            p[i] = base + (i % 2 == 0 ? 1 : -1); // oscillating prices -> non-zero volatility
        }
        DerivedMetrics m = calc.compute(BigDecimal.valueOf(101), 100L, priors(p));
        assertThat(m.getVolatility20d()).isNotNull();
        assertThat(m.getVolatility20d().signum()).isPositive();
    }
}

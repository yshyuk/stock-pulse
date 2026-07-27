package com.stockpulse.plan;

import com.stockpulse.market.DailyMarketSnapshot;
import com.stockpulse.market.MarketIndicatorCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketContextTest {

    private DailyMarketSnapshot snap(String code, String value, String changeRate) {
        return DailyMarketSnapshot.builder()
                .indicatorCode(code).name(code).tradeDate(LocalDate.of(2026, 7, 16))
                .value(new BigDecimal(value))
                .changeRate(changeRate == null ? null : new BigDecimal(changeRate))
                .source("test").build();
    }

    @Test
    void mapsKnownIndicatorsIntoContext() {
        MarketContext ctx = MarketContext.from(List.of(
                snap(MarketIndicatorCodes.KOSPI, "2650", "1.2"),
                snap(MarketIndicatorCodes.KOSDAQ, "850", "-0.5"),
                snap(MarketIndicatorCodes.USDKRW, "1350.5", "0.3")));

        assertThat(ctx.getKospiChangeRate()).isEqualByComparingTo("1.2");
        assertThat(ctx.getKosdaqChangeRate()).isEqualByComparingTo("-0.5");
        assertThat(ctx.getUsdkrw()).isEqualByComparingTo("1350.5"); // FX uses the value, not the change
    }

    @Test
    void unknownIndicatorsAreIgnored_absentStayNull() {
        MarketContext ctx = MarketContext.from(List.of(
                snap(MarketIndicatorCodes.FOREIGN_NET_KOSPI, "12345000", "0.0")));

        assertThat(ctx.getKospiChangeRate()).isNull();
        assertThat(ctx.getKosdaqChangeRate()).isNull();
        assertThat(ctx.getUsdkrw()).isNull();
    }

    @Test
    void emptyListYieldsAllNullContext() {
        MarketContext ctx = MarketContext.from(List.of());
        assertThat(ctx.getKospiChangeRate()).isNull();
        assertThat(ctx.getUsdkrw()).isNull();
    }
}

package com.stockpulse.intraday.review;

import com.stockpulse.intraday.domain.PositionRecord;
import com.stockpulse.intraday.domain.PositionRecordRepository;
import com.stockpulse.intraday.domain.PositionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(IntradayReviewReporter.class)
class IntradayReviewReporterTest {

    @Autowired
    private PositionRecordRepository positionRepository;
    @Autowired
    private IntradayReviewReporter reporter;

    // Batch runs Thursday 2026-07-16; previous trading day = Wednesday 2026-07-15.
    private final LocalDate runDate = LocalDate.of(2026, 7, 16);
    private final Instant closedWed = Instant.parse("2026-07-15T06:00:00Z"); // 15:00 KST on the 15th

    private PositionRecord closed(String symbol, int qty, String avg, String pnl, Instant closedAt) {
        return positionRepository.save(PositionRecord.builder()
                .symbol(symbol).quantity(qty).avgPriceKrw(new BigDecimal(avg)).planDate(LocalDate.of(2026, 7, 15))
                .targetPriceKrw(new BigDecimal("64000")).stopLossPriceKrw(new BigDecimal("59000"))
                .openedAt(closedAt).status(PositionStatus.CLOSED).realizedPnlKrw(new BigDecimal(pnl))
                .closedAt(closedAt).build());
    }

    private PositionRecord open(String symbol, int qty, String avg) {
        return positionRepository.save(PositionRecord.builder()
                .symbol(symbol).quantity(qty).avgPriceKrw(new BigDecimal(avg)).planDate(runDate)
                .targetPriceKrw(new BigDecimal("70000")).stopLossPriceKrw(new BigDecimal("65000"))
                .openedAt(Instant.parse("2026-07-16T01:00:00Z")).status(PositionStatus.OPEN).build());
    }

    @Test
    void noActivityYieldsEmptySection() {
        assertThat(reporter.morningSection(runDate)).isEmpty();
    }

    @Test
    void summarizesPreviousDayRealizedPnl() {
        closed("005930", 8, "61000", "24000", closedWed);
        closed("000660", 5, "120000", "-15000", closedWed);

        String section = reporter.morningSection(runDate);

        assertThat(section).contains("전일 매매 요약 (2026-07-15)");
        assertThat(section).contains("2건 청산");
        assertThat(section).contains("+9000");     // 24000 + (-15000) net
        assertThat(section).contains("005930").contains("+24000");
        assertThat(section).contains("000660").contains("-15000");
    }

    @Test
    void listsHeldPositions() {
        open("035420", 3, "180000");

        String section = reporter.morningSection(runDate);

        assertThat(section).contains("보유 중**: 1종목");
        assertThat(section).contains("035420").contains("180000").contains("70000"); // target shown
    }

    @Test
    void closesOnOtherDaysAreExcluded() {
        // Closed on Monday the 13th, not the previous trading day (Wed the 15th) → excluded.
        closed("005930", 8, "61000", "24000", Instant.parse("2026-07-13T06:00:00Z"));

        assertThat(reporter.morningSection(runDate)).isEmpty();
    }
}

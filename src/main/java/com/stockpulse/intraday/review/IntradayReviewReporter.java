package com.stockpulse.intraday.review;

import com.stockpulse.intraday.MarketClock;
import com.stockpulse.intraday.domain.PositionRecord;
import com.stockpulse.intraday.domain.PositionRecordRepository;
import com.stockpulse.intraday.domain.PositionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * M4 feedback loop: turns the intraday engine's execution results into a Markdown section for the
 * next morning's batch report — closing the loop plan → execution → morning review.
 *
 * <p>Two parts: the previous trading day's REALIZED P&L (from positions closed that day) and the
 * positions still HELD (carried into today, with their exit targets). Read-only over the intraday
 * position store, so it works for dry-run/paper data too. Returns "" when there is nothing to show.
 */
@Slf4j
@Component
public class IntradayReviewReporter {

    private final PositionRecordRepository positionRepository;

    public IntradayReviewReporter(PositionRecordRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    /** Markdown "전일 매매 요약" section for the batch report generated on {@code runDate}. */
    public String morningSection(LocalDate runDate) {
        LocalDate reviewDate = previousTradingDay(runDate);

        List<PositionRecord> closed = positionRepository.findByStatus(PositionStatus.CLOSED).stream()
                .filter(p -> p.getClosedAt() != null
                        && p.getClosedAt().atZone(MarketClock.KST).toLocalDate().equals(reviewDate))
                .toList();
        List<PositionRecord> open = positionRepository.findByStatus(PositionStatus.OPEN);

        if (closed.isEmpty() && open.isEmpty()) {
            return ""; // no execution activity to report
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n## 전일 매매 요약 (").append(reviewDate).append(")\n\n");
        sb.append("> 장중 실행 엔진(Part 2)의 결과입니다. 객관적 실행 기록이며 매매 판단이 아닙니다.\n\n");

        appendRealized(sb, closed);
        appendHeld(sb, open);
        return sb.toString();
    }

    private void appendRealized(StringBuilder sb, List<PositionRecord> closed) {
        BigDecimal total = closed.stream()
                .map(p -> p.getRealizedPnlKrw() == null ? BigDecimal.ZERO : p.getRealizedPnlKrw())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        sb.append("**실현 손익**: ").append(closed.size()).append("건 청산 · 합계 ")
                .append(signed(total)).append("원\n\n");
        if (closed.isEmpty()) {
            return;
        }
        sb.append("| 종목 | 수량 | 평단가 | 실현손익 |\n");
        sb.append("|------|-----:|-------:|--------:|\n");
        for (PositionRecord p : closed) {
            sb.append("| ").append(p.getSymbol())
                    .append(" | ").append(p.getQuantity())
                    .append(" | ").append(plain(p.getAvgPriceKrw()))
                    .append(" | ").append(signed(p.getRealizedPnlKrw()))
                    .append(" |\n");
        }
        sb.append("\n");
    }

    private void appendHeld(StringBuilder sb, List<PositionRecord> open) {
        sb.append("**보유 중**: ").append(open.size()).append("종목\n");
        if (open.isEmpty()) {
            return;
        }
        sb.append("\n| 종목 | 수량 | 평단가 | 목표가 | 손절가 |\n");
        sb.append("|------|-----:|-------:|-------:|-------:|\n");
        for (PositionRecord p : open) {
            sb.append("| ").append(p.getSymbol())
                    .append(" | ").append(p.getQuantity())
                    .append(" | ").append(plain(p.getAvgPriceKrw()))
                    .append(" | ").append(plain(p.getTargetPriceKrw()))
                    .append(" | ").append(plain(p.getStopLossPriceKrw()))
                    .append(" |\n");
        }
        sb.append("\n");
    }

    /** Most recent weekday strictly before {@code runDate}. (Holiday calendar TODO — weekend-only.) */
    private LocalDate previousTradingDay(LocalDate runDate) {
        LocalDate d = runDate.minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private String plain(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }

    private String signed(BigDecimal v) {
        if (v == null) {
            return "-";
        }
        return (v.signum() > 0 ? "+" : "") + v.toPlainString();
    }
}

package com.stockpulse.report.render;

import com.stockpulse.domain.ReportModel;
import com.stockpulse.domain.StockMetric;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provenance in the rendered report.
 *
 * <p>A report of hard-coded sample prices once ran in production for months without anyone
 * noticing, even though its Samsung price was off by a factor of three. Nothing in the document
 * said where the numbers came from, so there was nothing to notice. The renderer now states the
 * source, and says so loudly when any row is sample data.
 */
class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void statesWhereTheNumbersCameFrom() {
        String md = renderer.render(model(metric("005930", "krx-all"), metric("000660", "krx-all")));

        assertThat(md).contains("데이터 출처").contains("krx-all");
    }

    @Test
    void listsEverySourceWhenRowsAreMixed() {
        String md = renderer.render(model(metric("005930", "krx-all"), metric("000660", "naver-finance")));

        assertThat(md).contains("krx-all").contains("naver-finance");
    }

    @Test
    void warnsLoudlyWhenAnyRowIsSampleData() {
        String md = renderer.render(model(metric("005930", "krx-all"), metric("000660", "dummy")));

        assertThat(md).contains("⚠️").contains("샘플 데이터");
        // The warning must precede the numbers — a footnote would be missed exactly as before.
        assertThat(md.indexOf("샘플 데이터")).isLessThan(md.indexOf("## 종목 요약"));
    }

    @Test
    void doesNotWarnWhenEveryRowIsReal() {
        String md = renderer.render(model(metric("005930", "krx-all")));

        assertThat(md).doesNotContain("샘플 데이터");
    }

    private ReportModel model(StockMetric... metrics) {
        return ReportModel.builder()
                .reportDate(LocalDate.of(2026, 9, 10))
                .generatedAt(Instant.parse("2026-09-10T06:00:00Z"))
                .metrics(List.of(metrics))
                .disclosures(List.of())
                .build();
    }

    private StockMetric metric(String symbol, String source) {
        return StockMetric.builder()
                .symbol(symbol).name("종목" + symbol).source(source)
                .price(new BigDecimal("10000")).previousPrice(new BigDecimal("9900"))
                .changeRate(new BigDecimal("1.01")).volume(1000L)
                .build();
    }
}

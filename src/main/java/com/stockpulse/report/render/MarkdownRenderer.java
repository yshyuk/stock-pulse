package com.stockpulse.report.render;

import com.stockpulse.domain.ReportFormat;
import com.stockpulse.domain.ReportModel;
import com.stockpulse.domain.StockMetric;
import com.stockpulse.report.ReportRenderer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a report as Markdown, structured per-stock with sections and tables.
 *
 * <p>The output is intentionally easy for a human to read AND to paste straight into
 * Claude for second-stage analysis: a summary table up top, then one section per stock.
 * The renderer states only objective numbers — it offers no judgement.
 */
@Component
public class MarkdownRenderer implements ReportRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public ReportFormat format() {
        return ReportFormat.MARKDOWN;
    }

    /** The dummy source's name, as reported on every {@link StockMetric} it produces. */
    private static final String SAMPLE_SOURCE = "dummy";

    @Override
    public String render(ReportModel model) {
        StringBuilder sb = new StringBuilder();

        sb.append("# StockPulse 일일 리포트 — ").append(model.getReportDate().format(DATE)).append("\n\n");
        sb.append("> 생성 시각: ").append(model.getGeneratedAt()).append("  \n");
        sb.append("> 본 리포트는 **객관적 지표만** 담습니다. 종목에 대한 판단/추천은 포함하지 않으며, ")
                .append("2차 분석(Claude)에서 수행하세요.\n\n");

        sb.append(provenance(model));

        boolean hasMetrics = model.getMetrics() != null && !model.getMetrics().isEmpty();
        boolean hasDisclosures = model.getDisclosures() != null && !model.getDisclosures().isEmpty();
        if (!hasMetrics && !hasDisclosures) {
            sb.append("_수집된 데이터가 없습니다._\n");
            return sb.toString();
        }

        if (hasMetrics) {
        // Summary table.
        sb.append("## 종목 요약\n\n");
        sb.append("| 종목 | 코드 | 현재가 | 등락률 | 거래량 | 거래량 변화율 |\n");
        sb.append("|------|------|-------:|-------:|-------:|-------------:|\n");
        for (StockMetric m : model.getMetrics()) {
            sb.append("| ").append(nz(m.getName()))
                    .append(" | ").append(nz(m.getSymbol()))
                    .append(" | ").append(num(m.getPrice()))
                    .append(" | ").append(pct(m.getChangeRate()))
                    .append(" | ").append(num(m.getVolume()))
                    .append(" | ").append(pct(m.getVolumeChangeRate()))
                    .append(" |\n");
        }
        sb.append("\n");

        // Per-stock sections.
        sb.append("## 종목별 상세\n\n");
        for (StockMetric m : model.getMetrics()) {
            sb.append("### ").append(nz(m.getName())).append(" (").append(nz(m.getSymbol())).append(")\n\n");
            sb.append("| 지표 | 값 |\n");
            sb.append("|------|----|\n");
            sb.append("| 현재가 | ").append(num(m.getPrice())).append(" |\n");
            sb.append("| 전일/기준가 | ").append(num(m.getPreviousPrice())).append(" |\n");
            sb.append("| 등락률 | ").append(pct(m.getChangeRate())).append(" |\n");
            sb.append("| 거래량 | ").append(num(m.getVolume())).append(" |\n");
            sb.append("| 기준 거래량 | ").append(num(m.getPreviousVolume())).append(" |\n");
            sb.append("| 거래량 변화율 | ").append(pct(m.getVolumeChangeRate())).append(" |\n");
            sb.append("\n");
        }
        } // end hasMetrics

        // Disclosures (e.g. OpenDART) — objective listing, no judgement.
        if (hasDisclosures) {
            sb.append("## 공시\n\n");
            sb.append("| 일자 | 종목 | 코드 | 보고서명 | 제출인 |\n");
            sb.append("|------|------|------|----------|--------|\n");
            for (com.stockpulse.domain.Disclosure d : model.getDisclosures()) {
                sb.append("| ").append(nz(d.getReceiptDate()))
                        .append(" | ").append(nz(d.getCorpName()))
                        .append(" | ").append(nz(d.getStockCode()))
                        .append(" | ").append(nz(d.getReportName()))
                        .append(" | ").append(nz(d.getFiler()))
                        .append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("---\n");
        sb.append("### 2차 분석용 안내\n");
        sb.append("위 표를 그대로 Claude에 붙여넣고, 관심 종목/전략 관점에서 해석을 요청하세요. ")
                .append("이 리포트 자체에는 어떤 매수/매도 신호도 포함되어 있지 않습니다.\n");

        return sb.toString();
    }

    /**
     * Where the numbers came from, stated before the numbers themselves.
     *
     * <p>Sample data gets a banner rather than a footnote: the incident this guards against was
     * not that a warning was ignored, but that there was nothing to ignore — a report of
     * hard-coded prices looked exactly like a real one.
     */
    private String provenance(ReportModel model) {
        if (model.getMetrics() == null || model.getMetrics().isEmpty()) {
            return "";
        }
        List<String> sources = model.getMetrics().stream()
                .map(StockMetric::getSource)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (sources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("> 데이터 출처: ").append(String.join(", ", sources)).append("\n\n");
        if (sources.contains(SAMPLE_SOURCE)) {
            sb.append("> ⚠️ **이 리포트에는 샘플 데이터가 포함되어 있습니다. 실제 시세가 아니므로 ")
                    .append("어떤 판단에도 사용하지 마세요.** (`stockpulse.collector.dummy.enabled`)\n\n");
        }
        return sb.toString();
    }

    private String nz(Object v) {
        return v == null ? "-" : v.toString();
    }

    private String num(Object v) {
        if (v == null) {
            return "-";
        }
        if (v instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (v instanceof Number n) {
            return String.format("%,d", n.longValue());
        }
        return v.toString();
    }

    private String pct(BigDecimal v) {
        if (v == null) {
            return "-";
        }
        String sign = v.signum() > 0 ? "+" : "";
        return sign + v.toPlainString() + "%";
    }
}

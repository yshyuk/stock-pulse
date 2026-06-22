package com.stockpulse.report.render;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockpulse.domain.ReportFormat;
import com.stockpulse.domain.ReportModel;
import com.stockpulse.domain.StockMetric;
import com.stockpulse.report.ReportRenderer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a report as machine-readable JSON.
 *
 * <p>Mirrors {@link MarkdownRenderer}'s data (objective metrics only) but in a structured
 * shape suited for programmatic consumers — e.g. a future automated second-stage analyzer
 * that feeds the report to the Claude API, or the read API. Selected via
 * {@link ReportFormat#JSON}; the default pipeline still produces Markdown.
 */
@Component
public class JsonRenderer implements ReportRenderer {

    private final ObjectMapper objectMapper;

    public JsonRenderer() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public ReportFormat format() {
        return ReportFormat.JSON;
    }

    @Override
    public String render(ReportModel model) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("reportDate", model.getReportDate().toString());
        root.put("generatedAt", model.getGeneratedAt().toString());
        // Make the "objective only, no judgement" contract explicit in the payload too.
        root.put("disclaimer", "Objective metrics only. No recommendation/judgement; "
                + "second-stage analysis is performed separately (Claude).");

        List<Map<String, Object>> stocks = new ArrayList<>();
        for (StockMetric m : model.getMetrics()) {
            Map<String, Object> stock = new LinkedHashMap<>();
            stock.put("symbol", m.getSymbol());
            stock.put("name", m.getName());
            stock.put("price", m.getPrice());
            stock.put("previousPrice", m.getPreviousPrice());
            stock.put("changeRate", m.getChangeRate());
            stock.put("volume", m.getVolume());
            stock.put("previousVolume", m.getPreviousVolume());
            stock.put("volumeChangeRate", m.getVolumeChangeRate());
            stocks.add(stock);
        }
        root.put("stocks", stocks);

        List<Map<String, Object>> disclosures = new ArrayList<>();
        if (model.getDisclosures() != null) {
            for (com.stockpulse.domain.Disclosure d : model.getDisclosures()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("corpName", d.getCorpName());
                item.put("stockCode", d.getStockCode());
                item.put("reportName", d.getReportName());
                item.put("receiptNo", d.getReceiptNo());
                item.put("receiptDate", d.getReceiptDate());
                item.put("filer", d.getFiler());
                disclosures.add(item);
            }
        }
        root.put("disclosures", disclosures);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // Should not happen for this plain map; surface clearly if it ever does.
            throw new IllegalStateException("Failed to render JSON report", e);
        }
    }
}

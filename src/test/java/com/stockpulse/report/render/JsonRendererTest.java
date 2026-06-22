package com.stockpulse.report.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpulse.domain.ReportFormat;
import com.stockpulse.domain.ReportModel;
import com.stockpulse.domain.StockMetric;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRendererTest {

    private final JsonRenderer renderer = new JsonRenderer();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rendersValidJsonWithObjectiveFields() throws Exception {
        ReportModel model = ReportModel.builder()
                .reportDate(LocalDate.of(2026, 6, 21))
                .generatedAt(Instant.parse("2026-06-21T06:00:00Z"))
                .metrics(List.of(StockMetric.builder()
                        .symbol("005930")
                        .name("삼성전자")
                        .price(new BigDecimal("78900"))
                        .previousPrice(new BigDecimal("77000"))
                        .changeRate(new BigDecimal("2.47"))
                        .volume(15_200_000L)
                        .previousVolume(12_800_000L)
                        .volumeChangeRate(new BigDecimal("18.75"))
                        .build()))
                .build();

        assertThat(renderer.format()).isEqualTo(ReportFormat.JSON);

        JsonNode root = mapper.readTree(renderer.render(model));
        assertThat(root.get("reportDate").asText()).isEqualTo("2026-06-21");
        assertThat(root.get("stocks")).hasSize(1);
        JsonNode stock = root.get("stocks").get(0);
        assertThat(stock.get("symbol").asText()).isEqualTo("005930");
        assertThat(stock.get("changeRate").decimalValue()).isEqualByComparingTo("2.47");
        // No judgement/score fields leak into the payload.
        assertThat(stock.has("score")).isFalse();
        assertThat(stock.has("recommendation")).isFalse();
    }
}

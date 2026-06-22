package com.stockpulse.processor;

import com.stockpulse.domain.Disclosure;
import com.stockpulse.domain.RawData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Processor for disclosure-type raw items (payload {@code type == "disclosure"}, e.g. from
 * {@link com.stockpulse.collector.source.DartDataSource}). Turns them into {@link Disclosure}
 * value objects for the report's disclosure section. Price metrics are handled separately by
 * {@link MetricProcessor}.
 */
@Slf4j
@Service
public class DisclosureProcessor {

    public List<Disclosure> process(List<RawData> rawData) {
        List<Disclosure> disclosures = new ArrayList<>();
        for (RawData raw : rawData) {
            Map<String, Object> payload = raw.getPayload();
            if (payload == null || !"disclosure".equals(payload.get("type"))) {
                continue;
            }
            disclosures.add(Disclosure.builder()
                    .corpName(str(payload.get("corpName"), raw.getName()))
                    .stockCode(str(payload.get("stockCode"), raw.getSymbol()))
                    .reportName(str(payload.get("reportName"), null))
                    .receiptNo(str(payload.get("receiptNo"), null))
                    .receiptDate(str(payload.get("receiptDate"), null))
                    .filer(str(payload.get("filer"), null))
                    .build());
        }
        if (!disclosures.isEmpty()) {
            log.info("[processor] collected {} disclosure(s)", disclosures.size());
        }
        return disclosures;
    }

    private String str(Object v, String fallback) {
        return v != null ? v.toString() : fallback;
    }
}

package com.stockpulse.market;

import com.stockpulse.domain.RawData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Processor stage for market-level items: turns raw market readings into {@link MarketIndicator}s.
 *
 * <p>Market sources tag their items with a {@code marketValue} payload key, which is how this
 * processor picks them out of the shared collection stream (stock items carry {@code price}
 * instead and are handled by {@link com.stockpulse.processor.MetricProcessor}).
 */
@Slf4j
@Service
public class MarketProcessor {

    /** Payload key that marks a RawData item as a market indicator reading. */
    public static final String PAYLOAD_VALUE = "marketValue";

    public List<MarketIndicator> process(List<RawData> rawData) {
        List<MarketIndicator> indicators = new ArrayList<>();
        for (RawData raw : rawData) {
            if (raw.getPayload() == null || !raw.getPayload().containsKey(PAYLOAD_VALUE)) {
                continue;
            }
            try {
                Object v = raw.getPayload().get(PAYLOAD_VALUE);
                if (v == null || raw.getSymbol() == null) {
                    continue;
                }
                indicators.add(MarketIndicator.builder()
                        .code(raw.getSymbol())
                        .name(raw.getName())
                        .value(new BigDecimal(v.toString()))
                        .source(raw.getSourceName())
                        .build());
            } catch (Exception e) {
                log.warn("[market] could not parse market item '{}': {}", raw.getSymbol(), e.getMessage());
            }
        }
        log.info("[market] processed {} market indicator(s)", indicators.size());
        return indicators;
    }
}

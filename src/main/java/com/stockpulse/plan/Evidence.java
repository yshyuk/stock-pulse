package com.stockpulse.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;

/**
 * Snapshot of the indicator values that produced a match, captured so a plan can be audited
 * and reproduced. Null fields mean the indicator was not available at plan time.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Evidence {

    BigDecimal price;
    BigDecimal changeRate1d;
    BigDecimal changeRate5d;
    BigDecimal changeRate20d;
    BigDecimal volumeMa20Ratio;
    Integer streakDays;
    BigDecimal volatility20d;
    BigDecimal rangePosition;
}

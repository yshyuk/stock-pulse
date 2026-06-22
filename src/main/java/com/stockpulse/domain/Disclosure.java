package com.stockpulse.domain;

import lombok.Builder;
import lombok.Value;

/**
 * A corporate disclosure item (e.g. from OpenDART), produced by the processor stage.
 *
 * <p>Like {@link StockMetric}, this is objective/factual only — it records that a filing
 * happened, not whether it is good or bad news. Interpretation is the second-stage job.
 */
@Value
@Builder
public class Disclosure {

    String corpName;
    String stockCode;
    String reportName;
    String receiptNo;
    String receiptDate;
    String filer;
}

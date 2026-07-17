package com.stockpulse.market;

import com.stockpulse.domain.RawData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Cohesive market step for the pipeline: extract market indicators from the raw stream, upsert
 * their daily snapshots, and render the report summary. Grouping the market collaborators behind
 * one seam keeps {@link com.stockpulse.batch.BatchPipeline} small (mirrors
 * {@link com.stockpulse.plan.PlanStage}).
 */
@Slf4j
@Component
public class MarketStage {

    private final MarketProcessor marketProcessor;
    private final MarketSnapshotService marketSnapshotService;
    private final MarketSummaryFormatter marketSummaryFormatter;

    public MarketStage(MarketProcessor marketProcessor,
                       MarketSnapshotService marketSnapshotService,
                       MarketSummaryFormatter marketSummaryFormatter) {
        this.marketProcessor = marketProcessor;
        this.marketSnapshotService = marketSnapshotService;
        this.marketSummaryFormatter = marketSummaryFormatter;
    }

    /** Processes market items from {@code raw} and upserts their snapshots for {@code runDate}. */
    public List<DailyMarketSnapshot> recordFrom(LocalDate runDate, List<RawData> raw) {
        List<MarketIndicator> indicators = marketProcessor.process(raw);
        return marketSnapshotService.record(runDate, indicators);
    }

    /** Markdown "시장 컨텍스트" section for the report (empty when no snapshots). */
    public String summaryMarkdown(List<DailyMarketSnapshot> snapshots) {
        return marketSummaryFormatter.toMarkdown(snapshots);
    }
}

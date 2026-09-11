package com.stockpulse.timeseries;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.StockMetric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Time-series stage: persists one {@link DailyStockSnapshot} per stock for {@code runDate},
 * computing {@link DerivedMetrics} from accumulated history first.
 *
 * <p>Upsert on the natural key {@code (symbol, trade_date)} keeps a re-run for the same date
 * idempotent (F-03): the existing row is updated in place rather than duplicated. Derived
 * metrics use only data already in the DB (prior snapshots) plus today's collected values —
 * no external re-fetch — so results are reproducible.
 */
@Slf4j
@Service
public class SnapshotService {

    private final DailyStockSnapshotRepository repository;
    private final DerivedMetricCalculator calculator;
    private final StockPulseProperties properties;
    private final Clock clock;

    public SnapshotService(DailyStockSnapshotRepository repository,
                           DerivedMetricCalculator calculator,
                           StockPulseProperties properties,
                           Clock clock) {
        this.repository = repository;
        this.calculator = calculator;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Records/updates snapshots for all price-bearing metrics on {@code runDate}.
     *
     * @return the persisted snapshots (for logging / downstream plan generation)
     */
    @Transactional
    public List<DailyStockSnapshot> record(LocalDate runDate, List<StockMetric> metrics) {
        int window = properties.getTimeseries().getHistoryWindowDays();
        Instant now = Instant.now(clock);
        // One trip for the whole day's existing rows instead of one per symbol. At whole-market
        // scale that is ~2,765 round trips removed from every run.
        Map<String, DailyStockSnapshot> existingBySymbol = new HashMap<>();
        for (DailyStockSnapshot existing : repository.findByTradeDate(runDate)) {
            existingBySymbol.put(existing.getSymbol(), existing);
        }

        List<DailyStockSnapshot> toSave = new ArrayList<>();

        for (StockMetric m : metrics) {
            if (m.getSymbol() == null || m.getPrice() == null) {
                continue;
            }
            List<DailyStockSnapshot> priors = repository
                    .findBySymbolAndTradeDateLessThanOrderByTradeDateDesc(
                            m.getSymbol(), runDate, PageRequest.of(0, window));

            DerivedMetrics derived = calculator.compute(
                    m.getPrice(), m.getPreviousPrice(), m.getVolume(), priors);

            DailyStockSnapshot snapshot = existingBySymbol.get(m.getSymbol());
            if (snapshot == null) {
                snapshot = DailyStockSnapshot.builder()
                        .symbol(m.getSymbol())
                        .tradeDate(runDate)
                        .build();
            }

            snapshot.setName(m.getName());
            snapshot.setPrice(m.getPrice());
            snapshot.setPreviousPrice(m.getPreviousPrice());
            snapshot.setVolume(m.getVolume());
            snapshot.setDerived(derived);
            snapshot.setSource(m.getSource() == null ? "unknown" : m.getSource());
            snapshot.setCollectedAt(now);

            toSave.add(snapshot);
        }

        List<DailyStockSnapshot> saved = repository.saveAll(toSave);
        log.info("[timeseries] upserted {} snapshot(s) for {}", saved.size(), runDate);
        return saved;
    }
}

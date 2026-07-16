package com.stockpulse.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Time-series stage for market indicators: persists one {@link DailyMarketSnapshot} per
 * indicator for {@code runDate}, computing the day-over-day change rate from the prior stored
 * value first.
 *
 * <p>Upsert on {@code (indicator_code, trade_date)} keeps re-runs idempotent. Change rate uses
 * only DB data (prior snapshot) — reproducible, and null when there is no prior.
 */
@Slf4j
@Service
public class MarketSnapshotService {

    private static final int SCALE = 4;

    private final DailyMarketSnapshotRepository repository;
    private final Clock clock;

    public MarketSnapshotService(DailyMarketSnapshotRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public List<DailyMarketSnapshot> record(LocalDate runDate, List<MarketIndicator> indicators) {
        Instant now = Instant.now(clock);
        List<DailyMarketSnapshot> saved = new ArrayList<>();

        for (MarketIndicator ind : indicators) {
            if (ind.getCode() == null || ind.getValue() == null) {
                continue;
            }
            BigDecimal changeRate = repository
                    .findFirstByIndicatorCodeAndTradeDateLessThanOrderByTradeDateDesc(ind.getCode(), runDate)
                    .map(prior -> percentChange(prior.getValue(), ind.getValue()))
                    .orElse(null);

            DailyMarketSnapshot snapshot = repository
                    .findByIndicatorCodeAndTradeDate(ind.getCode(), runDate)
                    .orElseGet(() -> DailyMarketSnapshot.builder()
                            .indicatorCode(ind.getCode())
                            .tradeDate(runDate)
                            .build());

            snapshot.setName(ind.getName());
            snapshot.setValue(ind.getValue());
            snapshot.setChangeRate(changeRate);
            snapshot.setSource(ind.getSource() == null ? "unknown" : ind.getSource());
            snapshot.setCollectedAt(now);

            saved.add(repository.save(snapshot));
        }

        log.info("[market] upserted {} market snapshot(s) for {}", saved.size(), runDate);
        return saved;
    }

    /** (current - base) / base * 100, null-safe. */
    private BigDecimal percentChange(BigDecimal base, BigDecimal current) {
        if (base == null || current == null || base.signum() == 0) {
            return null;
        }
        return current.subtract(base)
                .divide(base, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }
}

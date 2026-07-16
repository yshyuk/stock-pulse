package com.stockpulse.intraday.position;

import com.stockpulse.broker.BrokerClient;
import com.stockpulse.broker.Quote;
import com.stockpulse.intraday.domain.ExitReason;
import com.stockpulse.intraday.domain.PositionRecord;
import com.stockpulse.intraday.domain.PositionRecordRepository;
import com.stockpulse.intraday.domain.PositionStatus;
import com.stockpulse.intraday.order.OrderService;
import com.stockpulse.intraday.signal.ExitEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Monitors every open position each tick and exits it when its own target/stop is hit. Exit
 * criteria live on the position (persisted at entry), so this works for positions carried over
 * from a prior day too. A quote failure for one symbol is isolated — the rest still get checked.
 */
@Slf4j
@Component
public class PositionManager {

    private final BrokerClient broker;
    private final PositionRecordRepository positionRepository;
    private final ExitEvaluator exitEvaluator;
    private final OrderService orderService;

    public PositionManager(BrokerClient broker,
                           PositionRecordRepository positionRepository,
                           ExitEvaluator exitEvaluator,
                           OrderService orderService) {
        this.broker = broker;
        this.positionRepository = positionRepository;
        this.exitEvaluator = exitEvaluator;
        this.orderService = orderService;
    }

    /** Evaluate exits for all open positions on {@code tradeDate}. */
    public void monitorExits(LocalDate tradeDate) {
        List<PositionRecord> open = positionRepository.findByStatus(PositionStatus.OPEN);
        for (PositionRecord position : open) {
            try {
                Quote quote = broker.getQuote(position.getSymbol());
                Optional<ExitReason> reason = exitEvaluator.evaluate(position, quote);
                reason.ifPresent(r -> {
                    log.info("[position] exit {} triggered for {} @ {}", r, position.getSymbol(), quote.price());
                    orderService.submitSell(position, quote, r, tradeDate);
                });
            } catch (Exception e) {
                log.warn("[position] exit check failed for {} (skipped this tick): {}",
                        position.getSymbol(), e.getMessage());
            }
        }
    }
}

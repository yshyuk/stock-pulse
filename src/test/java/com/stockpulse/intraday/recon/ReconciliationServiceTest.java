package com.stockpulse.intraday.recon;

import com.stockpulse.broker.BrokerMode;
import com.stockpulse.broker.FakeBrokerClient;
import com.stockpulse.config.BrokerProperties;
import com.stockpulse.intraday.domain.PositionRecord;
import com.stockpulse.intraday.domain.PositionRecordRepository;
import com.stockpulse.intraday.domain.PositionStatus;
import com.stockpulse.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ReconciliationServiceTest {

    @Autowired
    private PositionRecordRepository positionRepository;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-16T02:00:00Z"), ZoneOffset.UTC);
    private final LocalDate day = LocalDate.of(2026, 7, 16);

    private FakeBrokerClient broker;
    private BrokerProperties brokerProps;
    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        broker = new FakeBrokerClient(clock);
        brokerProps = new BrokerProperties();
        brokerProps.setMode(BrokerMode.PAPER);
        service = new ReconciliationService(broker, positionRepository, brokerProps,
                new NotificationService(List.of()), clock);
    }

    private PositionRecord openLocal(String symbol, int qty, String avg) {
        return positionRepository.save(PositionRecord.builder()
                .symbol(symbol).quantity(qty).avgPriceKrw(new BigDecimal(avg)).planDate(day)
                .targetPriceKrw(new BigDecimal("64000")).stopLossPriceKrw(new BigDecimal("59000"))
                .openedAt(Instant.now(clock)).status(PositionStatus.OPEN).build());
    }

    /** Give the fake broker a holding by buying into it. */
    private void brokerHolds(String symbol, int qty, String price) {
        broker.setQuote(symbol, new BigDecimal(price));
        broker.placeOrder(new com.stockpulse.broker.OrderRequest(
                "seed-" + symbol, symbol, com.stockpulse.broker.OrderSide.BUY,
                com.stockpulse.broker.OrderType.LIMIT, qty, new BigDecimal(price)));
    }

    @Test
    void matchingPositionsLeftAlone() {
        openLocal("005930", 8, "61000");
        brokerHolds("005930", 8, "61000");

        ReconciliationResult r = service.reconcile();

        assertThat(r.matched()).isEqualTo(1);
        assertThat(r.hasDiscrepancy()).isFalse();
        assertThat(positionRepository.findByStatus(PositionStatus.OPEN)).hasSize(1);
    }

    @Test
    void localOnlyPositionIsClosedAsPhantom() {
        openLocal("005930", 8, "61000"); // broker holds nothing

        ReconciliationResult r = service.reconcile();

        assertThat(r.localClosed()).isEqualTo(1);
        assertThat(r.hasDiscrepancy()).isTrue();
        assertThat(positionRepository.findByStatus(PositionStatus.OPEN)).isEmpty();
    }

    @Test
    void quantityMismatchCorrectedToBroker() {
        openLocal("005930", 8, "61000");
        brokerHolds("005930", 5, "62000"); // broker holds fewer, at a different avg

        ReconciliationResult r = service.reconcile();

        assertThat(r.quantityAdjusted()).isEqualTo(1);
        PositionRecord local = positionRepository.findBySymbolAndStatus("005930", PositionStatus.OPEN).orElseThrow();
        assertThat(local.getQuantity()).isEqualTo(5);                 // corrected to broker's truth
        assertThat(local.getAvgPriceKrw()).isEqualByComparingTo("62000"); // avg also corrected
    }

    @Test
    void brokerOnlyHoldingReportedNotAdopted() {
        brokerHolds("000660", 10, "120000"); // no local record

        ReconciliationResult r = service.reconcile();

        assertThat(r.brokerOnly()).isEqualTo(1);
        assertThat(r.hasDiscrepancy()).isTrue();
        assertThat(positionRepository.findByStatus(PositionStatus.OPEN)).isEmpty(); // not adopted
    }

    @Test
    void dryRunSkipsReconciliation() {
        brokerProps.setMode(BrokerMode.DRY_RUN);
        openLocal("005930", 8, "61000");

        ReconciliationResult r = service.reconcile();

        assertThat(r.skipped()).isTrue();
        assertThat(positionRepository.findByStatus(PositionStatus.OPEN)).hasSize(1); // untouched
    }
}

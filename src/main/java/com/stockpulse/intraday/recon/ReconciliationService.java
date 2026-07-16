package com.stockpulse.intraday.recon;

import com.stockpulse.broker.BrokerClient;
import com.stockpulse.broker.BrokerPosition;
import com.stockpulse.config.BrokerProperties;
import com.stockpulse.intraday.domain.PositionRecord;
import com.stockpulse.intraday.domain.PositionRecordRepository;
import com.stockpulse.intraday.domain.PositionStatus;
import com.stockpulse.notification.NotificationMessage;
import com.stockpulse.notification.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Start-up reconciliation (design ADR-007 §3): if the engine restarts mid-day, the local
 * position store and the broker account can disagree (a fill landed before the crash, a manual
 * trade happened, etc.). The BROKER is authoritative.
 *
 * <p>Three kinds of drift, all surfaced (never silent):
 * <ul>
 *   <li><b>quantity mismatch</b> — local corrected to the broker's quantity/avg price.</li>
 *   <li><b>local-only</b> — an OPEN local position the broker no longer holds → closed as phantom
 *       (realized P&L unknown, left null).</li>
 *   <li><b>broker-only</b> — a holding the engine doesn't track → reported but NOT adopted, since
 *       it has no exit criteria; the operator decides.</li>
 * </ul>
 *
 * <p>Skipped entirely in dry-run mode — no real orders were sent, so the broker holds nothing of
 * ours and "reconciling" would wrongly close local paper positions.
 */
@Slf4j
@Service
public class ReconciliationService {

    private final BrokerClient broker;
    private final PositionRecordRepository positionRepository;
    private final BrokerProperties brokerProperties;
    private final NotificationService notificationService;
    private final Clock clock;

    public ReconciliationService(BrokerClient broker,
                                 PositionRecordRepository positionRepository,
                                 BrokerProperties brokerProperties,
                                 NotificationService notificationService,
                                 Clock clock) {
        this.broker = broker;
        this.positionRepository = positionRepository;
        this.brokerProperties = brokerProperties;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /**
     * Not {@code @Transactional}: the broker call is a network round-trip that must not be held
     * inside a DB transaction. Each per-position {@code save} commits independently; a crash
     * mid-loop leaves a partially-reconciled state that the next boot simply re-reconciles.
     */
    public ReconciliationResult reconcile() {
        if (!brokerProperties.getMode().sendsOrders()) {
            log.info("[recon] dry-run mode — skipping reconciliation (no real orders sent)");
            return ReconciliationResult.skippedResult();
        }

        // Fetch broker truth first — OUTSIDE any transaction.
        Map<String, BrokerPosition> brokerBySymbol = new HashMap<>();
        for (BrokerPosition bp : broker.getPositions()) {
            brokerBySymbol.put(bp.symbol(), bp);
        }

        int matched = 0;
        int adjusted = 0;
        int localClosed = 0;
        List<String> notes = new ArrayList<>();
        Instant now = Instant.now(clock);

        for (PositionRecord local : positionRepository.findByStatus(PositionStatus.OPEN)) {
            BrokerPosition bp = brokerBySymbol.remove(local.getSymbol());
            if (bp == null) {
                // Broker no longer holds it — close the phantom local position.
                local.setStatus(PositionStatus.CLOSED);
                local.setClosedAt(now);
                positionRepository.save(local);
                localClosed++;
                notes.add("local-only(closed): " + local.getSymbol());
            } else if (bp.quantity() != local.getQuantity()) {
                notes.add("qty-adjusted: " + local.getSymbol()
                        + " " + local.getQuantity() + "→" + bp.quantity());
                local.setQuantity(bp.quantity());
                local.setAvgPriceKrw(bp.avgPrice());
                positionRepository.save(local);
                adjusted++;
            } else {
                matched++;
            }
        }

        int brokerOnly = brokerBySymbol.size();
        for (String symbol : brokerBySymbol.keySet()) {
            notes.add("broker-only(unmanaged): " + symbol);
        }

        ReconciliationResult result = new ReconciliationResult(matched, adjusted, brokerOnly, localClosed, false);
        log.info("[recon] matched={}, adjusted={}, brokerOnly={}, localClosed={}",
                matched, adjusted, brokerOnly, localClosed);
        if (result.hasDiscrepancy()) {
            notify(notes);
        }
        return result;
    }

    private void notify(List<String> notes) {
        try {
            notificationService.broadcast(NotificationMessage.builder()
                    .severity(NotificationMessage.Severity.FAILURE)
                    .title("⚠️ 장중 엔진: 포지션 불일치 감지 (리컨실)")
                    .body("재시작 대조에서 불일치가 발견되었습니다:\n- " + String.join("\n- ", notes))
                    .build());
        } catch (Exception e) {
            log.warn("[recon] notification failed (ignored): {}", e.getMessage());
        }
    }
}

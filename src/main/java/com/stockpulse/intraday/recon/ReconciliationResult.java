package com.stockpulse.intraday.recon;

/**
 * Summary of a start-up reconciliation pass (design ADR-007 §3).
 *
 * @param matched          positions that agreed between broker and local store
 * @param quantityAdjusted local positions whose quantity/avg was corrected to the broker's
 * @param brokerOnly       broker holdings the engine doesn't track (unmanaged — no exit criteria)
 * @param localClosed      local OPEN positions the broker no longer holds (closed as phantom)
 * @param skipped          true when reconciliation was skipped (dry-run mode — no real orders)
 */
public record ReconciliationResult(
        int matched,
        int quantityAdjusted,
        int brokerOnly,
        int localClosed,
        boolean skipped) {

    public static ReconciliationResult skippedResult() {
        return new ReconciliationResult(0, 0, 0, 0, true);
    }

    public boolean hasDiscrepancy() {
        return quantityAdjusted > 0 || brokerOnly > 0 || localClosed > 0;
    }
}

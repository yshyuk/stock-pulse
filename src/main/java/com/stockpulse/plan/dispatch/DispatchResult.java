package com.stockpulse.plan.dispatch;

/**
 * Outcome of one plan-delivery attempt sequence.
 *
 * <p>{@code SKIPPED} and {@code FAILED} are deliberately distinct: skipped is the expected state
 * when dispatch is off, while failed means we intended to deliver and could not — only the latter
 * is worth alerting the operator about.
 */
public record DispatchResult(Status status, int attempts, String reason) {

    public enum Status { DELIVERED, SKIPPED, FAILED }

    public static DispatchResult delivered(int attempts) {
        return new DispatchResult(Status.DELIVERED, attempts, null);
    }

    public static DispatchResult skipped(String reason) {
        return new DispatchResult(Status.SKIPPED, 0, reason);
    }

    public static DispatchResult failed(int attempts, String reason) {
        return new DispatchResult(Status.FAILED, attempts, reason);
    }

    public boolean isDelivered() {
        return status == Status.DELIVERED;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }
}

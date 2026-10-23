package com.stockpulse.report.dispatch;

/**
 * Outcome of sending the day's report to stock-api {@code /v1/signals/reports}.
 *
 * <ul>
 *   <li>{@code CREATED} — the api stored a new report (it answers 202; 201 is accepted too).</li>
 *   <li>{@code DUPLICATE} — 200: the same reportId with the same hash was already there. Success.</li>
 *   <li>{@code CONFLICT} — 409: the same reportId with a DIFFERENT body. Expected, not a failure:
 *       the rendered report body carries a "생성 시각" (generated-at) line, so any same-day re-run
 *       gets a new reportHash and the api keeps the first report. Retrying cannot change that
 *       answer, so it is reported, not retried.</li>
 *   <li>{@code INVALID} — any other 4xx (or an unexpected status). Not retried.</li>
 *   <li>{@code FAILED} — connection errors / timeouts / 5xx on every attempt.</li>
 *   <li>{@code SKIPPED} — deliberately not sent; {@link #reason()} says why.</li>
 * </ul>
 *
 * <p>None of these fails the batch.
 */
public record ReportDispatchResult(Status status, int attempts, String reason) {

    public enum Status { CREATED, DUPLICATE, CONFLICT, INVALID, FAILED, SKIPPED }

    /** Reason used when the feature is off — the expected state, not worth a line in the report. */
    public static final String DISABLED = "disabled or not configured";

    public static ReportDispatchResult skipped(String reason) {
        return new ReportDispatchResult(Status.SKIPPED, 0, reason);
    }

    public static ReportDispatchResult failed(int attempts, String reason) {
        return new ReportDispatchResult(Status.FAILED, attempts, reason);
    }

    /** True only for the "feature switched off" skip; other skips are worth surfacing. */
    public boolean isDisabled() {
        return status == Status.SKIPPED && DISABLED.equals(reason);
    }
}

package com.stockpulse.report.dispatch;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What pulse POSTs to stock-api {@code /v1/signals/reports}: the rendered report plus the
 * structured screening evidence the daily PAPER strategy is allowed to cite.
 *
 * <p>Built by {@link ReportPayloadBuilder}; send the bytes of
 * {@link ReportPayloadBuilder#toJson(ReportPayload)}, never a re-serialization — the hash is
 * computed over the exact screening JSON that goes on the wire.
 *
 * @param reportId   {@code "pulse-" + tradeDate} — one report per trade day; a re-run with a
 *                   different body is a conflict on the api side, not a new report
 * @param reportHash SHA-256 hex of {@code body + "\n" + screening JSON}, UTF-8
 * @param tradeDate  KST date of the batch run (the day the strategy trades)
 * @param dataAsOf   close of the collected session (sessionDate 15:30 KST)
 * @param body       rendered report text
 * @param screening  screening contract object, see {@link ScreeningEvidenceSerializer}
 */
public record ReportPayload(String reportId,
                            String reportHash,
                            LocalDate tradeDate,
                            Instant dataAsOf,
                            String body,
                            JsonNode screening) {
}

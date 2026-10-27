package com.stockpulse.report.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockpulse.domain.Report;
import com.stockpulse.screening.ScreeningResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HexFormat;

/**
 * Builds the report payload stock-api ingests at {@code /v1/signals/reports}.
 *
 * <p>Two dates, deliberately separate:
 * <ul>
 *   <li>{@code tradeDate} — the KST date the batch runs, i.e. the day the PAPER strategy trades.
 *       It keys the report ({@code reportId = "pulse-" + tradeDate}).</li>
 *   <li>{@code sessionDate} — the market session the prices come from. At 06:00 that is the
 *       PREVIOUS session (KRX walks back from the run date), and after a holiday it is further
 *       back still, so it must come from the collector. It is required: guessing it would stamp
 *       {@code dataAsOf} with a close that never happened.</li>
 * </ul>
 */
@Component
public class ReportPayloadBuilder {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** KRX regular session close. */
    static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);

    private final Clock clock;

    public ReportPayloadBuilder(Clock clock) {
        this.clock = clock;
    }

    /** {@code tradeDate} = today in KST per the injected clock. */
    public ReportPayload build(Report report, ScreeningResult screened, LocalDate sessionDate) {
        return build(report, screened, sessionDate, LocalDate.ofInstant(clock.instant(), KST));
    }

    public ReportPayload build(Report report, ScreeningResult screened, LocalDate sessionDate, LocalDate tradeDate) {
        if (report == null || report.getContent() == null) {
            throw new IllegalArgumentException("report body is required");
        }
        if (screened == null) {
            throw new IllegalArgumentException("screening result is required");
        }
        if (sessionDate == null) {
            throw new IllegalArgumentException("sessionDate is required (the collected session, not the run date)");
        }
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate is required");
        }
        String reportId = "pulse-" + tradeDate;
        ObjectNode screening = ScreeningEvidenceSerializer.toNode(reportId, sessionDate, screened);
        String body = report.getContent();
        String hash = sha256Hex(body + "\n" + ScreeningEvidenceSerializer.write(screening));
        Instant dataAsOf = sessionDate.atTime(SESSION_CLOSE).atZone(KST).toInstant();
        return new ReportPayload(reportId, hash, tradeDate, dataAsOf, body, screening);
    }

    /**
     * The exact bytes to POST (UTF-8 of this string). Field order is fixed:
     * reportId, reportHash, tradeDate (YYYY-MM-DD), dataAsOf (ISO-8601 instant), body, screening.
     * Retries must resend this same string.
     */
    public String toJson(ReportPayload payload) {
        ObjectNode root = ScreeningEvidenceSerializer.newObject();
        root.put("reportId", payload.reportId());
        root.put("reportHash", payload.reportHash());
        root.put("tradeDate", payload.tradeDate().toString());
        root.put("dataAsOf", payload.dataAsOf().toString());
        root.put("body", payload.body());
        JsonNode screening = payload.screening();
        root.set("screening", screening);
        return ScreeningEvidenceSerializer.write(root);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package com.stockpulse.notification;

import lombok.Builder;
import lombok.Value;

/**
 * A channel-agnostic message handed to a {@link Notifier}.
 *
 * <p>Carries a short title/summary plus the (possibly long) body. The optional
 * {@code attachmentPath} lets a notifier upload the full report as a file when the body
 * is too long to send inline; otherwise notifiers split the body as needed.
 */
@Value
@Builder
public class NotificationMessage {

    Severity severity;
    String title;
    String body;

    /** Optional path to a file to attach (e.g. the .md report). Nullable. */
    String attachmentPath;

    public enum Severity {
        SUCCESS,
        FAILURE
    }
}

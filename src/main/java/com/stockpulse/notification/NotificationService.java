package com.stockpulse.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fans a message out to every enabled {@link Notifier}.
 *
 * <p>Used by the batch to report both SUCCESS (with the report) and FAILURE (so failures
 * are never silent). A failure to deliver on one channel is logged and does not stop the
 * others, nor does it change the batch exit code by itself.
 */
@Slf4j
@Service
public class NotificationService {

    private final List<Notifier> notifiers;

    public NotificationService(List<Notifier> notifiers) {
        this.notifiers = notifiers;
    }

    public void broadcast(NotificationMessage message) {
        boolean anyEnabled = false;
        for (Notifier notifier : notifiers) {
            if (!notifier.isEnabled()) {
                continue;
            }
            anyEnabled = true;
            try {
                notifier.send(message);
            } catch (Exception e) {
                log.error("[notify] channel '{}' failed to deliver: {}", notifier.channel(), e.getMessage(), e);
            }
        }
        if (!anyEnabled) {
            // Still surface the outcome somewhere if no channel is configured.
            log.warn("[notify] no notifier enabled — message not delivered: severity={} title='{}'",
                    message.getSeverity(), message.getTitle());
        }
    }
}

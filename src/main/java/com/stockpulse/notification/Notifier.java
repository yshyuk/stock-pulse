package com.stockpulse.notification;

/**
 * ★ Core abstraction for a delivery channel.
 *
 * <p>Implementations (Telegram, Discord) are outbound webhooks. Each notifier is
 * responsible for channel-specific limits — a long report should be split into multiple
 * messages or sent as a file attachment.
 */
public interface Notifier {

    /** Channel id, e.g. "telegram", "discord". */
    String channel();

    /** Whether this channel is configured/enabled (e.g. token present). */
    boolean isEnabled();

    /** Send the message. Implementations handle splitting / attachment for long bodies. */
    void send(NotificationMessage message);
}

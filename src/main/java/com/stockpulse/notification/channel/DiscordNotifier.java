package com.stockpulse.notification.channel;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.notification.NotificationMessage;
import com.stockpulse.notification.Notifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Discord delivery via an incoming webhook URL (outbound from our side).
 *
 * <p>Enabled only when a webhook URL is configured (injected from env var). The actual
 * HTTP send is left as a TODO; for now it logs what it would send so the pipeline runs
 * end-to-end without credentials.
 */
@Slf4j
@Component
public class DiscordNotifier implements Notifier {

    /** Discord caps a webhook message content at 2000 chars; split beyond this. */
    private static final int MAX_MESSAGE_CHARS = 2000;

    private final StockPulseProperties properties;
    private final WebClient webClient;

    public DiscordNotifier(StockPulseProperties properties, WebClient webClient) {
        this.properties = properties;
        this.webClient = webClient;
    }

    @Override
    public String channel() {
        return "discord";
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.hasText(properties.getNotification().getDiscord().getWebhookUrl());
    }

    @Override
    public void send(NotificationMessage message) {
        if (!isEnabled()) {
            log.warn("[notify:discord] not configured (webhook url missing) — skipping");
            return;
        }
        // TODO: real send via webhook:
        //   POST <webhookUrl>  { content }  (or multipart with the report file)
        //   - split content into <=MAX_MESSAGE_CHARS chunks, OR attach the report file.
        log.info("[notify:discord] (TODO send) severity={} title='{}' bodyChars={} attachment={} -> would split into {} message(s)",
                message.getSeverity(), message.getTitle(),
                message.getBody() == null ? 0 : message.getBody().length(),
                message.getAttachmentPath(),
                chunkCount(message.getBody()));
    }

    private int chunkCount(String body) {
        if (body == null || body.isEmpty()) {
            return 1;
        }
        return (body.length() + MAX_MESSAGE_CHARS - 1) / MAX_MESSAGE_CHARS;
    }
}

package com.stockpulse.notification.channel;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.notification.NotificationMessage;
import com.stockpulse.notification.Notifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.util.StringUtils;

/**
 * Telegram delivery via the Bot API (outbound webhook).
 *
 * <p>Enabled only when a bot token and chat id are configured (injected from env vars).
 * The actual HTTP send is left as a TODO; for now it logs what it would send so the
 * pipeline runs end-to-end without credentials.
 */
@Slf4j
@Component
public class TelegramNotifier implements Notifier {

    /** Telegram caps a single text message at 4096 chars; split beyond this. */
    private static final int MAX_MESSAGE_CHARS = 4096;

    private final StockPulseProperties properties;
    private final WebClient webClient;

    public TelegramNotifier(StockPulseProperties properties, WebClient webClient) {
        this.properties = properties;
        this.webClient = webClient;
    }

    @Override
    public String channel() {
        return "telegram";
    }

    @Override
    public boolean isEnabled() {
        StockPulseProperties.Telegram t = properties.getNotification().getTelegram();
        return StringUtils.hasText(t.getBotToken()) && StringUtils.hasText(t.getChatId());
    }

    @Override
    public void send(NotificationMessage message) {
        if (!isEnabled()) {
            log.warn("[notify:telegram] not configured (bot token / chat id missing) — skipping");
            return;
        }
        // TODO: real send via Bot API:
        //   POST https://api.telegram.org/bot<token>/sendMessage  { chat_id, text, parse_mode }
        //   - split body into <=MAX_MESSAGE_CHARS chunks, OR
        //   - sendDocument with the report file when attachmentPath is present.
        log.info("[notify:telegram] (TODO send) severity={} title='{}' bodyChars={} attachment={} -> would split into {} message(s)",
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

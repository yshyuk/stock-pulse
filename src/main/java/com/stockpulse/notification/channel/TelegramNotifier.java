package com.stockpulse.notification.channel;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.notification.MessageSplitter;
import com.stockpulse.notification.NotificationMessage;
import com.stockpulse.notification.Notifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Telegram delivery via the Bot API (outbound).
 *
 * <p>Enabled only when a bot token and chat id are configured (env-injected). For a long
 * report we attach the rendered file via {@code sendDocument}; otherwise the text is split
 * into {@value #MAX_MESSAGE_CHARS}-char chunks sent via {@code sendMessage}.
 */
@Slf4j
@Component
public class TelegramNotifier implements Notifier {

    /** Telegram caps a single text message at 4096 chars; split beyond this. */
    private static final int MAX_MESSAGE_CHARS = 4096;
    /** Telegram caps a document caption at 1024 chars. */
    private static final int MAX_CAPTION_CHARS = 1024;

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
        StockPulseProperties.Telegram t = properties.getNotification().getTelegram();
        String base = t.getApiBaseUrl() + "/bot" + t.getBotToken();
        String chatId = t.getChatId();
        String header = message.getTitle() == null ? "" : message.getTitle();

        Path attachment = resolveAttachment(message);
        if (attachment != null) {
            sendDocument(base, chatId, attachment, truncate(header, MAX_CAPTION_CHARS));
            log.info("[notify:telegram] sent report document '{}'", attachment.getFileName());
            return;
        }

        String full = header.isEmpty() ? nz(message.getBody()) : header + "\n\n" + nz(message.getBody());
        List<String> chunks = MessageSplitter.split(full, MAX_MESSAGE_CHARS);
        for (String chunk : chunks) {
            sendMessage(base, chatId, chunk);
        }
        log.info("[notify:telegram] sent {} message chunk(s)", chunks.size());
    }

    private void sendMessage(String base, String chatId, String text) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("chat_id", chatId);
        form.add("text", text);
        webClient.post()
                .uri(base + "/sendMessage")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private void sendDocument(String base, String chatId, Path file, String caption) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", chatId);
        if (StringUtils.hasText(caption)) {
            builder.part("caption", caption);
        }
        builder.part("document", new FileSystemResource(file));
        webClient.post()
                .uri(base + "/sendDocument")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private Path resolveAttachment(NotificationMessage message) {
        if (!StringUtils.hasText(message.getAttachmentPath())) {
            return null;
        }
        Path p = Path.of(message.getAttachmentPath());
        return Files.exists(p) ? p : null;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}

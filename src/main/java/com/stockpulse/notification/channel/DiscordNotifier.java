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
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Discord delivery via an incoming webhook URL (outbound).
 *
 * <p>Enabled only when a webhook URL is configured (env-injected). For a long report we
 * upload the rendered file as a multipart attachment with a short content line; otherwise
 * the content is split into {@value #MAX_MESSAGE_CHARS}-char chunks.
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
        String url = properties.getNotification().getDiscord().getWebhookUrl();
        String header = message.getTitle() == null ? "" : message.getTitle();

        Path attachment = resolveAttachment(message);
        if (attachment != null) {
            sendWithFile(url, truncate(header, MAX_MESSAGE_CHARS), attachment);
            log.info("[notify:discord] sent report file '{}'", attachment.getFileName());
            return;
        }

        String full = header.isEmpty() ? nz(message.getBody()) : header + "\n\n" + nz(message.getBody());
        List<String> chunks = MessageSplitter.split(full, MAX_MESSAGE_CHARS);
        for (String chunk : chunks) {
            sendContent(url, chunk);
        }
        log.info("[notify:discord] sent {} message chunk(s)", chunks.size());
    }

    private void sendContent(String url, String content) {
        webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", content))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private void sendWithFile(String url, String content, Path file) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        if (StringUtils.hasText(content)) {
            builder.part("content", content);
        }
        builder.part("files[0]", new FileSystemResource(file));
        webClient.post()
                .uri(url)
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

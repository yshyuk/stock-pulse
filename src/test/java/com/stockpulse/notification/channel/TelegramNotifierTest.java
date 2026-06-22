package com.stockpulse.notification.channel;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.notification.NotificationMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramNotifierTest {

    private MockWebServer server;
    private TelegramNotifier notifier;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        StockPulseProperties props = new StockPulseProperties();
        StockPulseProperties.Telegram t = props.getNotification().getTelegram();
        t.setBotToken("test-token");
        t.setChatId("12345");
        t.setApiBaseUrl(server.url("/").toString().replaceAll("/$", ""));

        notifier = new TelegramNotifier(props, WebClient.builder().build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void sendsTextViaSendMessageWhenNoAttachment() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        notifier.send(NotificationMessage.builder()
                .severity(NotificationMessage.Severity.FAILURE)
                .title("❌ failed")
                .body("reason here")
                .build());

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/bottest-token/sendMessage");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("chat_id=12345");
        assertThat(body).contains("failed");
    }

    @Test
    void sendsDocumentWhenAttachmentPresent(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path report = tmp.resolve("2026-06-21.md");
        Files.writeString(report, "# report body");
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        notifier.send(NotificationMessage.builder()
                .severity(NotificationMessage.Severity.SUCCESS)
                .title("✅ done")
                .body("long body")
                .attachmentPath(report.toString())
                .build());

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/bottest-token/sendDocument");
        assertThat(req.getHeader("Content-Type")).startsWith("multipart/form-data");
    }

    @Test
    void skipsWhenNotConfigured() {
        StockPulseProperties props = new StockPulseProperties(); // no token/chat id
        TelegramNotifier disabled = new TelegramNotifier(props, WebClient.builder().build());
        assertThat(disabled.isEnabled()).isFalse();
        // Should not throw or make any request.
        disabled.send(NotificationMessage.builder().title("x").body("y").build());
    }
}

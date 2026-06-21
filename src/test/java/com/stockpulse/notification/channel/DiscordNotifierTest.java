package com.stockpulse.notification.channel;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.notification.NotificationMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordNotifierTest {

    private MockWebServer server;
    private StockPulseProperties props;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        props = new StockPulseProperties();
        props.getNotification().getDiscord().setWebhookUrl(server.url("/webhook/abc").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private DiscordNotifier notifier() {
        return new DiscordNotifier(props, WebClient.builder().build());
    }

    @Test
    void sendsJsonContentWhenNoAttachment() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));

        notifier().send(NotificationMessage.builder()
                .severity(NotificationMessage.Severity.FAILURE)
                .title("❌ failed")
                .body("reason")
                .build());

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/webhook/abc");
        assertThat(req.getHeader("Content-Type")).startsWith("application/json");
        assertThat(req.getBody().readUtf8()).contains("content").contains("failed");
    }

    @Test
    void sendsMultipartFileWhenAttachmentPresent(@TempDir Path tmp) throws Exception {
        Path report = tmp.resolve("2026-06-21.md");
        Files.writeString(report, "# report body");
        server.enqueue(new MockResponse().setResponseCode(204));

        notifier().send(NotificationMessage.builder()
                .severity(NotificationMessage.Severity.SUCCESS)
                .title("✅ done")
                .attachmentPath(report.toString())
                .build());

        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("Content-Type")).startsWith("multipart/form-data");
    }
}

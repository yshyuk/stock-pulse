package com.stockpulse.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Strongly-typed binding for the {@code stockpulse.*} configuration tree.
 *
 * <p>All secrets (DB password, API keys, bot token, webhook URL) are injected via env vars
 * referenced from application.yml — they are never hard-coded here.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "stockpulse")
public class StockPulseProperties {

    /** Directory where Markdown reports are written (default ./reports). */
    private String reportDir = "reports";

    /**
     * The dawn run hour (0-23). The actual schedule is owned by launchd; this is a reference
     * value the app can read/log so config stays in one mental model.
     */
    private int runHour = 6;

    @NestedConfigurationProperty
    private Notification notification = new Notification();

    @NestedConfigurationProperty
    private Analysis analysis = new Analysis();

    @Getter
    @Setter
    public static class Notification {
        @NestedConfigurationProperty
        private Telegram telegram = new Telegram();
        @NestedConfigurationProperty
        private Discord discord = new Discord();
    }

    @Getter
    @Setter
    public static class Telegram {
        private String botToken;
        private String chatId;
        /** Bot API base URL; overridable in tests to point at a mock server. */
        private String apiBaseUrl = "https://api.telegram.org";
    }

    @Getter
    @Setter
    public static class Discord {
        private String webhookUrl;
    }

    /** Second-stage analysis settings. Unused today; reserved for a future Claude integration. */
    @Getter
    @Setter
    public static class Analysis {
        /** Anthropic API key (env-injected). Not used until an AnthropicReportAnalyzer is added. */
        private String anthropicApiKey;
    }
}

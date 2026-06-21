package com.stockpulse.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

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

    @NestedConfigurationProperty
    private Collector collector = new Collector();

    /** Data-source settings. */
    @Getter
    @Setter
    public static class Collector {
        @NestedConfigurationProperty
        private Dart dart = new Dart();
        @NestedConfigurationProperty
        private Naver naver = new Naver();
    }

    /** Naver Finance realtime price source (unofficial polling endpoint). Off by default. */
    @Getter
    @Setter
    public static class Naver {
        /** When true, NaverDataSource fetches realtime quotes for {@link #symbols}. */
        private boolean enabled = false;
        /** Realtime polling base URL (stock codes are appended). */
        private String baseUrl = "https://polling.finance.naver.com/api/realtime/domestic/stock";
        /** Stock codes to track, e.g. ["005930", "000660"]. */
        private List<String> symbols = new ArrayList<>();
    }

    /** OpenDART (dart.fss.or.kr) disclosure source. Off by default. */
    @Getter
    @Setter
    public static class Dart {
        /** When true, DartDataSource fetches recent disclosures from OpenDART. */
        private boolean enabled = false;
        /** OpenDART API key (env-injected). Required when {@link #enabled} is true. */
        private String apiKey;
        /** OpenDART API base URL. */
        private String baseUrl = "https://opendart.fss.or.kr/api";
        /** How many days back to query disclosures (inclusive of today). */
        private int lookbackDays = 1;
        /** Max disclosures to keep. */
        private int maxItems = 50;
    }

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

    /** Second-stage (Claude) analysis settings. Off by default — the NoOp analyzer runs. */
    @Getter
    @Setter
    public static class Analysis {
        /** When true, AnthropicReportAnalyzer replaces the NoOp and calls the Claude API. */
        private boolean enabled = false;
        /** Anthropic API key (env-injected). Required when {@link #enabled} is true. */
        private String anthropicApiKey;
        /** Claude model id used for the second-stage analysis. */
        private String model = "claude-opus-4-8";
    }
}

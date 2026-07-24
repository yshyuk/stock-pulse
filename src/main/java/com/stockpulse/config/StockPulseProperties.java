package com.stockpulse.config;

import com.stockpulse.plan.rule.PlanRule;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.math.BigDecimal;
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

    /** Directory where trading-plan JSON files are written (default ./plan). */
    private String planDir = "plan";

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

    @NestedConfigurationProperty
    private Timeseries timeseries = new Timeseries();

    @NestedConfigurationProperty
    private Plan plan = new Plan();

    @NestedConfigurationProperty
    private Heartbeat heartbeat = new Heartbeat();

    @NestedConfigurationProperty
    private Dispatch dispatch = new Dispatch();

    /**
     * Outbound delivery of the day's plan to an external execution system (stock-api
     * SignalIngest). Off by default: without this the plan stays a local artifact.
     *
     * <p>See stock-api ADR-002 — this process never places an order itself; it hands the plan
     * to a consumer that applies its own guardrails and deterministic execution.
     */
    @Getter
    @Setter
    public static class Dispatch {
        /** When true (and a URL is set), the batch pushes the finished plan downstream. */
        private boolean enabled = false;

        /** Full ingest endpoint, e.g. {@code http://127.0.0.1:8081/v1/signals/plans}. */
        private String url;

        /** Shared secret sent as the ingest API-key header. Env-injected. */
        private String apiKey;

        /** Total attempts including the first (3 = initial + 2 retries). */
        private int maxAttempts = 3;

        /** Fixed delay between attempts. */
        private long retryDelayMs = 2000;

        /** Per-attempt request timeout. */
        private int timeoutSeconds = 10;
    }

    /**
     * External dead-man's-switch heartbeat (e.g. healthchecks.io). When {@link #url} is set,
     * the batch pings it on success so a MISSED run (launchd skipped, machine asleep) is
     * detected externally — the batch cannot alert on its own non-execution.
     */
    @Getter
    @Setter
    public static class Heartbeat {
        /** Ping URL; empty disables the heartbeat. Env-injected. */
        private String url;
    }

    /** Time-series accumulation settings (daily snapshot + derived metrics). */
    @Getter
    @Setter
    public static class Timeseries {
        /**
         * How many prior trading days to load when computing derived metrics
         * (~52 weeks). Bounds the range-position lookback and history query size.
         */
        private int historyWindowDays = 260;
    }

    /** Trading-plan generation settings (rule engine + risk limits + entry/exit sizing). */
    @Getter
    @Setter
    public static class Plan {
        /** When true, the pipeline generates and stores a daily plan. */
        private boolean enabled = true;

        /**
         * What the generated plan is FOR: {@code plan-only} (local reference) or {@code signal}
         * (intended for an external execution consumer). Deliberately explicit rather than
         * inferred from {@link Dispatch#isEnabled()} — marking a plan as executable is a
         * decision worth writing down, and {@code PlanDispatcher} refuses to send anything that
         * is not marked {@code signal}.
         */
        private String mode = "plan-only";

        /** Max budget per single symbol (KRW). */
        private BigDecimal maxBudgetPerSymbolKrw = new BigDecimal("500000");

        /** Max total budget across all candidates (KRW). */
        private BigDecimal maxTotalBudgetKrw = new BigDecimal("2000000");

        /** Externalized signal rules. Empty = no candidates. */
        private List<PlanRule> rules = new ArrayList<>();

        @NestedConfigurationProperty
        private Entry entry = new Entry();

        @NestedConfigurationProperty
        private ExitTargets exit = new ExitTargets();

        /**
         * Warn when the gap between a symbol's most recent prior snapshot and the run date
         * exceeds this many days (stale/missing data detection, F-04).
         */
        private int maxDataGapDays = 4;
    }

    /** How the entry price is derived from the current price. */
    @Getter
    @Setter
    public static class Entry {
        /** Order type placed by the plan (e.g. limit, market). */
        private String type = "limit";
        /** Entry price = price * (1 + offsetPct/100). 0 = at current price. */
        private BigDecimal offsetPct = BigDecimal.ZERO;
    }

    /** How target/stop prices are derived from the current price. */
    @Getter
    @Setter
    public static class ExitTargets {
        /** Take-profit at price * (1 + targetPct/100). */
        private BigDecimal targetPct = new BigDecimal("5.0");
        /** Stop-loss at price * (1 + stopLossPct/100); use a negative value. */
        private BigDecimal stopLossPct = new BigDecimal("-3.0");
    }

    /** Data-source settings. */
    @Getter
    @Setter
    public static class Collector {
        @NestedConfigurationProperty
        private Dart dart = new Dart();
        @NestedConfigurationProperty
        private Naver naver = new Naver();
        @NestedConfigurationProperty
        private NaverIndex naverIndex = new NaverIndex();
        @NestedConfigurationProperty
        private Ecos ecos = new Ecos();
        @NestedConfigurationProperty
        private Krx krx = new Krx();
    }

    /** Naver Finance index polling (KOSPI/KOSDAQ). Unofficial endpoint; off by default. */
    @Getter
    @Setter
    public static class NaverIndex {
        private boolean enabled = false;
        /** Realtime index polling base URL (index codes are appended). */
        private String baseUrl = "https://polling.finance.naver.com/api/realtime/domestic/index";
        /** Index codes to fetch, e.g. ["KOSPI", "KOSDAQ"]. */
        private List<String> codes = new ArrayList<>(List.of("KOSPI", "KOSDAQ"));
    }

    /** Bank of Korea ECOS API — USD/KRW exchange rate. Off by default. Requires a free key. */
    @Getter
    @Setter
    public static class Ecos {
        private boolean enabled = false;
        /** ECOS API key (env-injected). Required when {@link #enabled} is true. */
        private String apiKey;
        private String baseUrl = "https://ecos.bok.or.kr/api";
        /** Statistic code for the USD/KRW rate (BOK 731Y001). */
        private String statCode = "731Y001";
        /** Item code under the statistic (원/미국달러). */
        private String itemCode = "0000001";
        /** Cycle: D(daily), M(monthly), ... */
        private String cycle = "D";
        /** How many days back to query (the latest row within the window is used). */
        private int lookbackDays = 7;
    }

    /** KRX (data.krx.co.kr) investor supply/demand (foreign/institution net buy). Off by default. */
    @Getter
    @Setter
    public static class Krx {
        private boolean enabled = false;
        /** KRX JSON data endpoint. */
        private String baseUrl = "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd";
        /** The KRX "bld" identifier for the investor trading dataset. */
        private String bld = "dbms/MDC/STAT/standard/MDCSTAT02203";
        /** Market id (STK=KOSPI, KSQ=KOSDAQ). */
        private String marketId = "STK";
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

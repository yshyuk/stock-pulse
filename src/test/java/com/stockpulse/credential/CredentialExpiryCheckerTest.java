package com.stockpulse.credential;

import com.stockpulse.config.StockPulseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lead time, not detection.
 *
 * <p>An expired key already fails the batch loudly. The problem is that renewing a KRX key takes
 * a login, a request, about a day for approval, and a per-API re-application — so learning about
 * it on the morning it breaks costs at least a day of data. These warnings arrive early enough
 * to act on, and rarely enough that they stay worth reading.
 */
class CredentialExpiryCheckerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 11);

    private StockPulseProperties properties;
    private CredentialExpiryChecker checker;

    @BeforeEach
    void setUp() {
        properties = new StockPulseProperties();
        checker = new CredentialExpiryChecker(properties);
    }

    @Test
    void saysNothingWhenNoExpiryIsConfigured() {
        assertThat(checker.warnings(TODAY)).isEmpty();
    }

    @Test
    void staysQuietWhileExpiryIsFarAway() {
        expiring("krx-api-key", TODAY.plusDays(60));

        assertThat(checker.warnings(TODAY)).isEmpty();
    }

    @Test
    void warnsAtTheFirstThreshold() {
        expiring("krx-api-key", TODAY.plusDays(30));

        assertThat(checker.warnings(TODAY))
                .singleElement().asString()
                .contains("krx-api-key").contains("30").contains("2026-10-11");
    }

    @Test
    void staysQuietBetweenThresholdsSoTheWarningKeepsItsMeaning() {
        // 20 days out is inside the window but not a threshold — warning daily for a month
        // is how an alert becomes background noise.
        expiring("krx-api-key", TODAY.plusDays(20));

        assertThat(checker.warnings(TODAY)).isEmpty();
    }

    @Test
    void warnsAtEachRemainingThreshold() {
        for (int days : new int[]{14, 7, 3, 2, 1}) {
            properties.getCredentials().getExpiry().clear();
            expiring("krx-api-key", TODAY.plusDays(days));

            assertThat(checker.warnings(TODAY))
                    .as("%d days before expiry", days)
                    .hasSize(1);
        }
    }

    @Test
    void warnsEveryDayOnceExpiredBecauseItIsNoLongerAHeadsUp() {
        expiring("krx-api-key", TODAY.minusDays(5));

        assertThat(checker.warnings(TODAY))
                .singleElement().asString()
                .contains("만료");
    }

    @Test
    void warnsOnTheExpiryDayItself() {
        expiring("krx-api-key", TODAY);

        assertThat(checker.warnings(TODAY)).hasSize(1);
    }

    @Test
    void reportsEachCredentialSeparately() {
        properties.getCredentials().setExpiry(Map.of(
                "krx-api-key", TODAY.plusDays(7),
                "dart-api-key", TODAY.plusDays(1),
                "ecos-api-key", TODAY.plusDays(90)));

        List<String> warnings = checker.warnings(TODAY);

        assertThat(warnings).hasSize(2);
        assertThat(String.join("\n", warnings)).contains("krx-api-key").contains("dart-api-key")
                .doesNotContain("ecos-api-key");
    }

    @Test
    void honoursAConfiguredWindow() {
        properties.getCredentials().setWarnBeforeDays(3);
        expiring("krx-api-key", TODAY.plusDays(30));

        // 30 is a threshold, but it is outside a 3-day window — the window wins.
        assertThat(checker.warnings(TODAY)).isEmpty();
    }

    private void expiring(String name, LocalDate date) {
        properties.getCredentials().getExpiry().put(name, date);
    }
}

package com.stockpulse.credential;

import com.stockpulse.config.StockPulseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Advance notice that a credential is about to lapse.
 *
 * <p>This is not about detecting an expired key — that already fails the batch loudly, because
 * the price source is required and a run with no prices is an error. What this adds is LEAD TIME.
 * Renewing a KRX key means logging in, requesting, waiting about a day for approval, and
 * re-applying per API service; learning about it on the morning it breaks costs at least a day
 * of data.
 *
 * <p>Warnings fire only on a few thresholds rather than every day inside the window. An alert
 * that arrives thirty mornings in a row stops being read, which would leave us no better off
 * than having no alert at all.
 *
 * <p>Expiry dates are configured by hand ({@code stockpulse.credentials.expiry}) because no
 * provider reports its own. A date left unset simply means "never warn about this one" — that
 * keeps a forgotten entry silent rather than noisy or wrong.
 */
@Slf4j
@Component
public class CredentialExpiryChecker {

    /** Days-remaining values that earn a message. Chosen to escalate, not to nag. */
    private static final Set<Long> THRESHOLDS = Set.of(30L, 14L, 7L, 3L, 2L, 1L);

    private final StockPulseProperties properties;

    public CredentialExpiryChecker(StockPulseProperties properties) {
        this.properties = properties;
    }

    /** One line per credential that needs attention today; empty when nothing does. */
    public List<String> warnings(LocalDate today) {
        StockPulseProperties.Credentials cfg = properties.getCredentials();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, LocalDate> entry : cfg.getExpiry().entrySet()) {
            LocalDate expiresOn = entry.getValue();
            if (expiresOn == null) {
                continue;
            }
            long daysLeft = ChronoUnit.DAYS.between(today, expiresOn);

            if (daysLeft < 0) {
                // Past the date: this is no longer a heads-up, so repeat it every run.
                warnings.add("🔴 `%s` 인증키가 **이미 만료**되었습니다 (만료일 %s, %d일 경과). 즉시 재발급이 필요합니다."
                        .formatted(entry.getKey(), expiresOn, -daysLeft));
                continue;
            }
            if (daysLeft > cfg.getWarnBeforeDays()) {
                continue;
            }
            if (daysLeft == 0) {
                warnings.add("🔴 `%s` 인증키가 **오늘(%s) 만료**됩니다. 재발급하지 않으면 내일 배치가 실패합니다."
                        .formatted(entry.getKey(), expiresOn));
                continue;
            }
            if (THRESHOLDS.contains(daysLeft)) {
                warnings.add("⏳ `%s` 인증키가 **%d일 뒤 만료**됩니다 (만료일 %s). 재발급에는 승인 대기가 필요하니 미리 진행하세요."
                        .formatted(entry.getKey(), daysLeft, expiresOn));
            }
        }
        if (!warnings.isEmpty()) {
            log.warn("[credentials] {} credential warning(s) for {}", warnings.size(), today);
        }
        return warnings;
    }
}

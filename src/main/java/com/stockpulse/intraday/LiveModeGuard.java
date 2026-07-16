package com.stockpulse.intraday;

import com.stockpulse.broker.BrokerMode;
import com.stockpulse.config.BrokerProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fails fast at boot if real-money trading ({@code mode=live}) is set without the explicit
 * {@code live-confirmed=true} gate (design ADR-007). A stray config flip must never silently
 * enable live trading.
 */
@Slf4j
@Component
public class LiveModeGuard {

    private final BrokerProperties brokerProperties;

    public LiveModeGuard(BrokerProperties brokerProperties) {
        this.brokerProperties = brokerProperties;
    }

    @PostConstruct
    void verify() {
        if (brokerProperties.getMode() == BrokerMode.LIVE && !brokerProperties.isLiveConfirmed()) {
            throw new IllegalStateException(
                    "broker.mode=LIVE requires broker.live-confirmed=true — refusing to boot into "
                            + "real-money trading without explicit confirmation");
        }
        log.info("[broker] mode={} impl={}", brokerProperties.getMode(), brokerProperties.getImpl());
    }
}

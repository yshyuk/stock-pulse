package com.stockpulse.intraday;

import com.stockpulse.broker.BrokerMode;
import com.stockpulse.config.BrokerProperties;
import com.stockpulse.config.IntradayProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Boot-time safety gate for real-money trading (design ADR-007). Fails fast when {@code mode=live}
 * is set without explicit confirmation, or without a durable out-of-band stop.
 */
@Slf4j
@Component
public class LiveModeGuard {

    private final BrokerProperties brokerProperties;
    private final IntradayProperties intradayProperties;

    public LiveModeGuard(BrokerProperties brokerProperties, IntradayProperties intradayProperties) {
        this.brokerProperties = brokerProperties;
        this.intradayProperties = intradayProperties;
    }

    @PostConstruct
    void verify() {
        if (brokerProperties.getMode() == BrokerMode.LIVE) {
            if (!brokerProperties.isLiveConfirmed()) {
                throw new IllegalStateException(
                        "broker.mode=LIVE requires broker.live-confirmed=true — refusing to boot into "
                                + "real-money trading without explicit confirmation");
            }
            String killFile = intradayProperties.getKillSwitchFile();
            if (killFile == null || killFile.isBlank()) {
                // Without a kill file the operator has no way to halt a running engine out-of-band,
                // and a tripped kill switch would not survive a restart.
                throw new IllegalStateException(
                        "broker.mode=LIVE requires stockpulse.intraday.kill-switch-file — refusing to "
                                + "trade real money without a durable out-of-band stop");
            }
        }
        log.info("[broker] mode={} impl={}", brokerProperties.getMode(), brokerProperties.getImpl());
    }
}

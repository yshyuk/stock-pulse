package com.stockpulse.config;

import com.stockpulse.broker.BrokerMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for {@code stockpulse.broker.*} — which broker implementation to use, the execution
 * mode, and the live-trading confirmation gate (design ADR-006/007).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "stockpulse.broker")
public class BrokerProperties {

    /** Which BrokerClient bean is active: {@code fake} (default) or {@code kis}. */
    private String impl = "fake";

    /** Execution mode. Default is the safest, DRY_RUN (orders logged, never sent). */
    private BrokerMode mode = BrokerMode.DRY_RUN;

    /**
     * Must be {@code true} for {@code mode=LIVE} to boot. A safety gate so real-money trading
     * is never enabled by a stray config flip.
     */
    private boolean liveConfirmed = false;
}

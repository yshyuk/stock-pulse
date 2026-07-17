package com.stockpulse.intraday;

import com.stockpulse.broker.BrokerMode;
import com.stockpulse.config.BrokerProperties;
import com.stockpulse.config.IntradayProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveModeGuardTest {

    private LiveModeGuard guard(BrokerMode mode, boolean confirmed, String killFile) {
        BrokerProperties broker = new BrokerProperties();
        broker.setMode(mode);
        broker.setLiveConfirmed(confirmed);
        IntradayProperties intraday = new IntradayProperties();
        intraday.setKillSwitchFile(killFile);
        return new LiveModeGuard(broker, intraday);
    }

    @Test
    void liveWithoutConfirmationRefusesToBoot() {
        assertThatThrownBy(() -> guard(BrokerMode.LIVE, false, "/tmp/kill.flag").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("live-confirmed");
    }

    @Test
    void liveWithoutKillSwitchFileRefusesToBoot() {
        // No durable out-of-band stop → refuse real-money trading.
        assertThatThrownBy(() -> guard(BrokerMode.LIVE, true, "").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kill-switch-file");
    }

    @Test
    void liveFullyGatedBoots() {
        assertThatCode(() -> guard(BrokerMode.LIVE, true, "/tmp/kill.flag").verify())
                .doesNotThrowAnyException();
    }

    @Test
    void dryRunAndPaperNeedNoGates() {
        assertThatCode(() -> guard(BrokerMode.DRY_RUN, false, "").verify()).doesNotThrowAnyException();
        assertThatCode(() -> guard(BrokerMode.PAPER, false, "").verify()).doesNotThrowAnyException();
    }
}

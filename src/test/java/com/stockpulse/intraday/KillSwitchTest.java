package com.stockpulse.intraday;

import com.stockpulse.config.IntradayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class KillSwitchTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-17T02:00:00Z"), ZoneOffset.UTC);

    private IntradayProperties propsWithFile(Path file) {
        IntradayProperties props = new IntradayProperties();
        props.setKillSwitchFile(file.toString());
        return props;
    }

    @Test
    void engagingPersistsToFileSoTripSurvivesRestart(@TempDir Path dir) {
        Path killFile = dir.resolve("kill.flag");
        IntradayProperties props = propsWithFile(killFile);

        new KillSwitch(props, clock).engage("daily loss limit breached");

        assertThat(killFile).exists();
        // A fresh instance (i.e. after a restart) must still see the trip.
        assertThat(new KillSwitch(props, clock).isEngaged()).isTrue();
    }

    @Test
    void existingKillFileEngagesWithoutInMemoryTrip(@TempDir Path dir) throws IOException {
        Path killFile = dir.resolve("kill.flag");
        Files.writeString(killFile, "manual stop"); // operator touched the file out-of-band

        assertThat(new KillSwitch(propsWithFile(killFile), clock).isEngaged()).isTrue();
    }

    @Test
    void notEngagedWhenNoTripAndNoFile(@TempDir Path dir) {
        IntradayProperties props = propsWithFile(dir.resolve("absent.flag"));

        assertThat(new KillSwitch(props, clock).isEngaged()).isFalse();
    }

    @Test
    void engageStillTripsInMemoryWhenNoFileConfigured() {
        IntradayProperties props = new IntradayProperties(); // kill-switch-file = ""
        KillSwitch killSwitch = new KillSwitch(props, clock);

        killSwitch.engage("no file configured");

        assertThat(killSwitch.isEngaged()).isTrue(); // memory-only (logged as a durability warning)
    }
}

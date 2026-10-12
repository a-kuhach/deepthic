
package com.freemaf.agent;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @Test

    void loadsExistingFile() {

        Config config = new Config();

        assertNotNull(config.getWindowTitle());

    }

    @Test

    void defaultIntWhenKeyMissing() {

        Config config = new Config();

        assertEquals(42, config.getInt("nonexistent.test.key", 42));

    }

    @Test

    void defaultLongWhenKeyMissing() {

        Config config = new Config();

        assertEquals(180000L, config.getLong("nonexistent.test.key", 180000L));

    }

    @Test

    void invalidIntFallsBackToDefault() {

        Config config = new Config();

        assertEquals(7, config.getInt("window.title", 7));

    }


    @Test

    void watchdogDefaultsPresent() {

        assertEquals("1800", Config.DEFAULTS.get("watchdog.script.timeout.seconds"));

        assertEquals("1000", Config.DEFAULTS.get("watchdog.script.check.interval.ms"));

        assertEquals("1200", Config.DEFAULTS.get("watchdog.script.warn.threshold.seconds"));

    }

    @Test
    void sessionKeysDefaultsPresent() {
        assertEquals("0", Config.DEFAULTS.get("session.lastManagedHwnd"));
        assertEquals("", Config.DEFAULTS.get("session.lastManagedTitle"));
        assertEquals("EXECUTOR", Config.DEFAULTS.get("session.lastPhase"));
        assertEquals("0", Config.DEFAULTS.get("session.lastReviewerHwnd"));
        assertEquals("", Config.DEFAULTS.get("session.lastReviewerTitle"));
    }

    @Test
    void sessionSettersRoundTrip() {
        Config config = new Config();
        config.setLastManagedHwnd(12345L);
        config.setLastManagedTitle("DeepSeek - test");
        config.setLastPhase("JUDGE");
        config.setLastReviewerHwnd(67890L);
        config.setLastReviewerTitle("DeepSeek - review");
        try {
            Config reread = new Config();
            assertEquals(12345L, reread.getLastManagedHwnd());
            assertEquals("DeepSeek - test", reread.getLastManagedTitle());
            assertEquals("JUDGE", reread.getLastPhase());
            assertEquals(67890L, reread.getLastReviewerHwnd());
            assertEquals("DeepSeek - review", reread.getLastReviewerTitle());
        } finally {
            config.setLastManagedHwnd(0L);
            config.setLastManagedTitle("");
            config.setLastPhase("EXECUTOR");
            config.setLastReviewerHwnd(0L);
            config.setLastReviewerTitle("");
        }
    }

    @Test
    void sessionExecutorMessageCounterDefaults() {
        assertEquals("0", Config.DEFAULTS.get("session.executorMessageCount"));
        assertEquals("", Config.DEFAULTS.get("session.ignoredHwnds"));
    }

    @Test
    void sessionExecutorMessageCounterRoundTrip() {
        Config config = new Config();
        long previous = config.getExecutorMessageCount();
        config.setExecutorMessageCount(42L);
        try {
            Config reread = new Config();
            assertEquals(42L, reread.getExecutorMessageCount());
        } finally {
            config.setExecutorMessageCount(previous);
        }
    }

    @Test
    void ignoredHwndsRoundTripFiltersNonPositive() {
        Config config = new Config();
        List<Long> previous = config.getIgnoredHwnds();
        config.setIgnoredHwnds(List.of(-1L, 0L, 1L, 2L, 3L));
        try {
            Config reread = new Config();
            assertEquals(List.of(1L, 2L, 3L), reread.getIgnoredHwnds());
        } finally {
            config.setIgnoredHwnds(previous);
        }
    }

    @Test
    void ignoredHwndsTrimsToLastTwenty() {
        Config config = new Config();
        List<Long> previous = config.getIgnoredHwnds();
        List<Long> input = new ArrayList<>();
        for (long i = 1L; i <= 25L; i++) input.add(i);
        config.setIgnoredHwnds(input);
        try {
            Config reread = new Config();
            List<Long> got = reread.getIgnoredHwnds();
            assertEquals(20, got.size());
            assertEquals(6L, got.get(0));
            assertEquals(25L, got.get(19));
        } finally {
            config.setIgnoredHwnds(previous);
        }
    }

    @Test
    void migratedV4ResetsOnlyCalibrationKeys() {
        Config config = new Config();
        config.setMigratedV4(false);
        config.set("session.lastManagedHwnd", "7777777");
        config.set("window.work.x", "777");
        config.set("window.work.y", "888");
        config.set("watchdog.script.timeout.seconds", "5000");
        config.set("timeout.script_ms", "999999");
        config.set("marker.retry.max_attempts", "42");
        config.set("marker.send.dx", "111");
        config.set("marker.regenerate.dx", "222");
        config.set("marker.input_area.dy_offset", "-333");
        try {
            config.migrate();
            assertEquals("7777777", config.get("session.lastManagedHwnd", ""));
            assertEquals("777", config.get("window.work.x", ""));
            assertEquals("888", config.get("window.work.y", ""));
            assertEquals("5000", config.get("watchdog.script.timeout.seconds", ""));
            assertEquals("999999", config.get("timeout.script_ms", ""));
            assertEquals("42", config.get("marker.retry.max_attempts", ""));
            for (String key : Config.CALIBRATION_KEYS) {
                String expected = Config.DEFAULTS.get(key);
                if (expected != null) {
                    assertEquals(expected, config.get(key, null), "key " + key + " should be reset");
                }
            }
            assertTrue(config.isMigratedV4());
        } finally {
            config.set("session.lastManagedHwnd", Config.DEFAULTS.get("session.lastManagedHwnd"));
            config.set("window.work.x", Config.DEFAULTS.get("window.work.x"));
            config.set("window.work.y", Config.DEFAULTS.get("window.work.y"));
            config.set("watchdog.script.timeout.seconds", Config.DEFAULTS.get("watchdog.script.timeout.seconds"));
            config.set("timeout.script_ms", Config.DEFAULTS.get("timeout.script_ms"));
            config.set("marker.retry.max_attempts", Config.DEFAULTS.get("marker.retry.max_attempts"));
            config.set("config.migrated.v4", Config.DEFAULTS.get("config.migrated.v4"));
            try { config.save(); } catch (Exception ignored) { }
        }
    }

    @Test
    void setSystemScalePercentRespectsRange() {
        Config config = new Config();
        int previous = config.getSystemScalePercent();
        try {
            config.setSystemScalePercent(0);
            assertEquals(0, config.getSystemScalePercent());
            config.setSystemScalePercent(50);
            assertEquals(0, config.getSystemScalePercent());
            config.setSystemScalePercent(300);
            assertEquals(0, config.getSystemScalePercent());
            config.setSystemScalePercent(100);
            assertEquals(100, config.getSystemScalePercent());
            config.setSystemScalePercent(225);
            assertEquals(225, config.getSystemScalePercent());
        } finally {
            config.setSystemScalePercent(previous);
        }
    }

    @Test
    void migratedV4Idempotent() {
        Config config = new Config();
        config.setMigratedV4(false);
        config.set("marker.send.dx", "111");
        try {
            config.migrate();
            assertEquals("609", config.get("marker.send.dx", ""));
            assertTrue(config.isMigratedV4());
            config.set("marker.send.dx", "999");
            config.migrate();
            assertEquals("999", config.get("marker.send.dx", ""));
        } finally {
            config.set("config.migrated.v4", Config.DEFAULTS.get("config.migrated.v4"));
            try { config.save(); } catch (Exception ignored) { }
        }
    }

    @Test
    void windowGrowthAndSizeMaxDefaultsPresentAndRoundTrip() {
        assertEquals("10", Config.DEFAULTS.get("window.growth.step.px"));
        assertEquals("0", Config.DEFAULTS.get("window.size.max.width"));
        assertEquals("0", Config.DEFAULTS.get("window.size.max.height"));

        Config config = new Config();
        int prevStep = config.getWindowGrowthStepPx();
        int prevMaxW = config.getWindowSizeMaxWidth();
        int prevMaxH = config.getWindowSizeMaxHeight();
        try {
            config.setWindowGrowthStepPx(15);
            config.setWindowSizeMaxWidth(1400);
            config.setWindowSizeMaxHeight(900);
            Config reread = new Config();
            assertEquals(15, reread.getWindowGrowthStepPx());
            assertEquals(1400, reread.getWindowSizeMaxWidth());
            assertEquals(900, reread.getWindowSizeMaxHeight());

            // negative step -> no-op, previous value preserved
            int before = config.getWindowGrowthStepPx();
            config.setWindowGrowthStepPx(0);
            assertEquals(before, config.getWindowGrowthStepPx());
            config.setWindowGrowthStepPx(-5);
            assertEquals(before, config.getWindowGrowthStepPx());

            // negative max width/height -> no-op
            int beforeW = config.getWindowSizeMaxWidth();
            int beforeH = config.getWindowSizeMaxHeight();
            config.setWindowSizeMaxWidth(-1);
            config.setWindowSizeMaxHeight(-1);
            assertEquals(beforeW, config.getWindowSizeMaxWidth());
            assertEquals(beforeH, config.getWindowSizeMaxHeight());
        } finally {
            config.setWindowGrowthStepPx(prevStep);
            config.setWindowSizeMaxWidth(prevMaxW);
            config.setWindowSizeMaxHeight(prevMaxH);
        }
    }
}

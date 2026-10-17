
package com.freemaf.agent;

import java.io.IOException;

import java.io.Reader;

import java.io.Writer;

import java.nio.charset.StandardCharsets;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import java.util.Map;

import java.util.Properties;

public final class Config {

    private static final Path FILE = Path.of("agent", "config.properties");

    static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {

        DEFAULTS.put("window.title", "DeepSeek");

        DEFAULTS.put("window.work.x", "50");

        DEFAULTS.put("window.work.y", "50");

        DEFAULTS.put("window.work.width", "731");

        DEFAULTS.put("window.work.height", "508");
DEFAULTS.put("config.migrated.v5", "false");
        DEFAULTS.put("config.migrated.v6", "false");

        // Calibration mode: "legacy" = hardcoded coordinates + read colours at those pixels;
        //                  "dynamic" = colour-search (kept for future work, disabled by default).
        DEFAULTS.put("calibration.mode", "legacy");

        // Legacy calibration coordinates (absolute pixel positions inside a Chrome window at (50, 50), size 731x508).
        // The runtime adds (window.left()-50, window.top()-50) to each when the window is not at (50, 50).
        DEFAULTS.put("legacy.input.fresh.x", "97");
        DEFAULTS.put("legacy.input.fresh.y", "304");
        DEFAULTS.put("legacy.input.cleared.x", "97");
        DEFAULTS.put("legacy.input.cleared.y", "406");
        DEFAULTS.put("legacy.send.fresh.x", "717");
        DEFAULTS.put("legacy.send.fresh.y", "390");
        DEFAULTS.put("legacy.send.cleared.x", "717");
        DEFAULTS.put("legacy.send.cleared.y", "492");
        DEFAULTS.put("legacy.code_copy.x", "606");
        DEFAULTS.put("legacy.code_copy.y", "247");
        DEFAULTS.put("legacy.regenerate.x", "125");
        DEFAULTS.put("legacy.regenerate.y", "343");

        DEFAULTS.put("chrome.exe.path", "");

        DEFAULTS.put("chrome.deepseek.url", "https://chat.deepseek.com/");

        DEFAULTS.put("chrome.signin.keywords", "sign_in,Sign in,Login,Вход");

        DEFAULTS.put("calibration.done", "false");

        DEFAULTS.put("calibration.prompt",

                "Reply with exactly one fenced code block and no text outside it. The block must contain a single line: OK");

        DEFAULTS.put("marker.regenerate.x", "0");

        DEFAULTS.put("marker.regenerate.y", "0");

        DEFAULTS.put("marker.regenerate.color", "000000");

        DEFAULTS.put("marker.regenerate.dx", "73");

        DEFAULTS.put("marker.regenerate.dy", "258");

        DEFAULTS.put("marker.regenerate.dx_offset", "103");
        DEFAULTS.put("marker.regenerate.x_offset_from_code_copy", "481");

        DEFAULTS.put("marker.copy_last.x", "0");

        DEFAULTS.put("marker.copy_last.y", "0");

        DEFAULTS.put("marker.copy_last.color", "000000");

        DEFAULTS.put("marker.copy_last.dx", "482");

        DEFAULTS.put("marker.copy_last.dy", "198");

        DEFAULTS.put("marker.input_field.x", "94");

        DEFAULTS.put("marker.input_field.y", "393");

        DEFAULTS.put("marker.input_field.color", "333335");

        DEFAULTS.put("marker.input_field.dx", "44");

        DEFAULTS.put("marker.input_field.dy", "343");

        DEFAULTS.put("marker.deepthink.x", "119");

        DEFAULTS.put("marker.deepthink.y", "453");

        DEFAULTS.put("marker.deepthink.color", "6196F8");

        DEFAULTS.put("marker.deepthink.on_color", "6196F8");

        DEFAULTS.put("marker.deepthink.dx", "69");

        DEFAULTS.put("marker.deepthink.dy", "403");

        DEFAULTS.put("marker.search.x", "236");

        DEFAULTS.put("marker.search.y", "453");

        DEFAULTS.put("marker.search.color", "5B82E6");

        DEFAULTS.put("marker.search.on_color", "5B82E6");

        DEFAULTS.put("marker.search.dx", "186");

        DEFAULTS.put("marker.search.dy", "403");

        DEFAULTS.put("timeout.generation_ms", "180000");

        DEFAULTS.put("timeout.script_ms", "120000");

        DEFAULTS.put("timeout.scroll_ms", "30000");

        DEFAULTS.put("timeout.new_window_ms", "45000");

        DEFAULTS.put("marker.retry.x", "140");

        DEFAULTS.put("marker.retry.y", "240");

        DEFAULTS.put("marker.retry.color", "F5AC31");

        DEFAULTS.put("timeout.retry_base_ms", "300000");

        DEFAULTS.put("timeout.retry_base_jitter_ms", "100000");

        DEFAULTS.put("timeout.retry_increment_base_ms", "300000");

        DEFAULTS.put("timeout.retry_increment_step_ms", "100000");

        DEFAULTS.put("marker.retry.max_attempts", "10");

        DEFAULTS.put("marker.send.dx", "609");

        DEFAULTS.put("marker.send.dy", "323");

        DEFAULTS.put("marker.send.x", "0");

        DEFAULTS.put("marker.send.y", "0");

        DEFAULTS.put("marker.send.active_color", "000000");

        DEFAULTS.put("marker.send.disabled_color", "000000");

        DEFAULTS.put("attachments.max_files", "20");

        DEFAULTS.put("attachments.max_size_mb", "50");

        DEFAULTS.put("timeout.attach_upload_ms", "120000");

        DEFAULTS.put("timeout.attach_poll_ms", "500");

        DEFAULTS.put("min_interval_ms", "1000");

        DEFAULTS.put("max_retries", "3");

        DEFAULTS.put("max_copy_retries", "3");

        DEFAULTS.put("debug.show_in_chat", "true");

        DEFAULTS.put("speed.mode", "INSTANT");

        DEFAULTS.put("ps.output.limit_chars", "60000");
        DEFAULTS.put("watchdog.script.timeout.seconds", "1800");
        DEFAULTS.put("watchdog.script.check.interval.ms", "1000");
        DEFAULTS.put("watchdog.script.warn.threshold.seconds", "1200");
        DEFAULTS.put("session.lastManagedHwnd", "0");
        DEFAULTS.put("session.lastManagedTitle", "");
        DEFAULTS.put("session.lastPhase", "EXECUTOR");
        DEFAULTS.put("session.lastReviewerHwnd", "0");
        DEFAULTS.put("session.lastReviewerTitle", "");
        DEFAULTS.put("session.executorMessageCount", "0");
        DEFAULTS.put("session.ignoredHwnds", "");
        DEFAULTS.put("session.resumeAfterStop", "false");
        DEFAULTS.put("marker.input_area.dy_offset", "0");
        DEFAULTS.put("config.migrated.v2", "no");
        DEFAULTS.put("config.migrated.v3", "no");
        DEFAULTS.put("session.shiftActiveMap", "");;
        DEFAULTS.put("windows.system.scale.percent", "0");
        DEFAULTS.put("windows.scale.min.percent", "100");
        DEFAULTS.put("windows.scale.max.percent", "225");
        DEFAULTS.put("config.migrated.v4", "false");
        DEFAULTS.put("window.growth.step.px", "10");
        DEFAULTS.put("window.size.max.width", "0");
        DEFAULTS.put("window.size.max.height", "0");
        DEFAULTS.put("cdp.enabled", "true");
        DEFAULTS.put("config.migrated.v7", "false");
        DEFAULTS.put("cdp.executor.tabId", "");
        DEFAULTS.put("cdp.reviewer.tabId", "");

    }

    static final List<String> CALIBRATION_KEYS = List.of(
            "marker.regenerate.x", "marker.regenerate.y",
            "marker.regenerate.dx", "marker.regenerate.dy",
            "marker.copy_last.x", "marker.copy_last.y",
            "marker.copy_last.dx", "marker.copy_last.dy",
            "marker.input_field.x", "marker.input_field.y",
            "marker.input_field.dx", "marker.input_field.dy",
            "marker.deepthink.x", "marker.deepthink.y",
            "marker.deepthink.dx", "marker.deepthink.dy",
            "marker.search.x", "marker.search.y",
            "marker.search.dx", "marker.search.dy",
            "marker.retry.x", "marker.retry.y",
            "marker.send.dx", "marker.send.dy",
            "marker.send.x", "marker.send.y",
            "marker.send.active_color", "marker.send.disabled_color",
            "marker.input_area.dy_offset"
    );

    private final Properties props = new Properties();

    public Config() {

        for (Map.Entry<String, String> e : DEFAULTS.entrySet()) {

            props.setProperty(e.getKey(), e.getValue());

        }

        if (Files.exists(FILE)) {

            try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {

                props.load(reader);

                AppLogger.info("Config loaded from " + FILE.toAbsolutePath());

                migrate();

            } catch (IOException e) {

                AppLogger.warn("Config load failed: " + e.getMessage());

            }

        } else {

            AppLogger.info("Config not found, creating default at " + FILE.toAbsolutePath());

            try { save(); } catch (IOException e) {

                AppLogger.warn("Failed to create default config: " + e.getMessage());

            }

        }

    }

    void migrate() {
        boolean changed = false;

        // Schema migration v2: input-area Y coordinates are now stored as normal (post-send) baseline.
        if (!getMigratedV2()) {
            int canonical = -84;
            shiftMarkerY("marker.input_field.y", canonical);
            shiftMarkerY("marker.deepthink.y", canonical);
            shiftMarkerY("marker.search.y", canonical);
            shiftMarkerY("marker.send.y", canonical);
            props.setProperty("config.migrated.v2", "yes");
            AppLogger.info("Config: schema migration v2 applied (input-area dy_offset=" + canonical + ")");
            changed = true;
        }


        // Schema migration v3: correct SEND marker coordinates for the fresh-window input-area shift.

        // The SEND button sits 84 px higher in a fresh chat window. The previous send.dy (402) was

        // measured against the shifted pixel and hit the background. Correct values, derived from the

        // post-send absolute measurement (659,457) with window origin (50,50) and dyOffset -84:

        //   marker.send.dx = 609  (fresh-window X within window; X never shifts)

        //   marker.send.dy = 323  (fresh-window Y within window)

        //   marker.send.x  = 659  (post-send absolute X, informational)

        //   marker.send.y  = 457  (post-send absolute Y; runtime baseline after first Enter)

        if (!getMigratedV3()) {

            props.setProperty("marker.send.dx", "609");

            props.setProperty("marker.send.dy", "323");

            props.setProperty("marker.send.x", "659");

            props.setProperty("marker.send.y", "457");

            props.setProperty("marker.send.active_color", "000000");

            props.setProperty("marker.send.disabled_color", "000000");

            props.setProperty("config.migrated.v3", "yes");

            AppLogger.info("Config: schema migration v3 applied (SEND marker corrected: dx=609, dy=323, y=457)");

            changed = true;

        }


        // Schema migration v4 (Stage S1): reset only calibration keys.
        if (!isMigratedV4()) {
            int reset = 0;
            for (String key : CALIBRATION_KEYS) {
                if (DEFAULTS.containsKey(key)) {
                    props.setProperty(key, DEFAULTS.get(key));
                    reset++;
                }
            }
            props.setProperty("config.migrated.v4", "true");
            AppLogger.info("Config: schema migration v4 applied (" + reset + " calibration keys reset)");
            changed = true;
        }

        // Schema migration v5 (S5): enforce minimum window width 731 px.
        // 731 is required so that the input field stops growing horizontally on resize
        // and the Regenerate button keeps a stable offset from the code-block Copy button.
        // Do NOT lower a user-set larger width; only raise values below 731.
        if (!"true".equalsIgnoreCase(props.getProperty("config.migrated.v5", "false"))) {
            int ww = 0;
            try { ww = Integer.parseInt(props.getProperty("window.work.width", "0").trim()); }
            catch (NumberFormatException ignored) { }
            if (ww < 731) {
                props.setProperty("window.work.width", "731");
            }
            props.setProperty("config.migrated.v5", "true");
            AppLogger.info("Config: schema migration v5 applied (window.work.width -> 731)");
            changed = true;
        }

                // Schema migration v6 (S5 fix 2026-10-15): enlarge window to 731x508 and refresh legacy coordinates.
        if (!"true".equalsIgnoreCase(props.getProperty("config.migrated.v6", "false"))) {
            props.setProperty("window.work.height", "508");
            props.setProperty("legacy.input.fresh.x", "97");
            props.setProperty("legacy.input.fresh.y", "304");
            props.setProperty("legacy.input.cleared.x", "97");
            props.setProperty("legacy.input.cleared.y", "406");
            props.setProperty("legacy.send.fresh.x", "717");
            props.setProperty("legacy.send.fresh.y", "390");
            props.setProperty("legacy.send.cleared.x", "717");
            props.setProperty("legacy.send.cleared.y", "492");
            props.setProperty("legacy.code_copy.x", "606");
            props.setProperty("legacy.code_copy.y", "247");
            props.setProperty("legacy.regenerate.x", "125");
            props.setProperty("legacy.regenerate.y", "343");
            props.setProperty("config.migrated.v6", "true");
            AppLogger.info("Config: schema migration v6 applied (window.work.height -> 508, legacy coords updated)");
            changed = true;
        }
        // Schema migration v7 (S7 MASTER_PLAN): promote cdp.enabled to true for existing profiles.
        if (!isMigratedV7()) {
            String currentCdp = props.getProperty("cdp.enabled", "false");
            if (!"true".equalsIgnoreCase(currentCdp)) {
                props.setProperty("cdp.enabled", "true");
                AppLogger.info("Config: schema migration v7 applied (cdp.enabled -> true by default; re-set to false manually to keep legacy)");
                changed = true;
            }
            props.setProperty("config.migrated.v7", "true");
            changed = true;
        }
java.util.List<String> obsolete = new java.util.ArrayList<>();
        for (Object rawKey : props.keySet()) {
            String key = String.valueOf(rawKey);
            if (!DEFAULTS.containsKey(key)) obsolete.add(key);
        }
        if (!obsolete.isEmpty()) {
            for (String key : obsolete) props.remove(key);
            AppLogger.info("Config: removed obsolete keys (" + obsolete.size() + "): " + obsolete);
            changed = true;
        }

        if (changed) {
            try { save(); }
            catch (IOException e) { AppLogger.warn("Config: failed to persist migration: " + e.getMessage()); }
        }
    }

    public String getWindowTitle() { return get("window.title", "DeepSeek"); }

    public int getInt(String key, int defaultValue) {

        String value = props.getProperty(key);

        if (value == null || value.isBlank()) return defaultValue;

        try { return Integer.parseInt(value.trim()); }

        catch (NumberFormatException e) {

            AppLogger.warn("Invalid int config " + key + ": " + value);

            return defaultValue;

        }

    }

    public long getLong(String key, long defaultValue) {

        String value = props.getProperty(key);

        if (value == null || value.isBlank()) return defaultValue;

        try { return Long.parseLong(value.trim()); }

        catch (NumberFormatException e) {

            AppLogger.warn("Invalid long config " + key + ": " + value);

            return defaultValue;

        }

    }

    public boolean getBoolean(String key, boolean defaultValue) {

        String value = props.getProperty(key);

        if (value == null || value.isBlank()) return defaultValue;

        return Boolean.parseBoolean(value.trim());

    }

    public String get(String key, String defaultValue) { return props.getProperty(key, defaultValue); }

    public void set(String key, String value) { props.setProperty(key, value); }

    public boolean isCalibrated() { return getBoolean("calibration.done", false); }

    public void setCalibrated(boolean value) { props.setProperty("calibration.done", String.valueOf(value)); }

    public void saveMarker(String markerKey, int x, int y, int rgb) {

        props.setProperty("marker." + markerKey + ".x", String.valueOf(x));

        props.setProperty("marker." + markerKey + ".y", String.valueOf(y));

        props.setProperty("marker." + markerKey + ".color", ColorUtils.toHex(rgb));

    }

    public void save() throws IOException {

        Path parent = FILE.getParent();

        if (parent != null) Files.createDirectories(parent);

        try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {

            props.store(writer, "Deepthic config");

        }

    }


    public long getScriptTimeoutSeconds() {
        return getLong("watchdog.script.timeout.seconds", 1800L);
    }

    public long getScriptCheckIntervalMs() {
        return getLong("watchdog.script.check.interval.ms", 1000L);
    }

        public long getScriptWarnThresholdSeconds() {

        return getLong("watchdog.script.warn.threshold.seconds", 1200L);

    }



    public long getLastManagedHwnd() {

        return getLong("session.lastManagedHwnd", 0L);

    }



    public String getLastManagedTitle() {

        return get("session.lastManagedTitle", "");

    }



    public String getLastPhase() {

        return get("session.lastPhase", "EXECUTOR");

    }



    public long getLastReviewerHwnd() {

        return getLong("session.lastReviewerHwnd", 0L);

    }



    public String getLastReviewerTitle() {

        return get("session.lastReviewerTitle", "");

    }



    public void setLastManagedHwnd(long hwnd) {

        props.setProperty("session.lastManagedHwnd", String.valueOf(hwnd));

        persist();

    }



    public void setLastManagedTitle(String title) {

        props.setProperty("session.lastManagedTitle", title == null ? "" : title);

        persist();

    }



    public void setLastPhase(String phase) {

        props.setProperty("session.lastPhase", phase == null ? "EXECUTOR" : phase);

        persist();

    }



    public void setLastReviewerHwnd(long hwnd) {

        props.setProperty("session.lastReviewerHwnd", String.valueOf(hwnd));

        persist();

    }



    public void setLastReviewerTitle(String title) {

        props.setProperty("session.lastReviewerTitle", title == null ? "" : title);

        persist();

    }



    public void persist() {

        try {

            save();

        } catch (IOException e) {

            AppLogger.warn("Config persist failed: " + e.getMessage());

        }

    }


    public long getExecutorMessageCount() {
        long value = getLong("session.executorMessageCount", 0L);
        return value < 0 ? 0L : value;
    }

    public List<Long> getIgnoredHwnds() {
        String raw = get("session.ignoredHwnds", "");
        List<Long> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                long v = Long.parseLong(trimmed);
                if (v > 0L) result.add(v);
            } catch (NumberFormatException e) {
                // skip non-numeric tokens
            }
        }
        return result;
    }

    public void setExecutorMessageCount(long count) {
        props.setProperty("session.executorMessageCount", String.valueOf(count < 0 ? 0L : count));
        persist();
    }

    public void setIgnoredHwnds(List<Long> hwnds) {
        List<Long> sanitized = new ArrayList<>();
        if (hwnds != null) {
            for (Long h : hwnds) {
                if (h != null && h > 0L) sanitized.add(h);
            }
        }
        int size = sanitized.size();
        if (size > 20) {
            sanitized = new ArrayList<>(sanitized.subList(size - 20, size));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sanitized.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(sanitized.get(i));
        }
        props.setProperty("session.ignoredHwnds", sb.toString());
        persist();
    }

    public boolean getResumeAfterStop() {
        return getBoolean("session.resumeAfterStop", false);
    }

    public void setResumeAfterStop(boolean value) {
        props.setProperty("session.resumeAfterStop", String.valueOf(value));
        persist();
    }

    @Deprecated
    public int getInputAreaDyOffset() {
        String raw = get("marker.input_area.dy_offset", "-84");
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { return -84; }
    }

    
    public boolean getShiftActiveForHwnd(long hwndValue) {

        if (hwndValue == 0L) return true;

        String raw = get("session.shiftActiveMap", "");

        if (raw == null || raw.isBlank()) return true;

        for (String entry : raw.split(";")) {

            int idx = entry.indexOf(':');

            if (idx <= 0) continue;

            try {

                long h = Long.parseLong(entry.substring(0, idx).trim());

                if (h == hwndValue) return "1".equals(entry.substring(idx + 1).trim());

            } catch (NumberFormatException ignored) { }

        }

        return true;

    }



    public void setShiftActiveForHwnd(long hwndValue, boolean value) {

        if (hwndValue == 0L) return;

        java.util.LinkedHashMap<Long, String> map = new java.util.LinkedHashMap<>();

        String existing = get("session.shiftActiveMap", "");

        if (existing != null && !existing.isBlank()) {

            for (String entry : existing.split(";")) {

                int idx = entry.indexOf(':');

                if (idx <= 0) continue;

                try {

                    long h = Long.parseLong(entry.substring(0, idx).trim());

                    map.put(h, entry.substring(idx + 1).trim());

                } catch (NumberFormatException ignored) { }

            }

        }

        map.remove(hwndValue);

        map.put(hwndValue, value ? "1" : "0");

        while (map.size() > 20) {

            java.util.Iterator<Long> it = map.keySet().iterator();

            it.next();

            it.remove();

        }

        StringBuilder sb = new StringBuilder();

        for (java.util.Map.Entry<Long, String> e : map.entrySet()) {

            if (sb.length() > 0) sb.append(';');

            sb.append(e.getKey()).append(':').append(e.getValue());

        }

        props.setProperty("session.shiftActiveMap", sb.toString());

        persist();

    }


    public boolean getMigratedV2() {
        return "yes".equalsIgnoreCase(get("config.migrated.v2", "no"));
    }

    public boolean getMigratedV3() {

        return "yes".equalsIgnoreCase(get("config.migrated.v3", "no"));

    }


    public int getSystemScalePercent() {
        return getInt("windows.system.scale.percent", 0);
    }

    public void setSystemScalePercent(int percent) {
        if (percent == 0) {
            props.setProperty("windows.system.scale.percent", "0");
            persist();
            return;
        }
        int min = getScaleMinPercent();
        int max = getScaleMaxPercent();
        if (percent < min || percent > max) {
            AppLogger.warn("Config: scale percent " + percent + " out of range [" + min + "," + max + "]");
            return;
        }
        props.setProperty("windows.system.scale.percent", String.valueOf(percent));
        persist();
    }

    public int getScaleMinPercent() {
        return getInt("windows.scale.min.percent", 100);
    }

    public void setScaleMinPercent(int percent) {
        int max = getScaleMaxPercent();
        if (percent >= max) {
            AppLogger.warn("Config: scale.min.percent must be < max");
            return;
        }
        props.setProperty("windows.scale.min.percent", String.valueOf(percent));
        persist();
    }

    public int getScaleMaxPercent() {
        return getInt("windows.scale.max.percent", 225);
    }

    public void setScaleMaxPercent(int percent) {
        int min = getScaleMinPercent();
        if (percent <= min) {
            AppLogger.warn("Config: scale.max.percent must be > min");
            return;
        }
        props.setProperty("windows.scale.max.percent", String.valueOf(percent));
        persist();
    }

    public boolean isMigratedV4() {
        return "true".equalsIgnoreCase(get("config.migrated.v4", "false"));
    }

    public void setMigratedV4(boolean value) {
        props.setProperty("config.migrated.v4", String.valueOf(value));
    }

    public boolean isMigratedV7() {
        return "true".equalsIgnoreCase(get("config.migrated.v7", "false"));
    }

    public void setMigratedV7(boolean value) {
        props.setProperty("config.migrated.v7", String.valueOf(value));
    }

    public int getWindowGrowthStepPx() {
        int value = getInt("window.growth.step.px", 10);
        return value >= 1 ? value : 10;
    }

    public void setWindowGrowthStepPx(int stepPx) {
        if (stepPx < 1) {
            AppLogger.warn("Config: window.growth.step.px must be >= 1, ignoring " + stepPx);
            return;
        }
        props.setProperty("window.growth.step.px", String.valueOf(stepPx));
        persist();
    }

    public int getWindowSizeMaxWidth() {
        int value = getInt("window.size.max.width", 0);
        return value >= 0 ? value : 0;
    }

    public int getRegenerateXOffsetFromCodeCopy() {
        int value = getInt("marker.regenerate.x_offset_from_code_copy", 481);
        return value > 0 ? value : 481;
    }

    public void setWindowSizeMaxWidth(int width) {
        if (width < 0) {
            AppLogger.warn("Config: window.size.max.width must be >= 0, ignoring " + width);
            return;
        }
        props.setProperty("window.size.max.width", String.valueOf(width));
        persist();
    }

    public int getWindowSizeMaxHeight() {
        int value = getInt("window.size.max.height", 0);
        return value >= 0 ? value : 0;
    }

    public boolean isCdpEnabled() {
        return getBoolean("cdp.enabled", false);
    }

    public String getCdpExecutorTabId() { return get("cdp.executor.tabId", ""); }
    public void setCdpExecutorTabId(String tabId) { props.setProperty("cdp.executor.tabId", tabId == null ? "" : tabId); persist(); }
    public String getCdpReviewerTabId() { return get("cdp.reviewer.tabId", ""); }
    public void setCdpReviewerTabId(String tabId) { props.setProperty("cdp.reviewer.tabId", tabId == null ? "" : tabId); persist(); }

    public void setWindowSizeMaxHeight(int height) {
        if (height < 0) {
            AppLogger.warn("Config: window.size.max.height must be >= 0, ignoring " + height);
            return;
        }
        props.setProperty("window.size.max.height", String.valueOf(height));
        persist();
    }

    private void shiftMarkerY(String key, int dyOffset) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v > 0) props.setProperty(key, String.valueOf(v - dyOffset));
        } catch (NumberFormatException ignored) { }
    }
}

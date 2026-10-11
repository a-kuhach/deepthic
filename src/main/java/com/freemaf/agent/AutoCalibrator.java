package com.freemaf.agent;

import java.io.IOException;

import java.util.LinkedHashMap;

import java.util.Map;

public final class AutoCalibrator {

    private static final String[] STATIC_MARKERS = {"input_field", "deepthink", "search"};

    private static final String[] DYNAMIC_MARKERS = {"regenerate", "copy_last"};

    private AutoCalibrator() {}

    public static CalibrationResult calibrateStatic(Config config) throws IOException {

        return calibrate(config, STATIC_MARKERS, true);

    }

    public static CalibrationResult calibrateDynamic(Config config) throws IOException {

        return calibrate(config, DYNAMIC_MARKERS, false);

    }

    private static CalibrationResult calibrate(Config config, String[] markers, boolean applyShift) throws IOException {

        int winX = config.getInt("window.work.x", 50);

        int winY = config.getInt("window.work.y", 50);

        PixelColorService pixels = new PixelColorService();

        Map<String, String> result = new LinkedHashMap<>();

        for (String marker : markers) {

            int dx = config.getInt("marker." + marker + ".dx", -1);

            int dy = config.getInt("marker." + marker + ".dy", -1);

            if (dx < 0 || dy < 0) {

                AppLogger.warn("AutoCalibrator: skipping " + marker + " (dx/dy not configured)");

                continue;

            }

            int absX = winX + dx;

            int readY = winY + dy;

            int rgb = pixels.readPixel(absX, readY);

            String hex = ColorUtils.toHex(rgb);

            int storedY = applyShift ? (readY - config.getInputAreaDyOffset()) : readY;

            config.saveMarker(marker, absX, storedY, rgb);

            result.put(marker, hex);

            AppLogger.info("AutoCalibrator: " + marker + " (" + absX + "," + readY + ") = #" + hex);

        }

        config.save();

        return new CalibrationResult(result);

    }

    public static CalibrationResult calibrateSendDisabled(Config config) throws IOException {

        return calibrateSendColor(config, "disabled_color");

    }

    public static CalibrationResult calibrateSendActive(Config config) throws IOException {

        return calibrateSendActiveByPolling(config);

    }

    private static CalibrationResult calibrateSendActiveByPolling(Config config) throws IOException {

        int winX = config.getInt("window.work.x", 50);

        int winY = config.getInt("window.work.y", 50);

        int dx = config.getInt("marker.send.dx", -1);

        int dy = config.getInt("marker.send.dy", -1);

        if (dx < 0 || dy < 0) {

            throw new IOException("marker.send.dx/dy not configured");

        }

        int absX = winX + dx;

        int readY = winY + dy;

        PixelColorService pixels = new PixelColorService();

        String disabled = config.get("marker.send.disabled_color", "");

        long deadline = System.currentTimeMillis() + 3000L;

        int observedRgb = pixels.readPixel(absX, readY);

        String observedHex = ColorUtils.toHex(observedRgb);

        while (System.currentTimeMillis() < deadline) {

            if (disabled != null && !disabled.isBlank() && !observedHex.equalsIgnoreCase(disabled.trim())) {

                break;

            }

            try { Thread.sleep(50L); }

            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("calibrateSendActiveByPolling interrupted"); }

            observedRgb = pixels.readPixel(absX, readY);

            observedHex = ColorUtils.toHex(observedRgb);

        }

        if (disabled != null && !disabled.isBlank() && observedHex.equalsIgnoreCase(disabled.trim())) {

            AppLogger.warn("AutoCalibrator: send.active_color did not differ from disabled_color after 3000 ms; check marker.send.dx/dy - the pixel may not hit the SEND button visual");

        }

        int storedY = readY - config.getInputAreaDyOffset();

        config.set("marker.send.x", String.valueOf(absX));

        config.set("marker.send.y", String.valueOf(storedY));

        config.set("marker.send.active_color", observedHex);

        config.save();

        Map<String, String> result = new LinkedHashMap<>();

        result.put("send.active_color", observedHex);

        AppLogger.info("AutoCalibrator: send.active_color (" + absX + "," + readY + ") = #" + observedHex);

        return new CalibrationResult(result);

    }

    private static CalibrationResult calibrateSendColor(Config config, String colorKey) throws IOException {

        int winX = config.getInt("window.work.x", 50);

        int winY = config.getInt("window.work.y", 50);

        int dx = config.getInt("marker.send.dx", -1);

        int dy = config.getInt("marker.send.dy", -1);

        if (dx < 0 || dy < 0) {

            throw new IOException("marker.send.dx/dy not configured");

        }

        PixelColorService pixels = new PixelColorService();

        int absX = winX + dx;

        int readY = winY + dy;

        int rgb = pixels.readPixel(absX, readY);

        String hex = ColorUtils.toHex(rgb);

        int storedY = readY - config.getInputAreaDyOffset();

        config.set("marker.send.x", String.valueOf(absX));

        config.set("marker.send.y", String.valueOf(storedY));

        config.set("marker.send." + colorKey, hex);

        config.save();

        Map<String, String> result = new LinkedHashMap<>();

        result.put("send." + colorKey, hex);

        AppLogger.info("AutoCalibrator: send." + colorKey + " (" + absX + "," + readY + ") = #" + hex);

        return new CalibrationResult(result);

    }

    public record CalibrationResult(Map<String, String> colors) {

        public String summary() {

            StringBuilder sb = new StringBuilder();

            boolean first = true;

            for (Map.Entry<String, String> e : colors.entrySet()) {

                if (!first) sb.append(System.lineSeparator());

                sb.append("  ").append(e.getKey()).append(" = #").append(e.getValue());

                first = false;

            }

            return sb.toString();

        }

    }

}

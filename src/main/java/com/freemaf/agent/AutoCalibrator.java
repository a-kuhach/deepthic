package com.freemaf.agent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AutoCalibrator {

    private static final String[] STATIC_MARKERS = {"input_field", "deepthink", "search"};

    private static final String[] DYNAMIC_MARKERS = {"regenerate", "copy_last"};

    private AutoCalibrator() {}

    public static CalibrationResult calibrate(WindowInfo window, WindowManager wm) throws IOException {
        return calibrate(window, wm, new PixelColorService()::readPixel);
    }

    public static CalibrationResult calibrate(WindowInfo window, WindowManager wm, PixelReader reader) throws IOException {
        if (window == null) throw new IllegalArgumentException("window is null");
        if (reader == null) throw new IllegalArgumentException("reader is null");
        Map<CalibrationMarker, MarkerHit> hits = new LinkedHashMap<>();
        Map<String, String> colors = new LinkedHashMap<>();
        for (CalibrationMarker marker : CalibrationMarker.values()) {
            MarkerSpec spec;
            try {
                spec = MarkerSpec.forType(marker);
            } catch (IllegalArgumentException e) {
                AppLogger.warn("AutoCalibrator: no MarkerSpec for " + marker.id() + " - skipped");
                continue;
            }
            MarkerHit hit = search(window, spec, reader);
            if (hit == null) {
                AppLogger.warn("AutoCalibrator: color-search found no match for " + marker.id());
                continue;
            }
            hits.put(marker, hit);
            colors.put(marker.id(), hit.observedColorHex());
            AppLogger.info("AutoCalibrator: " + marker.id() + " (" + hit.x() + "," + hit.y() + ") = #" + hit.observedColorHex()
                    + (hit.disabledObserved() ? " [disabled]" : ""));
        }
        return new CalibrationResult(colors, hits);
    }

    private static MarkerHit search(WindowInfo window, MarkerSpec spec, PixelReader reader) {
        MarkerSpec.SearchArea area = spec.area();
        int wl = window.left();
        int wt = window.top();
        int wr = window.right();
        int wb = window.bottom();
        int ww = Math.max(1, wr - wl);
        int wh = Math.max(1, wb - wt);
        int x0 = wl + (int) Math.floor(area.leftRatio() * ww);
        int y0 = wt + (int) Math.floor(area.topRatio() * wh);
        int x1 = wl + (int) Math.ceil(area.rightRatio() * ww);
        int y1 = wt + (int) Math.ceil(area.bottomRatio() * wh);
        x0 = Math.max(wl, x0);
        y0 = Math.max(wt, y0);
        x1 = Math.min(wr, x1);
        y1 = Math.min(wb, y1);
        if (x1 <= x0 || y1 <= y0) return null;

        long ax = 0L, ay = 0L;
        int ac = 0;
        String aHex = null;
        long dxc = 0L, dyc = 0L;
        int dc = 0;
        String dHex = null;

        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = reader.readPixel(x, y);
                String hex = ColorUtils.toHex(rgb);
                if (matchesAny(hex, spec.activeColorsHex())) {
                    ax += x; ay += y; ac++;
                    if (aHex == null) aHex = hex.toUpperCase();
                } else if (matchesAny(hex, spec.disabledColorsHex())) {
                    dxc += x; dyc += y; dc++;
                    if (dHex == null) dHex = hex.toUpperCase();
                }
            }
        }

        if (ac > 0) return new MarkerHit((int) (ax / ac), (int) (ay / ac), aHex, false);
        if (dc > 0) return new MarkerHit((int) (dxc / dc), (int) (dyc / dc), dHex, true);
        return null;
    }

    private static boolean matchesAny(String hex, List<String> colors) {
        if (colors == null || colors.isEmpty()) return false;
        for (String c : colors) {
            if (c != null && !c.isBlank() && c.equalsIgnoreCase(hex.trim())) return true;
        }
        return false;
    }

    @Deprecated
    public static CalibrationResult calibrateStatic(Config config) throws IOException {
        return calibrate(config, STATIC_MARKERS, true);
    }

    @Deprecated
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

    @Deprecated
    public static CalibrationResult calibrateSendDisabled(Config config) throws IOException {
        return calibrateSendColor(config, "disabled_color");
    }

    @Deprecated
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

    public static Optional<MarkerHit> findCodeCopy(WindowInfo window, WindowManager wm, PixelReader reader) {
        if (window == null) throw new IllegalArgumentException("window is null");
        if (reader == null) throw new IllegalArgumentException("reader is null");
        MarkerSpec spec = MarkerSpec.forType(CalibrationMarker.CODE_COPY);
        return Optional.ofNullable(search(window, spec, reader));
    }

    public record MarkerHit(int x, int y, String observedColorHex, boolean disabledObserved) { }

    public static final class CalibrationResult {

        public static final List<CalibrationMarker> REQUIRED_FOR_CALIBRATION = List.of(
                CalibrationMarker.INPUT_FIELD,
                CalibrationMarker.DEEPTHINK,
                CalibrationMarker.SEARCH,
                CalibrationMarker.SEND
        );

        private final Map<String, String> colors;
        private final Map<CalibrationMarker, MarkerHit> hits;
        private final MarkerHit codeCopyHit;

        public CalibrationResult(Map<String, String> colors) {
            this(colors, Map.of(), null);
        }

        public CalibrationResult(Map<String, String> colors, Map<CalibrationMarker, MarkerHit> hits) {
            this(colors, hits, null);
        }

        public CalibrationResult(Map<String, String> colors, Map<CalibrationMarker, MarkerHit> hits, MarkerHit codeCopyHit) {
            this.colors = colors == null ? Map.of() : Map.copyOf(colors);
            this.hits = hits == null ? Map.of() : Map.copyOf(hits);
            this.codeCopyHit = codeCopyHit;
        }

        public Map<String, String> colors() { return colors; }

        public Map<CalibrationMarker, MarkerHit> hits() { return hits; }

        public Optional<MarkerHit> hit(CalibrationMarker marker) {
            if (marker == null) return Optional.empty();
            return Optional.ofNullable(hits.get(marker));
        }

        public boolean found(CalibrationMarker marker) {
            return marker != null && hits.containsKey(marker);
        }

        public List<CalibrationMarker> missing() {
            List<CalibrationMarker> result = new java.util.ArrayList<>();
            for (CalibrationMarker marker : REQUIRED_FOR_CALIBRATION) {
                if (!hits.containsKey(marker)) result.add(marker);
            }
            return List.copyOf(result);
        }

        public boolean allRequiredFound() {
            for (CalibrationMarker marker : REQUIRED_FOR_CALIBRATION) {
                if (!hits.containsKey(marker)) return false;
            }
            return true;
        }

        public MarkerHit codeCopyHit() { return codeCopyHit; }

        public CalibrationResult withCodeCopy(MarkerHit hit) {
            return new CalibrationResult(this.colors, this.hits, hit);
        }

        public boolean allFoundAndVisible() {
            return allRequiredFound() && codeCopyHit != null;
        }

        public List<CalibrationMarker> invisibleMarkers() {
            return List.of();
        }

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

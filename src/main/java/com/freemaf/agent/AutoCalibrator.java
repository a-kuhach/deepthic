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
        return calibrate(window, wm, new PixelColorService());
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

        final int step = spec.step() > 0 ? spec.step() : 4;
        final int rw = x1 - x0;
        final int rh = y1 - y0;
        AppLogger.info("AutoCalibrator.search[" + spec.type().id() + "]: start area=" + rw + "x" + rh + " step=" + step);
        long tStartNs = System.nanoTime();

        int[] pixels;
        try {
            pixels = reader.readRegion(x0, y0, rw, rh);
        } catch (RuntimeException e) {
            AppLogger.info("AutoCalibrator.search[" + spec.type().id() + "]: readRegion failed: " + e.getMessage());
            return null;
        }
        long tRegionMs = (System.nanoTime() - tStartNs) / 1_000_000L;
        AppLogger.info("AutoCalibrator.search[" + spec.type().id() + "]: readRegion done in " + tRegionMs
                + " ms, pixels=" + (pixels == null ? "null" : pixels.length));
        if (pixels == null || pixels.length < rw * rh) {
            AppLogger.info("AutoCalibrator.search[" + spec.type().id() + "]: readRegion returned short buffer");
            return null;
        }

        int scanned = 0;
        long tScanStart = System.nanoTime();

        // TOPMOST_LEFT / FIRST_MATCH: return the first pixel hit in a top-down, left-right scan.
        // No mass-centering, no full-region aggregation - early exit on match.
        if (spec.rule() == MarkerSpec.SelectionRule.TOPMOST_LEFT
                || spec.rule() == MarkerSpec.SelectionRule.FIRST_MATCH) {
            int blackCount = 0;
            java.util.Map<String, Integer> freqFm = new java.util.HashMap<>();
            for (int yy = 0; yy < rh; yy += step) {
                int rowBase = yy * rw;
                int screenY = y0 + yy;
                for (int xx = 0; xx < rw; xx += step) {
                    int rgb = pixels[rowBase + xx];
                    if (rgb == -1) continue;
                    scanned++;
                    String hex = ColorUtils.toHex(rgb);
                    String hexU = hex.toUpperCase();
                    freqFm.merge(hexU, 1, Integer::sum);
                    if ("000000".equals(hexU)) blackCount++;
                    boolean activeMatch;
                    boolean disabledMatch;
                    if (spec.dynamicLightGray()) {
                        activeMatch = isLightGrayIcon(hex);
                        disabledMatch = false;
                    } else {
                        activeMatch = matchesAny(hex, spec.activeColorsHex(), spec.tolerance());
                        disabledMatch = matchesAny(hex, spec.disabledColorsHex(), spec.tolerance());
                    }
                    if (activeMatch) {
                        long ms = (System.nanoTime() - tStartNs) / 1_000_000L;
                        AppLogger.info("AutoCalibrator.search[" + spec.type().id() + "]: FIRST_MATCH ("
                                + (x0 + xx) + "," + screenY + ") #" + hexU
                                + " in " + ms + " ms (scanned=" + scanned + ", rule=" + spec.rule()
                                + (spec.dynamicLightGray() ? ", dynamic" : "") + ")");
                        return new MarkerHit(x0 + xx, screenY, hexU, false);
                    }
                    if (disabledMatch) {
                        long ms = (System.nanoTime() - tStartNs) / 1_000_000L;
                        AppLogger.info("AutoCalibrator.search[" + spec.type().id() + "]: FIRST_MATCH disabled ("
                                + (x0 + xx) + "," + screenY + ") #" + hexU
                                + " in " + ms + " ms (scanned=" + scanned + ", rule=" + spec.rule() + ")");
                        return new MarkerHit(x0 + xx, screenY, hexU, true);
                    }
                }
            }
            int unique = freqFm.size();
            double blackRatio = scanned == 0 ? 0.0 : (double) blackCount / scanned;
            java.util.List<java.util.Map.Entry<String, Integer>> topFm = new java.util.ArrayList<>(freqFm.entrySet());
            topFm.sort((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()));
            StringBuilder tnFm = new StringBuilder();
            for (int i = 0; i < Math.min(15, topFm.size()); i++) {
                if (i > 0) tnFm.append(", ");
                tnFm.append("#").append(topFm.get(i).getKey()).append(" x").append(topFm.get(i).getValue());
            }
            AppLogger.info("AutoCalibrator.search[" + spec.type().id() + "]: NO MATCH (rule=" + spec.rule()
                    + ", area=" + rw + "x" + rh + ", scanned=" + scanned
                    + ", unique=" + unique
                    + ", blackCount=" + blackCount
                    + ", blackRatio=" + String.format(java.util.Locale.ROOT, "%.3f", blackRatio)
                    + ", top15=[" + tnFm + "]"
                    + ", expectedActive=" + spec.activeColorsHex()
                    + ", expectedDisabled=" + spec.disabledColorsHex() + ")");
            return null;
        }

        // CENTER_MATCH: mass-centre of every matching pixel in the region.
        long ax = 0L, ay = 0L;
        int ac = 0;
        String aHex = null;
        long dxc = 0L, dyc = 0L;
        int dc = 0;
        String dHex = null;
        java.util.Map<String, Integer> freq = new java.util.HashMap<>();

        for (int yy = 0; yy < rh; yy += step) {
            int rowBase = yy * rw;
            int screenY = y0 + yy;
            for (int xx = 0; xx < rw; xx += step) {
                int rgb = pixels[rowBase + xx];
                if (rgb == -1) continue;
                scanned++;
                String hex = ColorUtils.toHex(rgb);
                freq.merge(hex.toUpperCase(), 1, Integer::sum);
                if (matchesAny(hex, spec.activeColorsHex(), spec.tolerance())) {
                    ax += (x0 + xx); ay += screenY; ac++;
                    if (aHex == null) aHex = hex.toUpperCase();
                } else if (matchesAny(hex, spec.disabledColorsHex(), spec.tolerance())) {
                    dxc += (x0 + xx); dyc += screenY; dc++;
                    if (dHex == null) dHex = hex.toUpperCase();
                }
            }
        }

        long elapsedMs = (System.nanoTime() - tScanStart) / 1_000_000L;
        AppLogger.info("AutoCalibrator.search[" + spec.type().id() + "]: CENTER_MATCH scanned " + scanned
                + " px (step=" + step + ") in " + elapsedMs + " ms; active=" + ac + " disabled=" + dc);
        if (ac > 0) return new MarkerHit((int) (ax / ac), (int) (ay / ac), aHex, false);
        if (dc > 0) return new MarkerHit((int) (dxc / dc), (int) (dyc / dc), dHex, true);
        java.util.List<java.util.Map.Entry<String, Integer>> top = new java.util.ArrayList<>(freq.entrySet());
        top.sort((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()));
        StringBuilder tn = new StringBuilder();
        for (int i = 0; i < Math.min(15, top.size()); i++) {
            if (i > 0) tn.append(", ");
            tn.append("#").append(top.get(i).getKey()).append(" x").append(top.get(i).getValue());
        }
        AppLogger.info("AutoCalibrator.search[" + spec.type().id() + "]: NO MATCH. area="
                + rw + "x" + rh + " unique=" + freq.size()
                + " top15=[" + tn + "] expectedActive=" + spec.activeColorsHex()
                + " expectedDisabled=" + spec.disabledColorsHex());
        return null;
    }
    private static boolean isLightGrayIcon(String hex) {
        if (hex == null || hex.length() != 6) return false;
        try {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            int mn = Math.min(r, Math.min(g, b));
            int mx = Math.max(r, Math.max(g, b));
            if (mn < 190 || mx > 215) return false;
            return (mx - mn) <= 12;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean matchesAny(String hex, List<String> colors) {
        return matchesAny(hex, colors, 0);
    }

    private static boolean matchesAny(String hex, List<String> colors, int tolerance) {
        if (colors == null || colors.isEmpty()) return false;
        for (String c : colors) {
            if (matchesHexWithTolerance(hex, c, tolerance)) return true;
        }
        return false;
    }

    private static boolean matchesHexWithTolerance(String observed, String expected, int tolerance) {
        if (observed == null || expected == null) return false;
        String o = observed.trim();
        String e = expected.trim();
        if (o.isEmpty() || e.isEmpty()) return false;
        if (tolerance <= 0 || o.length() != 6 || e.length() != 6) return o.equalsIgnoreCase(e);
        try {
            int or_ = Integer.parseInt(o.substring(0, 2), 16);
            int og  = Integer.parseInt(o.substring(2, 4), 16);
            int ob  = Integer.parseInt(o.substring(4, 6), 16);
            int er  = Integer.parseInt(e.substring(0, 2), 16);
            int eg  = Integer.parseInt(e.substring(2, 4), 16);
            int eb  = Integer.parseInt(e.substring(4, 6), 16);
            int dR = Math.abs(or_ - er);
            int dG = Math.abs(og - eg);
            int dB = Math.abs(ob - eb);
            return (dR + dG + dB) <= tolerance;
        } catch (NumberFormatException ex) {
            return o.equalsIgnoreCase(e);
        }
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
        MarkerHit hit = search(window, spec, reader);
        if (hit != null) {
            AppLogger.info("findCodeCopy: hit " + hit.x() + "," + hit.y() + " #" + hit.observedColorHex()
                    + " (anchor=#C9CBD0 tol=" + spec.tolerance() + ")");
        }
        return Optional.ofNullable(hit);
    }

    private static final String REGENERATE_ANCHOR_HEX = "9A9FA4";

    /**
     * Locates the Regenerate icon in a single vertical column at absolute X = xR.
     * Scans top-down between yTop = 0.40*H and yBottom = 0.95*H (absolute).
     * FIRST_MATCH semantics; returns null if the anchor colour is absent.
     * Colour #9A9FA4 was captured on the 731x473 DeepSeek window at scale 100%.
     */
    public static MarkerHit findRegenerate(WindowInfo window, int xR, PixelReader reader) {
        if (window == null) throw new IllegalArgumentException("window is null");
        if (reader == null) throw new IllegalArgumentException("reader is null");
        if (xR <= 0) throw new IllegalArgumentException("xR must be > 0");
        int h = window.bottom() - window.top();
        int yStartAbs = window.top() + (int) Math.floor(0.40 * h);
        int yEndAbs = window.top() + (int) Math.ceil(0.95 * h);
        if (yEndAbs <= yStartAbs) return null;
        final int xBand = 8;
        int xStart = Math.max(1, xR - xBand);
        int xEnd = xR + xBand + 1;
        AppLogger.info("AutoCalibrator.findRegenerate: xR=" + xR
                + " xRange=[" + xStart + ".." + xEnd + ")"
                + " y=[" + yStartAbs + ".." + yEndAbs + ") anchor=#" + REGENERATE_ANCHOR_HEX);
        long t0 = System.nanoTime();
        int scanned = 0;
        for (int x = xStart; x < xEnd; x++) {
            for (int y = yStartAbs; y < yEndAbs; y++) {
                int rgb;
                try { rgb = reader.readPixel(x, y); }
                catch (RuntimeException e) {
                    AppLogger.warn("findRegenerate: readPixel failed at " + x + "," + y + ": " + e.getMessage());
                    continue;
                }
                if (rgb == -1) continue;
                scanned++;
                String hex = ColorUtils.toHex(rgb);
                if (REGENERATE_ANCHOR_HEX.equalsIgnoreCase(hex)) {
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    AppLogger.info("AutoCalibrator.findRegenerate: FOUND (" + x + "," + y + ") #" + hex
                            + " in " + ms + " ms (scanned=" + scanned + ")");
                    return new MarkerHit(x, y, hex.toUpperCase(), false);
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        AppLogger.info("AutoCalibrator.findRegenerate: NOT FOUND xR=" + xR
                + " scanned=" + scanned + " in " + ms + " ms");
        return null;
    }

    public static MarkerHit findRetry(WindowInfo window, PixelReader reader) {
        if (window == null) throw new IllegalArgumentException("window is null");
        if (reader == null) throw new IllegalArgumentException("reader is null");
        return search(window, MarkerSpec.forType(CalibrationMarker.RETRY), reader);
    }

    public static MarkerHit findInputField(WindowInfo window, PixelReader reader) {
        if (window == null) throw new IllegalArgumentException("window is null");
        if (reader == null) throw new IllegalArgumentException("reader is null");
        return search(window, MarkerSpec.forType(CalibrationMarker.INPUT_FIELD), reader);
    }

    public static MarkerHit findSend(WindowInfo window, PixelReader reader) {
        if (window == null) throw new IllegalArgumentException("window is null");
        if (reader == null) throw new IllegalArgumentException("reader is null");
        return search(window, MarkerSpec.forType(CalibrationMarker.SEND), reader);
    }

    public record MarkerHit(int x, int y, String observedColorHex, boolean disabledObserved) { }

    public static final class CalibrationResult {

        public static final List<CalibrationMarker> REQUIRED_FOR_CALIBRATION = List.of(
                CalibrationMarker.INPUT_FIELD,
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

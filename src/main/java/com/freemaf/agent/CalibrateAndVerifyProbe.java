package com.freemaf.agent;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * SizingProbe, выполняющий внутри одной итерации роста окна:
 * calibrate -> scrollToBottom -> findCodeCopy -> allFoundAndVisible.
 * Payload ProbeResult всегда имеет тип AutoCalibrator.CalibrationResult;
 * потребитель (S5) выполняет безопасное приведение instanceof.
 */
public final class CalibrateAndVerifyProbe implements SizingProbe {

    private final WindowManager wm;
    private final PixelReader reader;
    private final ChatScroller scroller;

    private volatile AutoCalibrator.CalibrationResult lastSuccessfulResult;

    public CalibrateAndVerifyProbe(WindowManager wm, PixelReader reader, ChatScroller scroller) {
        if (reader == null) throw new IllegalArgumentException("reader is null");
        if (scroller == null) throw new IllegalArgumentException("scroller is null");
        this.wm = wm;
        this.reader = reader;
        this.scroller = scroller;
    }

    public AutoCalibrator.CalibrationResult lastSuccessfulResult() { return lastSuccessfulResult; }

    @Override
    public ProbeResult probe(WindowInfo current) {
        AutoCalibrator.CalibrationResult base;
        try {
            base = AutoCalibrator.calibrate(current, wm, reader);
        } catch (IOException e) {
            return new ProbeResult(false, null, "calibrate failed: " + e.getMessage());
        } catch (RuntimeException e) {
            return new ProbeResult(false, null, "calibrate runtime error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try { scroller.scrollToBottom(current); }
        catch (RuntimeException e) { AppLogger.warn("probe: scrollToBottom failed: " + e.getMessage()); }
        Optional<AutoCalibrator.MarkerHit> copy;
        try { copy = AutoCalibrator.findCodeCopy(current, wm, reader); }
        catch (RuntimeException e) {
            return new ProbeResult(false, base, "findCodeCopy runtime error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        AutoCalibrator.CalibrationResult full = copy.map(base::withCodeCopy).orElse(base);
        boolean success = full.allFoundAndVisible();
        if (success) {
            lastSuccessfulResult = full;
        }
        String diagnostic = success ? "" : buildDiagnostic(full);
        return new ProbeResult(success, full, diagnostic);
    }

    private static String buildDiagnostic(AutoCalibrator.CalibrationResult r) {
        StringBuilder sb = new StringBuilder();
        List<CalibrationMarker> missing = r.missing();
        if (!missing.isEmpty()) {
            sb.append("missing required: ");
            boolean first = true;
            for (CalibrationMarker m : missing) {
                if (!first) sb.append(", ");
                sb.append(m.id());
                first = false;
            }
        }
        if (r.codeCopyHit() == null) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("code_copy not found");
        }
        if (sb.length() == 0) sb.append("probe failed");
        return sb.toString();
    }
}

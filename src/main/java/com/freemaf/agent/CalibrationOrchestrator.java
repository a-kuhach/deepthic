package com.freemaf.agent;

import com.freemaf.agent.winapi.WinApiService;
import com.sun.jna.Pointer;

/**
 * S5 integration orchestrator.
 * Единственная точка оркестрации sizing+probe: precheck -> WindowSizer.run(CalibrateAndVerifyProbe) ->
 * adopt(CalibrationResult). UI-код (модальные окна, статус) остаётся в вызывающем слое.
 *
 * Outcome.calibration не null только при Status.READY.
 * Outcome.compatibility содержит диагностику и при READY, и при INCOMPATIBLE_PC.
 * payload приводится через instanceof AutoCalibrator.CalibrationResult; при несоответствии типа -
 * Status.FAILED с тем же compatibility.
 */
// Legacy WinAPI path; not reachable in cdp.enabled=true. Scheduled for removal beyond S7 MASTER_PLAN.
// Do not import from new code under com.freemaf.agent.cdp.
@Deprecated
public final class CalibrationOrchestrator {

    public enum Status { READY, INCOMPATIBLE_PC, FAILED }

    public record Outcome(Status status,
                          AutoCalibrator.CalibrationResult calibration,
                          CompatibilityChecker.CompatibilityResult compatibility,
                          WindowSizeResult sizeResult) { }

    private CalibrationOrchestrator() { }

    public static Outcome run(WindowInfo managed, WindowManager wm,
                              PixelReader reader, ChatScroller scroller, Config config) {
        if (managed == null) throw new IllegalArgumentException("managed is null");
        if (wm == null) throw new IllegalArgumentException("wm is null");
        if (reader == null) throw new IllegalArgumentException("reader is null");
        if (scroller == null) throw new IllegalArgumentException("scroller is null");
        if (config == null) throw new IllegalArgumentException("config is null");

        AppLogger.info("CalibrationOrchestrator: managed hwnd=" + managed.hwndValue() + " title=" + managed.title());
        Pointer hwnd = managed.hwnd();
        WinApiService.WorkspaceBounds bounds = WinApiService.getWorkspaceBounds(hwnd);
        int workspaceW = bounds.width();
        int workspaceH = bounds.height();
        AppLogger.info("CalibrationOrchestrator: workspace=" + workspaceW + "x" + workspaceH);

        CompatibilityChecker.CompatibilityResult pre =
                CompatibilityChecker.precheck(workspaceW, workspaceH);
        AppLogger.info("CalibrationOrchestrator: precheck=" + pre.status() + " diag=" + pre.diagnostic());
        if (pre.status() == CompatibilityChecker.CompatibilityResult.Status.INCOMPATIBLE_PC) {
            return new Outcome(Status.INCOMPATIBLE_PC, null, pre, null);
        }

        int stepPx = config.getWindowGrowthStepPx();
        int maxW = config.getWindowSizeMaxWidth();
        int maxH = config.getWindowSizeMaxHeight();

        final long managedHwndLong = managed.hwndValue();
        final String managedTitle = managed.title();
        WindowSizer.WindowInfoSupplier infoSupplier = () ->
                WindowFinder.findByHwnd(managedHwndLong, managedTitle).orElse(null);
        SizeApplier applier = (w, h) -> wm.applySize(w, h);

        WindowSizer sizer = new WindowSizer(applier, infoSupplier, stepPx, maxW, maxH, workspaceW, workspaceH);
        CalibrateAndVerifyProbe probe = new CalibrateAndVerifyProbe(wm, reader, scroller);
        WindowSizeResult r = sizer.run(managed, probe);
        AppLogger.info("CalibrationOrchestrator: sizer status=" + r.status()
                + " size=" + r.finalWidth() + "x" + r.finalHeight()
                + " reason=" + r.lastReason());

        if (r.status() == WindowSizeResult.Status.FOUND) {
            ProbeResult finalProbe = r.finalProbeResult();
            Object payload = finalProbe == null ? null : finalProbe.payload();
            if (payload instanceof AutoCalibrator.CalibrationResult cal) {
                return new Outcome(Status.READY, cal, pre, r);
            }
            return new Outcome(Status.FAILED, null, pre, r);
        }

        CompatibilityChecker.CompatibilityResult ex =
                CompatibilityChecker.evaluateExhausted(r, workspaceW, workspaceH);
        return new Outcome(Status.INCOMPATIBLE_PC, null, ex, r);
    }
}

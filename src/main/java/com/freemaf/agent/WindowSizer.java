package com.freemaf.agent;

/**
 * Итеративный подбор минимально достаточного размера окна.
 * Класс не обращается к WinAPI/WindowFinder: workspace bounds и актуальный
 * WindowInfo поступают через конструктор, изменение размера — через SizeApplier.
 */
// Legacy WinAPI path; not reachable in cdp.enabled=true. Scheduled for removal beyond S7 MASTER_PLAN.
// Do not import from new code under com.freemaf.agent.cdp.
@Deprecated
public final class WindowSizer {

    @FunctionalInterface
    public interface WindowInfoSupplier {
        WindowInfo get();
    }

    private final SizeApplier applier;
    private final WindowInfoSupplier infoSupplier;
    private final int stepPx;
    private final int maxWidth;
    private final int maxHeight;
    private final int workspaceWidth;
    private final int workspaceHeight;
    private final int maxIterations;

    public WindowSizer(SizeApplier applier, WindowInfoSupplier infoSupplier, int stepPx,
                       int maxWidth, int maxHeight, int workspaceWidth, int workspaceHeight) {
        this(applier, infoSupplier, stepPx, maxWidth, maxHeight, workspaceWidth, workspaceHeight, 10000);
    }

    public WindowSizer(SizeApplier applier, WindowInfoSupplier infoSupplier, int stepPx,
                       int maxWidth, int maxHeight, int workspaceWidth, int workspaceHeight,
                       int maxIterations) {
        if (applier == null) throw new IllegalArgumentException("applier is null");
        if (infoSupplier == null) throw new IllegalArgumentException("infoSupplier is null");
        if (maxWidth < 0) throw new IllegalArgumentException("maxWidth must be >= 0");
        if (maxHeight < 0) throw new IllegalArgumentException("maxHeight must be >= 0");
        if (workspaceWidth <= 0) throw new IllegalArgumentException("workspaceWidth must be > 0");
        if (workspaceHeight <= 0) throw new IllegalArgumentException("workspaceHeight must be > 0");
        if (maxIterations < 1) throw new IllegalArgumentException("maxIterations must be >= 1");
        this.applier = applier;
        this.infoSupplier = infoSupplier;
        this.stepPx = stepPx >= 1 ? stepPx : 10;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.workspaceWidth = workspaceWidth;
        this.workspaceHeight = workspaceHeight;
        this.maxIterations = maxIterations;
    }

    public WindowSizeResult run(WindowInfo initial, SizingProbe probe) {
        if (initial == null) throw new IllegalArgumentException("initial is null");
        if (probe == null) throw new IllegalArgumentException("probe is null");
        int effectiveMaxW = maxWidth > 0 ? maxWidth : workspaceWidth;
        int effectiveMaxH = maxHeight > 0 ? maxHeight : workspaceHeight;
        int width = Math.max(1, initial.right() - initial.left());
        int height = Math.max(1, initial.bottom() - initial.top());
        ProbeResult lastProbe = null;
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            boolean applied = applier.apply(width, height);
            if (!applied) {
                return new WindowSizeResult(width, height, WindowSizeResult.Status.EXHAUSTED, lastProbe,
                        "applySize failed at " + width + "x" + height);
            }
            WindowInfo current = infoSupplier.get();
            if (current == null) {
                return new WindowSizeResult(width, height, WindowSizeResult.Status.EXHAUSTED, lastProbe,
                        "window lost at " + width + "x" + height);
            }
            ProbeResult r = probe.probe(current);
            lastProbe = r;
            if (r.success()) {
                return new WindowSizeResult(width, height, WindowSizeResult.Status.FOUND, r, "");
            }
            if ((width + stepPx > effectiveMaxW) || (height + stepPx > effectiveMaxH)) {
                return new WindowSizeResult(width, height, WindowSizeResult.Status.EXHAUSTED, r,
                        "reached bounds: " + width + "x" + height
                                + ", effectiveMax=" + effectiveMaxW + "x" + effectiveMaxH
                                + ", probe=" + r.diagnostic());
            }
            width += stepPx;
            height += stepPx;
        }
        return new WindowSizeResult(width, height, WindowSizeResult.Status.EXHAUSTED, lastProbe,
                "iteration cap reached (" + maxIterations + ")");
    }
}

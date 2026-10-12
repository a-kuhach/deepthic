package com.freemaf.agent;

/**
 * Чистая логика проверки совместимости ПК с фреймворком.
 * workspace WxH инъектируется; WinAPI здесь не вызывается.
 * Минимально требуемый рабочий стол — 1920x1080.
 */
public final class CompatibilityChecker {

    public static final int MIN_WORKSPACE_WIDTH = 1920;
    public static final int MIN_WORKSPACE_HEIGHT = 1080;

    public record CompatibilityResult(Status status, String diagnostic) {
        public enum Status { COMPATIBLE, INCOMPATIBLE_PC }
    }

    private CompatibilityChecker() { }

    public static CompatibilityResult precheck(int workspaceWidth, int workspaceHeight) {
        if (workspaceWidth < MIN_WORKSPACE_WIDTH || workspaceHeight < MIN_WORKSPACE_HEIGHT) {
            String diag = "требуется минимум " + MIN_WORKSPACE_WIDTH + "x" + MIN_WORKSPACE_HEIGHT
                    + ", фактически " + workspaceWidth + "x" + workspaceHeight;
            return new CompatibilityResult(CompatibilityResult.Status.INCOMPATIBLE_PC, diag);
        }
        return new CompatibilityResult(CompatibilityResult.Status.COMPATIBLE, "");
    }

    public static CompatibilityResult evaluateExhausted(WindowSizeResult exhausted,
                                                        int workspaceWidth, int workspaceHeight) {
        String lastReason = exhausted == null ? "" : exhausted.lastReason();
        int fw = exhausted == null ? 0 : exhausted.finalWidth();
        int fh = exhausted == null ? 0 : exhausted.finalHeight();
        String diag = "фреймворк несовместим с данным ПК: окно " + fw + "x" + fh
                + " не вмещает все элементы, workspace=" + workspaceWidth + "x" + workspaceHeight
                + ", требуется минимум " + MIN_WORKSPACE_WIDTH + "x" + MIN_WORKSPACE_HEIGHT
                + ", причина: " + lastReason;
        return new CompatibilityResult(CompatibilityResult.Status.INCOMPATIBLE_PC, diag);
    }
}

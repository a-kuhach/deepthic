package com.freemaf.agent;
/**
 * Результат одной итерации пробника размера окна.
 * payload непрозрачен для WindowSizer и пробрасывается как есть
 * (в Stage S4 здесь будет CalibrationResult).
 */
public record ProbeResult(boolean success, Object payload, String diagnostic) {
    public ProbeResult {
        diagnostic = diagnostic == null ? "" : diagnostic;
    }
}

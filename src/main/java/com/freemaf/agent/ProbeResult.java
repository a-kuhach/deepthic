package com.freemaf.agent;
/**
 * Результат одной итерации пробника размера окна.
 * payload непрозрачен для WindowSizer и пробрасывается как есть.
 *
 * Контракт payload (S4/S5): для CalibrateAndVerifyProbe поле payload всегда имеет
 * тип AutoCalibrator.CalibrationResult; потребитель (S5) выполняет безопасное
 * instanceof-приведение. Другие реализации SizingProbe могут использовать иной
 * тип payload; WindowSizer payload не читает.
 */
public record ProbeResult(boolean success, Object payload, String diagnostic) {
    public ProbeResult {
        diagnostic = diagnostic == null ? "" : diagnostic;
    }
}

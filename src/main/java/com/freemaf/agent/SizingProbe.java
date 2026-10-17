package com.freemaf.agent;
/**
 * Абстракция «пробник текущего размера окна».
 * WindowSizer не знает про AutoCalibrator и CalibrationResult:
 * каждая итерация вызывает probe(current) и получает ProbeResult.
 *
 * Контракт payload (S4): для CalibrateAndVerifyProbe payload всегда имеет
 * тип AutoCalibrator.CalibrationResult; потребитель (S5) выполняет безопасное
 * приведение instanceof AutoCalibrator.CalibrationResult. Другие реализации
 * SizingProbe могут использовать иной тип payload; WindowSizer payload не читает.
 */
@FunctionalInterface
// Legacy WinAPI path; not reachable in cdp.enabled=true. Scheduled for removal beyond S7 MASTER_PLAN.
// Do not import from new code under com.freemaf.agent.cdp.
@Deprecated
public interface SizingProbe {
    ProbeResult probe(WindowInfo current);
}

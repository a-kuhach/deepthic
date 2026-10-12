package com.freemaf.agent;
/**
 * Абстракция «пробник текущего размера окна».
 * WindowSizer не знает про AutoCalibrator и CalibrationResult:
 * каждая итерация вызывает probe(current) и получает ProbeResult.
 */
@FunctionalInterface
public interface SizingProbe {
    ProbeResult probe(WindowInfo current);
}

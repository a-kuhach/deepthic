package com.freemaf.agent;
/**
 * Итог работы WindowSizer: финальный размер, статус, последний ProbeResult, диагностика.
 */
public record WindowSizeResult(int finalWidth, int finalHeight, Status status, ProbeResult finalProbeResult, String lastReason) {
    public enum Status { FOUND, EXHAUSTED }
}

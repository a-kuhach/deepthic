package com.freemaf.agent;
/**
 * Итог работы WindowSizer: финальный размер, статус, последний ProbeResult, диагностика.
 */
// Legacy WinAPI path; not reachable in cdp.enabled=true. Scheduled for removal beyond S7 MASTER_PLAN.
// Do not import from new code under com.freemaf.agent.cdp.
@Deprecated
public record WindowSizeResult(int finalWidth, int finalHeight, Status status, ProbeResult finalProbeResult, String lastReason) {
    public enum Status { FOUND, EXHAUSTED }
}

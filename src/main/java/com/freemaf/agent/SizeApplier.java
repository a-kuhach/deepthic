package com.freemaf.agent;
/**
 * Инъекция применения размера окна.
 * Возвращает true при успешном применении и верификации,
 * false при неуспехе/откате.
 */
@FunctionalInterface
// Legacy WinAPI path; not reachable in cdp.enabled=true. Scheduled for removal beyond S7 MASTER_PLAN.
// Do not import from new code under com.freemaf.agent.cdp.
@Deprecated
public interface SizeApplier {
    boolean apply(int width, int height);
}

package com.freemaf.agent;

/**
 * Абстракция прокрутки чата до упора вниз.
 * Не зависит от InputSimulator и CalibrationResult.
 */
@FunctionalInterface
// Legacy WinAPI path; not reachable in cdp.enabled=true. Scheduled for removal beyond S7 MASTER_PLAN.
// Do not import from new code under com.freemaf.agent.cdp.
@Deprecated
public interface ChatScroller {
    void scrollToBottom(WindowInfo window);
}

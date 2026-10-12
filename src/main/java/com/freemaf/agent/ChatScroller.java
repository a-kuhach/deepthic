package com.freemaf.agent;

/**
 * Абстракция прокрутки чата до упора вниз.
 * Не зависит от InputSimulator и CalibrationResult.
 */
@FunctionalInterface
public interface ChatScroller {
    void scrollToBottom(WindowInfo window);
}

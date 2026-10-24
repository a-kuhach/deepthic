
package com.freemaf.agent;

import com.freemaf.agent.cdp.CdpBootstrap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 2.1.9 (N-38, N-44): narrow guard-helper test.
 * Note: helper's `b != null` is redundant (isRunning short-circuits on session != null);
 * duplication is harmless for testability and kept as documented in PLAN.
 */
class MainFrameCdpStartedGuardTest {

    @Test
    void evaluateCdpStartedGuardForTestReturnsFalseWhenNotRunning() {
        CdpBootstrap mockB = Mockito.mock(CdpBootstrap.class);
        Mockito.when(mockB.isRunning()).thenReturn(false);
        assertFalse(MainFrame.evaluateCdpStartedGuardForTest(mockB));
    }

    @Test
    void evaluateCdpStartedGuardForTestReturnsTrueWhenRunning() {
        CdpBootstrap mockB = Mockito.mock(CdpBootstrap.class);
        Mockito.when(mockB.isRunning()).thenReturn(true);
        assertTrue(MainFrame.evaluateCdpStartedGuardForTest(mockB));
    }

    @Test
    void evaluateCdpStartedGuardForTestNullIsFalse() {
        assertFalse(MainFrame.evaluateCdpStartedGuardForTest(null));
    }
}

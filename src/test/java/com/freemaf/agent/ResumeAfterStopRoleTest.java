package com.freemaf.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ResumeAfterStopRoleTest {

    @Test void cdpReviewerActive_roleIsExecutor() {
        assertEquals("EXECUTOR", MainFrame.computeResumeAfterStopRole(true, true, "JUDGE"));
        assertEquals("EXECUTOR", MainFrame.computeResumeAfterStopRole(true, true, "CODE_REVIEWER"));
    }

    @Test void cdpExecutorActive_roleIsExecutor() {
        assertEquals("EXECUTOR", MainFrame.computeResumeAfterStopRole(true, false, "EXECUTOR"));
    }

    @Test void legacyReviewerActive_rolePreserved() {
        assertEquals("JUDGE", MainFrame.computeResumeAfterStopRole(false, true, "JUDGE"));
        assertEquals("CODE_REVIEWER", MainFrame.computeResumeAfterStopRole(false, true, "CODE_REVIEWER"));
    }

    @Test void legacyExecutorActive_roleIsExecutor() {
        assertEquals("EXECUTOR", MainFrame.computeResumeAfterStopRole(false, false, "EXECUTOR"));
    }

    @Test void nullOrBlankFallsBackToExecutor() {
        assertEquals("EXECUTOR", MainFrame.computeResumeAfterStopRole(true, false, null));
        assertEquals("EXECUTOR", MainFrame.computeResumeAfterStopRole(true, false, ""));
        assertEquals("EXECUTOR", MainFrame.computeResumeAfterStopRole(false, true, null));
    }
}

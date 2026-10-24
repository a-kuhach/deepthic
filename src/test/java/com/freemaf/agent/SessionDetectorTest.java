
package com.freemaf.agent;

import com.freemaf.agent.winapi.WinApiService;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

class SessionDetectorTest {

    private Config config;

    private Config newConfig() {
        config = new Config();
        config.setLastManagedHwnd(0L);
        config.setLastManagedTitle("");
        config.setLastPhase("EXECUTOR");
        config.setLastReviewerHwnd(0L);
        config.setLastReviewerTitle("");
        return config;
    }

    @AfterEach
    void cleanup() {
        if (config == null) return;
        config.setLastManagedHwnd(0L);
        config.setLastManagedTitle("");
        config.setLastPhase("EXECUTOR");
        config.setLastReviewerHwnd(0L);
        config.setLastReviewerTitle("");
    }

    private static WindowInfo win(long hwnd, String title) {
        return new WindowInfo(new Pointer(hwnd), title, 0, 0, 100, 100);
    }

    @Test
    void executorPhaseExecutorAliveContinues() {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastManagedTitle("DeepSeek - work");
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(anyLong(), anyString()))
                    .thenAnswer(inv -> {
                        long h = inv.getArgument(0);
                        return h == 100L ? Optional.of(win(100L, "DeepSeek - work")) : Optional.empty();
                    });
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.CONTINUE_OLD_SESSION, d.decision());
            assertTrue(d.isContinuation());
            assertNotNull(d.activeWindow());
            assertEquals(100L, d.activeWindow().hwndValue());
        }
    }

    @Test
    void executorPhaseExecutorDeadOpensNew() {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(anyLong(), anyString()))
                    .thenReturn(Optional.empty());
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.OPEN_NEW_CHAT, d.decision());
            assertFalse(d.isContinuation());
            assertNull(d.activeWindow());
        }
    }

    @Test
    void executorPhaseOrphanReviewerClosed() {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastReviewerHwnd(200L);
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(eq(100L), anyString()))
                    .thenReturn(Optional.of(win(100L, "DeepSeek - work")));
            wf.when(() -> WindowFinder.findByHwnd(eq(200L), anyString()))
                    .thenReturn(Optional.of(win(200L, "DeepSeek - review")));
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.CONTINUE_OLD_SESSION, d.decision());
            wa.verify(() -> WinApiService.closeWindow(200L), times(1));
        }
    }

    @Test
    void reviewerPhaseBothAliveJudge() {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastReviewerHwnd(200L);
        c.setLastPhase("JUDGE");
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(eq(100L), anyString()))
                    .thenReturn(Optional.of(win(100L, "DeepSeek - work")));
            wf.when(() -> WindowFinder.findByHwnd(eq(200L), anyString()))
                    .thenReturn(Optional.of(win(200L, "DeepSeek - judge")));
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.RESUME_REVIEWER_SESSION, d.decision());
            assertNotNull(d.recoveryMessage());
            assertTrue(d.recoveryMessage().contains("JUDGE"));
            assertEquals(200L, d.activeWindow().hwndValue());
        }
    }

    @Test
    void reviewerPhaseBothAliveCodeReviewer() {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastReviewerHwnd(200L);
        c.setLastPhase("CODE_REVIEWER");
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(eq(100L), anyString()))
                    .thenReturn(Optional.of(win(100L, "DeepSeek - work")));
            wf.when(() -> WindowFinder.findByHwnd(eq(200L), anyString()))
                    .thenReturn(Optional.of(win(200L, "DeepSeek - cr")));
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.RESUME_REVIEWER_SESSION, d.decision());
            assertNotNull(d.recoveryMessage());
            assertTrue(d.recoveryMessage().contains("CODE_REVIEWER"));
        }
    }

    @Test
    void reviewerPhaseReviewerDeadRecoversNewExecutor() {
        // Executor found, reviewer missing -> executor is closed under new logic.
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastReviewerHwnd(200L);
        c.setLastPhase("JUDGE");
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(eq(100L), anyString()))
                    .thenReturn(Optional.of(win(100L, "DeepSeek - work")));
            wf.when(() -> WindowFinder.findByHwnd(eq(200L), anyString()))
                    .thenReturn(Optional.empty());
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.RECOVER_NEW_EXECUTOR, d.decision());
            assertNotNull(d.recoveryMessage());
            wa.verify(() -> WinApiService.closeWindow(100L), times(1));
        }
    }

    @Test
    void reviewerPhaseReviewerAliveExecutorDeadClosesReviewer() {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastReviewerHwnd(200L);
        c.setLastPhase("JUDGE");
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(eq(100L), anyString()))
                    .thenReturn(Optional.empty());
            wf.when(() -> WindowFinder.findByHwnd(eq(200L), anyString()))
                    .thenReturn(Optional.of(win(200L, "DeepSeek - judge")));
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.RECOVER_NEW_EXECUTOR, d.decision());
            wa.verify(() -> WinApiService.closeWindow(200L), times(1));
        }
    }

    @Test
    void reviewerPhaseBothDeadRecoversNewExecutor() {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastReviewerHwnd(200L);
        c.setLastPhase("JUDGE");
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(anyLong(), anyString()))
                    .thenReturn(Optional.empty());
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.RECOVER_NEW_EXECUTOR, d.decision());
            wa.verify(() -> WinApiService.closeWindow(anyLong()), times(0));
        }
    }

    @Test
    void isContinuationOnlyForContinueOldSession() {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(anyLong(), anyString()))
                    .thenReturn(Optional.empty());
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertFalse(d.isContinuation());
        }
    }

    @Test
    void reviewerPhaseReviewerInIgnoredListTreatedAsAbsent() {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastReviewerHwnd(200L);
        c.setLastPhase("JUDGE");
        c.setIgnoredHwnds(java.util.List.of(200L));
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(eq(100L), anyString()))
                    .thenReturn(Optional.of(win(100L, "DeepSeek - work")));
            wf.when(() -> WindowFinder.findByHwnd(eq(200L), anyString()))
                    .thenReturn(Optional.of(win(200L, "DeepSeek - judge")));
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.RECOVER_NEW_EXECUTOR, d.decision());
            wa.verify(() -> WinApiService.closeWindow(200L), times(0));
        } finally {
            c.setIgnoredHwnds(java.util.List.of());
        }
    }

    @Test
    void executorPhaseReviewerInIgnoredListNotClosed() {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastReviewerHwnd(200L);
        c.setIgnoredHwnds(java.util.List.of(200L));
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(eq(100L), anyString()))
                    .thenReturn(Optional.of(win(100L, "DeepSeek - work")));
            wf.when(() -> WindowFinder.findByHwnd(eq(200L), anyString()))
                    .thenReturn(Optional.of(win(200L, "DeepSeek - orphan")));
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.CONTINUE_OLD_SESSION, d.decision());
            wa.verify(() -> WinApiService.closeWindow(200L), times(0));
        } finally {
            c.setIgnoredHwnds(java.util.List.of());
        }
    }

    // --- Step 2.1.3 explicit cases (from PLAN.md) ---

    @Test
    void recoverNewExecutorClosesSurvivingReviewer() {
        // reviewer found, executor missing -> reviewer must be closed
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastReviewerHwnd(200L);
        c.setLastPhase("CODE_REVIEWER");
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(eq(100L), anyString()))
                    .thenReturn(Optional.empty());
            wf.when(() -> WindowFinder.findByHwnd(eq(200L), anyString()))
                    .thenReturn(Optional.of(win(200L, "DeepSeek - cr")));
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.RECOVER_NEW_EXECUTOR, d.decision());
            wa.verify(() -> WinApiService.closeWindow(200L), times(1));
        }
    }

    @Test
    void recoverNewExecutorClosesSurvivingExecutor() {
        // executor found, reviewer missing -> executor must be closed (AUD-05 part 1)
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastReviewerHwnd(200L);
        c.setLastPhase("JUDGE");
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(eq(100L), anyString()))
                    .thenReturn(Optional.of(win(100L, "DeepSeek - work")));
            wf.when(() -> WindowFinder.findByHwnd(eq(200L), anyString()))
                    .thenReturn(Optional.empty());
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.RECOVER_NEW_EXECUTOR, d.decision());
            wa.verify(() -> WinApiService.closeWindow(100L), times(1));
            wa.verify(() -> WinApiService.closeWindow(200L), times(0));
        }
    }

    @Test
    void recoverNewExecutorBothMissingNoClose() {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastReviewerHwnd(200L);
        c.setLastPhase("CODE_REVIEWER");
        try (MockedStatic<WindowFinder> wf = mockStatic(WindowFinder.class);
             MockedStatic<WinApiService> wa = mockStatic(WinApiService.class)) {
            wf.when(() -> WindowFinder.findByHwnd(anyLong(), anyString()))
                    .thenReturn(Optional.empty());
            SessionDetector.SessionDecision d = SessionDetector.detect("DeepSeek", c);
            assertEquals(SessionDetector.Decision.RECOVER_NEW_EXECUTOR, d.decision());
            wa.verify(() -> WinApiService.closeWindow(anyLong()), times(0));
        }
    }
}

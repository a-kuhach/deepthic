
package com.freemaf.agent;

import com.freemaf.agent.winapi.WinApiService;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationLoopSessionMigrationTest {

    private Config config;

    @AfterEach
    void cleanup() {
        if (config == null) return;
        config.setExecutorMessageCount(0L);
        config.setIgnoredHwnds(List.of());
        config.setLastManagedHwnd(0L);
        config.setLastManagedTitle("");
        config.setLastPhase("EXECUTOR");
        config.setLastReviewerHwnd(0L);
        config.setLastReviewerTitle("");
    }

    private Config newConfig() {
        config = new Config();
        config.setExecutorMessageCount(0L);
        config.setIgnoredHwnds(List.of());
        config.setLastManagedHwnd(0L);
        config.setLastManagedTitle("");
        config.setLastPhase("EXECUTOR");
        config.setLastReviewerHwnd(0L);
        config.setLastReviewerTitle("");
        return config;
    }

    private static WindowManager mockWindowManager() {
        WindowManager wm = mock(WindowManager.class);
        when(wm.hwnd()).thenReturn(null);
        return wm;
    }

    private AutomationLoop newLoop(Config c) {
        return new AutomationLoop(
                mock(ChatConsole.class),
                mock(ConsolePanel.class),
                mock(StatusMarker.class),
                mock(PromptQueue.class),
                mock(DeepSeekSession.class),
                mock(PowerShellExecutor.class),
                c,
                mockWindowManager());
    }

    private static void setActiveReviewerRole(AutomationLoop loop, String role) throws Exception {
        Field f = AutomationLoop.class.getDeclaredField("activeReviewerRole");
        f.setAccessible(true);
        f.set(loop, role);
    }





    private static MarkerEvent startEvent() {
        return new MarkerEvent(MarkerType.AGENT_SESSION_MIGRATE_START, "prep");
    }

    @Test
    void counterIncrementsOnlyForExecutor() throws Exception {
        Config c = newConfig();
        c.setExecutorMessageCount(10L);
        AutomationLoop loop = newLoop(c);

        long after = loop.touchExecutorCounterIfActive();
        assertEquals(11L, after);
        assertEquals(11L, c.getExecutorMessageCount());

        setActiveReviewerRole(loop, "JUDGE");
        long preserved = loop.touchExecutorCounterIfActive();
        assertEquals(11L, preserved);
        assertEquals(11L, c.getExecutorMessageCount());
    }

    @Test
    void warningInjectedIntoExecutorPromptAtCounterHundred() throws Exception {
        Config c = newConfig();
        c.setExecutorMessageCount(100L);
        AutomationLoop loop = newLoop(c);

        String out = loop.injectMigrationWarningIfNeeded("PROMPT");
        assertTrue(out.contains("Счётчик сообщений Executor достиг 100"));
        loop.applyMigrationWarningDelivery(true);
        assertTrue(loop.isWarningInjected());
        assertTrue(out.endsWith("PROMPT"));
    }

    @Test
    void warningNotInjectedIntoReviewerPrompt() throws Exception {
        Config c = newConfig();
        c.setExecutorMessageCount(100L);
        AutomationLoop loop = newLoop(c);
        setActiveReviewerRole(loop, "JUDGE");

        String out = loop.injectMigrationWarningIfNeeded("PROMPT");
        assertEquals("PROMPT", out);
        assertFalse(loop.isWarningInjected());
    }

    @Test
    void warningNotInjectedWhileAwaitingConfirm() throws Exception {
        Config c = newConfig();
        c.setExecutorMessageCount(100L);
        AutomationLoop loop = newLoop(c);
        loop.recognizeMigrationStart(List.of(startEvent()), "EXECUTOR");
        assertTrue(loop.isAwaitingMigrationConfirm());

        String out = loop.injectMigrationWarningIfNeeded("PROMPT");
        assertEquals("PROMPT", out);
    }

    @Test
    void recognizeStartSetsFlagOnlyForExecutor() {
        Config c = newConfig();
        AutomationLoop loop = newLoop(c);
        loop.recognizeMigrationStart(List.of(startEvent()), "EXECUTOR");
        assertTrue(loop.isAwaitingMigrationConfirm());

        Config c2 = newConfig();
        AutomationLoop loop2 = newLoop(c2);
        loop2.recognizeMigrationStart(List.of(startEvent()), "JUDGE");
        assertFalse(loop2.isAwaitingMigrationConfirm());
    }



    @Test
    void performSessionMigrationClosesOldAndResetsState() throws Exception {
        Config c = newConfig();
        c.setLastManagedHwnd(100L);
        c.setLastPhase("EXECUTOR");
        c.setExecutorMessageCount(42L);

        try (MockedStatic<WinApiService> wa = mockStatic(WinApiService.class);
             MockedStatic<ChromeLauncher> cl = mockStatic(ChromeLauncher.class)) {
            final boolean[] closed = { false };
            wa.when(() -> WinApiService.isWindow(100L)).thenAnswer(inv -> !closed[0]);
            wa.when(() -> WinApiService.closeWindow(100L)).thenAnswer(inv -> {
                closed[0] = true;
                return null;
            });

            WindowInfo newWin = new WindowInfo(new Pointer(999L), "DeepSeek - migrated", 0, 0, 100, 100);
            cl.when(() -> ChromeLauncher.launchNewWindow(any(Config.class), anyList())).thenReturn(newWin);

            AutomationLoop loop = newLoop(c);
            loop.performSessionMigration("commit log");

            wa.verify(() -> WinApiService.closeWindow(100L), times(1));
            assertEquals(999L, c.getLastManagedHwnd());
            assertEquals("DeepSeek - migrated", c.getLastManagedTitle());
            assertEquals("EXECUTOR", c.getLastPhase());
            assertEquals(0L, c.getLastReviewerHwnd());
            assertEquals(0L, c.getExecutorMessageCount());
            assertTrue(c.getIgnoredHwnds().contains(100L));
            assertFalse(loop.isAwaitingMigrationConfirm());
            assertFalse(loop.isWarningInjected());
        }
    }

    @Test
    void openNewChatResetsCounter() throws Exception {
        Config c = newConfig();
        c.setExecutorMessageCount(77L);

        try (MockedStatic<SessionDetector> sd = mockStatic(SessionDetector.class);
             MockedStatic<ChromeLauncher> cl = mockStatic(ChromeLauncher.class)) {
            SessionDetector.SessionDecision d = new SessionDetector.SessionDecision(
                    SessionDetector.Decision.OPEN_NEW_CHAT, null, null, null, null);
            sd.when(() -> SessionDetector.detect(anyString(), any(Config.class))).thenReturn(d);

            WindowInfo newWin = new WindowInfo(new Pointer(555L), "DeepSeek - new", 0, 0, 100, 100);
            cl.when(() -> ChromeLauncher.launchNewWindow(any(Config.class), anyList())).thenReturn(newWin);

            AutomationLoop loop = newLoop(c);
            loop.bootstrapSession();

            assertEquals(0L, c.getExecutorMessageCount());
        }
    }

    @Test
    void resumeReviewerSessionPreservesCounter() throws Exception {
        Config c = newConfig();
        c.setExecutorMessageCount(77L);
        c.setLastPhase("JUDGE");

        try (MockedStatic<SessionDetector> sd = mockStatic(SessionDetector.class)) {
            WindowInfo execWin = new WindowInfo(new Pointer(100L), "DeepSeek - work", 0, 0, 100, 100);
            WindowInfo revWin = new WindowInfo(new Pointer(200L), "DeepSeek - judge", 0, 0, 100, 100);
            SessionDetector.SessionDecision d = new SessionDetector.SessionDecision(
                    SessionDetector.Decision.RESUME_REVIEWER_SESSION,
                    revWin, execWin, revWin, "resume judge");
            sd.when(() -> SessionDetector.detect(anyString(), any(Config.class))).thenReturn(d);

            AutomationLoop loop = newLoop(c);
            loop.bootstrapSession();

            assertEquals(77L, c.getExecutorMessageCount());
        }
    }

    @Test
    void recoverNewExecutorResetsCounter() throws Exception {
        Config c = newConfig();
        c.setExecutorMessageCount(77L);
        c.setLastPhase("JUDGE");

        try (MockedStatic<SessionDetector> sd = mockStatic(SessionDetector.class);
             MockedStatic<ChromeLauncher> cl = mockStatic(ChromeLauncher.class)) {
            SessionDetector.SessionDecision d = new SessionDetector.SessionDecision(
                    SessionDetector.Decision.RECOVER_NEW_EXECUTOR, null, null, null, "recover");
            sd.when(() -> SessionDetector.detect(anyString(), any(Config.class))).thenReturn(d);

            WindowInfo newWin = new WindowInfo(new Pointer(777L), "DeepSeek - rec", 0, 0, 100, 100);
            cl.when(() -> ChromeLauncher.launchNewWindow(any(Config.class), anyList())).thenReturn(newWin);

            AutomationLoop loop = newLoop(c);
            loop.bootstrapSession();

            assertEquals(0L, c.getExecutorMessageCount());
        }
    }

    // ---- S2.2 REJECT-CORRECTION / JUDGE 2c: R8 layering (Fix-B role guard must survive AUD-39 fix) ----

    private static String extractRawRoleMarkerReflect(String script) throws Exception {
        Method m = AutomationLoop.class.getDeclaredMethod("extractRawRoleMarker", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, script);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> validRolesReflect() throws Exception {
        Field f = AutomationLoop.class.getDeclaredField("VALID_ROLES");
        f.setAccessible(true);
        return (java.util.Set<String>) f.get(null);
    }

    @Test
    void fixBRoleGuard_preOnlyInvalidRoleRejected() throws Exception {
        String script = "Write-Output \"ROLE: WIZARD\"";
        String role = extractRawRoleMarkerReflect(script);
        assertEquals("WIZARD", role);
        assertFalse(validRolesReflect().contains(role));
    }

    @Test
    void fixBRoleGuard_prePlusMarkerInvalidRoleRejected() throws Exception {
        String script = "Write-Output \"ROLE: WIZARD\"" + "\n"
                + "Write-Output \"USER_MSG: start\"" + "\n"
                + "Write-Output 1";
        String role = extractRawRoleMarkerReflect(script);
        assertEquals("WIZARD", role);
        assertFalse(validRolesReflect().contains(role));
    }

    @Test
    void fixBRoleGuard_prePlusMarkerValidRoleAccepted() throws Exception {
        String script = "Write-Output \"ROLE: EXECUTOR\"" + "\n"
                + "Write-Output \"USER_MSG: start\"" + "\n"
                + "Write-Output 1";
        String role = extractRawRoleMarkerReflect(script);
        assertEquals("EXECUTOR", role);
        assertTrue(validRolesReflect().contains(role));
    }

    @Test
    void injectMigrationWarning_counterBelow100_doesNotSetFlag() throws Exception {
        Config c = newConfig();
        c.setExecutorMessageCount(50L);
        AutomationLoop loop = newLoop(c);

        String out = loop.injectMigrationWarningIfNeeded("PROMPT");
        assertEquals("PROMPT", out);
        assertFalse(loop.isWarningInjected());
        assertFalse(loop.isWarningPrependPending());

        loop.applyMigrationWarningDelivery(true);
        assertFalse(loop.isWarningInjected());
    }

    @Test
    void injectMigrationWarning_counterAt100_setsFlagOnlyAfterDelivery() throws Exception {
        Config c = newConfig();
        c.setExecutorMessageCount(100L);
        AutomationLoop loop = newLoop(c);

        String out = loop.injectMigrationWarningIfNeeded("PROMPT");
        assertTrue(out.contains("Счётчик сообщений Executor достиг 100"));
        assertTrue(out.endsWith("PROMPT"));
        assertFalse(loop.isWarningInjected());
        assertTrue(loop.isWarningPrependPending());

        loop.applyMigrationWarningDelivery(true);
        assertTrue(loop.isWarningInjected());
    }

    @Test
    void injectMigrationWarning_deliveryFailure_retainsWarning() throws Exception {
        Config c = newConfig();
        c.setExecutorMessageCount(100L);
        AutomationLoop loop = newLoop(c);

        String first = loop.injectMigrationWarningIfNeeded("P1");
        assertTrue(first.contains("Счётчик сообщений Executor достиг 100"));
        assertFalse(loop.isWarningInjected());

        // Simulate a delivery throw: skip applyMigrationWarningDelivery entirely.
        String second = loop.injectMigrationWarningIfNeeded("P2");
        assertTrue(second.contains("Счётчик сообщений Executor достиг 100"));
        assertTrue(loop.isWarningPrependPending());

        loop.applyMigrationWarningDelivery(false);
        assertFalse(loop.isWarningInjected());

        loop.applyMigrationWarningDelivery(true);
        assertTrue(loop.isWarningInjected());
    }
}

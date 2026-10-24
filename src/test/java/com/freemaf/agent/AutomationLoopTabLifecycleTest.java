
package com.freemaf.agent;

import com.freemaf.agent.cdp.CdpBootstrap;
import com.freemaf.agent.cdp.CdpDeepSeek;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;

/**
 * Pure-unit tests for AutomationLoop CDP tab-lifecycle enforcement (S4 MASTER_PLAN)
 * and Step 2.1.6 performSessionMigration retry (AUD-53).
 * Uses Mockito.mock without MockitoExtension — mockito-junit-jupiter is not a
 * project dependency.
 */
class AutomationLoopTabLifecycleTest {

    private static AutomationLoop newLoop(CdpBootstrap bootstrap, Config config, PromptQueue promptQueue) {
        return newLoopWithStatus(bootstrap, config, promptQueue, Mockito.mock(StatusMarker.class));
    }

    private static AutomationLoop newLoopWithStatus(CdpBootstrap bootstrap, Config config, PromptQueue promptQueue,
                                                    StatusMarker statusMarker) {
        ChatConsole chatConsole = Mockito.mock(ChatConsole.class);
        ConsolePanel psConsole = Mockito.mock(ConsolePanel.class);
        DeepSeekSession deepSeek = Mockito.mock(DeepSeekSession.class);
        PowerShellExecutor psExec = Mockito.mock(PowerShellExecutor.class);
        WindowManager wm = Mockito.mock(WindowManager.class);
        return new AutomationLoop(chatConsole, psConsole, statusMarker, promptQueue,
                deepSeek, psExec, config, wm, bootstrap);
    }

    @Test
    void switchToExecutorClosesReviewerInCdpMode() throws Exception {
        CdpBootstrap bootstrap = Mockito.mock(CdpBootstrap.class);
        Config config = Mockito.mock(Config.class);
        PromptQueue promptQueue = Mockito.mock(PromptQueue.class);
        AutomationLoop loop = newLoop(bootstrap, config, promptQueue);

        Method m = AutomationLoop.class.getDeclaredMethod("switchToExecutor");
        m.setAccessible(true);
        m.invoke(loop);

        InOrder inOrder = inOrder(bootstrap);
        inOrder.verify(bootstrap).closeReviewer();
        inOrder.verify(bootstrap).activateExecutor();
    }

    @Test
    void openReviewerClosesPreviousInCdpMode() throws Exception {
        CdpBootstrap bootstrap = Mockito.mock(CdpBootstrap.class);
        Config config = Mockito.mock(Config.class);
        PromptQueue promptQueue = Mockito.mock(PromptQueue.class);
        AutomationLoop loop = newLoop(bootstrap, config, promptQueue);

        Method m = AutomationLoop.class.getDeclaredMethod("openReviewerWindow", String.class);
        m.setAccessible(true);
        m.invoke(loop, "JUDGE");

        InOrder inOrder = inOrder(bootstrap);
        inOrder.verify(bootstrap).closeReviewer();
        inOrder.verify(bootstrap).openReviewer();
        inOrder.verify(bootstrap).activateReviewer();
    }

    @Test
    void performSessionMigrationClosesBothInCdpMode() throws Exception {
        CdpBootstrap bootstrap = Mockito.mock(CdpBootstrap.class);
        Config config = Mockito.mock(Config.class);
        PromptQueue promptQueue = Mockito.mock(PromptQueue.class);
        AutomationLoop loop = newLoop(bootstrap, config, promptQueue);

        loop.performSessionMigration(null);

        InOrder inOrder = inOrder(bootstrap);
        inOrder.verify(bootstrap).closeReviewer();
        inOrder.verify(bootstrap).closeExecutor();
        inOrder.verify(bootstrap).openExecutor();
        inOrder.verify(bootstrap).activateExecutor();
    }

    // ----- Step 2.1.6 retry coverage (AUD-53) -----

    @Test
    void performSessionMigrationRetriesOnFailure() throws Exception {
        CdpBootstrap bootstrap = Mockito.mock(CdpBootstrap.class);
        Config config = Mockito.mock(Config.class);
        PromptQueue promptQueue = Mockito.mock(PromptQueue.class);
        StatusMarker statusMarker = Mockito.mock(StatusMarker.class);

        Mockito.doThrow(new Exception("first fails"))
                .doAnswer(inv -> null)
                .when(bootstrap).openExecutor();

        AutomationLoop loop = newLoopWithStatus(bootstrap, config, promptQueue, statusMarker);

        long t0 = System.currentTimeMillis();
        loop.performSessionMigration(null);
        long elapsed = System.currentTimeMillis() - t0;

        Mockito.verify(bootstrap, times(1)).closeReviewer();
        Mockito.verify(bootstrap, times(2)).closeExecutor();
        Mockito.verify(bootstrap, times(2)).openExecutor();
        Mockito.verify(bootstrap, times(1)).activateExecutor();
        Mockito.verify(statusMarker, Mockito.never()).setState(StatusMarker.State.PAUSED);
        // One backoff of 1 s between attempt 1 and 2 (documented cost).
        assert elapsed >= 900L : "expected >=900 ms retry delay, observed " + elapsed;
    }

    @Test
    void performSessionMigrationPausesAfterExhaustion() throws Exception {
        CdpBootstrap bootstrap = Mockito.mock(CdpBootstrap.class);
        Config config = Mockito.mock(Config.class);
        PromptQueue promptQueue = Mockito.mock(PromptQueue.class);
        StatusMarker statusMarker = Mockito.mock(StatusMarker.class);

        Mockito.doThrow(new Exception("always fails")).when(bootstrap).openExecutor();

        AutomationLoop loop = newLoopWithStatus(bootstrap, config, promptQueue, statusMarker);

        long t0 = System.currentTimeMillis();
        loop.performSessionMigration(null);
        long elapsed = System.currentTimeMillis() - t0;

        Mockito.verify(bootstrap, times(1)).closeReviewer();
        Mockito.verify(bootstrap, times(3)).closeExecutor();
        Mockito.verify(bootstrap, times(3)).openExecutor();
        Mockito.verify(bootstrap, Mockito.never()).activateExecutor();
        Mockito.verify(statusMarker, times(1)).setState(StatusMarker.State.PAUSED);
        // Two backoffs of 1 s + 2 s (documented cost).
        assert elapsed >= 2900L : "expected >=2900 ms retry delay, observed " + elapsed;
    }
}

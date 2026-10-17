
package com.freemaf.agent;

import com.freemaf.agent.cdp.CdpBootstrap;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.mockito.Mockito.inOrder;

/**
 * Pure-unit tests for AutomationLoop CDP tab-lifecycle enforcement
 * (S4 MASTER_PLAN). Uses Mockito.mock without MockitoExtension —
 * mockito-junit-jupiter is not a project dependency.
 */
class AutomationLoopTabLifecycleTest {

    private static AutomationLoop newLoop(CdpBootstrap bootstrap, Config config, PromptQueue promptQueue) {
        ChatConsole chatConsole = Mockito.mock(ChatConsole.class);
        ConsolePanel psConsole = Mockito.mock(ConsolePanel.class);
        StatusMarker statusMarker = Mockito.mock(StatusMarker.class);
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
}
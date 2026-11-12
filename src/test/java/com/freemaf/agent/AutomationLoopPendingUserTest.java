package com.freemaf.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationLoopPendingUserTest {

    private static AutomationLoop newLoopWithQueue(PromptQueue pq) { WindowManager wm = mock(WindowManager.class); when(wm.hwnd()).thenReturn(null); return new AutomationLoop(mock(ChatConsole.class), mock(ConsolePanel.class), mock(StatusMarker.class), pq, mock(DeepSeekSession.class), mock(PowerShellExecutor.class), new Config(), wm); }

    private static boolean invokeTryConsume(AutomationLoop loop, String role, String trigger, String expl) throws Exception { Method m = AutomationLoop.class.getDeclaredMethod("tryConsumePendingOverMarker", String.class, String.class, String.class); m.setAccessible(true); return (Boolean) m.invoke(loop, role, trigger, expl); }

    @Test void tryConsumeOverMarkerWithPendingOverrides() throws Exception { PromptQueue pq = mock(PromptQueue.class); when(pq.hasPendingUserDuringWorking()).thenReturn(true); when(pq.drainPendingUser()).thenReturn(new PromptQueue.PendingUser("user msg", List.of())); AutomationLoop loop = newLoopWithQueue(pq); boolean r = invokeTryConsume(loop, "EXECUTOR", "AGENT_PAUSE", "expl text"); assertTrue(r); verify(pq, times(1)).addDirect(any(Prompt.class)); }

    @Test void tryConsumeOverMarkerWithoutPendingReturnsFalse() throws Exception { PromptQueue pq = mock(PromptQueue.class); when(pq.hasPendingUserDuringWorking()).thenReturn(false); AutomationLoop loop = newLoopWithQueue(pq); boolean r = invokeTryConsume(loop, "EXECUTOR", "AGENT_STOP", "expl"); assertFalse(r); verify(pq, never()).addDirect(any(Prompt.class)); }

    @Test void tryConsumeProducesExpectedExplanation() throws Exception { PromptQueue pq = mock(PromptQueue.class); when(pq.hasPendingUserDuringWorking()).thenReturn(true); when(pq.drainPendingUser()).thenReturn(new PromptQueue.PendingUser("user msg content", List.of())); AutomationLoop loop = newLoopWithQueue(pq); invokeTryConsume(loop, "JUDGE", "AGENT_STOP", "Ты запросил AGENT_STOP до того, как это сообщение пришло."); ArgumentCaptor<Prompt> cap = ArgumentCaptor.forClass(Prompt.class); verify(pq).addDirect(cap.capture()); String out = cap.getValue().text(); assertTrue(out.contains("user msg content")); assertTrue(out.contains("Ты запросил AGENT_STOP до того, как это сообщение пришло.")); assertTrue(out.contains("AGENT_STOP")); assertTrue(out.contains("ROLE_TO_ACT: JUDGE")); }

    @Test void tryConsumeNullDrainReturnsFalse() throws Exception { PromptQueue pq = mock(PromptQueue.class); when(pq.hasPendingUserDuringWorking()).thenReturn(true); when(pq.drainPendingUser()).thenReturn(null); AutomationLoop loop = newLoopWithQueue(pq); boolean r = invokeTryConsume(loop, "EXECUTOR", "AGENT_STOP", "x"); assertFalse(r); }
}

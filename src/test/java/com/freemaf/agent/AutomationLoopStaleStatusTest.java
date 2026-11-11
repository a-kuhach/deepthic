package com.freemaf.agent;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationLoopStaleStatusTest {

    private static AutomationLoop newLoop(StatusMarker sm) {
        WindowManager wm = mock(WindowManager.class); when(wm.hwnd()).thenReturn(null);
        return new AutomationLoop(mock(ChatConsole.class), mock(ConsolePanel.class), sm, mock(PromptQueue.class), mock(DeepSeekSession.class), mock(PowerShellExecutor.class), new Config(), wm);
    }

    private static void safeSetState(AutomationLoop loop, StatusMarker.State s) throws Exception {
        Method m = AutomationLoop.class.getDeclaredMethod("safeSetState", StatusMarker.State.class);
        m.setAccessible(true);
        m.invoke(loop, s);
    }

    @Test
    void noSupplierGuardInactive_legacyBehavior() throws Exception {
        StatusMarker mockSM = mock(StatusMarker.class);
        AutomationLoop a = newLoop(mockSM);
        safeSetState(a, StatusMarker.State.WORKING);
        verify(mockSM, times(1)).setState(StatusMarker.State.WORKING);
    }

    @Test
    void staleLoopStatusIgnored() throws Exception {
        StatusMarker mockSM = mock(StatusMarker.class);
        AtomicReference<AutomationLoop> ref = new AtomicReference<>();
        AutomationLoop a = newLoop(mockSM); a.setCurrentLoopSupplier(ref::get);
        AutomationLoop b = newLoop(mockSM); b.setCurrentLoopSupplier(ref::get);
        ref.set(a);
        safeSetState(b, StatusMarker.State.PAUSED);
        verify(mockSM, never()).setState(StatusMarker.State.PAUSED);
        safeSetState(a, StatusMarker.State.WORKING);
        verify(mockSM, times(1)).setState(StatusMarker.State.WORKING);
    }

    @Test
    void markTerminatingSetsFlag() {
        AutomationLoop a = newLoop(mock(StatusMarker.class));
        assertFalse(a.isTerminating());
        a.markTerminating();
        assertTrue(a.isTerminating());
    }
}

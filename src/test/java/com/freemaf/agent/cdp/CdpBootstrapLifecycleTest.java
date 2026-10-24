
package com.freemaf.agent.cdp;

import com.freemaf.agent.Config;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;

/**
 * Step 2.1.9 unit tests for CdpBootstrap lifecycle changes (AUD-05/AUD-56/AUD-57).
 * Uses Mockito CALLS_REAL_METHODS so the real method body runs on a mock whose
 * constructor was bypassed. No live Chrome, no ChromeProcessLauncher spawn.
 */
class CdpBootstrapLifecycleTest {

    private static CdpBootstrap newRealMethodsMock() {
        return Mockito.mock(CdpBootstrap.class, Mockito.CALLS_REAL_METHODS);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setBooleanField(Class<?> owner, Object target, String name, boolean value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.setBoolean(target, value);
    }

    private static void setLongField(Class<?> owner, Object target, String name, long value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(target, value);
    }

    @Test
    void ensureExecutorTabFreshClosesOldBeforeOpen() throws Exception {
        CdpBootstrap b = newRealMethodsMock();
        doReturn(true).doReturn(true).when(b).isExecutorTabOpen();
        doNothing().when(b).closeExecutor();
        doReturn(null).when(b).openExecutor();

        b.ensureExecutorTabFresh();

        InOrder io = inOrder(b);
        io.verify(b).closeExecutor();
        io.verify(b).openExecutor();
    }

    @Test
    void ensureExecutorTabFreshThrowsWhenOpenFails() throws Exception {
        CdpBootstrap b = newRealMethodsMock();
        doReturn(false).doReturn(false).when(b).isExecutorTabOpen();
        doNothing().when(b).closeExecutor();
        doReturn(null).when(b).openExecutor();

        assertThrows(IllegalStateException.class, () -> b.ensureExecutorTabFresh());
    }

    @Test
    void adoptedChromeStopClosesViaBrowserClose() throws Exception {
        CdpBootstrap b = newRealMethodsMock();
        doNothing().when(b).closeReviewer();
        doNothing().when(b).closeExecutor();

        CdpSession sessMock = Mockito.mock(CdpSession.class);
        Mockito.when(sessMock.waitForDisconnect(anyLong())).thenReturn(true);
        setField(CdpBootstrap.class, b, "session", sessMock);
        setBooleanField(CdpBootstrap.class, b, "chromeAdopted", true);
        setLongField(CdpBootstrap.class, b, "adoptedChromePid", 12345L);

        try (MockedStatic<ChromeProcessLauncher> cpl = mockStatic(ChromeProcessLauncher.class)) {
            b.stop();
            Mockito.verify(sessMock).browserClose();
            cpl.verify(() -> ChromeProcessLauncher.killPidTree(anyLong()), Mockito.never());
        }
    }

    @Test
    void adoptedChromeStopFallsBackToKill() throws Exception {
        CdpBootstrap b = newRealMethodsMock();
        doNothing().when(b).closeReviewer();
        doNothing().when(b).closeExecutor();

        CdpSession sessMock = Mockito.mock(CdpSession.class);
        Mockito.when(sessMock.waitForDisconnect(anyLong())).thenReturn(false);
        setField(CdpBootstrap.class, b, "session", sessMock);
        setBooleanField(CdpBootstrap.class, b, "chromeAdopted", true);
        setLongField(CdpBootstrap.class, b, "adoptedChromePid", 12345L);

        try (MockedStatic<ChromeProcessLauncher> cpl = mockStatic(ChromeProcessLauncher.class)) {
            b.stop();
            Mockito.verify(sessMock).browserClose();
            cpl.verify(() -> ChromeProcessLauncher.killPidTree(12345L));
        }
    }

    @Test
    void isRunningReturnsTrueForAdoptedChrome() throws Exception {
        CdpBootstrap b = newRealMethodsMock();
        CdpSession sessMock = Mockito.mock(CdpSession.class);
        Mockito.when(sessMock.isConnected()).thenReturn(true);
        setField(CdpBootstrap.class, b, "session", sessMock);
        // chromeProcess left null (adopted-Chrome marker)

        assertTrue(b.isRunning());
    }

    @Test
    void isRunningReturnsFalseWhenSessionNotConnected() throws Exception {
        CdpBootstrap b = newRealMethodsMock();
        CdpSession sessMock = Mockito.mock(CdpSession.class);
        Mockito.when(sessMock.isConnected()).thenReturn(false);
        setField(CdpBootstrap.class, b, "session", sessMock);

        assertFalse(b.isRunning());
    }
}

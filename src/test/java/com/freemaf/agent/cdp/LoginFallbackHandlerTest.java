
package com.freemaf.agent.cdp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginFallbackHandlerTest {

    private static Process dummyProcess() throws IOException {
        return new ProcessBuilder("cmd", "/C", "exit", "0").start();
    }

    @Test
    void ensureLoggedInReturnsTrueWithoutRestartWhenAlreadyLoggedIn() throws Exception {
        AtomicInteger startCalls = new AtomicInteger(0);
        AtomicInteger stopCalls = new AtomicInteger(0);
        ChromeLauncherFacade facade = new ChromeLauncherFacade() {
            @Override
            public Process start(boolean headless, int port) throws IOException {
                startCalls.incrementAndGet();
                return dummyProcess();
            }
            @Override
            public void stop(Process process) {
                stopCalls.incrementAndGet();
            }
        };
        LoginFallbackHandler handler = new LoginFallbackHandler(9224, "agent/cache/cdp-profile", facade);
        Supplier<Boolean> check = () -> Boolean.TRUE;
        boolean result = handler.ensureLoggedIn(check, 1000L);
        assertTrue(result, "already-logged-in must return true");
        assertEquals(0, startCalls.get(), "no Chrome restart expected");
        assertEquals(0, stopCalls.get(), "no Chrome stop expected");
    }

    @Test
    void ensureLoggedInReturnsFalseAndStopsHeadfulOnTimeout() throws Exception {
        AtomicInteger startCalls = new AtomicInteger(0);
        AtomicInteger stopCalls = new AtomicInteger(0);
        ChromeLauncherFacade facade = new ChromeLauncherFacade() {
            @Override
            public Process start(boolean headless, int port) throws IOException {
                startCalls.incrementAndGet();
                return dummyProcess();
            }
            @Override
            public void stop(Process process) {
                stopCalls.incrementAndGet();
            }
        };
        LoginFallbackHandler handler = new LoginFallbackHandler(9224, "agent/cache/cdp-profile", facade);
        Supplier<Boolean> check = () -> Boolean.FALSE;
        boolean result = handler.ensureLoggedIn(check, 100L);
        assertFalse(result, "timeout must return false");
        assertEquals(1, startCalls.get(), "exactly one headful launch expected");
        assertTrue(stopCalls.get() >= 1, "headful must be stopped on timeout");
    }
}

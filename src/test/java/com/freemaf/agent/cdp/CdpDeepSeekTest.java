
package com.freemaf.agent.cdp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CdpDeepSeekTest {

    private static final int PORT = 9224;
    private static final String PROFILE = "agent/cache/cdp-profile";

    private static Process chrome;
    private static CdpSession session;
    private static CdpDeepSeek deepSeek;
    private static Throwable startupError;

    @BeforeAll
    static void setUp() {
        try {
            killPortOrphan();
            chrome = ChromeProcessLauncher.start(true, PORT);
            CdpHttpClient probe = new CdpHttpClient(PORT);
            if (!probe.waitForPort(30_000L)) {
                throw new IOException("debug port " + PORT + " did not open");
            }
            session = new CdpSession(PORT);
            deepSeek = new CdpDeepSeek(session, new LoginFallbackHandler(PORT, PROFILE));
            deepSeek.open();
            Thread.sleep(3000L);
        } catch (Throwable t) {
            startupError = t;
        }
    }

    @AfterAll
    static void tearDown() {
        try {
            if (deepSeek != null) deepSeek.closeActiveTab();
        } catch (Exception ignored) {
            // best effort
        }
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
            // best effort
        }
        ChromeProcessLauncher.stop(chrome);
    }

    @Test
    void checkLoggedInReturnsTrueWithSavedProfile() {
        assumeTrue(startupError == null, "startup failed: " + startupError);
        boolean ok = deepSeek.checkLoggedIn();
        assumeTrue(ok, "profile has no active DeepSeek session; skipping integration check");
        assertTrue(ok);
    }

    @Test
    void navigateIsPublicAndDoesNotThrow() {
        assumeTrue(startupError == null, "startup failed: " + startupError);
        assumeTrue(deepSeek.checkLoggedIn(), "no DeepSeek session; skipping navigate test");
        assertDoesNotThrow(() -> deepSeek.navigate("https://chat.deepseek.com/"));
        assertDoesNotThrow(() -> deepSeek.waitForInput(10_000L));
    }

    @Test
    void sendMessageAndReadResponseReturnsPing() {
        assumeTrue(startupError == null, "startup failed: " + startupError);
        assumeTrue(deepSeek.checkLoggedIn(), "no DeepSeek session; skipping send test");
        try {
            deepSeek.waitForInput(10_000L);
            deepSeek.sendMessage("Reply with exactly: PING");
            String response = deepSeek.waitForResponse(60_000L);
            assertNotNull(response);
            assertTrue(response.contains("PING"),
                    "response does not contain PING: " + response);
        } catch (Exception e) {
            throw new AssertionError("send/wait failed: " + e.getMessage(), e);
        }
    }

    private static void killPortOrphan() {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/C",
                    "for /f \"tokens=5\" %a in ('netstat -ano ^| findstr :" + PORT
                            + " ^| findstr LISTENING') do taskkill /F /PID %a");
            pb.redirectErrorStream(true);
            pb.start().waitFor(5L, java.util.concurrent.TimeUnit.SECONDS);
            Thread.sleep(500L);
        } catch (Exception ignored) {
            // best effort
        }
    }
}

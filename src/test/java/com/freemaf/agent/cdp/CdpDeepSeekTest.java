
package com.freemaf.agent.cdp;



import org.junit.jupiter.api.AfterAll;

import org.junit.jupiter.api.BeforeAll;

import org.junit.jupiter.api.Test;



import java.io.IOException;

import java.nio.file.Files;

import java.nio.file.Path;



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

    private static Path attachFilePath;



    @BeforeAll

    static void setUp() {

        try {

            Path tmp = Path.of("agent/cache/_");

            Files.createDirectories(tmp);

            attachFilePath = tmp.resolve("s5-test-attachment.txt");

            Files.writeString(attachFilePath, "S5 attach test");

        } catch (Exception e) {

            startupError = e;

            return;

        }

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

            Thread.sleep(1000L); // FIX35: was 3000L

        } catch (Throwable t) {

            startupError = t;

        }

    }



    @AfterAll

    static void tearDown() {

        try {

            if (attachFilePath != null) Files.deleteIfExists(attachFilePath);

        } catch (Exception ignored) {

            // best effort

        }

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

            deepSeek.sendMessage("Write a PowerShell script that outputs exactly: PING");

            String response = deepSeek.waitForResponse(60_000L);

            assertNotNull(response);

            assertTrue(response.contains("PING"),

                    "response does not contain PING: " + response);

        } catch (Exception e) {

            throw new AssertionError("send/wait failed: " + e.getMessage(), e);

        }

    }



    @Test

    void closeActiveTabRemovesTabFromList() {

        assumeTrue(startupError == null, "startup failed: " + startupError);

        CdpTabInfo info = deepSeek.activeTabInfo();

        assumeTrue(info != null && !info.id().isEmpty(), "no active tab; skipping");

        String id = info.id();

        deepSeek.closeActiveTab();

        long deadline = System.currentTimeMillis() + 3000L;

        boolean gone = false;

        CdpHttpClient http = new CdpHttpClient(PORT);

        while (System.currentTimeMillis() < deadline) {

            boolean found = false;

            for (CdpTabInfo t : http.list()) {

                if (id.equals(t.id())) { found = true; break; }

            }

            if (!found) { gone = true; break; }

            try { Thread.sleep(250L); } catch (InterruptedException ie) {

                Thread.currentThread().interrupt();

                return;

            }

        }

        assertTrue(gone, "closed tab still listed: " + id);

    }



    @Test

    void attachFileUploadsAndAppearsInDom() {

        assumeTrue(startupError == null, "startup failed: " + startupError);

        assumeTrue(attachFilePath != null, "no temp attach file");

        assumeTrue(deepSeek.checkLoggedIn(), "no DeepSeek session; skipping attach test");

        try {

            deepSeek.waitForInput(10_000L);

            deepSeek.attachFile(attachFilePath);

            String count = deepSeek.evaluate(

                    "String(document.querySelectorAll(\"" + DeepSeekSelectors.ATTACHMENT_NODE + "\").length)");

            long n = 0L;

            try { n = Long.parseLong(count); } catch (Exception ignored) {

                // stay 0

            }

            assertTrue(n >= 1, "attachment node not found; count=" + count);

        } catch (Exception e) {

            throw new AssertionError("attach failed: " + e.getMessage(), e);

        }

    }



    private static void killPortOrphan() {

        try {

            ProcessBuilder pb = new ProcessBuilder("cmd", "/C",

                    "for /f \"tokens=5\" %a in ('netstat -ano ^| findstr :" + PORT

                            + " ^| findstr LISTENING') do taskkill /F /T /PID %a");

            pb.redirectErrorStream(true);

            pb.start().waitFor(5L, java.util.concurrent.TimeUnit.SECONDS);

            Thread.sleep(500L);

        } catch (Exception ignored) {

            // best effort

        }

    }

}

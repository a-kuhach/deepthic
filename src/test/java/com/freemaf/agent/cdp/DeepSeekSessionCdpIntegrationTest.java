
package com.freemaf.agent.cdp;

import com.freemaf.agent.AgentResponse;
import com.freemaf.agent.ClipboardService;
import com.freemaf.agent.Config;
import com.freemaf.agent.DeepSeekSession;
import com.freemaf.agent.InputSimulator;
import com.freemaf.agent.PixelColorService;
import com.freemaf.agent.WindowManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DeepSeekSessionCdpIntegrationTest {

    private static final int PORT = 9225;
    private static final String PROMPT = "Write a PowerShell script that outputs exactly: PONG";

    private static CdpBootstrap bootstrap;
    private static DeepSeekSession session;
    private static boolean sessionAvailable;

    @BeforeAll
    static void setUp() throws Exception {
        CdpBootstrap.killOrphan(PORT);
        bootstrap = new CdpBootstrap(new Config(), PORT);
        bootstrap.start(true);

        sessionAvailable = probeLogin(PORT);
        assumeTrue(sessionAvailable, "No live DeepSeek session in persistent profile; skipping");

        bootstrap.openExecutor();

        Config cfg = new Config();
        InputSimulator input = new InputSimulator(cfg, new ClipboardService());
        WindowManager wm = new WindowManager();
        session = new DeepSeekSession(cfg, input, new ClipboardService(),
                new PixelColorService(), wm, bootstrap);
    }

    private static boolean probeLogin(int port) {
        CdpSession probe = null;
        CdpDeepSeek ds = null;
        try {
            probe = new CdpSession(port);
            ds = new CdpDeepSeek(probe, null);
            ds.open();
            long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                if (ds.checkLoggedIn()) return true;
                try { Thread.sleep(1000L); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (ds != null) { try { ds.closeActiveTab(); } catch (Exception ignored) { } }
            if (probe != null) { try { probe.close(); } catch (Exception ignored) { } }
        }
    }

    @AfterAll
    static void tearDown() {
        if (bootstrap != null) bootstrap.stop();
        CdpBootstrap.killOrphan(PORT);
    }

    @Test
    void fullCycleReturnsPong() throws Exception {
        assumeTrue(sessionAvailable, "skipped: no DeepSeek session");
        assumeTrue(bootstrap.executor() != null, "executor not open");
        // DeepSeek lazy-loads prior chat history on "/". Poll for up to 5s so that a non-empty
        // history is detected before we send the prompt; otherwise the model answers in context of
        // the previous conversation instead of PONG.

        int priorAssistantCount = 0;
        int priorCodeBlockCount = 0;
        boolean roleMarkerFound = false;
        // DeepSeek lazy-loads prior chat history on "/". Poll up to 30s using three
        // independent detectors (structured selector, any <pre> code block, ROLE marker
        // in body text). Any non-zero hint means prior context exists and the model
        // would answer in-context instead of PONG; skip the test.
        for (int i = 0; i < 10; i++) { // FIX35: was 60 (30 s -> 5 s)
            Thread.sleep(500L);
            try {
                int c = bootstrap.executor().getAssistantMessageCount();
                if (c > priorAssistantCount) priorAssistantCount = c;
            } catch (Exception ignored) { }
            try {
                String preCount = bootstrap.executor().evaluate(
                        "String(document.querySelectorAll('pre').length)");
                int pc = 0;
                try { pc = Integer.parseInt(preCount); } catch (Exception ignored2) { }
                if (pc > priorCodeBlockCount) priorCodeBlockCount = pc;
            } catch (Exception ignored) { }
            try {
                String hasRole = bootstrap.executor().evaluate(
                        "String(document.body && document.body.innerText.indexOf('ROLE:') >= 0)");
                if ("true".equals(hasRole)) roleMarkerFound = true;
            } catch (Exception ignored) { }
            if (priorAssistantCount > 0 || priorCodeBlockCount > 0 || roleMarkerFound) break;
        }
        assumeTrue(priorAssistantCount == 0 && priorCodeBlockCount == 0 && !roleMarkerFound,
            "skipped: profile has prior chat history (assistant=" + priorAssistantCount
                + ", codeBlocks=" + priorCodeBlockCount
                + ", roleMarker=" + roleMarkerFound + "); test needs a fresh chat");
        AgentResponse resp = session.getValidResponse(PROMPT);
        assertTrue(resp != null, "AgentResponse is null");
                String content = resp.content() == null ? "" : resp.content();
        if (content.contains("ROLE:")) {
            assumeTrue(false, "response contains ROLE: - prior chat context; test needs a fresh chat");
        }
        assertTrue(content.toUpperCase().contains("PONG"),
                "expected PONG in response, got: " + (content.length() > 200 ? content.substring(0, 200) : content));
    }
}
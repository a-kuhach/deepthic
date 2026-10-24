
package com.freemaf.agent.cdp;



import com.freemaf.agent.Config;

import org.junit.jupiter.api.AfterAll;

import org.junit.jupiter.api.BeforeAll;

import org.junit.jupiter.api.MethodOrderer;

import org.junit.jupiter.api.Order;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.TestMethodOrder;



import java.util.List;



import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assumptions.assumeTrue;



@TestMethodOrder(MethodOrderer.OrderAnnotation.class)

@Disabled("Requires live DeepSeek session with fresh chat; excluded from automated suite")
class CdpBootstrapTest {



    private static final int PORT = 9225;

    private static CdpBootstrap bootstrap;

    private static boolean sessionAvailable;



    @BeforeAll

    static void setUp() throws Exception {

        CdpBootstrap.killOrphan(PORT);

        bootstrap = new CdpBootstrap(new Config(), PORT);

        bootstrap.start(true);

        sessionAvailable = probeLogin(PORT);

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

    @Order(1)

    void startIsIdempotentOrRejectsSecondStart() throws Exception {

        bootstrap.start(true);

        assertNotNull(bootstrap);

    }



    @Test

    @Order(2)

    void openExecutorOpensTabAndWaitsInput() throws Exception {

        assumeTrue(sessionAvailable, "No live DeepSeek session in persistent profile; skipping");

        CdpDeepSeek ex = bootstrap.openExecutor();

        assertNotNull(ex);

        assertNotNull(ex.activeTabInfo());

        assertTrue(!ex.activeTabInfo().id().isEmpty(), "tab id is empty");

    }



    @Test

    @Order(3)

    void closeExecutorRemovesTab() throws Exception {

        assumeTrue(sessionAvailable, "No live DeepSeek session in persistent profile; skipping");

        CdpDeepSeek ex = bootstrap.executor();

        String id = ex == null || ex.activeTabInfo() == null ? "" : ex.activeTabInfo().id();

        bootstrap.closeExecutor();

        assertNull(bootstrap.executor());

        if (!id.isEmpty()) {

            assertTrue(waitTabGone(id, 3000L), "closed tab still listed after 3 s: " + id);

        }

    }



    @Test

    @Order(4)

    void openReviewerTwiceClosesPrevious() throws Exception {

        assumeTrue(sessionAvailable, "No live DeepSeek session; skipping");

        bootstrap.openReviewer();

        CdpTabInfo first = bootstrap.reviewerTabInfo();

        assertNotNull(first);

        String firstId = first.id();

        bootstrap.openReviewer();

        CdpTabInfo second = bootstrap.reviewerTabInfo();

        assertNotNull(second);

        assertTrue(!firstId.equals(second.id()), "reviewer tab id was reused");

        assertTrue(waitTabGone(firstId, 3000L), "previous reviewer tab still listed: " + firstId);

    }



    @Test

    @Order(5)

    void closeReviewerRemovesTab() throws Exception {

        assumeTrue(sessionAvailable, "No live DeepSeek session; skipping");

        if (bootstrap.reviewer() == null) bootstrap.openReviewer();

        CdpTabInfo info = bootstrap.reviewerTabInfo();

        String id = info == null ? "" : info.id();

        bootstrap.closeReviewer();

        assertNull(bootstrap.reviewerTabInfo());

        if (!id.isEmpty()) {

            assertTrue(waitTabGone(id, 3000L), "closed reviewer tab still listed: " + id);

        }

    }



    @Test

    @Order(6)

    void activateTabSwitchesActive() throws Exception {

        assumeTrue(sessionAvailable, "No live DeepSeek session; skipping");

        bootstrap.openExecutor();

        bootstrap.openReviewer();

        assertDoesNotThrow(() -> bootstrap.activateExecutor());

        assertDoesNotThrow(() -> bootstrap.activateReviewer());

        assertTrue(bootstrap.isExecutorTabOpen(), "executor tab not detected as open");

        assertTrue(bootstrap.isReviewerTabOpen(), "reviewer tab not detected as open");

    }



    @Test

    @Order(7)

    void stopClosesAllTabs() throws Exception {

        assumeTrue(sessionAvailable, "No live DeepSeek session; skipping");

        bootstrap.openExecutor();

        bootstrap.openReviewer();

        bootstrap.stop();

        CdpHttpClient http = new CdpHttpClient(PORT);

        List<CdpTabInfo> remaining = http.list();

        int deepseekCount = 0;

        for (CdpTabInfo t : remaining) {

            String url = t.url() == null ? "" : t.url();

            if (url.contains("deepseek.com")) deepseekCount++;

        }

        assertEquals(0, deepseekCount, "deepseek tabs remained after stop()");

        bootstrap = null;

    }



    private static boolean waitTabGone(String id, long timeoutMs) {

        long deadline = System.currentTimeMillis() + timeoutMs;

        CdpHttpClient http = new CdpHttpClient(PORT);

        while (System.currentTimeMillis() < deadline) {

            boolean found = false;

            for (CdpTabInfo t : http.list()) {

                if (id.equals(t.id())) { found = true; break; }

            }

            if (!found) return true;

            try { Thread.sleep(250L); } catch (InterruptedException ie) {

                Thread.currentThread().interrupt();

                return false;

            }

        }

        return false;

    }

}

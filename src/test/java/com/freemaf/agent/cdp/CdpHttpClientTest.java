package com.freemaf.agent.cdp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdpHttpClientTest {

    private static final int PORT = 9222;
    private static Process chrome;
    private static CdpHttpClient client;

    @BeforeAll
    static void setUp() throws Exception {
        chrome = ChromeProcessLauncher.start(true, PORT);
        client = new CdpHttpClient(PORT);
        assertTrue(client.waitForPort(30_000L), "debug port did not become ready");
    }

    @AfterAll
    static void tearDown() {
        ChromeProcessLauncher.stop(chrome);
    }

    @Test
    void versionReturnsNonEmpty() {
        String v = client.version();
        assertNotNull(v);
        assertFalse(v.isEmpty(), "version() must not be empty");
        assertFalse("unknown".equals(v), "version() must not return \"unknown\" on a live Chrome");
    }

    @Test
    void listAfterNewTabContainsBlank() {
        CdpTabInfo tab = client.newTab();
        assertNotNull(tab, "newTab returned null");
        assertFalse(tab.id().isEmpty(), "newTab returned empty id");
        List<CdpTabInfo> list = client.list();
        boolean found = false;
        for (CdpTabInfo t : list) {
            if (t.id().equals(tab.id())) {
                found = true;
                assertEquals("about:blank", t.url(), "new tab url should be about:blank");
                break;
            }
        }
        assertTrue(found, "newly created tab not present in list");
        client.closeTab(tab.id());
    }


    @Test
    void closeTabRemovesFromList() throws Exception {
        CdpTabInfo tab = client.newTab();
        assertNotNull(tab);
        assertTrue(client.closeTab(tab.id()), "closeTab returned false");
        long deadline = System.currentTimeMillis() + 3000L;
        boolean stillPresent = true;
        while (System.currentTimeMillis() < deadline) {
            stillPresent = false;
            for (CdpTabInfo t : client.list()) {
                if (t.id().equals(tab.id())) { stillPresent = true; break; }
            }
            if (!stillPresent) break;
            Thread.sleep(100L);
        }
        assertFalse(stillPresent, "tab still present after close");
    }

    @Test
    void closeNonexistentReturnsFalse() {
        assertFalse(client.closeTab("no-such-id-12345"),
                "closing a nonexistent tab must return false");
    }
}

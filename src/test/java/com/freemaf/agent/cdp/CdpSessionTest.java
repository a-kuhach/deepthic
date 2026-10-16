package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdpSessionTest {

    private static final int PORT = 9223;
    private static Process chrome;
    private static CdpSession session;

    @BeforeAll
    static void setUp() throws Exception {
        chrome = ChromeProcessLauncher.start(true, PORT);
        session = new CdpSession(PORT);
    }

    @AfterAll
    static void tearDown() {
        if (session != null) session.close();
        ChromeProcessLauncher.stop(chrome);
    }

    @Test
    void attachReturnsTabSession() throws Exception {
        CdpTabInfo tab = session.newTab();
        assertNotNull(tab, "newTab returned null");
        TabSession ts = session.attach(tab);
        assertNotNull(ts, "attach returned null");
        assertFalse(ts.sessionId().isEmpty(), "sessionId must not be empty");
        ts.close();
        session.closeTab(tab);
    }

    @Test
    void navigateViaTabSession() throws Exception {
        CdpTabInfo tab = session.newTab();
        TabSession ts = session.attach(tab);
        Map<String, Object> params = new HashMap<>();
        params.put("url", "about:blank");
        JsonNode resp = ts.send("Page.navigate", params);
        assertNotNull(resp, "Page.navigate returned null");
        ts.close();
        session.closeTab(tab);
    }


    @Test
    void closeTabRemovesFromList() throws Exception {
        CdpTabInfo tab = session.newTab();
        TabSession ts = session.attach(tab);
        assertTrue(session.closeTab(tab), "closeTab returned false");
        long deadline = System.currentTimeMillis() + 3000L;
        boolean stillPresent = true;
        while (System.currentTimeMillis() < deadline) {
            stillPresent = false;
            for (CdpTabInfo t : session.listTabs()) {
                if (t.id().equals(tab.id())) { stillPresent = true; break; }
            }
            if (!stillPresent) break;
            Thread.sleep(100L);
        }
        assertFalse(stillPresent, "tab still in list after close");
        ts.close();
    }
}

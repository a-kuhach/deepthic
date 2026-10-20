package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CdpDeepSeekReadResponseTest {

    private static final ObjectMapper M = new ObjectMapper();

    private CdpDeepSeek dsWithTab(TabSession tab) throws Exception {
        CdpSession session = mock(CdpSession.class);
        CdpDeepSeek ds = new CdpDeepSeek(session, null);
        Field f = CdpDeepSeek.class.getDeclaredField("activeTab");
        f.setAccessible(true);
        f.set(ds, tab);
        return ds;
    }

    private static JsonNode nestedValue(String value) {
        ObjectNode root = M.createObjectNode();
        ObjectNode result1 = root.putObject("result");
        ObjectNode result2 = result1.putObject("result");
        result2.put("type", "string").put("value", value);
        return root;
    }

    @Test
    void readLastResponsePassesThroughPreExtractedValue() throws Exception {
        String script = "Write-Output \"ROLE: ANALYST\"\r\nWrite-Output \"CWD: X\"";
        TabSession tab = mock(TabSession.class);
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(nestedValue(script));
        assertEquals(script, dsWithTab(tab).readLastResponse());
    }

    @Test
    void readLastResponseReturnsEmptyWhenNoMessages() throws Exception {
        TabSession tab = mock(TabSession.class);
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(nestedValue(""));
        assertEquals("", dsWithTab(tab).readLastResponse());
    }


    @Test
    void readLastResponseFallsBackWhenCodeBlockEmpty() throws Exception {
        // First evaluate (md-code-block pre) returns empty; second (fallback innerText) returns full text.
        String fullText = "Write-Output \"ROLE: EXECUTOR\"";
        TabSession tab = mock(TabSession.class);
        when(tab.send(eq("Runtime.evaluate"), any()))
                .thenReturn(nestedValue(""), nestedValue(fullText));
        assertEquals(fullText, dsWithTab(tab).readLastResponse());
    }


    // ---- FIX141: computeIsNew unit tests ----

    @Test
    void computeIsNewAcceptsNewTextResponse() {
        // Different text => new, regardless of counts.
        assertTrue(CdpDeepSeek.computeIsNew("B", "A", 5, 5, false));
        assertTrue(CdpDeepSeek.computeIsNew("B", "A", 20, 5, true));
    }

    @Test
    void computeIsNewAcceptsIdenticalTextWhenCountGrewWithoutVirtualization() {
        // Identical text, count grew, no virtualization => new (deterministic short answers).
        assertTrue(CdpDeepSeek.computeIsNew("A", "A", 6, 5, false));
    }

    @Test
    void computeIsNewRejectsIdenticalTextWhenVirtualizationOccurred() {
        // Identical text, count recovered after virtualization => NOT new (regression guard for FIX140).
        assertFalse(CdpDeepSeek.computeIsNew("A", "A", 65, 5, true));
        assertFalse(CdpDeepSeek.computeIsNew("A", "A", 5, 5, true));
    }

    @Test
    void computeIsNewRejectsIdenticalTextWithoutCountGrowth() {
        // Identical text, count unchanged => NOT new (still waiting).
        assertFalse(CdpDeepSeek.computeIsNew("A", "A", 5, 5, false));
    }

    @Test
    void computeIsNewHandlesNullInputsSafely() {
        assertFalse(CdpDeepSeek.computeIsNew(null, "A", 5, 5, false));
        // null preText treated as empty string
        assertTrue(CdpDeepSeek.computeIsNew("A", null, 5, 5, false));
        assertFalse(CdpDeepSeek.computeIsNew("", null, 5, 5, false));
    }
}

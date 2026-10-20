package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

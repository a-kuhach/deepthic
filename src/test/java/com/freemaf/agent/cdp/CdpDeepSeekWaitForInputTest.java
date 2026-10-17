package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CdpDeepSeekWaitForInputTest {

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
        ObjectNode r1 = root.putObject("result");
        ObjectNode r2 = r1.putObject("result");
        r2.put("type", "string").put("value", value);
        return root;
    }

    @Test
    void waitForInputSucceedsImmediately() throws Exception {
        TabSession tab = mock(TabSession.class);
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(nestedValue("true"));
        dsWithTab(tab).waitForInput(2000L);
    }

    @Test
    void waitForInputTimeoutMessageContainsDiagnostic() throws Exception {
        TabSession tab = mock(TabSession.class);
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(nestedValue("false"));
        TimeoutException ex = assertThrows(TimeoutException.class,
                () -> dsWithTab(tab).waitForInput(300L));
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(msg.contains("url="), "message should contain url=, got: " + msg);
        assertTrue(msg.contains("hasMarkdown="), "message should contain hasMarkdown=, got: " + msg);
        assertTrue(msg.contains("readyState="), "message should contain readyState=, got: " + msg);
    }
}

package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S2.2 / AUD-51: CdpDeepSeek.sendMessage must reject null/blank prompts loudly
 * instead of silently dispatching an empty message.
 */
class CdpDeepSeekSendMessageTest {

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
    void nullPromptIsRejected() throws Exception {
        CdpDeepSeek ds = dsWithTab(mock(TabSession.class));
        assertThrows(IllegalArgumentException.class, () -> ds.sendMessage(null));
    }

    @Test
    void blankPromptIsRejected() throws Exception {
        CdpDeepSeek ds = dsWithTab(mock(TabSession.class));
        assertThrows(IllegalArgumentException.class, () -> ds.sendMessage(""));
        assertThrows(IllegalArgumentException.class, () -> ds.sendMessage("   "));
        assertThrows(IllegalArgumentException.class, () -> ds.sendMessage("\t\n  "));
    }

    @Test
    void validPromptDoesNotFireGuard() throws Exception {
        TabSession tab = mock(TabSession.class);
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(nestedValue(""));
        CdpDeepSeek ds = dsWithTab(tab);
        try {
            ds.sendMessage("hello world");
        } catch (IllegalArgumentException iae) {
            throw new AssertionError("guard fired on non-blank prompt: " + iae.getMessage(), iae);
        } catch (Exception ignored) {
            // acceptable: e.g. IllegalStateException if mocked textarea never clears
        }
    }
}

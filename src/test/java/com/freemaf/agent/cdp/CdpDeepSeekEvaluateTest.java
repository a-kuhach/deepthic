package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CdpDeepSeekEvaluateTest {

    private static final ObjectMapper M = new ObjectMapper();

    private CdpDeepSeek dsWithTab(TabSession tab) throws Exception {
        CdpSession session = mock(CdpSession.class);
        CdpDeepSeek ds = new CdpDeepSeek(session, null);
        Field f = CdpDeepSeek.class.getDeclaredField("activeTab");
        f.setAccessible(true);
        f.set(ds, tab);
        return ds;
    }

    @Test
    void evaluateReturnsValueFromNestedResultResultValue() throws Exception {
        TabSession tab = mock(TabSession.class);
        JsonNode resp = M.readTree("{\"id\":1,\"result\":{\"result\":{\"type\":\"string\",\"value\":\"PONG\"}}}");
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(resp);
        assertEquals("PONG", dsWithTab(tab).evaluate("expr"));
    }

    @Test
    void evaluateReturnsFlatValueFromLegacyResultValue() throws Exception {
        TabSession tab = mock(TabSession.class);
        JsonNode resp = M.readTree("{\"id\":1,\"result\":{\"type\":\"string\",\"value\":\"1\"}}");
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(resp);
        assertEquals("1", dsWithTab(tab).evaluate("expr"));
    }

    @Test
    void evaluatePrefersNestedOverFlatWhenBothPresent() throws Exception {
        TabSession tab = mock(TabSession.class);
        JsonNode resp = M.readTree("{\"id\":1,\"result\":{\"type\":\"string\",\"value\":\"flat\",\"result\":{\"type\":\"string\",\"value\":\"nested\"}}}");
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(resp);
        assertEquals("nested", dsWithTab(tab).evaluate("expr"));
    }

    @Test
    void evaluateReturnsEmptyStringWhenValueMissing() throws Exception {
        TabSession tab = mock(TabSession.class);
        JsonNode resp = M.readTree("{\"id\":1,\"result\":{\"result\":{\"type\":\"undefined\"}}}");
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(resp);
        assertEquals("", dsWithTab(tab).evaluate("void 0"));
    }

    @Test
    void evaluateReturnsEmptyStringOnMissingResultResult() throws Exception {
        TabSession tab = mock(TabSession.class);
        JsonNode resp = M.readTree("{\"id\":1,\"result\":{}}");
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(resp);
        assertEquals("", dsWithTab(tab).evaluate("void 0"));
    }

    @Test
    void checkLoggedInTrueWhenInputPresent() throws Exception {
        TabSession tab = mock(TabSession.class);
        JsonNode urlResp = M.readTree("{\"id\":1,\"result\":{\"result\":{\"type\":\"string\",\"value\":\"https://chat.deepseek.com/\"}}}");
        JsonNode hasResp = M.readTree("{\"id\":2,\"result\":{\"result\":{\"type\":\"string\",\"value\":\"true\"}}}");
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(urlResp, hasResp);
        assertTrue(dsWithTab(tab).checkLoggedIn());
    }

    @Test
    void checkLoggedInFalseOnSignInUrl() throws Exception {
        TabSession tab = mock(TabSession.class);
        JsonNode urlResp = M.readTree("{\"id\":1,\"result\":{\"result\":{\"type\":\"string\",\"value\":\"https://chat.deepseek.com/sign_in\"}}}");
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(urlResp);
        assertFalse(dsWithTab(tab).checkLoggedIn());
    }

    @Test
    void checkLoggedInFalseWhenInputMissing() throws Exception {
        TabSession tab = mock(TabSession.class);
        JsonNode urlResp = M.readTree("{\"id\":1,\"result\":{\"result\":{\"type\":\"string\",\"value\":\"https://chat.deepseek.com/\"}}}");
        JsonNode hasResp = M.readTree("{\"id\":2,\"result\":{\"result\":{\"type\":\"string\",\"value\":\"false\"}}}");
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(urlResp, hasResp);
        assertFalse(dsWithTab(tab).checkLoggedIn());
    }
}

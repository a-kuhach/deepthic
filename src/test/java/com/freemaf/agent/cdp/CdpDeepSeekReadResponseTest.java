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
    void readLastResponseDelegatesToDecide() throws Exception {
        String json = "{\"t\":\"hello\",\"p\":\"hello\",\"r\":false}";
        TabSession tab = mock(TabSession.class);
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(nestedValue(json));
        assertEquals("hello", dsWithTab(tab).readLastResponse());
    }

    @Test
    void readLastResponseReturnsEmptyOnInvalidJson() throws Exception {
        TabSession tab = mock(TabSession.class);
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(nestedValue("not valid json"));
        assertEquals("", dsWithTab(tab).readLastResponse());
    }

    @Test
    void readLastResponseReturnsEmptyWhenNoMessages() throws Exception {
        TabSession tab = mock(TabSession.class);
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(nestedValue(""));
        assertEquals("", dsWithTab(tab).readLastResponse());
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


    @Test
    void isResponseCompleteRejectsShortGarbage() {
        assertFalse(CdpDeepSeek.isResponseComplete(""));
        assertFalse(CdpDeepSeek.isResponseComplete(null));
        assertFalse(CdpDeepSeek.isResponseComplete("[ROLE] EXECUTOR"));
        assertFalse(CdpDeepSeek.isResponseComplete("abc"));
    }

    @Test
    void isResponseCompleteAcceptsScriptMarkers() {
        assertTrue(CdpDeepSeek.isResponseComplete("Write-Output \"USER_MSG: ok\"" + System.lineSeparator() + "$ErrorActionPreference = 'Continue'"));
        assertTrue(CdpDeepSeek.isResponseComplete("USER_CHAT: hello"));
        assertTrue(CdpDeepSeek.isResponseComplete("AGENT_PAUSE: waiting"));
    }

    @Test
    void isResponseCompleteAcceptsLongContentWithoutMarkers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("line ").append(i).append(System.lineSeparator());
        assertTrue(CdpDeepSeek.isResponseComplete(sb.toString()));
    }

    @Test
    void isResponseCompleteAcceptsMigrationMarkers() {
        assertTrue(CdpDeepSeek.isResponseComplete("AGENT_SESSION_MIGRATE_START: prep"));
        assertTrue(CdpDeepSeek.isResponseComplete("AGENT_SESSION_MIGRATE_CONFIRM: ok"));
    }

    @Test
    void clickRegenerateReturnsTrueOnSvgMatch() throws Exception {
        TabSession tab = mock(TabSession.class);
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(nestedValue("clicked-svg"));
        assertTrue(dsWithTab(tab).clickRegenerate());
    }


    @Test
    void isResponseCompleteAcceptsInvalidResponseMarker() {
        assertTrue(CdpDeepSeek.isResponseComplete("INVALID_RESPONSE_TEXT_AFTER_BLOCK_250"));
        assertTrue(CdpDeepSeek.isResponseComplete("INVALID_RESPONSE_TEXT_AFTER_BLOCK_1000"));
        assertFalse(CdpDeepSeek.isResponseComplete("something else"));
    }

    // ---- S2.2 / AUD-39: readLastResponse after-marker preservation ----

    @Test
    void decideResponseTextMarkerInAfterReturnsFullText() {
        // Step 4.3 / JUDGE 2b: EXACT fixture -- pre + Write-Output "USER_MSG: start" + trailing "extra".
        String pre = "Write-Output \"USER_MSG: start\"";
        String full = pre + "\nextra";
        String result = CdpDeepSeek.decideResponseText(full, pre, false);
        assertTrue(result.contains(pre), "must contain preText");
        assertTrue(result.contains("extra"), "must contain extra tail");
        assertEquals(full, result);
    }

    @Test
    void decideResponseTextMarkerInPreTextReturnsFullText() {
        String pre = "Write-Output \"USER_MSG: ok\"";
        assertEquals(pre, CdpDeepSeek.decideResponseText(pre, pre, false));
    }

    @Test
    void decideResponseTextMarkerAbsentLargeAfterReturnsInvalid() {
        String pre = "Write-Output 1";
        String after = "x".repeat(150);
        String full = pre + "\n" + after;
        assertEquals("INVALID_RESPONSE_TEXT_AFTER_BLOCK_" + after.length(),
                CdpDeepSeek.decideResponseText(full, pre, false));
    }

    @Test
    void decideResponseTextMarkerAbsentSmallAfterReturnsPreText() {
        String pre = "Write-Output 1";
        String full = pre + "\ntail";
        assertEquals(pre, CdpDeepSeek.decideResponseText(full, pre, false));
    }

    @Test
    void decideResponseTextMarkerAbsentNoPreReturnsEmpty() {
        assertEquals("", CdpDeepSeek.decideResponseText("plain text no marker", "", false));
    }

    @Test
    void decideResponseTextReasoningReturnsEmpty() {
        assertEquals("", CdpDeepSeek.decideResponseText("whatever", "whatever", true));
    }

    @Test
    void readLastResponseExprContractGuard() {
        // S2.2 REJECT-CORRECTION: JS now returns JSON; decision moved to decideResponseText (Java).
        String expr = CdpDeepSeek.READ_LAST_RESPONSE_EXPR;
        assertTrue(expr.contains("ds-think-content"), "reasoning guard missing");
        assertTrue(expr.contains("JSON.stringify"), "must return JSON contract");
        assertTrue(expr.contains("md-code-block pre"), "pre extraction missing");
        assertFalse(expr.contains("INVALID_RESPONSE_TEXT_AFTER_BLOCK_"), "decision moved to Java, not JS");
        assertFalse(expr.contains("USER_CHAT:"), "marker checks moved to Java, not JS");
    }

    @Test
    void isResponseCompleteAcceptsShortWriteOutputWithMarker() {
        assertTrue(CdpDeepSeek.isResponseComplete("Write-Output \"USER_MSG: ok\""));
        assertTrue(CdpDeepSeek.isResponseComplete("Write-Output \"AGENT_PAUSE: waiting\""));
        assertTrue(CdpDeepSeek.isResponseComplete("Write-Output \"AGENT_SESSION_MIGRATE_CONFIRM: ok\""));
    }

    @Test
    void isResponseCompleteRejectsShortWriteOutputWithoutMarker() {
        assertFalse(CdpDeepSeek.isResponseComplete("Write-Output \"hi\""));
        assertFalse(CdpDeepSeek.isResponseComplete("Write-Output \"ROLE: EXECUTOR\""));
    }
}

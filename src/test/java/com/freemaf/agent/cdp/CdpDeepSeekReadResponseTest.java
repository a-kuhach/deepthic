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

    // ---- readLastResponse wrapper tests ----

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
        assertTrue(CdpDeepSeek.computeIsNew("B", "A", 5, 5, false));
        assertTrue(CdpDeepSeek.computeIsNew("B", "A", 20, 5, true));
    }

    @Test
    void computeIsNewAcceptsIdenticalTextWhenCountGrewWithoutVirtualization() {
        assertTrue(CdpDeepSeek.computeIsNew("A", "A", 6, 5, false));
    }

    @Test
    void computeIsNewRejectsIdenticalTextWhenVirtualizationOccurred() {
        assertFalse(CdpDeepSeek.computeIsNew("A", "A", 65, 5, true));
        assertFalse(CdpDeepSeek.computeIsNew("A", "A", 5, 5, true));
    }

    @Test
    void computeIsNewRejectsIdenticalTextWithoutCountGrowth() {
        assertFalse(CdpDeepSeek.computeIsNew("A", "A", 5, 5, false));
    }

    @Test
    void computeIsNewHandlesNullInputsSafely() {
        assertFalse(CdpDeepSeek.computeIsNew(null, "A", 5, 5, false));
        assertTrue(CdpDeepSeek.computeIsNew("A", null, 5, 5, false));
        assertFalse(CdpDeepSeek.computeIsNew("", null, 5, 5, false));
    }

    // ---- isResponseComplete ----

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
    void isResponseCompleteAcceptsInvalidResponseMarker() {
        assertTrue(CdpDeepSeek.isResponseComplete("INVALID_RESPONSE_TEXT_AFTER_BLOCK_250"));
        assertTrue(CdpDeepSeek.isResponseComplete("INVALID_RESPONSE_TEXT_AFTER_BLOCK_1000"));
        assertFalse(CdpDeepSeek.isResponseComplete("something else"));
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

    @Test
    void clickRegenerateReturnsTrueOnSvgMatch() throws Exception {
        TabSession tab = mock(TabSession.class);
        when(tab.send(eq("Runtime.evaluate"), any())).thenReturn(nestedValue("clicked-svg"));
        assertTrue(dsWithTab(tab).clickRegenerate());
    }

    // ---- S2.3-fix1 / AUD-76: decideResponseText contract ----
    // Rejected semantics (S2.2 REJECT-CORRECTION 2a/2b): marker in after returned full text.
    // Correct semantics: marker is valid ONLY inside pre; marker in after -> INVALID + regenerate.
    // UI chrome in after (Copy / Download / language banner) is discarded when short and marker-free.

    @Test
    void decideResponseTextReasoningReturnsEmpty() {
        assertEquals("", CdpDeepSeek.decideResponseText("whatever", "whatever", true));
    }

    @Test
    void decideResponseTextEmptyReturnsEmpty() {
        assertEquals("", CdpDeepSeek.decideResponseText("", "", false));
        assertEquals("", CdpDeepSeek.decideResponseText(null, null, false));
    }

    @Test
    void decideResponseTextMarkerInsidePreReturnsPreText() {
        String pre = "Write-Output \"USER_MSG: start\"";
        assertEquals(pre, CdpDeepSeek.decideResponseText(pre, pre, false));
    }

    @Test
    void decideResponseTextMarkerInAfterReturnsInvalid() {
        String pre = "Write-Output 1";
        String after = "Write-Output \"USER_MSG: start\"";
        String full = pre + "\n" + after;
        assertEquals("INVALID_RESPONSE_TEXT_AFTER_BLOCK_" + after.length(),
                CdpDeepSeek.decideResponseText(full, pre, false));
    }

    @Test
    void decideResponseTextAgentDoneInAfterReturnsInvalid() {
        String pre = "Write-Output 1";
        String after = "AGENT_DONE: done";
        String full = pre + "\n" + after;
        assertEquals("INVALID_RESPONSE_TEXT_AFTER_BLOCK_" + after.length(),
                CdpDeepSeek.decideResponseText(full, pre, false));
    }

    @Test
    void decideResponseTextUserChatInAfterReturnsInvalid() {
        String pre = "Write-Output 1";
        String after = "USER_CHAT: hi";
        String full = pre + "\n" + after;
        assertEquals("INVALID_RESPONSE_TEXT_AFTER_BLOCK_" + after.length(),
                CdpDeepSeek.decideResponseText(full, pre, false));
    }

    @Test
    void decideResponseTextPreWithChromeAfterReturnsPreText() {
        String pre = "Write-Output 1";
        String chrome = "Copy\npowershell\nDownload";
        String full = pre + "\n" + chrome;
        assertEquals(pre, CdpDeepSeek.decideResponseText(full, pre, false));
    }

    @Test
    void decideResponseTextPreWithLongAfterReturnsInvalid() {
        String pre = "Write-Output 1";
        String after = "x".repeat(150);
        String full = pre + "\n" + after;
        assertEquals("INVALID_RESPONSE_TEXT_AFTER_BLOCK_" + after.length(),
                CdpDeepSeek.decideResponseText(full, pre, false));
    }

    @Test
    void decideResponseTextPreLessUserChatReturnsText() {
        assertEquals("USER_CHAT: hello", CdpDeepSeek.decideResponseText("USER_CHAT: hello", "", false));
    }

    @Test
    void decideResponseTextPreLessAgentDoneReturnsInvalid() {
        assertEquals("INVALID_RESPONSE_TEXT_AFTER_BLOCK_" + "AGENT_DONE: done".length(),
                CdpDeepSeek.decideResponseText("AGENT_DONE: done", "", false));
    }

    @Test
    void decideResponseTextPreLessUnrelatedReturnsEmpty() {
        assertEquals("", CdpDeepSeek.decideResponseText("plain text no marker", "", false));
    }

    @Test
    void readLastResponseExprContractGuard() {
        String expr = CdpDeepSeek.READ_LAST_RESPONSE_EXPR;
        assertTrue(expr.contains("ds-think-content"), "reasoning guard missing");
        assertTrue(expr.contains("JSON.stringify"), "must return JSON contract");
        assertTrue(expr.contains("md-code-block pre"), "pre extraction missing");
        assertFalse(expr.contains("INVALID_RESPONSE_TEXT_AFTER_BLOCK_"), "decision moved to Java, not JS");
        assertFalse(expr.contains("USER_CHAT:"), "marker checks moved to Java, not JS");
    }
}

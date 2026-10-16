
package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * High-level DeepSeek facade over CdpSession / TabSession.
 *
 * All DOM interaction (navigation, login check, send message, read response)
 * is performed through CDP Runtime.evaluate against the active tab.
 * The clipboard and pixel-based calibration are not used.
 */
public final class CdpDeepSeek {

    private static final String DEEPSEEK_URL = "https://chat.deepseek.com/";
    private static final long POLL_INTERVAL_MS = 500L;
    private static final int STABLE_ITERATIONS = 6;

    private final CdpSession session;
    private final LoginFallbackHandler loginHandler;

    private TabSession activeTab;
    private CdpTabInfo activeTabInfo;

    public CdpDeepSeek(CdpSession session, LoginFallbackHandler loginHandler) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        this.session = session;
        this.loginHandler = loginHandler;
    }

    public void open() throws Exception {
        activeTabInfo = session.newTab();
        if (activeTabInfo == null || activeTabInfo.id().isEmpty()) {
            throw new IllegalStateException("newTab returned no usable tab");
        }
        session.activateTab(activeTabInfo);
        activeTab = session.attach(activeTabInfo);
        navigate(DEEPSEEK_URL);
    }

    public void navigate(String url) {
        if (activeTab == null) throw new IllegalStateException("no active tab");
        Map<String, Object> params = new HashMap<>();
        params.put("url", url);
        activeTab.send("Page.navigate", params);
    }

    public boolean checkLoggedIn() {
        try {
            String url = evaluate("location.href");
            if (url == null) return false;
            if (url.contains("accounts.google.com")
                    || url.contains("/signin")
                    || url.contains("/sign_in")
                    || url.contains("/login")
                    || url.contains("oauth")) {
                return false;
            }
            String has = evaluate(
                    "String(!!document.querySelector(\"" + escapeJs(DeepSeekSelectors.INPUT) + "\")"
                            + " || !!document.querySelector('div.ds-markdown'))");
            return "true".equals(has);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean ensureSession(long loginWaitMs) throws Exception {
        if (checkLoggedIn()) return true;
        if (loginHandler == null) return false;
        boolean ok = loginHandler.ensureLoggedIn(this::checkLoggedIn, loginWaitMs);
        if (ok) {
            open();
        }
        return ok;
    }

    public void waitForInput(long timeoutMs) throws TimeoutException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String expr = "String(!!document.querySelector(\""
                + escapeJs(DeepSeekSelectors.INPUT) + "\"))";
        while (System.currentTimeMillis() < deadline) {
            if ("true".equals(evaluate(expr))) return;
            sleep(POLL_INTERVAL_MS);
        }
        throw new TimeoutException("input not found within " + timeoutMs + " ms");
    }

    public String evaluate(String expression) {
        if (activeTab == null) return "";
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("expression", expression);
            params.put("returnByValue", Boolean.TRUE);
            JsonNode resp = activeTab.send("Runtime.evaluate", params);
            if (resp == null) return "";
            JsonNode result = resp.get("result");
            if (result == null) return "";
            JsonNode value = result.get("value");
            return value == null || value.isNull() ? "" : value.asText();
        } catch (Exception e) {
            return "";
        }
    }

    public void sendMessage(String text) {
        String focusExpr =
                "(function(){var el=document.querySelector(\""
                + escapeJs(DeepSeekSelectors.INPUT)
                + "\");if(el)el.focus();return '';})()";
        evaluate(focusExpr);
        sleep(300L);

        String safe = escapeJsSingleQuoted(text);
        String insertExpr = "document.execCommand('insertText', false, '" + safe + "')";
        evaluate(insertExpr);
        sleep(500L);

        String enterExpr =
                "(function(){var el=document.querySelector(\""
                + escapeJs(DeepSeekSelectors.INPUT)
                + "\");if(!el)return '';"
                + "el.dispatchEvent(new KeyboardEvent('keydown',"
                + "{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));"
                + "return '';})()";
        evaluate(enterExpr);
    }

    public String readLastResponse() {
        String expr =
                "(function(){var m=document.querySelectorAll(\""
                + escapeJs(DeepSeekSelectors.ASSISTANT_MESSAGE)
                + "\");return m.length?m[m.length-1].innerText:'';})()";
        String v = evaluate(expr);
        return v == null ? "" : v;
    }

    public String waitForResponse(long timeoutMs) throws TimeoutException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = "";
        int stableCount = 0;
        while (System.currentTimeMillis() < deadline) {
            String cur = readLastResponse();
            if (cur != null && !cur.isEmpty()) {
                boolean streaming = countStreaming() > 0L;
                if (!streaming && cur.equals(last)) {
                    stableCount++;
                    if (stableCount >= STABLE_ITERATIONS) return cur;
                } else {
                    stableCount = 0;
                }
                last = cur;
            }
            sleep(POLL_INTERVAL_MS);
        }
        throw new TimeoutException("response did not stabilize within " + timeoutMs + " ms");
    }

    private long countStreaming() {
        String expr = "String(document.querySelectorAll(\""
                + escapeJs(DeepSeekSelectors.STREAMING_INDICATOR)
                + "\").length)";
        String v = evaluate(expr);
        try {
            return Long.parseLong(v);
        } catch (Exception e) {
            return 0L;
        }
    }

    public void closeActiveTab() {
        try {
            if (activeTabInfo != null) session.closeTab(activeTabInfo);
        } catch (Exception ignored) {
            // best effort
        }
        try {
            if (activeTab != null) activeTab.close();
        } catch (Exception ignored) {
            // best effort
        }
        activeTab = null;
        activeTabInfo = null;
    }

    public TabSession activeTab() {
        return activeTab;
    }

    public CdpTabInfo activeTabInfo() {
        return activeTabInfo;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String escapeJsSingleQuoted(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

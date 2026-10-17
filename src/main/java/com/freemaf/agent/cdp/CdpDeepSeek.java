
package com.freemaf.agent.cdp;



import com.fasterxml.jackson.databind.JsonNode;



import java.util.HashMap;

import java.util.Map;

import java.util.concurrent.TimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import com.freemaf.agent.AppLogger;



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
    private static final long ATTACH_POLL_MS = 200L;
    private static final long ATTACH_VISIBLE_TIMEOUT_MS = 5000L;
    private static final long CHOOSER_TIMEOUT_MS = 3000L;
    private static final int ATTACH_CLICK_ATTEMPTS = 3;



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

        try {

            Map<String, Object> pageEnable = new HashMap<>();

            activeTab.send("Page.enable", pageEnable);

        } catch (Exception e) {

            AppLogger.warn("Page.enable failed: " + e.getMessage());

        }

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

            JsonNode value = resp.path("result").path("result").path("value");

            if (value.isMissingNode()) {

                // Backward-compat: accept the flat {result: {value: ...}} shape used by some test mocks. Real Chrome uses the nested shape above.

                value = resp.path("result").path("value");

            }

            return value.isMissingNode() || value.isNull() ? "" : value.asText();

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
        int initialCount = countAssistantMessages();
        String last = "";
        int stableCount = 0;
        while (System.currentTimeMillis() < deadline) {
            int curCount = countAssistantMessages();
            if (curCount > initialCount) {
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
            }
            sleep(POLL_INTERVAL_MS);
        }
        throw new TimeoutException("response did not stabilize within " + timeoutMs + " ms");
    }

    private int countAssistantMessages() {
        String expr = "String(document.querySelectorAll(\""
                + escapeJs(DeepSeekSelectors.ASSISTANT_MESSAGE)
                + "\").length)";
        String v = evaluate(expr);
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
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






    public void attachFile(Path path) throws CdpAttachException {

        if (path == null) {

            throw new CdpAttachException("validate", "path is null", null);

        }

        if (!Files.isRegularFile(path)) {

            throw new CdpAttachException("validate", "not a regular file: " + path, null);

        }

        if (activeTab == null) {

            throw new CdpAttachException("state", "no active tab", null);

        }

        String abs = path.toAbsolutePath().toString();

        long start = System.currentTimeMillis();

        long bytes = -1L;

        try { bytes = Files.size(path); } catch (Exception ignored) { /* best effort */ }



        Integer rootNodeId = nodeIdOfDocument();

        if (rootNodeId == null) {

            throw new CdpAttachException("query-input", "DOM.getDocument returned no root nodeId", null);

        }

        Integer inputNodeId = querySelectorNodeId(rootNodeId, DeepSeekSelectors.FILE_INPUT);



        int attempts = 0;

        while (inputNodeId == null && attempts < ATTACH_CLICK_ATTEMPTS) {

            attempts++;

            clickAttachButton();

            sleep(300L);

            rootNodeId = nodeIdOfDocument();

            if (rootNodeId != null) {

                inputNodeId = querySelectorNodeId(rootNodeId, DeepSeekSelectors.FILE_INPUT);

            }

        }



        if (inputNodeId == null) {

            attachFileViaChooser(abs, bytes, start);

            return;

        }



        Map<String, Object> params = new HashMap<>();

        params.put("files", List.of(abs));

        params.put("nodeId", inputNodeId);

        JsonNode resp;

        try {

            resp = activeTab.send("DOM.setFileInputFiles", params);

        } catch (Exception e) {

            throw new CdpAttachException("set-files",

                    "DOM.setFileInputFiles exception: " + e.getMessage(), e);

        }

        if (resp == null || resp.get("error") != null) {

            throw new CdpAttachException("set-files",

                    "DOM.setFileInputFiles error: " + resp, null);

        }

        waitForAttachmentVisible(ATTACH_VISIBLE_TIMEOUT_MS);

        long elapsed = System.currentTimeMillis() - start;

        AppLogger.info("attachFile: file=" + abs + ", bytes=" + bytes + ", elapsed=" + elapsed + "ms (dom)");

    }



    private Integer nodeIdOfDocument() {

        Map<String, Object> params = new HashMap<>();

        params.put("depth", Integer.valueOf(-1));

        JsonNode resp;

        try {

            resp = activeTab.send("DOM.getDocument", params);

        } catch (Exception e) {

            return null;

        }

        if (resp == null || resp.get("error") != null) return null;

        JsonNode result = resp.get("result");

        if (result == null) return null;

        JsonNode root = result.get("root");

        if (root == null) return null;

        JsonNode nodeId = root.get("nodeId");

        if (nodeId == null || nodeId.isNull()) return null;

        return Integer.valueOf(nodeId.asInt());

    }



    private Integer querySelectorNodeId(Integer rootNodeId, String selector) {

        Map<String, Object> params = new HashMap<>();

        params.put("nodeId", rootNodeId);

        params.put("selector", selector);

        JsonNode resp;

        try {

            resp = activeTab.send("DOM.querySelector", params);

        } catch (Exception e) {

            return null;

        }

        if (resp == null || resp.get("error") != null) return null;

        JsonNode result = resp.get("result");

        if (result == null) return null;

        JsonNode nodeId = result.get("nodeId");

        if (nodeId == null || nodeId.isNull()) return null;

        int v = nodeId.asInt();

        return v == 0 ? null : Integer.valueOf(v);

    }



    private void clickAttachButton() {

        String expr = "(function(){var b=document.querySelector(\""

                + escapeJs(DeepSeekSelectors.ATTACH_BUTTON)

                + "\");if(!b)return '';b.focus();b.click();return 'clicked';})()";

        evaluate(expr);

    }



    private void waitForAttachmentVisible(long timeoutMs) throws CdpAttachException {

        long deadline = System.currentTimeMillis() + timeoutMs;

        String expr = "String(document.querySelectorAll(\""

                + escapeJs(DeepSeekSelectors.ATTACHMENT_NODE)

                + "\").length)";

        while (System.currentTimeMillis() < deadline) {

            String v = evaluate(expr);

            try {

                if (v != null && Long.parseLong(v) > 0L) return;

            } catch (NumberFormatException ignored) { /* keep polling */ }

            sleep(ATTACH_POLL_MS);

        }

        throw new CdpAttachException("wait-upload",

                "attachment did not appear in DOM within " + timeoutMs + " ms", null);

    }



    private void attachFileViaChooser(String abs, long bytes, long start) throws CdpAttachException {

        CompletableFuture<Integer> chooserFuture = new CompletableFuture<>();

        Consumer<JsonNode> handler = node -> {

            try {

                JsonNode params = node.get("params");

                if (params == null) return;

                JsonNode bid = params.get("backendNodeId");

                if (bid == null || bid.isNull()) return;

                chooserFuture.complete(Integer.valueOf(bid.asInt()));

            } catch (Exception ignored) { /* timeout handles the rest */ }

        };



        boolean interceptEnabled = false;
        boolean handlerRegistered = false;

        try {

            Map<String, Object> enable = new HashMap<>();

            enable.put("enabled", Boolean.TRUE);

            JsonNode en = activeTab.send("Page.setInterceptFileChooserDialog", enable);

            if (en != null && en.get("error") == null) interceptEnabled = true;



            activeTab.on("Page.fileChooserOpened", handler);
            handlerRegistered = true;



            clickAttachButton();



            Integer backendNodeId;

            try {

                backendNodeId = chooserFuture.get(CHOOSER_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            } catch (TimeoutException te) {

                throw new CdpAttachException("chooser",

                        "Page.fileChooserOpened not received within " + CHOOSER_TIMEOUT_MS + " ms", te);

            } catch (Exception e) {

                throw new CdpAttachException("chooser",

                        "waiting for file chooser failed: " + e.getMessage(), e);

            }



            Map<String, Object> set = new HashMap<>();

            set.put("files", List.of(abs));

            set.put("backendNodeId", backendNodeId);

            JsonNode resp;

            try {

                resp = activeTab.send("DOM.setFileInputFiles", set);

            } catch (Exception e) {

                throw new CdpAttachException("set-files",

                        "DOM.setFileInputFiles (chooser) exception: " + e.getMessage(), e);

            }

            if (resp == null || resp.get("error") != null) {

                throw new CdpAttachException("set-files",

                        "DOM.setFileInputFiles (chooser) error: " + resp, null);

            }

            waitForAttachmentVisible(ATTACH_VISIBLE_TIMEOUT_MS);

            long elapsed = System.currentTimeMillis() - start;

            AppLogger.info("attachFile: file=" + abs + ", bytes=" + bytes + ", elapsed=" + elapsed + "ms (chooser)");

        } finally {

            if (handlerRegistered) { try { activeTab.off("Page.fileChooserOpened", handler); } catch (Exception ignored) { /* best effort */ } }
            if (interceptEnabled) {

                try {

                    Map<String, Object> disable = new HashMap<>();

                    disable.put("enabled", Boolean.FALSE);

                    activeTab.send("Page.setInterceptFileChooserDialog", disable);

                } catch (Exception ignored) { /* best effort */ }

            }

        }

    }

}


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
import com.freemaf.agent.EmergencyStop;



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
    private static final long ATTACH_VISIBLE_TIMEOUT_MS = 20000L;
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



    public CdpDeepSeek(CdpSession session, LoginFallbackHandler loginHandler, CdpTabInfo existingTab) throws java.io.IOException {
        this(session, loginHandler);
        if (existingTab == null) throw new IllegalArgumentException("existingTab is null");
        this.activeTabInfo = existingTab;
        this.activeTab = session.attach(existingTab);
        session.activateTab(existingTab);
        try {
            Map<String, Object> focus = new HashMap<>();
            focus.put("enabled", Boolean.TRUE);
            activeTab.send("Emulation.setFocusEmulationEnabled", focus);
            AppLogger.info("CdpDeepSeek(adopt): Emulation.setFocusEmulationEnabled(true) sent");
        } catch (Exception e) {
            AppLogger.warn("Emulation.setFocusEmulationEnabled failed (adopt): " + e.getMessage());
        }


        try {
            String stealthSource =
                    "(function(){"
                    + "try{Object.defineProperty(navigator,'webdriver',{get:function(){return undefined;},configurable:true});}catch(e){}"
                    + "try{Object.defineProperty(navigator,'plugins',{get:function(){return [1,2,3];},configurable:true});}catch(e){}"
                    + "try{Object.defineProperty(navigator,'languages',{get:function(){return ['ru-RU','ru','en-US','en'];},configurable:true});}catch(e){}"
                    + "try{if(!window.chrome){window.chrome={};}if(!window.chrome.runtime){window.chrome.runtime={};}}catch(e){}"
                    + "try{Object.defineProperty(window,'outerWidth',{get:function(){return window.innerWidth;},configurable:true});Object.defineProperty(window,'outerHeight',{get:function(){return window.innerHeight;},configurable:true});}catch(e){}"
                    + "})()";
            Map<String, Object> stealthParams = new HashMap<>();
            stealthParams.put("source", stealthSource);
            activeTab.send("Page.addScriptToEvaluateOnNewDocument", stealthParams);
            AppLogger.info("FIX30: stealth script installed (adopt)");
        } catch (Exception sx) {
            AppLogger.warn("FIX30: stealth script install failed (adopt): " + sx.getMessage());
        }
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

        // FIX30: stealth script via Page.addScriptToEvaluateOnNewDocument
        try {
            String stealthSource =
                    "(function(){"
                    + "try{Object.defineProperty(navigator,'webdriver',{get:function(){return undefined;},configurable:true});}catch(e){}"
                    + "try{Object.defineProperty(navigator,'plugins',{get:function(){return [1,2,3];},configurable:true});}catch(e){}"
                    + "try{Object.defineProperty(navigator,'languages',{get:function(){return ['ru-RU','ru','en-US','en'];},configurable:true});}catch(e){}"
                    + "try{if(!window.chrome){window.chrome={};}if(!window.chrome.runtime){window.chrome.runtime={};}}catch(e){}"
                    + "try{Object.defineProperty(window,'outerWidth',{get:function(){return window.innerWidth;},configurable:true});Object.defineProperty(window,'outerHeight',{get:function(){return window.innerHeight;},configurable:true});}catch(e){}"
                    + "try{var origQuery=navigator.permissions&&navigator.permissions.query;if(origQuery){navigator.permissions.query=function(p){if(p&&p.name==='notifications'){return Promise.resolve({state:'denied',onchange:null});}return origQuery.call(navigator.permissions,p);};}}catch(e){}"
                    + "})()";
            Map<String, Object> stealthParams = new HashMap<>();
            stealthParams.put("source", stealthSource);
            activeTab.send("Page.addScriptToEvaluateOnNewDocument", stealthParams);
            AppLogger.info("FIX30: stealth script installed via Page.addScriptToEvaluateOnNewDocument");
        } catch (Exception sx) {
            AppLogger.warn("FIX30: stealth script install failed: " + sx.getMessage());
        }

        try {
            Map<String, Object> focus = new HashMap<>();
            focus.put("enabled", Boolean.TRUE);
            activeTab.send("Emulation.setFocusEmulationEnabled", focus);
            AppLogger.info("CdpDeepSeek.open: Emulation.setFocusEmulationEnabled(true) sent");
        } catch (Exception e) {
            AppLogger.warn("Emulation.setFocusEmulationEnabled failed: " + e.getMessage());
        }

        navigate(DEEPSEEK_URL);



        try {

            waitForInput(15_000L);

        } catch (TimeoutException te) {

            throw new IllegalStateException("input field not found after navigate: " + te.getMessage(), te);

        }


        int landedCount = 0;
        for (int poll = 0; poll < 4; poll++) {
            int c = countAnyMessages();
            if (c > landedCount) landedCount = c;
            if (landedCount > 0) break;
            sleep(500L);
        }

        // FIX15: unconditionally force fresh chat via clickNewChat (DOM is virtualized, history not visible)
        AppLogger.info("CdpDeepSeek.open: initial count=" + landedCount + "; forcing fresh chat via clickNewChat()");
        String clickResult = clickNewChat();
        AppLogger.info("CdpDeepSeek.open: clickNewChat result = " + clickResult);
        sleep(2000L);
        try { waitForInput(10_000L); } catch (TimeoutException ignored) { }
        String postUrl = evaluate("String(location.href)");
        int postCount = countAnyMessages();
        AppLogger.info("CdpDeepSeek.open: post-click URL=" + postUrl + " count=" + postCount);

        // FIX17-DIAG: action-bar dump

        try {

            if (postCount > 0) {

                String abDump = evaluate(

                        "JSON.stringify({"

                        + "actionBars:Array.from(document.querySelectorAll('div.ds-flex')).map(function(e){return {"

                        + "className:(e.className||'').toString().slice(0,100),"

                        + "innerButtons:Array.from(e.querySelectorAll('button, [role=button], [class*=icon]')).map(function(b){return {"

                        + "tag:b.tagName,aria:(b.getAttribute('aria-label')||''),testid:(b.getAttribute('data-testid')||''),className:(b.className||'').toString().slice(0,60)"

                        + "};}),"

                        + "text:(e.textContent||'').trim().slice(0,60)"

                        + "};})"

                        + "})");

                AppLogger.info("FIX17-DIAG open action-bar dump: " + abDump);

            }

        } catch (Exception abEx) { AppLogger.warn("FIX17-DIAG action-bar dump error: " + abEx.getMessage()); }

    }



    private int countAnyMessages() {

        String expr = "String(document.querySelectorAll(\""

                + escapeJs(DeepSeekSelectors.ANY_MESSAGE_OR_CODE)

                + "\").length)";

        String v = evaluate(expr);

        try {

            return Integer.parseInt(v);

        } catch (Exception e) {

            return 0;

        }

    }



    private String clickNewChat() {

        String expr =

                "(function(){" +

                "var nodes=document.querySelectorAll('*');" +

                "for(var i=0;i<nodes.length;i++){" +

                "  var el=nodes[i];" +

                "  if(el.children.length===0 && el.textContent && el.textContent.trim()==='New chat'){" +

                "    var p=el;" +

                "    for(var j=0;j<6 && p;j++){" +

                "      var r=p.getAttribute && p.getAttribute('role');" +

                "      if(r==='button'){p.click();return 'clicked-role-button';}" +

                "      if(p.tagName==='BUTTON'){p.click();return 'clicked-BUTTON';}" +

                "      if(p.tagName==='A'){p.click();return 'clicked-A';}" +

                "      p=p.parentElement;" +

                "    }" +

                "    el.click();" +

                "    return 'clicked-leaf';" +

                "  }" +

                "}" +

                "var byTestId=document.querySelector('[data-testid*=\"new-chat\"], [data-testid*=\"new_chat\"], [data-testid*=\"newChat\"]');" +

                "if(byTestId){byTestId.click();return 'clicked-testid';}" +

                "var byAria=document.querySelector('[aria-label*=\"New chat\" i], [aria-label*=\"Новый чат\" i], [aria-label*=\"新对话\" i]');" +

                "if(byAria){byAria.click();return 'clicked-aria';}" +

                "var partial=document.querySelectorAll('div, span, button, a');" +

                "for(var k=0;k<partial.length;k++){" +

                "  var e2=partial[k];" +

                "  var t=(e2.textContent||'').trim();" +

                "  if(t.length>0 && t.length<40 && (t.indexOf('New chat')>=0 || t.indexOf('Новый чат')>=0 || t.indexOf('新对话')>=0)){" +

                "    var q=e2;" +

                "    for(var m=0;m<6 && q;m++){" +

                "      if(q.tagName==='BUTTON'||q.tagName==='A'||(q.getAttribute&&q.getAttribute('role')==='button')){q.click();return 'clicked-partial-'+q.tagName;}" +

                "      q=q.parentElement;" +

                "    }" +

                "    e2.click();return 'clicked-partial-leaf';" +

                "  }" +

                "}" +

                "var anchors=document.querySelectorAll('a[href=\"/\"], a[href=\"https://chat.deepseek.com/\"]');" +

                "for(var n=0;n<anchors.length;n++){" +

                "  var a=anchors[n];" +

                "  var par=a.closest('nav, aside, [class*=\"sidebar\"], [class*=\"sider\"]');" +

                "  if(par){a.click();return 'clicked-anchor-nav';}" +

                "}" +

                "return 'NOT_FOUND';" +

                "})()";

        String result = evaluate(expr);

        if ("NOT_FOUND".equals(result)) {

            try {

                String dump = evaluate("JSON.stringify(Array.from(document.querySelectorAll('button, [role=button], a')).slice(0,50).map(function(e){return {tag:e.tagName,role:e.getAttribute('role')||'',aria:e.getAttribute('aria-label')||'',testid:e.getAttribute('data-testid')||'',cls:(e.className||'').toString().slice(0,60),text:(e.textContent||'').trim().slice(0,40)};}))");

                AppLogger.info("CdpDeepSeek.clickNewChat NOT_FOUND, sidebar dump: " + dump);

            } catch (Exception ignored) { }

        }

        return result;

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
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        long midLog = start + 5_000L;
        boolean midLogged = false;
        String expr = "String(!!document.querySelector(\""
                + escapeJs(DeepSeekSelectors.INPUT) + "\"))";
        while (System.currentTimeMillis() < deadline) {
            if ("true".equals(evaluate(expr))) return;
            long now = System.currentTimeMillis();
            if (!midLogged && now >= midLog) {
                String url = evaluate("String(location.href)");
                String hasMd = evaluate("String(!!document.querySelector('div.ds-markdown'))");
                AppLogger.info("CdpDeepSeek.waitForInput: still waiting at 5s, url=" + url + ", hasMarkdown=" + hasMd);
                midLogged = true;
            }
            sleep(POLL_INTERVAL_MS);
        }
        String url = evaluate("String(location.href)");
        String title = evaluate("String(document.title)");
        String rs = evaluate("String(document.readyState || 'unknown')");
        String hasMd = evaluate("String(!!document.querySelector('div.ds-markdown'))");
        String bodyLen = evaluate("String((document.body && document.body.innerText || '').length)");
        String diag = "url=" + url + " title=" + title + " readyState=" + rs + " hasMarkdown=" + hasMd + " bodyLen=" + bodyLen;

        // FIX29: CloudFront/WAF block detection (title=ERROR: The request could not be satisfied, small bodyLen)

        String bodySnippet = evaluate("String(((document.body && document.body.innerText) || '').slice(0, 200))");

        boolean cfBlocked = title != null

                && (title.contains("request could not be satisfied") || title.startsWith("ERROR"))

                && url != null && !url.contains("/sign_in") && !url.contains("/signin")

                && bodyLen != null && Long.parseLong(bodyLen) < 1500L;

        if (cfBlocked) {

            String msg = "CloudFront/WAF блокирует доступ к chat.deepseek.com (title=\"" + title + "\", bodyLen=" + bodyLen + "). "

                    + "Причины: (1) IP/страна заблокированы CloudFront — включите/смените VPN (не RU, не датацентр); "

                    + "(2) rate-limit по IP после серии запросов — подождите 15-30 мин; "

                    + "(3) Chrome Secure DNS (DoH) конфликтует — chrome://settings/security → Use secure DNS → выключить или 'With your current service provider'; "

                    + "(4) профиль cdp-profile повреждён — удалите agent/cache/cdp-profile (потеряется логин). "

                    + "Body: " + bodySnippet;

            AppLogger.warn("CdpDeepSeek.waitForInput: FIX29 CloudFront blocked — " + msg);

            throw new IllegalStateException(msg);

        }

        AppLogger.warn("CdpDeepSeek.waitForInput TIMEOUT after " + timeoutMs + "ms: " + diag);

        throw new TimeoutException("input not found within " + timeoutMs + " ms [" + diag + "]");

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
        String preHref = evaluate("String(location.href)");
        String preHasInput = evaluate("String(!!document.querySelector(\"" + escapeJs(DeepSeekSelectors.INPUT) + "\"))");
        AppLogger.info("CdpDeepSeek.sendMessage: preState href=" + preHref + " hasInput=" + preHasInput);
        String focusExpr =
                "(function(){var el=document.querySelector(\""
                + escapeJs(DeepSeekSelectors.INPUT)
                + "\");if(el)el.focus();return '';})()";
        evaluate(focusExpr);
        sleep(300L);
        String safe = escapeJsSingleQuoted(text);
        String insertExpr = "document.execCommand('insertText', false, '" + safe + "')";
        String insertResult = evaluate(insertExpr);
        AppLogger.info("CdpDeepSeek.sendMessage: insertResult=" + insertResult + " textLen=" + text.length());
        sleep(500L);
        String enterExpr =
                "(function(){var el=document.querySelector(\""
                + escapeJs(DeepSeekSelectors.INPUT)
                + "\");if(!el)return 'no-input';"
                + "el.dispatchEvent(new KeyboardEvent('keydown',"
                + "{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));"
                + "return 'dispatched';})()";
        String enterResult = evaluate(enterExpr);
        AppLogger.info("CdpDeepSeek.sendMessage: enterResult=" + enterResult);
        sleep(500L);
        String postText = evaluate("String(((document.querySelector(\"" + escapeJs(DeepSeekSelectors.INPUT) + "\") || {}).value) || '')");
        AppLogger.info("CdpDeepSeek.sendMessage: postTextareaLen=" + postText.length());
    }



    public String readLastResponse() {
        String expr =
                "(function(){var m=document.querySelectorAll(\""
                + escapeJs(DeepSeekSelectors.ASSISTANT_MESSAGE)
                + "\");if(!m.length)return '';"
                + "var lastPre=null;"
                + "for(var i=0;i<m.length;i++){"
                + "var ps=m[i].querySelectorAll('pre');"
                + "if(ps.length)lastPre=ps[ps.length-1];"
                + "}"
                + "if(lastPre)return lastPre.innerText;"
                + "var allPre=document.querySelectorAll('pre');"
                + "if(allPre.length)return allPre[allPre.length-1].innerText;"
                + "return m[m.length-1].innerText;})()";
        String v = evaluate(expr);
        return v == null ? "" : v;
    }


    public String waitForResponse(long timeoutMs) throws TimeoutException {

        long deadline = System.currentTimeMillis() + timeoutMs;

        int initialCount = countAssistantMessages();
        long lastDiagMs = 0L;

        String last = "";

        int stableCount = 0;

        while (System.currentTimeMillis() < deadline) {

            int curCount = countAssistantMessages();
            if (EmergencyStop.isStopped() || Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("waitForResponse aborted by user stop or thread interrupt");
            }
            if (curCount < initialCount) {
                throw new IllegalStateException("assistant count dropped from " + initialCount + " to " + curCount + "; tab reset or closed");
            }
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastDiagMs >= 5000L) {
                lastDiagMs = nowMs;
                try {
                    String dsMd = evaluate("String(document.querySelectorAll('div.ds-markdown').length)");
                    String preC = evaluate("String(document.querySelectorAll('pre').length)");
                    String btnC = evaluate("String(document.querySelectorAll('div.ds-flex button, button[aria-label*='copy'], button[aria-label*='Copy'], button[aria-label*='Копировать']').length)");
                    String vis = evaluate("String(document.visibilityState || 'unknown')");
                    String hrf = evaluate("String(location.href)");
                    String hasInp = evaluate("String(!!document.querySelector(\"" + escapeJs(DeepSeekSelectors.INPUT) + "\"))");
                    AppLogger.info("CdpDeepSeek.waitForResponse diag: cur=" + curCount + " initial=" + initialCount + " streaming=" + countStreaming() + " dsMd=" + dsMd + " pre=" + preC + " btns=" + btnC + " vis=" + vis + " hasInput=" + hasInp + " href=" + hrf + " lastLen=" + (last == null ? 0 : last.length()));
                    if (last != null && last.length() > 0) {
                        String preview = last.length() > 200 ? last.substring(0, 200) : last;
                        AppLogger.info("CdpDeepSeek.waitForResponse lastRaw: " + preview.replace("\r", "\\r").replace("\n", "\\n"));
                    }
                } catch (Exception diagEx) {
                    AppLogger.warn("CdpDeepSeek.waitForResponse diag error: " + diagEx.getMessage());
                }
            }

            if (curCount > initialCount) {

                String cur = readLastResponse();

                if (cur != null && !cur.isEmpty()) {

                    boolean streaming = countStreaming() > 0L;

                    boolean complete = isResponseComplete(cur);

                    if (!streaming && complete && cur.equals(last)) {

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

        String finalDsMd = evaluate("String(document.querySelectorAll('div.ds-markdown').length)");
        String finalHasInput = evaluate("String(!!document.querySelector(\"" + escapeJs(DeepSeekSelectors.INPUT) + "\"))");
        throw new TimeoutException("response did not stabilize within " + timeoutMs + " ms [initial=" + initialCount + " cur=" + countAssistantMessages() + " streaming=" + countStreaming() + " dsMd=" + finalDsMd + " hasInput=" + finalHasInput + " lastLen=" + (last == null ? 0 : last.length()) + "]");

    }




    private static final java.util.regex.Pattern BARE_CMDLET_VERB =

            java.util.regex.Pattern.compile(

                    "(?i)^(Set|Get|New|Remove|Add|Out|Start|Stop|Wait|Copy|Move|Rename|Clear|Test|Import|Export|Invoke|Push|Pop|Group|Where|ForEach)$");



    private static boolean isResponseComplete(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        int q = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' && (i == 0 || raw.charAt(i - 1) != '\\')) q++;
        }
        // Truncated mid-string -> odd quote count -> keep polling.
        // Do NOT reject trailing ':', '(', '{', etc.: PowerShell comments and
        // multi-line constructs legitimately end with these characters.
        return (q % 2) == 0;
    }



    private static int countChar(String s, char c) {

        int n = 0;

        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;

        return n;

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

    public int getAssistantMessageCount() {         return countAssistantMessages();     }

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
        try { bytes = Files.size(path); } catch (Exception ignored) { }

        // FIX28: composer blob-img baseline before attach

        String baselineJson = evaluate("String((function(){var t=document.querySelector('textarea[name=search]');if(!t)return '{\"total\":0,\"composer\":0}';function inComposer(el){var p=el;for(var i=0;i<8&&p;i++){if(p.querySelector&&p.querySelector('textarea[name=search]'))return true;p=p.parentElement;}return false;}var imgs=document.querySelectorAll('img[src^=\"blob:\"]');var total=imgs.length;var comp=0;for(var k=0;k<imgs.length;k++){if(inComposer(imgs[k]))comp++;}return JSON.stringify({total:total,composer:comp});})())");

        AppLogger.info("FIX28: baseline blob-img = " + baselineJson);

        int baselineComposer = 0;

        try {

            com.fasterxml.jackson.databind.JsonNode bj = new com.fasterxml.jackson.databind.ObjectMapper().readTree(baselineJson);

            baselineComposer = bj.path("composer").asInt(0);

        } catch (Exception ignored) { }


        Integer rootNodeId = nodeIdOfDocument();
        if (rootNodeId == null) {
            throw new CdpAttachException("query-input", "DOM.getDocument returned no root nodeId", null);
        }
        Integer inputNodeId = querySelectorNodeId(rootNodeId, DeepSeekSelectors.FILE_INPUT);

        int attempts = 0;
        while (inputNodeId == null && attempts < ATTACH_CLICK_ATTEMPTS) {
            attempts++;
            // FIX14: use Input.dispatchMouseEvent instead of element.click() (headless chooser)
            try {
                String rectJson = evaluate("String((function(){var b=document.querySelector('[class*='icon-button'], [class*='paperclip'], button[aria-label*='attach' i], button[aria-label*='file' i]');if(!b)return '' ;var r=b.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})())");
                if (rectJson != null && rectJson.contains("x")) {
                    com.fasterxml.jackson.databind.JsonNode rj = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rectJson);
                    double cx = rj.path("x").asDouble();
                    double cy = rj.path("y").asDouble();
                    Map<String, Object> press = new HashMap<>();
                    press.put("type", "mousePressed");
                    press.put("x", cx);
                    press.put("y", cy);
                    press.put("button", "left");
                    press.put("clickCount", 1);
                    activeTab.send("Input.dispatchMouseEvent", press);
                    Map<String, Object> release = new HashMap<>();
                    release.put("type", "mouseReleased");
                    release.put("x", cx);
                    release.put("y", cy);
                    release.put("button", "left");
                    release.put("clickCount", 1);
                    activeTab.send("Input.dispatchMouseEvent", release);
                    AppLogger.info("CdpDeepSeek.attachFileViaChooser: dispatched CDP mouse click at " + cx + "," + cy);
                } else {
                    AppLogger.warn("CdpDeepSeek.attachFileViaChooser: attach button rect not found, falling back to element.click()");
                    // FIX18: input.click() first, then clickNearInput(), then legacy clickAttachButton()
                    String fix18Result = evaluate("(function(){var i=document.querySelector('input[type=file]');if(!i)return 'no-input';i.click();return 'input-clicked';})()");
                    AppLogger.info("FIX18: input.click() result = " + fix18Result);
                    if (!"input-clicked".equals(fix18Result)) {
                        String cnResult = clickNearInput();
                        AppLogger.info("FIX18: clickNearInput() result = " + cnResult);
                        if ("not-found".equals(cnResult) || "no-input".equals(cnResult)) {

            // FIX19: clickNearInput-first (React button click opens native chooser), then input.click, then legacy

            String cnResult19 = clickNearInput();

            AppLogger.info("FIX19: clickNearInput() result = " + cnResult19);

            if ("not-found".equals(cnResult19) || "no-input".equals(cnResult19)) {

                String icResult19 = evaluate("(function(){var i=document.querySelector('input[type=file]');if(!i)return 'no-input';i.click();return 'input-clicked';})()");

                AppLogger.info("FIX19: input.click() result = " + icResult19);

                if (!"input-clicked".equals(icResult19)) {

                    clickAttachButton();

                }

            }

                        }
                    }
                }
            } catch (Exception mce) {
                AppLogger.warn("CdpDeepSeek.attachFileViaChooser: CDP mouse click failed: " + mce.getMessage());
                clickAttachButton();
            }
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

        // Diagnostic: input attributes
        try {

        // FIX17-DIAG: attachFile pre-set dump

        try {

            String preDump = evaluate(

                    "JSON.stringify({"

                    + "fileInputs:Array.from(document.querySelectorAll('input[type=file]')).map(function(e){return {"

                    + "accept:(e.accept||''),multiple:e.multiple,disabled:e.disabled,hidden:e.hidden,"

                    + "id:(e.id||''),name:(e.name||''),className:(e.className||''),"

                    + "parentTag:e.parentElement?e.parentElement.tagName:null,"

                    + "parentClass:e.parentElement?(e.parentElement.className||''):null,"

                    + "grandParentTag:e.parentElement&&e.parentElement.parentElement?e.parentElement.parentElement.tagName:null,"

                    + "grandParentClass:e.parentElement&&e.parentElement.parentElement?(e.parentElement.parentElement.className||''):null"

                    + "};}),"

                    + "paperclipCandidates:Array.from(document.querySelectorAll('[class*=paperclip], [class*=attach], button[aria-label*=attach i], button[aria-label*=file i], [data-testid*=attach], [data-testid*=file]')).map(function(e){return {"

                    + "tag:e.tagName,aria:(e.getAttribute('aria-label')||''),testid:(e.getAttribute('data-testid')||''),"

                    + "className:(e.className||'').toString().slice(0,80),text:(e.textContent||'').trim().slice(0,30)"

                    + "};}),"

                    + "textareaParent:(function(){var t=document.querySelector('textarea[name=search]');if(!t)return null;var p=t;for(var i=0;i<5&&p;i++){p=p.parentElement;if(p&&p.querySelector('button, [role=button]')){return {tag:p.tagName,className:(p.className||'').toString()};}}return null;})()"

                    + "})");

            AppLogger.info("FIX17-DIAG attachFile pre-set dump: " + preDump);

            try {

                String inputCtxDump = evaluate(

                        "JSON.stringify((function(){"

                        + "var i=document.querySelector('input[type=file]');if(!i)return null;"

                        + "function d(e){return {tag:e.tagName,aria:(e.getAttribute('aria-label')||''),testid:(e.getAttribute('data-testid')||''),cls:(e.className||'').toString().slice(0,80),text:(e.textContent||'').trim().slice(0,30)};}"

                        + "var p=i.parentElement;"

                        + "var gp=p&&p.parentElement;"

                        + "return {"

                        + "parentHtml:p?(p.outerHTML||'').slice(0,600):'',"

                        + "siblings:p?Array.from(p.children).map(d):[],"

                        + "gpSiblings:gp?Array.from(gp.children).map(d):[]"

                        + "};"

                        + "})())");

                AppLogger.info("FIX18-DIAG inputCtx: " + inputCtxDump);

            } catch (Exception icx) { AppLogger.warn("FIX18-DIAG inputCtx error: " + icx.getMessage()); }


        } catch (Exception diagEx) { AppLogger.warn("FIX17-DIAG pre-set dump error: " + diagEx.getMessage()); }

            String inputAttrs = evaluate("String((function(){var i=document.querySelector('input[type=file]');if(!i)return 'none';return 'accept='+(i.accept||'')+' multiple='+i.multiple+' disabled='+i.disabled+' hidden='+i.hidden+' offsetParent='+(i.offsetParent?'yes':'no');})())");
            AppLogger.info("CdpDeepSeek.attachFile: inputAttrs " + inputAttrs);
        } catch (Exception ignored) { }

        // Get backendNodeId for fallback
        Integer backendNodeId = null;
        try {
            Map<String, Object> descParams = new HashMap<>();
            descParams.put("nodeId", inputNodeId);
            JsonNode descResp = activeTab.send("DOM.describeNode", descParams);
            if (descResp != null && descResp.get("error") == null) {
                JsonNode node = descResp.path("result").path("node");
                JsonNode bid = node.path("backendNodeId");
                if (!bid.isMissingNode()) backendNodeId = bid.asInt();
            }
        } catch (Exception ignored) { }

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

        // Check if files were actually set
        String firstFiles = evaluate("String((function(){var i=document.querySelector('input[type=file]');return i && i.files ? i.files.length : -1;})())");
        AppLogger.info("CdpDeepSeek.attachFile: after setFileInputFiles firstFiles=" + firstFiles);
        if ("0".equals(firstFiles) || "-1".equals(firstFiles)) {
            AppLogger.warn("CdpDeepSeek.attachFile: primary setFileInputFiles did not populate files, trying backendNodeId");
            if (backendNodeId != null) {
                Map<String, Object> p2 = new HashMap<>();
                p2.put("files", List.of(abs));
                p2.put("backendNodeId", backendNodeId);
                try {
                    JsonNode r2 = activeTab.send("DOM.setFileInputFiles", p2);
                    AppLogger.info("CdpDeepSeek.attachFile: backendNodeId attempt resp=" + r2);
                } catch (Exception e) {
                    AppLogger.warn("CdpDeepSeek.attachFile: backendNodeId attempt failed: " + e.getMessage());
                }
                String firstFiles2 = evaluate("String((function(){var i=document.querySelector('input[type=file]');return i && i.files ? i.files.length : -1;})())");
                AppLogger.info("CdpDeepSeek.attachFile: after backendNodeId firstFiles=" + firstFiles2);

            if ("0".equals(firstFiles2) || "-1".equals(firstFiles2)) {

                try {

                    String postDump = evaluate(

                            "JSON.stringify({"

                            + "fileInputs:Array.from(document.querySelectorAll('input[type=file]')).map(function(e){return {"

                            + "accept:(e.accept||'').slice(0,50),multiple:e.multiple,filesLen:(e.files?e.files.length:-1),"

                            + "id:(e.id||''),name:(e.name||''),className:(e.className||'')"

                            + "};}),"

                            + "fileCards:Array.from(document.querySelectorAll('[class*=file-card], [class*=file-preview], [class*=attachment], [class*=upload], [class*=file-item], [class*=ds-file]')).map(function(e){return {"

                            + "tag:e.tagName,className:(e.className||'').toString().slice(0,100),text:(e.textContent||'').trim().slice(0,40)"

                            + "};})"

                            + "})");

                    AppLogger.info("FIX17-DIAG attachFile post-set dump: " + postDump);

                } catch (Exception diagEx2) { AppLogger.warn("FIX17-DIAG post-set dump error: " + diagEx2.getMessage()); }

            }

                if ("0".equals(firstFiles2) || "-1".equals(firstFiles2)) {
                    AppLogger.warn("CdpDeepSeek.attachFile: backendNodeId did not populate files, trying DataTransfer");
                    attachFileViaDataTransfer(path);
                    attachFileViaDragEvent(path);
                    attachFileViaPasteEvent(path);
                    waitForAttachmentVisible(ATTACH_VISIBLE_TIMEOUT_MS, baselineComposer);
                    AppLogger.info("attachFile: file=" + abs + ", bytes=" + bytes + ", elapsed=" + (System.currentTimeMillis() - start) + "ms (datatransfer)");
                    return;
                }
            } else {
                AppLogger.warn("CdpDeepSeek.attachFile: no backendNodeId, trying DataTransfer");
                attachFileViaDataTransfer(path);
                attachFileViaDragEvent(path);
                attachFileViaPasteEvent(path);
                waitForAttachmentVisible(ATTACH_VISIBLE_TIMEOUT_MS, baselineComposer);
                AppLogger.info("attachFile: file=" + abs + ", bytes=" + bytes + ", elapsed=" + (System.currentTimeMillis() - start) + "ms (datatransfer)");
                return;
            }
        }

        // FIX14 diagnostics: input count, attached, shadow, iframe
        try {
            String fileInputs = evaluate("String(document.querySelectorAll('input[type=file]').length)");
            String attached = evaluate("String((function(){var i=document.querySelector('input[type=file]');return i ? document.contains(i) : 'no-input';})())");
            String rootIsDoc = evaluate("String((function(){var i=document.querySelector('input[type=file]');return i ? (i.getRootNode() === document) : 'no-input';})())");
            String iframeCount = evaluate("String(document.querySelectorAll('iframe').length)");
            String iconButtons = evaluate("String(document.querySelectorAll('[class*='icon-button'], [class*='paperclip'], button[aria-label*='attach' i], button[aria-label*='file' i]').length)");
            AppLogger.info("CdpDeepSeek.attachFile: pierce diag fileInputs=" + fileInputs + " attached=" + attached + " rootIsDoc=" + rootIsDoc + " iframes=" + iframeCount + " iconButtons=" + iconButtons);
        } catch (Exception diagEx) {
            AppLogger.warn("CdpDeepSeek.attachFile: pierce diag error: " + diagEx.getMessage());
        }

        // FIX14: objectId fallback (Playwright uses objectId, not nodeId/backendNodeId)
        if ("0".equals(firstFiles) || "-1".equals(firstFiles)) {
            try {
                Map<String, Object> evalParams = new HashMap<>();
                evalParams.put("expression", "document.querySelector(\"input[type=file]\")");
                evalParams.put("returnByValue", Boolean.FALSE);
                JsonNode evalResp = activeTab.send("Runtime.evaluate", evalParams);
                if (evalResp != null && evalResp.get("error") == null) {
                    JsonNode objIdNode = evalResp.path("result").path("result").path("objectId");
                    if (!objIdNode.isMissingNode()) {
                        String objectId = objIdNode.asText();
                        Map<String, Object> pObj = new HashMap<>();
                        pObj.put("files", List.of(abs));
                        pObj.put("objectId", objectId);
                        JsonNode rObj = activeTab.send("DOM.setFileInputFiles", pObj);
                        AppLogger.info("CdpDeepSeek.attachFile: objectId attempt resp=" + rObj);
                        String firstFilesObj = evaluate("String((function(){var i=document.querySelector('input[type=file]');return i && i.files ? i.files.length : -1;})())");
                        AppLogger.info("CdpDeepSeek.attachFile: after objectId firstFiles=" + firstFilesObj);
                        if (!"0".equals(firstFilesObj) && !"-1".equals(firstFilesObj)) {
                            firstFiles = firstFilesObj;
                        }
                    }
                }
            } catch (Exception objEx) {
                AppLogger.warn("CdpDeepSeek.attachFile: objectId attempt failed: " + objEx.getMessage());
            }
        }

        if ("0".equals(firstFiles) || "-1".equals(firstFiles)) {
            AppLogger.warn("CdpDeepSeek.attachFile: all setFileInputFiles variants failed, trying DataTransfer");
            attachFileViaDataTransfer(path);
            attachFileViaDragEvent(path);
            attachFileViaPasteEvent(path);
            waitForAttachmentVisible(ATTACH_VISIBLE_TIMEOUT_MS, baselineComposer);
            long elapsedDt = System.currentTimeMillis() - start;
            AppLogger.info("attachFile: file=" + abs + ", bytes=" + bytes + ", elapsed=" + elapsedDt + "ms (datatransfer)");
            return;
        }

        waitForAttachmentVisible(ATTACH_VISIBLE_TIMEOUT_MS, baselineComposer);
        long elapsed = System.currentTimeMillis() - start;
        AppLogger.info("attachFile: file=" + abs + ", bytes=" + bytes + ", elapsed=" + elapsed + "ms (dom)");
    }



    private Integer nodeIdOfDocument() {

        Map<String, Object> params = new HashMap<>();

        params.put("depth", Integer.valueOf(-1));
        params.put("pierce", Boolean.TRUE);

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



    private String clickNearInput() {

        String expr =

                "(function(){"

                + "var i=document.querySelector('input[type=file]');if(!i)return 'no-input';"

                + "var p=i.parentElement;"

                + "if(p){"

                + "  var sib=Array.from(p.children).filter(function(e){return e!==i && (e.tagName==='BUTTON'||e.getAttribute('role')==='button'||(e.tagName||'').toUpperCase()==='SVG');});"

                + "  if(sib.length){sib[0].click();return 'clicked-parent-'+sib[0].tagName+'.'+((sib[0].className||'').toString().slice(0,30));}"

                + "}"

                + "var gp=p&&p.parentElement;"

                + "if(gp){"

                + "  var gsib=Array.from(gp.children).filter(function(e){return e!==p && (e.tagName==='BUTTON'||e.getAttribute('role')==='button'||(e.tagName||'').toUpperCase()==='SVG');});"

                + "  if(gsib.length){gsib[0].click();return 'clicked-grandparent-'+gsib[0].tagName+'.'+((gsib[0].className||'').toString().slice(0,30));}"

                + "}"

                + "return 'not-found';"

                + "})()";

        return evaluate(expr);

    }

    private void clickComposerButton() {

        try {

            String rectJson = evaluate("String((function(){var i=document.querySelector('input[type=file]');if(!i)return '';var p=i.parentElement;if(!p)return '';var btn=null;for(var k=0;k<p.children.length;k++){var c=p.children[k];if(c===i)continue;if(c.tagName==='BUTTON'||(c.getAttribute&&c.getAttribute('role')==='button')){btn=c;break;}}if(!btn)return '';var r=btn.getBoundingClientRect();if(r.width===0||r.height===0)return '';return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})())");

            if (rectJson != null && rectJson.contains("x")) {

                com.fasterxml.jackson.databind.JsonNode rj = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rectJson);

                double cx = rj.path("x").asDouble();

                double cy = rj.path("y").asDouble();

                Map<String, Object> press = new HashMap<>();

                press.put("type", "mousePressed");

                press.put("x", cx); press.put("y", cy);

                press.put("button", "left"); press.put("clickCount", 1);

                activeTab.send("Input.dispatchMouseEvent", press);

                sleep(50L);

                Map<String, Object> release = new HashMap<>();

                release.put("type", "mouseReleased");

                release.put("x", cx); release.put("y", cy);

                release.put("button", "left"); release.put("clickCount", 1);

                activeTab.send("Input.dispatchMouseEvent", release);

                AppLogger.info("FIX20: CDP mouse click on composer button at " + cx + "," + cy);

                return;

            }

            AppLogger.info("FIX20: composer button rect not found");

        } catch (Exception e) {

            AppLogger.warn("FIX20: clickComposerButton mouse failed: " + e.getMessage());

        }

        String cnr = clickNearInput();

        AppLogger.info("FIX20: clickNearInput() = " + cnr);

        if ("not-found".equals(cnr) || "no-input".equals(cnr)) {

            String icr = evaluate("(function(){var i=document.querySelector('input[type=file]');if(!i)return 'no-input';i.click();return 'input-clicked';})()");

            AppLogger.info("FIX20: input.click() = " + icr);

            if (!"input-clicked".equals(icr)) {

                clickAttachButton();

            }

        }

    }








    private void waitForAttachmentVisible(long timeoutMs) throws CdpAttachException {

        // Simple length check (used by attachFileViaChooser fallback; keeps backward-compat with existing test).

        long deadline = System.currentTimeMillis() + timeoutMs;

        String expr = "String(document.querySelectorAll(\"" + escapeJs(DeepSeekSelectors.ATTACHMENT_NODE) + "\").length)";

        while (System.currentTimeMillis() < deadline) {

            String v = evaluate(expr);

            try {

                if (v != null && Long.parseLong(v) > 0L) return;

            } catch (NumberFormatException ignored) { }

            sleep(ATTACH_POLL_MS);

        }

        throw new CdpAttachException("wait-upload",

                "attachment did not appear in DOM within " + timeoutMs + " ms", null);

    }




    private void waitForAttachmentVisible(long timeoutMs, int baselineComposer) throws CdpAttachException {

        long startMs = System.currentTimeMillis();

        long deadline = startMs + timeoutMs;

        long lastDiagMs = 0L;

        long lastDumpMs = 0L;

        while (System.currentTimeMillis() < deadline) {

            String json = evaluate("String((function(){var t=document.querySelector('textarea[name=search]');if(!t)return '{\"composerBlob\":-1,\"totalBlob\":-1,\"attachNodes\":-1}';function inComposer(el){var p=el;for(var i=0;i<8&&p;i++){if(p.querySelector&&p.querySelector('textarea[name=search]'))return true;p=p.parentElement;}return false;}var imgs=document.querySelectorAll('img[src^=\"blob:\"]');var total=imgs.length;var comp=0;for(var k=0;k<imgs.length;k++){if(inComposer(imgs[k]))comp++;}var an=document.querySelectorAll(\"" + escapeJs(DeepSeekSelectors.ATTACHMENT_NODE) + "\").length;return JSON.stringify({composerBlob:comp,totalBlob:total,attachNodes:an});})())");

            int curComposer = -1;

            try {

                com.fasterxml.jackson.databind.JsonNode j = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);

                curComposer = j.path("composerBlob").asInt(-1);

            } catch (Exception ignored) { }

            if (curComposer > baselineComposer) {

                AppLogger.info("FIX28: attachment appeared after " + (System.currentTimeMillis() - startMs) + "ms (composerBlob=" + curComposer + " baseline=" + baselineComposer + ")");

                return;

            }

            long now = System.currentTimeMillis();

            if (now - lastDiagMs >= 2000L) {

                lastDiagMs = now;

                AppLogger.info("FIX28: waitForAttachment " + (now - startMs) + "ms json = " + json + " baseline=" + baselineComposer);

            }

            if (now - lastDumpMs >= 6000L) {

                lastDumpMs = now;

                try {

                    String dump = evaluate("JSON.stringify(Array.from(document.querySelectorAll('img[src^=\"blob:\"]')).slice(0,10).map(function(e){var p=e.parentElement;return {cls:(e.className||'').toString().slice(0,40),alt:(e.alt||'').slice(0,20),nw:e.naturalWidth,nh:e.naturalHeight,parentTag:p?p.tagName:'?',parentCls:p?(p.className||'').toString().slice(0,40):'?'};}))");

                    AppLogger.info("FIX28: blob-img dump = " + dump);

                } catch (Exception ignoredDump) { }

            }

            sleep(ATTACH_POLL_MS);

        }

        throw new CdpAttachException("wait-upload",

                "attachment did not appear in DOM within " + timeoutMs + " ms (baseline=" + baselineComposer + ")", null);

    }



    private boolean attachFileViaDragEvent(Path path) {

        try {

            String abs = path.toAbsolutePath().toString();



            // FIX25: install drop listener for diagnostics

            try {

                evaluate("(function(){window.__dropDiag={count:0,events:[]};if(window.__dropListener){document.removeEventListener('drop',window.__dropListener,true);}window.__dropListener=function(e){window.__dropDiag.count++;var dt=e.dataTransfer;window.__dropDiag.events.push({isTrusted:e.isTrusted,files:dt?dt.files.length:-1,items:dt?dt.items.length:-1,types:dt?Array.from(dt.types):[],target:e.target?(e.target.tagName+'.'+(e.target.className||'').toString().slice(0,40)):'?'});};document.addEventListener('drop',window.__dropListener,true);return 'installed';})()");

            } catch (Exception ignored) { }



            // FIX25: Playwright-style dragData (empty items, files only)

            Map<String, Object> dragData = new HashMap<>();

            dragData.put("items", List.of());

            dragData.put("files", List.of(abs));

            dragData.put("dragOperationsMask", Integer.valueOf(1));



            // FIX25: try two drop targets: viewport-center, then input-parent-center

            String[] targets = {"viewport-center", "input-parent"};

            for (String target : targets) {

                String coordJson = target.equals("viewport-center")

                        ? evaluate("JSON.stringify({x:Math.round(window.innerWidth/2),y:Math.round(window.innerHeight/2)})")

                        : evaluate("JSON.stringify((function(){var i=document.querySelector('input[type=file]');if(!i)return null;var p=i.parentElement;if(!p)return null;var r=p.getBoundingClientRect();if(r.width<=0||r.height<=0)return null;return {x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)};})())");

                if (coordJson == null || !coordJson.contains("x")) {

                    AppLogger.info("FIX25: skip " + target + " (no coords)");

                    continue;

                }

                double cx;

                double cy;

                try {

                    com.fasterxml.jackson.databind.JsonNode rj = new com.fasterxml.jackson.databind.ObjectMapper().readTree(coordJson);

                    cx = rj.path("x").asDouble();

                    cy = rj.path("y").asDouble();

                } catch (Exception pe) {

                    AppLogger.warn("FIX25: parse " + target + " coords failed: " + pe.getMessage());

                    continue;

                }



                AppLogger.info("FIX25: drop attempt " + target + " at " + cx + "," + cy + " (empty-items)");

                for (String phase : new String[]{"dragEnter", "dragOver", "drop"}) {

                    Map<String, Object> params = new HashMap<>();

                    params.put("type", phase);

                    params.put("x", cx);

                    params.put("y", cy);

                    params.put("data", dragData);

                    try {

                        activeTab.send("Input.dispatchDragEvent", params);

                    } catch (Exception pe) {

                        AppLogger.warn("FIX25: " + phase + " failed: " + pe.getMessage());

                    }

                    sleep(80L);

                }

                sleep(500L);



                String cardsCount = evaluate("String(document.querySelectorAll(\"" + escapeJs(DeepSeekSelectors.ATTACHMENT_NODE) + "\").length)");

                String inputFiles = evaluate("String((function(){var i=document.querySelector('input[type=file]');return i && i.files ? i.files.length : -1;})())");

                AppLogger.info("FIX25: after " + target + " attachNodes=" + cardsCount + " inputFiles=" + inputFiles);

                try {

                    if (Long.parseLong(cardsCount) > 0L) {

                        AppLogger.info("FIX25: SUCCESS via " + target);

                        return true;

                    }

                } catch (NumberFormatException ignored) { }

            }



            // FIX25: final diagnostic

            try {

                String diag = evaluate("JSON.stringify(window.__dropDiag)");

                AppLogger.info("FIX25: dropDiag = " + diag);

            } catch (Exception ignored) { }

            return true; // FIX27: drop event delivered; attachment appears async.

        } catch (Exception e) {

            AppLogger.warn("FIX25: dragEvent failed: " + e.getMessage());

            return false;

        }

    }



    private void attachFileViaPasteEvent(Path path) {

        try {

            byte[] fileBytes = Files.readAllBytes(path);

            String base64 = java.util.Base64.getEncoder().encodeToString(fileBytes);

            String fileName = path.getFileName().toString();

            String mimeType = java.net.URLConnection.guessContentTypeFromName(fileName);

            if (mimeType == null) mimeType = "application/octet-stream";

            String safeName = fileName.replace("\\", "\\\\").replace("'", "\\'");

            String safeMime = mimeType.replace("'", "\\'");

            StringBuilder js = new StringBuilder();

            js.append("(function(){");

            js.append("var t=document.querySelector('textarea[name=search]');if(!t)return 'no-textarea';");

            js.append("try{");

            js.append("var b64='").append(base64).append("';");

            js.append("var bin=atob(b64);");

            js.append("var bytes=new Uint8Array(bin.length);");

            js.append("for(var k=0;k<bin.length;k++)bytes[k]=bin.charCodeAt(k);");

            js.append("var file=new File([bytes],'").append(safeName).append("',{type:'").append(safeMime).append("'});");

            js.append("var dt=new DataTransfer();");

            js.append("dt.items.add(file);");

            js.append("var ev=new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true});");

            js.append("t.dispatchEvent(ev);");

            js.append("return 'paste-dispatched';");

            js.append("}catch(e){return 'err-'+e.message;}");

            js.append("})()");

            String result = evaluate(js.toString());

            AppLogger.info("FIX27: paste event result = " + result);

        } catch (Exception e) {

            AppLogger.warn("FIX27: paste event failed: " + e.getMessage());

        }

    }



    private boolean attachFileViaDataTransfer(Path path) {

        try {

            byte[] fileBytes = Files.readAllBytes(path);

            String base64 = java.util.Base64.getEncoder().encodeToString(fileBytes);

            String fileName = path.getFileName().toString();

            String mimeType = java.net.URLConnection.guessContentTypeFromName(fileName);

            if (mimeType == null) mimeType = "application/octet-stream";

            String safeName = fileName.replace("\\", "\\\\").replace("'", "\\'");

            String safeMime = mimeType.replace("'", "\\'");



            // FIX26 attempt A: direct assignment (article-proven: input.files = dt.files)

            StringBuilder a = new StringBuilder();

            a.append("(function(){");

            a.append("var i=document.querySelector('input[type=file]');if(!i)return 'no-input';");

            a.append("try{");

            a.append("var b64='").append(base64).append("';");

            a.append("var bin=atob(b64);");

            a.append("var bytes=new Uint8Array(bin.length);");

            a.append("for(var k=0;k<bin.length;k++)bytes[k]=bin.charCodeAt(k);");

            a.append("var blob=new Blob([bytes],{type:'").append(safeMime).append("'});");

            a.append("var file=new File([blob],'").append(safeName).append("',{type:'").append(safeMime).append("'});");

            a.append("var dt=new DataTransfer();");

            a.append("dt.items.add(file);");

            a.append("i.files=dt.files;");

            a.append("i.dispatchEvent(new Event('change',{bubbles:true}));");

            a.append("i.dispatchEvent(new Event('input',{bubbles:true}));");

            a.append("var n=i.files?i.files.length:-1;");

            a.append("return 'direct-'+n+'-'+file.name+'-'+file.size;");

            a.append("}catch(e){return 'err-'+e.message;}");

            a.append("})()");

            String resultA = evaluate(a.toString());

            AppLogger.info("FIX26: DataTransfer(direct) result = " + resultA);

            if (resultA != null && resultA.startsWith("direct-") && !resultA.startsWith("direct-0")) {

                return true;

            }



            // FIX26 attempt B: native setter (legacy FIX22 path, older React versions)

            StringBuilder b = new StringBuilder();

            b.append("(function(){");

            b.append("var i=document.querySelector('input[type=file]');if(!i)return 'no-input';");

            b.append("try{");

            b.append("var b64='").append(base64).append("';");

            b.append("var bin=atob(b64);");

            b.append("var bytes=new Uint8Array(bin.length);");

            b.append("for(var k=0;k<bin.length;k++)bytes[k]=bin.charCodeAt(k);");

            b.append("var blob=new Blob([bytes],{type:'").append(safeMime).append("'});");

            b.append("var file=new File([blob],'").append(safeName).append("',{type:'").append(safeMime).append("'});");

            b.append("var dt=new DataTransfer();");

            b.append("dt.items.add(file);");

            b.append("var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'files').set;");

            b.append("setter.call(i,dt.files);");

            b.append("i.dispatchEvent(new Event('input',{bubbles:true}));");

            b.append("i.dispatchEvent(new Event('change',{bubbles:true}));");

            b.append("var n=i.files?i.files.length:-1;");

            b.append("return 'setter-'+n+'-'+file.name+'-'+file.size;");

            b.append("}catch(e){return 'err-'+e.message;}");

            b.append("})()");

            String resultB = evaluate(b.toString());

            AppLogger.info("FIX26: DataTransfer(setter) result = " + resultB);

            return true; // FIX27: React may reset input.files; attachment appears async.

        } catch (Exception e) {

            AppLogger.warn("FIX26: DataTransfer failed: " + e.getMessage());

            return false;

        }

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



            clickComposerButton();



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
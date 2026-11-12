
package com.freemaf.agent.cdp;



import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;



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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long POLL_INTERVAL_MS = 500L;

    private static final int STABLE_ITERATIONS = 20;
    private static final int STABLE_ITERATIONS_WITH_BTN = 8;
    private static final long ATTACH_POLL_MS = 200L;
    private static final long ATTACH_VISIBLE_TIMEOUT_MS = 20000L;
    private static final long CHOOSER_TIMEOUT_MS = 3000L;
    private static final int ATTACH_CLICK_ATTEMPTS = 3;



    private final CdpSession session;

    private final LoginFallbackHandler loginHandler;



    private TabSession activeTab;

    private volatile int lastPreSendCount = 0;
    private volatile String lastPreSendText = "";

    // NET-DIAG: opt-in Network.* event logging (activated by -Dfreemaf.netDiag=true).
    private volatile boolean netDiagSubscribed = false;
    private volatile long netDiagSendStartMs = 0L;
    private volatile int netDiagApiRequests = 0;
    private Consumer<JsonNode> netReqHandler;
    private Consumer<JsonNode> netRespHandler;
    private Consumer<JsonNode> netFailHandler;
    private Consumer<JsonNode> netDataReceivedHandler;
    private Consumer<JsonNode> netLoadingFinishedHandler;
    private Consumer<JsonNode> netWsCreatedHandler;
    private Consumer<JsonNode> netWsClosedHandler;
    private Consumer<JsonNode> netWsFrameHandler;
    private Consumer<JsonNode> netConsoleHandler;
    private Consumer<JsonNode> netExceptionHandler;
    private final java.util.concurrent.ConcurrentHashMap<String, String> netTrackedReqs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> netDataTotals = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> netChunkCounts = new java.util.concurrent.ConcurrentHashMap<>();

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
        // A1-focus-preservation-comment
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

        enableNetworkDiagnostics();
        installHifRewrite();

    }


    public void open() throws Exception {

        activeTabInfo = session.newTab();

        if (activeTabInfo == null || activeTabInfo.id().isEmpty()) {

            throw new IllegalStateException("newTab returned no usable tab");

        }

        // A1-focus-preservation-comment

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

        enableNetworkDiagnostics();

        navigate(DEEPSEEK_URL);
        installHifRewrite();

        // FIX35: fast-fail if redirected to sign_in (cold profile, CloudFront sign_in response).
        // Saves ~13 s per open() call when not logged in.
        sleep(2000L);
        String urlAfterNav = evaluate("String(location.href)");
        if (urlAfterNav != null && (urlAfterNav.contains("/sign_in") || urlAfterNav.contains("/signin") || urlAfterNav.contains("/login"))) {
            throw new IllegalStateException("FIX35: sign_in detected after navigate (" + urlAfterNav + "); fast-fail");
        }



                try {
            waitForInput(15_000L);
        } catch (TimeoutException te) {
            AppLogger.warn("CdpDeepSeek.open: waitForInput 15s timeout, dumping diagnostics + retry 30s: " + te.getMessage());
            dumpTimeoutDiagnostic();
            try {
                waitForInput(30_000L);
            } catch (TimeoutException te2) {
                AppLogger.warn("CdpDeepSeek.open: waitForInput retry 30s timeout: " + te2.getMessage());
                dumpTimeoutDiagnostic();
                throw new IllegalStateException("input field not found after navigate (15s+30s): " + te2.getMessage(), te2);
            }
        }


        int landedCount = 0;
        for (int poll = 0; poll < 4; poll++) {
            int c = countAnyMessages();
            if (c > landedCount) landedCount = c;
            if (landedCount > 0) break;
            sleep(500L);
        }

        // FIX15: unconditionally force fresh chat via clickNewChat (DOM is virtualized, history not visible)
        
        // Fix-E (C6, N-62): diagnostic — record the caller of open() so we can identify

        // unexpected invocation paths (e.g. after async start completes).

        String callerTrace = "";

        try {

            StackTraceElement[] st = Thread.currentThread().getStackTrace();

            for (int ci = 2; ci < st.length && ci < 10; ci++) {

                String cn = st[ci].getClassName();

                if (cn.startsWith("java.") || cn.startsWith("com.freemaf.agent.cdp.CdpDeepSeek")) continue;

                callerTrace = cn + "." + st[ci].getMethodName() + ":" + st[ci].getLineNumber();

                break;

            }

        } catch (Throwable ignored) { }

        AppLogger.info("CdpDeepSeek.open: initial count=" + landedCount + "; forcing fresh chat via clickNewChat() [caller=" + callerTrace + "]");

        String clickResult = clickNewChatWithRetry();
        AppLogger.info("CdpDeepSeek.open: clickNewChat result = " + clickResult);
        if ("NOT_FOUND".equals(clickResult)) {
            AppLogger.warn("CdpDeepSeek.open: clickNewChat NOT_FOUND after retries; failing fast");
            throw new IllegalStateException("CdpDeepSeek.open: clickNewChat NOT_FOUND");
        }
        sleep(2000L);
        
try { waitForInput(10_000L); } catch (TimeoutException ignored) { }

        // Ensure DeepThink and Search are enabled before sending any prompt

        if (!ensureDeepThinkAndSearchEnabled()) {

            throw new IllegalStateException("DeepThink/Search cannot be enabled; refusing to start work");

        }
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



    private void dumpTimeoutDiagnostic() {
        try {
            String href = evaluate("String(location.href)");
            String title = evaluate("String(document.title)");
            String outerLen = evaluate("String(document.documentElement.outerHTML.length)");
            String innerLen = evaluate("String((document.body && document.body.innerText || '').length)");
            String textareaCount = evaluate("String(document.querySelectorAll('textarea').length)");
            String searchTa = evaluate("String(document.querySelectorAll('textarea[name=search]').length)");
            String ceCount = evaluate("String(document.querySelectorAll('[contenteditable]').length)");
            String iframeCount = evaluate("String(document.querySelectorAll('iframe').length)");
            String bodySnip = evaluate("String(((document.body && document.body.innerText) || '').substring(0, 200))");
            AppLogger.warn("CdpDeepSeek TIMEOUT-DIAG: href=" + href + " title=" + title
                + " outerLen=" + outerLen + " innerLen=" + innerLen
                + " textareas=" + textareaCount + " searchTa=" + searchTa
                + " contenteditables=" + ceCount + " iframes=" + iframeCount
                + " bodySnippet=[" + bodySnip + "]");
        } catch (Exception e) {
            AppLogger.warn("CdpDeepSeek TIMEOUT-DIAG failed: " + e.getMessage());
        }
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



    /**
     * S2.2 / AUD-52: bounded retry around clickNewChat().
     * clickNewChat() has no internal retry; the DOM may not be ready immediately
     * after navigation. Up to 4 attempts with 500 ms gaps (max 2 s) before open()
     * fails fast on NOT_FOUND. InterruptedException is flagged and the loop aborts.
     */
    private String clickNewChatWithRetry() {
        String lastResult = "NOT_FOUND";
        for (int attempt = 1; attempt <= 4; attempt++) {
            lastResult = clickNewChat();
            if (!"NOT_FOUND".equals(lastResult)) {
                AppLogger.info("CdpDeepSeek.clickNewChatWithRetry: succeeded on attempt " + attempt + " result=" + lastResult);
                return lastResult;
            }
            if (attempt < 4) {
                AppLogger.info("CdpDeepSeek.clickNewChatWithRetry: attempt " + attempt + " NOT_FOUND, retrying in 500ms");
                sleep(500L);
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("CdpDeepSeek.clickNewChatWithRetry: interrupted");
                }
            }
        }
        return lastResult;
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
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("CdpDeepSeek.sendMessage: prompt is null or blank");
        }
        lastPreSendCount = countAssistantMessages();
        final boolean __netDiag = "true".equalsIgnoreCase(System.getProperty("freemaf.netDiag", "true"));
        if (__netDiag) {
            netDiagSendStartMs = System.currentTimeMillis();
            netDiagApiRequests = 0;
            AppLogger.info("NET-DIAG: sendMessage start, textLen=" + text.length() + ", preCount=" + lastPreSendCount);
        }
        String snapText = readLastResponse();
        lastPreSendText = (snapText == null) ? "" : snapText;

        String preHref = evaluate("String(location.href)");

        String preHasInput = evaluate("String(!!document.querySelector(\"" + escapeJs(DeepSeekSelectors.INPUT) + "\"))");

        AppLogger.info("CdpDeepSeek.sendMessage: preState href=" + preHref + " hasInput=" + preHasInput);

        String focusExpr = "(function(){var el=document.querySelector(\"" + escapeJs(DeepSeekSelectors.INPUT) + "\");if(el)el.focus();return '';})()";

        evaluate(focusExpr);

        sleep(300L);

        
final int SAFE_CAP = 20000;
        String maxLenStr = evaluate("String(((document.querySelector('" + escapeJsSingleQuoted(DeepSeekSelectors.INPUT) + "') || {}).maxLength) || 0)");
        int maxLen = 0;
        try { maxLen = Integer.parseInt(maxLenStr.trim()); } catch (Exception ignored) { maxLen = 0; }
        // textarea.maxLength is usually unset (0/-1) on DeepSeek; React's internal
        // character counter enforces the real limit by disabling the Send button.
        // Hard cap at SAFE_CAP regardless of maxLength to stay below the observed limit.
        int effectiveCap = (maxLen > 0 && maxLen < SAFE_CAP) ? maxLen : SAFE_CAP;
        String textForSend = text;
        if (text.length() > effectiveCap) {
            int origLen = text.length();
            int dropped = origLen - effectiveCap;
            double pct = (dropped * 100.0) / origLen;
            textForSend = text.substring(0, effectiveCap);
            AppLogger.warn("CdpDeepSeek.sendMessage: text truncated to cap=" + effectiveCap
                    + " (maxLength=" + maxLen + ", original=" + origLen
                    + ", dropped=" + dropped + " chars, " + String.format("%.1f", pct) + "% over)");
        }
        String safe = escapeJsSingleQuoted(textForSend);

        String setterScript = "(function(){var el=document.querySelector('" + escapeJsSingleQuoted(DeepSeekSelectors.INPUT) + "');if(!el)return 'no-input';try{var ns=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;ns.call(el,'" + safe + "');el.dispatchEvent(new Event('input',{bubbles:true}));return 'setter-'+el.value.length;}catch(e){return 'err-'+e.message;}})()";

        String insertResult = evaluate(setterScript);

        AppLogger.info("CdpDeepSeek.sendMessage: insertResult=" + insertResult + " textLen=" + textForSend.length() + " originalTextLen=" + text.length() + " maxLength=" + maxLen);

        String afterInsertText = evaluate("String(((document.querySelector(\"" + escapeJs(DeepSeekSelectors.INPUT) + "\") || {}).value) || '')");

        
int afterInsertLen = afterInsertText.length();
        AppLogger.info("CdpDeepSeek.sendMessage: afterInsertLen=" + afterInsertLen);
        if (afterInsertLen == 0) {
            throw new IllegalStateException("sendMessage: setter produced empty textarea; expected=" + textForSend.length() + " chars");
        }
        if (afterInsertLen < textForSend.length()) {
            AppLogger.warn("CdpDeepSeek.sendMessage: React truncated text after insert; expected=" + textForSend.length() + " actual=" + afterInsertLen);
        }

        sleep(500L);
        String tabAlive = evaluate("String(1)");
        if (!"1".equals(tabAlive)) {
            throw new IllegalStateException("sendMessage: active tab is closed or unresponsive");
        }
        boolean sent = false;
        for (int attempt = 1; attempt <= 3 && !sent; attempt++) {

            evaluate(focusExpr);

            sleep(200L);

            try {

                Map<String, Object> keyDown = new HashMap<>();

                keyDown.put("type", "keyDown");

                keyDown.put("key", "Enter");

                keyDown.put("code", "Enter");

                keyDown.put("windowsVirtualKeyCode", Integer.valueOf(13));

                keyDown.put("nativeVirtualKeyCode", Integer.valueOf(13));

                keyDown.put("text", "\r");

                keyDown.put("unmodifiedText", "\r");

                activeTab.send("Input.dispatchKeyEvent", keyDown);

                sleep(50L);

                Map<String, Object> keyUp = new HashMap<>();

                keyUp.put("type", "keyUp");

                keyUp.put("key", "Enter");

                keyUp.put("code", "Enter");

                keyUp.put("windowsVirtualKeyCode", Integer.valueOf(13));

                keyUp.put("nativeVirtualKeyCode", Integer.valueOf(13));

                activeTab.send("Input.dispatchKeyEvent", keyUp);

                sleep(500L);

                String checkText = evaluate("String(((document.querySelector(\"" + escapeJs(DeepSeekSelectors.INPUT) + "\") || {}).value) || '')");

                if (checkText.isEmpty()) {

                    sent = true;

                    AppLogger.info("CdpDeepSeek.sendMessage: enterResult=dispatched-via-CDP (attempt " + attempt + ")");

                } else {

                    AppLogger.warn("CdpDeepSeek.sendMessage: textarea still has " + checkText.length() + " chars after Enter (attempt " + attempt + "), retrying");

                }

            } catch (Exception enterEx) {

                AppLogger.warn("CdpDeepSeek.sendMessage: CDP Enter failed: " + enterEx.getMessage());

            }

        }

        if (!sent) {

            
String finalText = evaluate("String(((document.querySelector(\"" + escapeJs(DeepSeekSelectors.INPUT) + "\") || {}).value) || '')");
            String domMaxLen = evaluate("String(((document.querySelector('" + escapeJsSingleQuoted(DeepSeekSelectors.INPUT) + "') || {}).maxLength) || 0)");
            try { dumpTimeoutDiagnostic(); } catch (Exception diagEx) { AppLogger.warn("dumpTimeoutDiagnostic failed: " + diagEx.getMessage()); }
            throw new IllegalStateException("sendMessage failed: textarea still contains " + finalText.length()
                    + " chars after 3 Enter attempts; maxLength=" + domMaxLen
                    + ", attempted=" + textForSend.length() + " chars, original=" + text.length() + " chars");

        }

        sleep(500L);

        String postText = evaluate("String(((document.querySelector(\"" + escapeJs(DeepSeekSelectors.INPUT) + "\") || {}).value) || '')");

        AppLogger.info("CdpDeepSeek.sendMessage: postTextareaLen=" + postText.length());

        if (__netDiag) {
            long __waited = System.currentTimeMillis() - netDiagSendStartMs;
            int __curCount = countAssistantMessages();
            AppLogger.info("NET-DIAG: sendMessage returned, waited=" + __waited + "ms, postTextareaLen=" + postText.length() + ", apiRequests=" + netDiagApiRequests + ", curCount=" + __curCount);
        }

    }









    public boolean clickRegenerate() {

        long deadline = System.currentTimeMillis() + 10000L;

        long lastLog = 0L;

        while (System.currentTimeMillis() < deadline) {

            String expr =

                    "(function(){" +

                    "var paths = document.querySelectorAll('div.ds-flex svg path');" +

                    "for (var i = paths.length - 1; i >= 0; i--) {" +

                    "  var d = paths[i].getAttribute('d') || '';" +

                    "  if (d.indexOf('M7.92136 0.349152') === 0) {" +

                    "    var btn = paths[i].closest('div[role=button], button');" +

                    "    if (btn && btn.getAttribute('aria-disabled') !== 'true') {" +

                    "      btn.click();" +

                    "      return 'clicked-svg';" +

                    "    }" +

                    "  }" +

                    "}" +

                    "var bars = document.querySelectorAll('div.ds-flex');" +

                    "for (var j = bars.length - 1; j >= 0; j--) {" +

                    "  var btns = bars[j].querySelectorAll('[role=button].ds-button--iconLabelTertiary');" +

                    "  if (btns.length < 5) continue;" +

                    "  var hasReadAloud = false;" +

                    "  for (var k = 0; k < btns.length; k++) {" +

                    "    if ((btns[k].getAttribute('aria-label') || '').indexOf('Read aloud') >= 0) { hasReadAloud = true; break; }" +

                    "  }" +

                    "  if (hasReadAloud && btns.length >= 2) {" +

                    "    btns[1].click();" +

                    "    return 'clicked-bar-pos1';" +

                    "  }" +

                    "}" +

                    "return 'not-found';" +

                    "})()";

            String res = evaluate(expr);

            long now = System.currentTimeMillis();

            if (now - lastLog >= 2000L) {

                AppLogger.info("clickRegenerate attempt: " + res);

                lastLog = now;

            }

            if (res != null && res.startsWith("clicked-")) {

                AppLogger.info("clickRegenerate succeeded: " + res);

                return true;

            }

            sleep(500L);

        }

        AppLogger.warn("clickRegenerate: button not found after 10s");

        return false;

    }



public boolean ensureDeepThinkAndSearchEnabled() {

        boolean ok1 = ensureButtonEnabled(DeepSeekSelectors.DEEP_THINK_TEXTS, "DeepThink");

        boolean ok2 = ensureButtonEnabled(DeepSeekSelectors.SEARCH_TEXTS, "Search");

        return ok1 && ok2;

    }



    private boolean ensureButtonEnabled(String[] texts, String label) {

        for (int attempt = 1; attempt <= 3; attempt++) {

            String state = evaluateButtonState(texts);

            AppLogger.info("CdpDeepSeek." + label + ": state=" + state + " (attempt " + attempt + ")");

            if ("enabled".equals(state)) return true;

            if ("not-found".equals(state)) {

                AppLogger.warn("CdpDeepSeek." + label + " button not found");

                return false;

            }

            String clickResult = clickButtonByText(texts);

            AppLogger.info("CdpDeepSeek." + label + " click result = " + clickResult);

            sleep(800L);

        }

        String finalState = evaluateButtonState(texts);

        AppLogger.warn("CdpDeepSeek." + label + " still disabled after 3 attempts (state=" + finalState + ")");

        return "enabled".equals(finalState);

    }



    
private String evaluateButtonState(String[] texts) {

        String arr = toJsonArray(texts);

        String expr =

            "(function(){" +

            "var texts = " + arr + ";" +

            "var all = document.querySelectorAll('*');" +

            "for (var i = 0; i < all.length; i++) {" +

            "  var el = all[i];" +

            "  var text = (el.textContent || '').trim();" +

            "  var aria = (el.getAttribute('aria-label') || '').trim();" +

            "  for (var j = 0; j < texts.length; j++) {" +

            "    var target = texts[j];" +

            "    if (text.indexOf(target) >= 0 || aria.indexOf(target) >= 0) {" +

            "      var p = el;" +

            "      for (var k = 0; k < 8 && p; k++) {" +

            "        var role = p.getAttribute && p.getAttribute('role');" +

            "        if (role === 'button' || p.tagName === 'BUTTON' || (p.getAttribute && p.getAttribute('tabindex') === '0')) {" +

            "          var pressed = p.getAttribute('aria-pressed');" +

            "          if (pressed === 'true') return 'enabled';" +

            "          if (pressed === 'false') return 'disabled';" +

            "          var cls = (p.className || '').toString();" +

            "          if (/(primary|filled|active|selected)/i.test(cls)) return 'enabled';" +

            "          var style = window.getComputedStyle(p);" +

            "          var fg = (style && style.color) || '';" +

            "          if (fg.indexOf('77, 107, 254') >= 0 || fg.indexOf('77,107,254') >= 0) return 'enabled';" +

            "          return 'disabled';" +

            "        }" +

            "        p = p.parentElement;" +

            "      }" +

            "    }" +

            "  }" +

            "}" +

            "return 'not-found';" +

            "})()";

        return evaluate(expr);

    }



    private String clickButtonByText(String[] texts) {

        String arr = toJsonArray(texts);

        String expr =

            "(function(){" +

            "var texts = " + arr + ";" +

            "var all = document.querySelectorAll('*');" +

            "for (var i = 0; i < all.length; i++) {" +

            "  var el = all[i];" +

            "  var text = (el.textContent || '').trim();" +

            "  var aria = (el.getAttribute('aria-label') || '').trim();" +

            "  for (var j = 0; j < texts.length; j++) {" +

            "    var target = texts[j];" +

            "    if (text.indexOf(target) >= 0 || aria.indexOf(target) >= 0) {" +

            "      var p = el;" +

            "      for (var k = 0; k < 8 && p; k++) {" +

            "        var role = p.getAttribute && p.getAttribute('role');" +

            "        if (role === 'button' || p.tagName === 'BUTTON' || (p.getAttribute && p.getAttribute('tabindex') === '0')) {" +

            "          p.click();" +

            "          return 'clicked-' + p.tagName;" +

            "        }" +

            "        p = p.parentElement;" +

            "      }" +

            "    }" +

            "  }" +

            "}" +

            "return 'not-found';" +

            "})()";

        return evaluate(expr);

    }



    
private static String toJsonArray(String[] texts) {

        StringBuilder sb = new StringBuilder("[");

        for (int i = 0; i < texts.length; i++) {

            if (i > 0) sb.append(",");

            sb.append("\"").append(escapeJs(texts[i])).append("\"");

        }

        sb.append("]");

        return sb.toString();

    }







    
    static final String READ_LAST_RESPONSE_EXPR =

                "(function(){" +

                "var msgs = document.querySelectorAll(\"div.ds-markdown, [data-message-author-role='assistant'], [class~='ds-markdown'], [data-chat-flow-kind='assistant-step']\");" +

                "if (msgs.length === 0) return JSON.stringify({t:'',p:'',r:false});" +

                "var m = msgs[msgs.length - 1];" +

                "if (m.closest('[class*=\"ds-think-content\"]')) return JSON.stringify({t:'',p:'',r:true});" +

                "var pre = m.querySelector('.md-code-block pre') || m.querySelector('pre');" +

                "var text = (m.innerText || '').trim();" +

                "var preText = pre ? (pre.innerText || '') : '';" +

                "return JSON.stringify({t:text,p:preText,r:false});" +

                "})()";



    public String readLastResponse() {

        String raw = evaluate(READ_LAST_RESPONSE_EXPR);

        if (raw == null || raw.isEmpty()) return "";

        try {

            JsonNode n = MAPPER.readTree(raw);

            return decideResponseText(n.path("t").asText(""), n.path("p").asText(""), n.path("r").asBoolean(false));

        } catch (Exception e) {

            return "";

        }

    }



    /**

     * S2.2 / AUD-39 (JUDGE REJECT-CORRECTION, plan Step 4.1 contract):

     * - reasoning step -> ""

     * - any of USER_CHAT:/USER_MSG:/AGENT_PAUSE:/AGENT_STOP:/AGENT_DONE:/AGENT_SESSION_MIGRATE_* anywhere in text -> full text

     * - marker absent + no pre -> ""

     * - marker absent + pre + after>100 chars -> INVALID_RESPONSE_TEXT_AFTER_BLOCK_<len>

     * - marker absent + pre + after<=100 chars -> preText

     * Package-private static for unit testing.

     */

        static String decideResponseText(String text, String preText, boolean reasoning) {
        if (reasoning) return "";
        String t = text == null ? "" : text;
        String p = preText == null ? "" : preText;
        if (t.isEmpty()) return "";
        if (!p.isEmpty()) {
            String pTrim = p.trim();
            if (!pTrim.isEmpty()) {
                int idx = t.indexOf(pTrim);
                if (idx < 0) {
                    return "INVALID_RESPONSE_TEXT_AFTER_BLOCK_" + t.length();
                }
                String after = t.substring(idx + pTrim.length()).trim();
                if (containsAnyMarker(after)) {
                    return "INVALID_RESPONSE_TEXT_AFTER_BLOCK_" + after.length();
                }
                if (after.length() > 100) {
                    return "INVALID_RESPONSE_TEXT_AFTER_BLOCK_" + after.length();
                }
                return pTrim;
            }
        }
        if (t.contains("USER_CHAT:")) return t;
        if (containsAnyMarker(t)) return "INVALID_RESPONSE_TEXT_AFTER_BLOCK_" + t.length();
        return "";
    }

    private static boolean containsAnyMarker(String s) {
        if (s == null) return false;
        return s.contains("USER_CHAT:")
                || s.contains("USER_MSG:")
                || s.contains("AGENT_PAUSE:")
                || s.contains("AGENT_STOP:")
                || s.contains("AGENT_DONE:")
                || s.contains("AGENT_SESSION_MIGRATE_");
    }







    public String waitForResponse(long timeoutMs) throws TimeoutException {



        long deadline = System.currentTimeMillis() + timeoutMs;

        int preCount = lastPreSendCount;
        final int originalPreCount = preCount;
        boolean virtualizationOccurred = false;

        String preText = lastPreSendText == null ? "" : lastPreSendText;



        int emptyEvalCount = 0;

        long lastDiagMs = 0L;

        String last = "";

        int stableCount = 0;



        while (System.currentTimeMillis() < deadline) {



            String tabAlive = evaluate("String(1)");

            if (!"1".equals(tabAlive)) {

                emptyEvalCount++;

                if (emptyEvalCount >= 3) {

                    throw new IllegalStateException("waitForResponse: active tab is closed or unresponsive");

                }

            } else {

                emptyEvalCount = 0;

            }



            if (EmergencyStop.isStopped() || Thread.currentThread().isInterrupted()) {

                throw new IllegalStateException("waitForResponse aborted by user stop or thread interrupt");

            }



            int curCount = countAssistantMessages();


            if (curCount < preCount) {

                // DOM virtualization in long chats: DeepSeek may drop old message nodes.

                // This is NOT a tab reset. Update baseline and continue.

                AppLogger.warn("CdpDeepSeek.waitForResponse: count dropped from " + preCount + " to " + curCount + "; treating as DOM virtualization, updating baseline");

                preCount = curCount;
                virtualizationOccurred = true;

                String newPre = readLastResponse();

                if (newPre != null && !newPre.isEmpty() && !newPre.equals(preText)) {

                    preText = newPre;

                }

                sleep(POLL_INTERVAL_MS);

                continue;

            }




            long nowMs = System.currentTimeMillis();

            if (nowMs - lastDiagMs >= 5000L) {

                lastDiagMs = nowMs;

                try {

                    String btnC = evaluate("String(document.querySelectorAll('div.ds-flex button').length)");

                    AppLogger.info("CdpDeepSeek.waitForResponse diag: cur=" + curCount + " pre=" + preCount + " preLen=" + preText.length() + " streaming=" + countStreaming() + " btns=" + btnC + " lastLen=" + (last == null ? 0 : last.length()));

                } catch (Exception diagEx) {

                    AppLogger.warn("CdpDeepSeek.waitForResponse diag error: " + diagEx.getMessage());

                }

            }



            String cur = readLastResponse();

            if (cur != null && !cur.isEmpty()) {

                boolean isNew = computeIsNew(cur, preText, curCount, originalPreCount, virtualizationOccurred);

                if (isNew) {

                    boolean streaming = countStreaming() > 0L;

                    boolean complete = isResponseComplete(cur);

                    boolean buttonsPresent = "true".equals(evaluate("String(document.querySelectorAll('div.ds-flex button').length > 0)"));



                    if (!streaming && complete && cur.equals(last)) {

                        stableCount++;

                        int required = buttonsPresent ? STABLE_ITERATIONS_WITH_BTN : STABLE_ITERATIONS;

                        if (stableCount >= required) {

                            return cur;

                        }

                    } else {

                        stableCount = 0;

                    }

                    last = cur;

                }

            }



            sleep(POLL_INTERVAL_MS);

        }



        throw new TimeoutException("response did not stabilize within " + timeoutMs + " ms [preCount=" + preCount + " cur=" + countAssistantMessages() + " streaming=" + countStreaming() + " lastLen=" + (last == null ? 0 : last.length()) + "]");

    }



    /**

     * Decides whether the current DOM text represents a genuinely new assistant response.

     *

     * Rules:

     *  - Text differs from preText: new (covers normal answers, edits in place, regenerate output).

     *  - Text equals preText, curCount grew beyond originalPreCount, and no virtualization happened

     *    since sendMessage: new (covers deterministic identical answers, e.g. short fixed scripts).

     *  - Text equals preText, curCount grew but virtualization happened: NOT new (the count recovery

     *    after DOM pruning is not evidence of a new message).

     *  - Text equals preText and curCount did not grow: NOT new (still waiting).

     *

     * Package-private static for unit testing.

     */

    static boolean computeIsNew(String cur, String preText, int curCount, int originalPreCount, boolean virtualizationOccurred) {

        if (cur == null) return false;

        String base = preText == null ? "" : preText;

        if (!cur.equals(base)) return true;

        return curCount > originalPreCount && !virtualizationOccurred;

    }



private static final java.util.regex.Pattern BARE_CMDLET_VERB =

            java.util.regex.Pattern.compile(

                    "(?i)^(Set|Get|New|Remove|Add|Out|Start|Stop|Wait|Copy|Move|Rename|Clear|Test|Import|Export|Invoke|Push|Pop|Group|Where|ForEach)$");






    static boolean isResponseComplete(String raw) {

        if (raw == null || raw.isEmpty()) return false;

        if (raw.startsWith("INVALID_RESPONSE_")) return true;

        if (raw.contains("Write-Output") && (raw.contains("USER_MSG:") || raw.contains("AGENT_PAUSE:") || raw.contains("AGENT_STOP:") || raw.contains("AGENT_DONE:") || raw.contains("AGENT_SESSION_MIGRATE_START") || raw.contains("AGENT_SESSION_MIGRATE_CONFIRM"))) return true;

        if (raw.contains("USER_CHAT:")) return true;

        if (raw.contains("AGENT_PAUSE:")) return true;

        if (raw.contains("AGENT_STOP:")) return true;

        if (raw.contains("AGENT_DONE:")) return true;

        if (raw.contains("AGENT_SESSION_MIGRATE_")) return true;

        if (raw.length() > 100) return true;

        return false;

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
        disableNetworkDiagnostics();

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



    // NET-DIAG: default ON for this diagnostic session. Pass -Dfreemaf.netDiag=false to disable.
    // TODO(fix): flip default to "false" once the WAF/React no-op issue is resolved.

    private void enableNetworkDiagnostics() {
        if (activeTab == null) return;
        if (!"true".equalsIgnoreCase(System.getProperty("freemaf.netDiag", "true"))) return;
        if (netDiagSubscribed) return;
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("maxTotalBufferSize", Integer.valueOf(100000000));
            params.put("maxResourceBufferSize", Integer.valueOf(10000000));
            params.put("maxPostDataSize", Integer.valueOf(10000000));
            activeTab.send("Network.enable", params);
            AppLogger.info("NET-DIAG: Network.enable sent (large buffers)");
        } catch (Exception e) {
            AppLogger.warn("NET-DIAG: Network.enable failed: " + e.getMessage());
            return;
        }
        try {
            activeTab.send("Runtime.enable", new HashMap<>());
            AppLogger.info("NET-DIAG: Runtime.enable sent");
        } catch (Exception e) {
            AppLogger.warn("NET-DIAG: Runtime.enable failed: " + e.getMessage());
        }
        try {
            Map<String, Object> cacheParams = new HashMap<>();
            cacheParams.put("cacheDisabled", Boolean.TRUE);
            activeTab.send("Network.setCacheDisabled", cacheParams);
            AppLogger.info("NET-DIAG: Network.setCacheDisabled(true)");
        } catch (Exception e) {
            AppLogger.warn("NET-DIAG: setCacheDisabled failed: " + e.getMessage());
        }
        netReqHandler = node -> {
            try {
                JsonNode p = node.get("params");
                if (p == null) return;
                JsonNode req = p.get("request");
                if (req == null) return;
                String url = req.path("url").asText("");
                if (!url.contains("deepseek.com")) return;
                String m = req.path("method").asText("?");
                String reqId = p.path("requestId").asText("?");
                netDiagApiRequests++;
                AppLogger.info("NET-DIAG req: " + m + " " + url + " reqId=" + reqId);
            } catch (Exception ignored) { }
        };
        netRespHandler = node -> {
            try {
                JsonNode p = node.get("params");
                if (p == null) return;
                JsonNode resp = p.get("response");
                if (resp == null) return;
                String url = resp.path("url").asText("");
                if (!url.contains("deepseek.com")) return;
                int status = resp.path("status").asInt(0);
                String reqId = p.path("requestId").asText("?");
                long elapsed = netDiagSendStartMs > 0 ? (System.currentTimeMillis() - netDiagSendStartMs) : -1L;
                String mime = resp.path("mimeType").asText("");
                AppLogger.info("NET-DIAG resp: status=" + status + " url=" + url + " reqId=" + reqId + " elapsed=" + elapsed + "ms mime=" + mime);
                if (url.contains("/chat/completion") || url.contains("/chat/regenerate") || url.contains("/hif-") || mime.contains("event-stream") || mime.contains("websocket")) {
                    netTrackedReqs.put(reqId, url + " [mime=" + mime + "]");
                    AppLogger.info("NET-DIAG: tracking reqId=" + reqId + " url=" + url + " mime=" + mime);
                }
            } catch (Exception ignored) { }
        };
        netFailHandler = node -> {
            try {
                JsonNode p = node.get("params");
                if (p == null) return;
                String reqId = p.path("requestId").asText("?");
                String errText = p.path("errorText").asText("?");
                String blocked = p.path("blockedReason").asText("");
                long elapsed = netDiagSendStartMs > 0 ? (System.currentTimeMillis() - netDiagSendStartMs) : -1L;
                AppLogger.warn("NET-DIAG fail: reqId=" + reqId + " errorText=" + errText + " blockedReason=" + blocked + " elapsed=" + elapsed + "ms");
            } catch (Exception ignored) { }
        };
        netDataReceivedHandler = node -> {
            try {
                JsonNode p = node.get("params");
                if (p == null) return;
                String reqId = p.path("requestId").asText("?");
                if (!netTrackedReqs.containsKey(reqId)) return;
                long dataLen = p.path("dataLength").asLong(0);
                Long prev = netDataTotals.get(reqId);
                long total = (prev == null ? 0L : prev.longValue()) + dataLen;
                netDataTotals.put(reqId, Long.valueOf(total));
                Integer prevCnt = netChunkCounts.get(reqId);
                int cnt = (prevCnt == null ? 0 : prevCnt.intValue()) + 1;
                netChunkCounts.put(reqId, Integer.valueOf(cnt));
                if (cnt <= 8 || cnt % 20 == 0) {
                    AppLogger.info("NET-DIAG dataReceived " + reqId + " chunk#" + cnt + "=" + dataLen + " total=" + total);
                }
            } catch (Exception ignored) { }
        };
        netLoadingFinishedHandler = node -> {
            try {
                JsonNode p = node.get("params");
                if (p == null) return;
                String reqId = p.path("requestId").asText("?");
                if (!netTrackedReqs.containsKey(reqId)) return;
                long encodedLen = p.path("encodedDataLength").asLong(0);
                String trackedUrl = netTrackedReqs.get(reqId);
                Long total = netDataTotals.get(reqId);
                long totVal = total == null ? 0L : total.longValue();
                AppLogger.info("NET-DIAG loadingFinished " + reqId + " url=" + trackedUrl + " encodedLen=" + encodedLen + " totalReceived=" + totVal);
        // N-103: skip getResponseBody for SSE/WS/known stream URLs.
        // Network.getResponseBody blocks until EOF; SSE streams have no EOF
        // within CdpWebSocket.SEND_TIMEOUT_MS (5s), so each call throws
        // TimeoutException AND blocks the WebSocket listener thread for 5s,
        // delaying subsequent CDP messages (incl. Runtime.evaluate).
        if (trackedUrl != null && (trackedUrl.startsWith("WS:")
                || trackedUrl.contains("event-stream")
                || trackedUrl.contains("/query")
                || trackedUrl.contains("/chat/completion")
                || trackedUrl.contains("/chat/regenerate"))) {
            AppLogger.info("NET-DIAG body " + reqId + " skipped (stream/ws)");
            return;
        }
                Map<String, Object> gb = new HashMap<>();
                gb.put("requestId", reqId);
                JsonNode resp = activeTab.send("Network.getResponseBody", gb);
                if (resp != null && resp.get("error") == null) {
                    String bodyStr = resp.path("result").path("body").asText("");
                    String truncated = bodyStr.length() > 1500 ? bodyStr.substring(0, 1500) + "...[trunc " + bodyStr.length() + "]" : bodyStr;
                    AppLogger.info("NET-DIAG body " + reqId + " (" + bodyStr.length() + " chars): " + truncated);
                } else {
                    AppLogger.warn("NET-DIAG getResponseBody " + reqId + " unavailable (error=" + (resp == null ? "null" : resp.get("error")) + ")");
                }
            } catch (Exception ex) {
                AppLogger.debug("NET-DIAG loadingFinished handler: " + ex.getMessage());
            }
        };
        netWsCreatedHandler = node -> {
            try {
                JsonNode p = node.get("params");
                if (p == null) return;
                String url = p.path("url").asText("?");
                if (!url.contains("deepseek.com")) return;
                String reqId = p.path("requestId").asText("?");
                AppLogger.info("NET-DIAG ws created: url=" + url + " reqId=" + reqId);
                netTrackedReqs.put(reqId, "WS:" + url);
            } catch (Exception ignored) { }
        };
        netWsClosedHandler = node -> {
            try {
                JsonNode p = node.get("params");
                if (p == null) return;
                String reqId = p.path("requestId").asText("?");
                if (!netTrackedReqs.containsKey(reqId)) return;
                AppLogger.warn("NET-DIAG ws closed: reqId=" + reqId);
            } catch (Exception ignored) { }
        };
        netWsFrameHandler = node -> {
            try {
                JsonNode p = node.get("params");
                if (p == null) return;
                String reqId = p.path("requestId").asText("?");
                if (!netTrackedReqs.containsKey(reqId)) return;
                Integer prev = netChunkCounts.get("ws-" + reqId);
                int cnt = (prev == null ? 0 : prev.intValue()) + 1;
                netChunkCounts.put("ws-" + reqId, Integer.valueOf(cnt));
                if (cnt > 10) return;
                String payload = p.path("response").path("payloadData").asText("");
                String truncated = payload.length() > 300 ? payload.substring(0, 300) + "..." : payload;
                AppLogger.info("NET-DIAG ws frame#" + cnt + " " + reqId + ": " + truncated);
            } catch (Exception ignored) { }
        };
        netConsoleHandler = node -> {
            try {
                JsonNode p = node.get("params");
                if (p == null) return;
                String type = p.path("type").asText("?");
                if (!"error".equals(type) && !"warning".equals(type)) return;
                JsonNode args = p.path("args");
                StringBuilder sb = new StringBuilder();
                for (JsonNode a : args) {
                    String v = a.path("value").asText("");
                    if (v.isEmpty()) v = a.path("description").asText("");
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(v.length() > 300 ? v.substring(0, 300) + "..." : v);
                }
                AppLogger.warn("NET-DIAG console." + type + ": " + sb.toString());
            } catch (Exception ignored) { }
        };
        netExceptionHandler = node -> {
            try {
                JsonNode p = node.get("params");
                if (p == null) return;
                JsonNode ex = p.path("exceptionDetails");
                String textEx = ex.path("text").asText("");
                String desc = ex.path("exception").path("description").asText("");
                String truncDesc = desc.length() > 800 ? desc.substring(0, 800) + "..." : desc;
                AppLogger.warn("NET-DIAG uncaught: " + textEx + " | " + truncDesc);
            } catch (Exception ignored) { }
        };
        activeTab.on("Network.requestWillBeSent", netReqHandler);
        activeTab.on("Network.responseReceived", netRespHandler);
        activeTab.on("Network.loadingFailed", netFailHandler);
        activeTab.on("Network.dataReceived", netDataReceivedHandler);
        activeTab.on("Network.loadingFinished", netLoadingFinishedHandler);
        activeTab.on("Network.webSocketCreated", netWsCreatedHandler);
        activeTab.on("Network.webSocketClosed", netWsClosedHandler);
        activeTab.on("Network.webSocketFrameReceived", netWsFrameHandler);
        activeTab.on("Runtime.consoleAPICalled", netConsoleHandler);
        activeTab.on("Runtime.exceptionThrown", netExceptionHandler);
        netDiagSubscribed = true;
        AppLogger.info("NET-DIAG: subscribed extended (Network + Runtime + WS)");
    }


    private void disableNetworkDiagnostics() {
        if (!netDiagSubscribed) return;
        if (activeTab != null) {
            try { activeTab.off("Network.requestWillBeSent", netReqHandler); } catch (Exception ignored) { }
            try { activeTab.off("Network.responseReceived", netRespHandler); } catch (Exception ignored) { }
            try { activeTab.off("Network.loadingFailed", netFailHandler); } catch (Exception ignored) { }
            try { activeTab.off("Network.dataReceived", netDataReceivedHandler); } catch (Exception ignored) { }
            try { activeTab.off("Network.loadingFinished", netLoadingFinishedHandler); } catch (Exception ignored) { }
            try { activeTab.off("Network.webSocketCreated", netWsCreatedHandler); } catch (Exception ignored) { }
            try { activeTab.off("Network.webSocketClosed", netWsClosedHandler); } catch (Exception ignored) { }
            try { activeTab.off("Network.webSocketFrameReceived", netWsFrameHandler); } catch (Exception ignored) { }
            try { activeTab.off("Runtime.consoleAPICalled", netConsoleHandler); } catch (Exception ignored) { }
            try { activeTab.off("Runtime.exceptionThrown", netExceptionHandler); } catch (Exception ignored) { }
        }
        netDiagSubscribed = false;
        netTrackedReqs.clear();
        netDataTotals.clear();
        netChunkCounts.clear();
        AppLogger.info("NET-DIAG: unsubscribed");
    }


    private static final String HIF_REWRITE_JS =

        "(function(){" +

        "if(window.__fmafHifRewriteInstalled)return;" +

        "window.__fmafHifRewriteInstalled=true;" +

        "var RE=/^(https?:\\/\\/)hif-[a-z0-9]+\\.deepseek\\.com(\\/|$)/i;" +

        "var FB='hif-leim.deepseek.com';" +

        "var of=window.fetch;" +

        "if(of){window.fetch=function(input,init){" +

        "var url=typeof input==='string'?input:(input&&input.url)||'';" +

        "var self=this;var p=of.call(self,input,init);" +

        "if(RE.test(url)&&url.indexOf(FB)<0){" +

        "return p.catch(function(err){" +

        "var nu=url.replace(RE,'$1'+FB+'$2');" +

        "try{console.warn('[FMAF-HIF] fetch fallback '+url+' -> '+nu);}catch(e){}" +

        "var ni=(typeof input==='string')?nu:new Request(nu,input);" +

        "return of.call(self,ni,init);});}" +

        "return p;};}" +

        "var OES=window.EventSource;" +

        "if(OES){window.EventSource=function(u,c){" +

        "if(typeof u==='string'&&RE.test(u)&&u.indexOf(FB)<0){" +

        "try{console.warn('[FMAF-HIF] EventSource fallback '+u);}catch(e){}" +

        "return new OES(u.replace(RE,'$1'+FB+'$2'),c);}" +

        "return new OES(u,c);};" +

        "window.EventSource.prototype=OES.prototype;" +

        "window.EventSource.CONNECTING=OES.CONNECTING;" +

        "window.EventSource.OPEN=OES.OPEN;" +

        "window.EventSource.CLOSED=OES.CLOSED;}" +

        "var XO=window.XMLHttpRequest&&XMLHttpRequest.prototype&&XMLHttpRequest.prototype.open;" +

        "if(XO){XMLHttpRequest.prototype.open=function(m,u){" +

        "if(typeof u==='string'&&RE.test(u)&&u.indexOf(FB)<0){" +

        "try{console.warn('[FMAF-HIF] XHR fallback '+u);}catch(e){}" +

        "return XO.apply(this,[m,u.replace(RE,'$1'+FB+'$2')].concat(Array.prototype.slice.call(arguments,2)));}" +

        "return XO.apply(this,arguments);};}" +

        "})()";



    private void installHifRewrite() {

        if (activeTab == null) return;

        if ("false".equalsIgnoreCase(System.getProperty("freemaf.hifFix", "true"))) {

            AppLogger.info("HIF-FIX: disabled by -Dfreemaf.hifFix=false");

            return;

        }

        try {

            Map<String, Object> p = new HashMap<>();

            p.put("source", HIF_REWRITE_JS);

            activeTab.send("Page.addScriptToEvaluateOnNewDocument", p);

            AppLogger.info("HIF-FIX: interceptor registered for next navigations");

        } catch (Exception e) {

            AppLogger.warn("HIF-FIX: addScriptToEvaluateOnNewDocument failed: " + e.getMessage());

        }

        try {

            evaluate(HIF_REWRITE_JS);

            AppLogger.info("HIF-FIX: interceptor installed on current page");

        } catch (Exception e) {

            AppLogger.warn("HIF-FIX: Runtime.evaluate failed: " + e.getMessage());

        }

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

                    String fix18Result = evaluate("(function(){var i=document.querySelector('input[type=file]');if(!i)return 'no-input';i.click();return 'input-clicked';})()");

                    AppLogger.info("FIX18: input.click() result = " + fix18Result);

                    if (!"input-clicked".equals(fix18Result)) {

                        String cnResult = clickNearInput();

                        AppLogger.info("FIX18: clickNearInput() result = " + cnResult);

                        if ("not-found".equals(cnResult) || "no-input".equals(cnResult)) {

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



        // FIX110: The call was successful. Do NOT check input.files (React clears it).

        // Just wait for the attachment to appear in the UI.

        AppLogger.info("CdpDeepSeek.attachFile: setFileInputFiles sent, waiting for DOM...");

        try {

            waitForAttachmentVisible(5000L, baselineComposer);

            AppLogger.info("attachFile: file=" + abs + ", bytes=" + bytes + ", elapsed=" + (System.currentTimeMillis() - start) + "ms (dom)");

            return;

        } catch (CdpAttachException e) {

            AppLogger.warn("CdpDeepSeek.attachFile: attachment did not appear after setFileInputFiles, trying DataTransfer fallback");

        }



        attachFileViaFallbacks(path, baselineComposer);

        long elapsedDt = System.currentTimeMillis() - start;

        AppLogger.info("attachFile: file=" + abs + ", bytes=" + bytes + ", elapsed=" + elapsedDt + "ms (datatransfer-fallback)");

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



    

private void attachFileViaFallbacks(Path path, int baselineComposer) throws CdpAttachException {
    // Single method: DataTransfer with change event only. No setter, no DragEvent, no PasteEvent.
    attachFileViaDataTransfer(path);
    waitForAttachmentVisible(ATTACH_VISIBLE_TIMEOUT_MS, baselineComposer);
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

            return false; // FIX27: caller decides based on DOM state

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

            // Use a synthetic drop event on the composer textarea.
            // DeepSeek ComposerAttachments listens for drop, not change.
            StringBuilder a = new StringBuilder();
            a.append("(function(){");
            a.append("var t=document.querySelector('textarea[name=search]');if(!t)return 'no-textarea';");
            a.append("try{");
            a.append("var b64='").append(base64).append("';");
            a.append("var bin=atob(b64);");
            a.append("var bytes=new Uint8Array(bin.length);");
            a.append("for(var k=0;k<bin.length;k++)bytes[k]=bin.charCodeAt(k);");
            a.append("var file=new File([bytes],'").append(safeName).append("',{type:'").append(safeMime).append("'});");
            a.append("var dt=new DataTransfer();");
            a.append("dt.items.add(file);");
            a.append("var ev=new DragEvent('drop',{dataTransfer:dt,bubbles:false,cancelable:true});");
            a.append("t.dispatchEvent(ev);");
            a.append("return 'drop-dispatched';");
            a.append("}catch(e){return 'err-'+e.message;}");
            a.append("})()");

            String result = evaluate(a.toString());
            AppLogger.info("FIX103: drop event result = " + result);
            return true;
        } catch (Exception e) {
            AppLogger.warn("FIX103: drop event failed: " + e.getMessage());
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

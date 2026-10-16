
package com.freemaf.agent.cdp;

import com.github.kklisura.cdt.protocol.commands.Page;
import com.github.kklisura.cdt.protocol.commands.Runtime;
import com.github.kklisura.cdt.protocol.types.runtime.Evaluate;
import com.github.kklisura.cdt.protocol.types.runtime.RemoteObject;
import com.github.kklisura.cdt.services.ChromeDevToolsService;
import com.github.kklisura.cdt.services.ChromeService;
import com.github.kklisura.cdt.services.impl.ChromeServiceImpl;
import com.github.kklisura.cdt.services.types.ChromeTab;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CDP probe with its own Chrome launcher.
 *
 * Key characteristics:
 *   - Chrome is launched directly by ProcessBuilder, NOT by ChromeLauncher
 *     (ChromeLauncher overrides --user-data-dir with a temp profile and
 *     breaks session persistence).
 *   - Uses --no-startup-window so Chrome does not open an initial tab;
 *     the only tab is created via HTTP PUT /json/new.
 *   - System property cdp.headless=true switches to --headless=new.
 *   - Uses textarea[name='search'] as the primary input selector.
 *   - Session persists across runs in agent/cache/cdp-profile.
 */
public final class CdpProbe {

    private static final String DEEPSEEK_URL = "https://chat.deepseek.com/";
    private static final String PROFILE_DIR = "agent/cache/cdp-profile";
    private static final int DEBUG_PORT = 9222;
    private static final String INPUT_SEL =
            "textarea[name='search'], textarea[placeholder*='Message'], textarea[placeholder*='\u0421\u043e\u043e\u0431\u0449']";
    private static final String MSG_SEL =
            "div.ds-markdown, [data-message-author-role='assistant']";
    private static final long LOGIN_WAIT_MS = 5L * 60L * 1000L;
    private static final long INPUT_WAIT_MS = 30L * 1000L;
    private static final String TEST_MESSAGE = "Reply with exactly: PONG";

    private CdpProbe() {}

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        boolean headless = Boolean.parseBoolean(System.getProperty("cdp.headless", "false"));
        System.out.println("=== CDP DeepSeek Probe (headless=" + headless + ") ===");

        Path profilePath = Path.of(PROFILE_DIR).toAbsolutePath();
        Files.createDirectories(profilePath);
        System.out.println("profile: " + profilePath);

        String chromePath = resolveChromePath();
        System.out.println("chrome: " + chromePath);

        List<String> chromeArgs = new ArrayList<>();
        chromeArgs.add(chromePath);
        chromeArgs.add("--remote-debugging-port=" + DEBUG_PORT);
        chromeArgs.add("--remote-allow-origins=*");
        chromeArgs.add("--user-data-dir=" + profilePath);
        chromeArgs.add("--no-first-run");
        chromeArgs.add("--no-default-browser-check");
        chromeArgs.add("--disable-sync");
        chromeArgs.add("--disable-backgrounding-occluded-windows");
        chromeArgs.add("--disable-renderer-backgrounding");
        chromeArgs.add("--disable-features=CalculateNativeWinOcclusion");
        chromeArgs.add("--hide-crash-restore-bubble");
        chromeArgs.add("--disable-session-crashed-bubble");
        chromeArgs.add("--no-startup-window");
        if (headless) {
            chromeArgs.add("--headless=new");
        }
        System.out.println("chrome args: " + chromeArgs);

        Process chrome = new ProcessBuilder(chromeArgs).start();
        System.out.println("chrome pid: " + chrome.pid());

        try {
            waitForDebugPort(DEBUG_PORT, 30_000L);
            System.out.println("debug port ready");

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest createRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + DEBUG_PORT + "/json/new"))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> createResponse = httpClient.send(createRequest, HttpResponse.BodyHandlers.ofString());
            System.out.println("create tab HTTP status: " + createResponse.statusCode());
            if (createResponse.statusCode() != 200) {
                throw new IllegalStateException("Failed to create tab: HTTP " + createResponse.statusCode());
            }
            TimeUnit.MILLISECONDS.sleep(1000);

            ChromeService chromeService = new ChromeServiceImpl(DEBUG_PORT);
            List<ChromeTab> tabs = chromeService.getTabs();
            System.out.println("tabs visible: " + (tabs == null ? 0 : tabs.size()));
            if (tabs != null) {
                for (ChromeTab t : tabs) {
                    System.out.println("  tab: " + t.getUrl());
                }
            }

            // Pick the tab we just created: prefer about:blank, fall back to first non-chrome-extension tab.
            ChromeTab tab = null;
            if (tabs != null) {
                for (ChromeTab t : tabs) {
                    String u = t.getUrl() == null ? "" : t.getUrl();
                    if ("about:blank".equals(u)) { tab = t; break; }
                }
                if (tab == null) {
                    for (ChromeTab t : tabs) {
                        String u = t.getUrl() == null ? "" : t.getUrl();
                        if (!u.startsWith("chrome-extension://")) { tab = t; break; }
                    }
                }
            }
            if (tab == null) {
                throw new IllegalStateException("No usable tab found");
            }
            System.out.println("attached to tab: " + tab.getUrl());

            ChromeDevToolsService devTools = chromeService.createDevToolsService(tab);
            try {
                Page page = devTools.getPage();
                Runtime runtime = devTools.getRuntime();

                System.out.println("[1] Chrome: " + devTools.getBrowser().getVersion().getProduct());

                page.navigate(DEEPSEEK_URL);
                TimeUnit.SECONDS.sleep(8);
                System.out.println("[2] title: " + evalString(runtime, "document.title"));
                System.out.println("[2] url: " + evalString(runtime, "location.href"));

                boolean loggedIn = checkLoggedIn(runtime);
                System.out.println("[2] logged in: " + loggedIn);
                if (!loggedIn) {
                    System.out.println("");
                    System.out.println("=== SESSION REQUIRED ===");
                    System.out.println("Please log in to DeepSeek in the opened Chrome window.");
                    System.out.println("Waiting up to " + (LOGIN_WAIT_MS / 60000L) + " minutes...");
                    long deadline = System.currentTimeMillis() + LOGIN_WAIT_MS;
                    int polls = 0;
                    while (System.currentTimeMillis() < deadline) {
                        TimeUnit.SECONDS.sleep(5);
                        polls++;
                        if (checkLoggedIn(runtime)) {
                            loggedIn = true;
                            System.out.println("login detected after " + (polls * 5) + " s");
                            break;
                        }
                        if (polls % 6 == 0) System.out.println("  still waiting (" + (polls * 5) + " s)");
                    }
                }
                if (!loggedIn) {
                    System.out.println("SESSION STILL EXPIRED. Probe aborted.");
                    return;
                }
                System.out.println("[2] proceeding with authenticated session");
                TimeUnit.SECONDS.sleep(3);

                boolean inputReady = waitForInput(runtime, INPUT_WAIT_MS);
                System.out.println("[3] input found on landing: " + inputReady);

                if (!inputReady) {
                    System.out.println("[3] trying to click 'New chat' button...");
                    String clickResult = evalString(runtime,
                            "(function(){" +
                            "  var target='\u041d\u043e\u0432\u044b\u0439 \u0447\u0430\u0442';" +
                            "  var all=document.querySelectorAll('*');" +
                            "  for(var i=0;i<all.length;i++){" +
                            "    var el=all[i];" +
                            "    if(el.children.length===0 && (el.textContent||'').trim()===target){" +
                            "      var p=el;" +
                            "      for(var j=0;j<6 && p;j++){" +
                            "        if(p.getAttribute && p.getAttribute('role')==='button'){p.click();return 'clicked-role-button';}" +
                            "        p=p.parentElement;" +
                            "      }" +
                            "      el.click();" +
                            "      return 'clicked-leaf';" +
                            "    }" +
                            "  }" +
                            "  return 'NOT_FOUND';" +
                            "})()");
                    System.out.println("[3] new chat click: " + clickResult);
                    TimeUnit.SECONDS.sleep(3);
                    inputReady = waitForInput(runtime, INPUT_WAIT_MS);
                    System.out.println("[3] input found after new-chat: " + inputReady);
                    System.out.println("[3] url now: " + evalString(runtime, "location.href"));
                }

                System.out.println("[3] input final: " + inputReady);
                System.out.println("[3] message containers: " + evalString(runtime,
                        "String(document.querySelectorAll(\"" + MSG_SEL + "\").length)"));

                if (!inputReady) {
                    System.out.println("=== DIAGNOSTICS ===");
                    System.out.println("textarea details: " + evalString(runtime,
                            "(function(){var a=Array.from(document.querySelectorAll('textarea')).slice(0,8).map(function(t){return {id:t.id,name:t.name,ph:t.placeholder,cls:(t.className||'').substring(0,60),vis:t.offsetParent!==null};});return JSON.stringify(a);})()"));
                    System.out.println("body.innerText head: " + evalString(runtime,
                            "(document.body ? document.body.innerText.substring(0,300) : 'NO_BODY')"));
                    System.out.println("=== END DIAGNOSTICS ===");
                    return;
                }

                evalString(runtime, "(function(){var el=document.querySelector(\"" + INPUT_SEL + "\");if(el)el.focus();return '';})()");
                TimeUnit.MILLISECONDS.sleep(300);
                evalString(runtime, "document.execCommand('insertText', false, '" + TEST_MESSAGE + "')");
                TimeUnit.MILLISECONDS.sleep(500);
                String value = evalString(runtime,
                        "(function(){var el=document.querySelector(\"" + INPUT_SEL + "\");return el?(el.value||el.innerText||''):'';})()");
                System.out.println("[5] input after insert: " + (value.length() > 80 ? value.substring(0,80)+"..." : value));

                evalString(runtime,
                        "(function(){var el=document.querySelector(\"" + INPUT_SEL + "\");if(!el)return '';el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));return '';})()");
                System.out.println("[5] message sent, waiting 20 s...");
                TimeUnit.SECONDS.sleep(20);

                String response = evalString(runtime,
                        "(function(){var m=document.querySelectorAll(\"" + MSG_SEL + "\");return m.length?m[m.length-1].innerText:'NO_RESPONSE';})()");
                System.out.println("[5] last response: " + (response.length() > 400 ? response.substring(0,400)+"..." : response));
                System.out.println("=== CDP probe complete ===");

            } finally {
                try { devTools.close(); } catch (Exception ignored) {}
            }
        } finally {
            try { chrome.destroy(); } catch (Exception ignored) {}
            try {
                if (chrome.isAlive()) {
                    new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(chrome.pid())).start().waitFor(5, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {}
        }
    }

    private static String resolveChromePath() {
        String local = System.getenv("LOCALAPPDATA");
        String[] candidates = {
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                local == null ? null : local + "\\Google\\Chrome\\Application\\chrome.exe"
        };
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) return c;
        }
        throw new IllegalStateException("chrome.exe not found");
    }

    private static void waitForDebugPort(int port, long timeoutMs) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/json/version"))
                .GET()
                .build();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return;
            } catch (Exception ignored) {}
            TimeUnit.MILLISECONDS.sleep(500);
        }
        throw new IllegalStateException("Debug port " + port + " not ready within " + timeoutMs + " ms");
    }

    private static boolean waitForInput(Runtime runtime, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            String hasInput = evalString(runtime,
                    "String(!!document.querySelector(\"" + INPUT_SEL + "\"))");
            if ("true".equals(hasInput)) return true;
            TimeUnit.SECONDS.sleep(2);
        }
        return false;
    }

    private static boolean checkLoggedIn(Runtime runtime) {
        try {
            String url = evalString(runtime, "location.href");
            if (url == null) return false;
            if (url.contains("accounts.google.com")
                    || url.contains("/signin")
                    || url.contains("/sign_in")
                    || url.contains("/login")
                    || url.contains("oauth")) {
                return false;
            }
            String hasDSElement = evalString(runtime,
                    "String(!!document.querySelector(\"" + INPUT_SEL + "\") || !!document.querySelector('div.ds-markdown'))");
            return "true".equals(hasDSElement);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String evalString(Runtime runtime, String expression) {
        try {
            Evaluate evaluate = runtime.evaluate(expression);
            if (evaluate.getExceptionDetails() != null) {
                return "ERROR: " + evaluate.getExceptionDetails().getText();
            }
            RemoteObject result = evaluate.getResult();
            if (result == null) return "null";
            Object v = result.getValue();
            return v == null ? "null" : v.toString();
        } catch (RuntimeException e) {
            return "EXCEPTION: " + e.getMessage();
        }
    }
}

package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Browser-level CDP session: manages tabs and their individual TabSessions.
 *
 * Lifecycle: create with a debug port, call newTab()/attach()/activateTab()/closeTab(),
 * then close(). The browser-level WebSocket is used for Target.* commands;
 * each attached tab gets its own page-level WebSocket via TabSession.
 */
public final class CdpSession {

    private static final long CONNECT_TIMEOUT_MS = 5000L;

    private final int port;
    private final CdpHttpClient http;
    private final CdpWebSocket browserWs;
    private final ScheduledExecutorService scheduler;

    public CdpSession(int port) throws IOException {
        this.port = port;
        this.http = new CdpHttpClient(port);
        if (!http.waitForPort(CONNECT_TIMEOUT_MS)) {
            throw new IOException("Debug port " + port + " not ready within " + CONNECT_TIMEOUT_MS + " ms");
        }
        String browserUrl = http.browserWebSocketUrl();
        if (browserUrl == null || browserUrl.isEmpty()) {
            throw new IOException("browser webSocketDebuggerUrl not available on port " + port);
        }
        this.browserWs = CdpWebSocket.connect(browserUrl, CONNECT_TIMEOUT_MS);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cdp-session-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public int port() {
        return port;
    }

    public CdpTabInfo newTab() {
        return http.newTab();
    }

    public List<CdpTabInfo> listTabs() {
        return http.list();
    }

    public TabSession attach(CdpTabInfo tab) throws IOException {
        if (tab == null || tab.id().isEmpty()) {
            throw new IOException("cannot attach: tab is null or has empty id");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("targetId", tab.id());
        params.put("flatten", Boolean.TRUE);
        JsonNode resp = browserWs.send("Target.attachToTarget", params).join();
        if (resp == null || resp.get("error") != null) {
            String err = resp == null ? "null response" : resp.get("error").toString();
            throw new IOException("Target.attachToTarget failed: " + err);
        }
        JsonNode result = resp.get("result");
        String sessionId = result == null ? "" : result.path("sessionId").asText("");
        if (sessionId.isEmpty()) {
            throw new IOException("Target.attachToTarget returned no sessionId");
        }
        String pageUrl = tab.webSocketDebuggerUrl();
        if (pageUrl == null || pageUrl.isEmpty()) {
            throw new IOException("tab has no webSocketDebuggerUrl: " + tab.id());
        }
        CdpWebSocket pageWs = CdpWebSocket.connect(pageUrl, CONNECT_TIMEOUT_MS);
        return new TabSession(pageWs, sessionId, tab);
    }

    public void activateTab(CdpTabInfo tab) {
        if (tab == null || tab.id().isEmpty()) return;
        Map<String, Object> params = new HashMap<>();
        params.put("targetId", tab.id());
        try {
            browserWs.send("Target.activateTarget", params).join();
        } catch (Exception ignored) {
            // activation is best-effort
        }
    }

    public boolean closeTab(CdpTabInfo tab) {
        if (tab == null || tab.id().isEmpty()) return false;
        return http.closeTab(tab.id());
    }

    /**
     * Sends Browser.close via CDP for graceful Chrome shutdown.
     *
     * Allows Chrome to flush cookies (Default/Network/Cookies sqlite),
     * WAL files, and session state before exiting. Without this, on
     * Windows p.destroy() maps to TerminateProcess; Network Service is
     * killed without flush and Cookies are lost on the next headless
     * restart (symptom: url=sign_in after headful login).
     *
     * Best-effort: caller should wait for graceful exit and fall back
     * to force-stop if Chrome does not respond within timeout.
     */
    public void browserClose() {
        try {
            Map<String, Object> params = new HashMap<>();
            browserWs.send("Browser.close", params).join();
        } catch (Exception ignored) {
            // best-effort; caller handles force-stop fallback
        }
    }

    /**
     * Step 2.1.5: reports whether the browser-level WebSocket is still open.
     * Delegates to CdpWebSocket.isOpen() (defensive) with an extra try/catch
     * so any failure here means "not connected".
     */
    public boolean isConnected() {
        try {
            return browserWs != null && browserWs.isOpen();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Step 2.1.5: polls every 250 ms until isConnected() returns false or the
     * timeout elapses. Returns true if disconnected within the timeout.
     */
    public boolean waitForDisconnect(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isConnected()) return true;
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return !isConnected();
            }
        }
        return !isConnected();
    }

    public void close() {
        try {
            browserWs.close();
        } catch (Exception ignored) {
            // best effort
        }
        try {
            scheduler.shutdownNow();
        } catch (Exception ignored) {
            // best effort
        }
    }
}

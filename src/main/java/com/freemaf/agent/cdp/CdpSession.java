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

package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;

/**
 * Connection to a single Chrome target (tab).
 *
 * The WebSocket is attached directly to the tab's own webSocketDebuggerUrl,
 * so CDP commands do not require a sessionId in the params map. The sessionId
 * returned by Target.attachToTarget(flatten=true) is stored for reference and
 * used by CdpSession when it needs to activate or close the target via the
 * browser-level WebSocket.
 */
public final class TabSession {

    private final CdpWebSocket ws;
    private final String sessionId;
    private final CdpTabInfo tabInfo;

    public TabSession(CdpWebSocket ws, String sessionId, CdpTabInfo tabInfo) {
        this.ws = ws;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.tabInfo = tabInfo;
    }

    public String sessionId() {
        return sessionId;
    }

    public CdpTabInfo tabInfo() {
        return tabInfo;
    }

    public CdpWebSocket webSocket() {
        return ws;
    }

    public JsonNode send(String method, Map<String, Object> params) {
        Map<String, Object> effective = params == null ? new HashMap<>() : new HashMap<>(params);
        return ws.send(method, effective).join();
    }

    public void close() {
        try {
            ws.close();
        } catch (Exception ignored) {
            // best effort
        }
    }
}

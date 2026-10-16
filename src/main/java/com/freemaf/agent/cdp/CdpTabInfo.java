package com.freemaf.agent.cdp;

/**
 * Immutable snapshot of a Chrome tab as reported by /json/list and /json/new.
 */
public record CdpTabInfo(String id, String title, String url, String webSocketDebuggerUrl) {
    public CdpTabInfo {
        id = id == null ? "" : id;
        title = title == null ? "" : title;
        url = url == null ? "" : url;
        webSocketDebuggerUrl = webSocketDebuggerUrl == null ? "" : webSocketDebuggerUrl;
    }
}

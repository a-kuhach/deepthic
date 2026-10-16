package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper over java.net.http.HttpClient for the CDP HTTP discovery endpoints:
 * /json/version, /json/list, /json/new, /json/close/{id}.
 *
 * All methods are exception-safe: on transport or parse failure they return
 * an empty/null/unknown result instead of throwing.
 */
public final class CdpHttpClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final int port;
    private final String baseUrl;

    public CdpHttpClient(int port) {
        this.port = port;
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.mapper = new ObjectMapper();
    }

    public int port() {
        return port;
    }

    /** Returns the Browser field from /json/version, or "unknown" on failure. */
    public String version() {
        try {
            HttpResponse<String> resp = sendGet("/json/version");
            if (resp.statusCode() != 200) return "unknown";
            JsonNode n = mapper.readTree(resp.body());
            JsonNode b = n == null ? null : n.get("Browser");
            return b == null || b.isNull() ? "unknown" : b.asText();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Returns the browser-level webSocketDebuggerUrl, or null on failure. */
    public String browserWebSocketUrl() {
        try {
            HttpResponse<String> resp = sendGet("/json/version");
            if (resp.statusCode() != 200) return null;
            JsonNode n = mapper.readTree(resp.body());
            JsonNode u = n == null ? null : n.get("webSocketDebuggerUrl");
            return u == null || u.isNull() ? null : u.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the current tab list; empty list on failure. */
    public List<CdpTabInfo> list() {
        try {
            HttpResponse<String> resp = sendGet("/json/list");
            if (resp.statusCode() != 200) return List.of();
            JsonNode n = mapper.readTree(resp.body());
            if (n == null || !n.isArray()) return List.of();
            List<CdpTabInfo> out = new ArrayList<>();
            for (JsonNode item : n) {
                out.add(toTabInfo(item));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Creates a new tab. Returns null on failure. */
    public CdpTabInfo newTab() {
        try {
            HttpResponse<String> resp = sendPut("/json/new");
            if (resp.statusCode() == 405) resp = sendGet("/json/new");
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
            JsonNode n = mapper.readTree(resp.body());
            if (n == null) return null;
            if (n.isArray() && n.size() > 0) n = n.get(0);
            return toTabInfo(n);
        } catch (Exception e) {
            return null;
        }
    }

    /** Closes the tab with the given id. Returns true on 2xx. */
    public boolean closeTab(String id) {
        if (id == null || id.isEmpty()) return false;
        try {
            HttpResponse<String> resp = sendPut("/json/close/" + id);
            if (resp.statusCode() == 405) resp = sendGet("/json/close/" + id);
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /** Polls /json/version every 250 ms until the port answers 200 or timeout expires. */
    public boolean waitForPort(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = sendGet("/json/version");
                if (resp.statusCode() == 200) return true;
            } catch (Exception ignored) {
                // not up yet
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private CdpTabInfo toTabInfo(JsonNode n) {
        if (n == null || !n.isObject()) return new CdpTabInfo("", "", "", "");
        return new CdpTabInfo(
                n.path("id").asText(""),
                n.path("title").asText(""),
                n.path("url").asText(""),
                n.path("webSocketDebuggerUrl").asText(""));
    }

    private HttpResponse<String> sendGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPut(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}

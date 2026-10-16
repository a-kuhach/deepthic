package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Minimal CDP WebSocket client over java.net.http.WebSocket.
 *
 * - id-based request/response correlation via the "id" field;
 * - event subscriptions via the "method" field;
 * - a single dedicated writer thread drains a BlockingQueue so that no two
 *   threads ever call sendText concurrently (the JDK WebSocket is not
 *   thread-safe for parallel sends);
 * - package-private handleIncoming hook is the single dispatch point and
 *   is exercised directly by unit tests without a real Chrome.
 */
public final class CdpWebSocket {

    public static final long SEND_TIMEOUT_MS  = 5000L;
    public static final long CLOSE_TIMEOUT_MS = 2000L;

    private final WebSocket ws;
    private final long sendTimeoutMs;
    private final ObjectMapper mapper;
    private final AtomicLong nextId = new AtomicLong(1L);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Consumer<JsonNode>>> eventHandlers = new ConcurrentHashMap<>();
    private final BlockingQueue<String> outbound = new LinkedBlockingQueue<>();
    private final Thread writerThread;
    private volatile boolean closed;

    /**
     * Package-private constructor used by unit tests. When ws == null, no
     * writer thread is started and send() only registers a pending future
     * (the caller drives handleIncoming manually).
     */
    CdpWebSocket(WebSocket ws, long sendTimeoutMs) {
        this.ws = ws;
        this.sendTimeoutMs = sendTimeoutMs;
        this.mapper = new ObjectMapper();
        if (ws == null) {
            this.writerThread = null;
        } else {
            this.writerThread = new Thread(this::writerLoop, "cdp-ws-writer");
            this.writerThread.setDaemon(true);
            this.writerThread.start();
        }
    }

    public static CdpWebSocket connect(String wsUrl, long timeoutMs) throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        final CdpWebSocket[] selfHolder = new CdpWebSocket[1];
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder buf = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buf.append(data);
                if (last) {
                    String raw = buf.toString();
                    buf.setLength(0);
                    try {
                        JsonNode node = mapper.readTree(raw);
                        CdpWebSocket self = selfHolder[0];
                        if (self != null) self.handleIncoming(node);
                    } catch (Exception ignored) {
                        // malformed frame: drop
                    }
                }
                webSocket.request(1);
                return null;
            }
        };
        WebSocket socket;
        try {
            socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .buildAsync(URI.create(wsUrl), listener)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IOException("WebSocket connect failed: " + e.getMessage(), e);
        }
        CdpWebSocket c = new CdpWebSocket(socket, SEND_TIMEOUT_MS);
        selfHolder[0] = c;
        return c;
    }

    public CompletableFuture<JsonNode> send(String method, Map<String, Object> params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        if (ws != null) {
            try {
                ObjectNode req = mapper.createObjectNode();
                req.put("id", id);
                req.put("method", method);
                if (params != null && !params.isEmpty()) {
                    req.set("params", mapper.valueToTree(params));
                }
                outbound.offer(mapper.writeValueAsString(req));
            } catch (Exception e) {
                pending.remove(id);
                future.completeExceptionally(e);
                return future;
            }
        }
        return future.orTimeout(sendTimeoutMs, TimeUnit.MILLISECONDS);
    }

    public void on(String method, Consumer<JsonNode> handler) {
        if (method == null || handler == null) return;
        eventHandlers.computeIfAbsent(method, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(handler);
    }

    public void close() {
        if (closed) return;
        closed = true;
        if (writerThread != null) {
            writerThread.interrupt();
        }
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
                        .get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    /**
     * Single dispatch point. If the frame carries an id, resolves the matching
     * pending future. Otherwise, if it carries a method, invokes all handlers
     * registered for that event name.
     */
    void handleIncoming(JsonNode message) {
        if (message == null) return;
        JsonNode idNode = message.get("id");
        if (idNode != null && !idNode.isNull()) {
            long id = idNode.asLong();
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null) f.complete(message);
            return;
        }
        JsonNode methodNode = message.get("method");
        if (methodNode != null && !methodNode.isNull()) {
            List<Consumer<JsonNode>> hs = eventHandlers.get(methodNode.asText());
            if (hs != null) {
                synchronized (hs) {
                    for (Consumer<JsonNode> h : hs) {
                        try { h.accept(message); } catch (Exception ignored) {
                            // handler failure must not break dispatch
                        }
                    }
                }
            }
        }
    }

    private void writerLoop() {
        while (!closed) {
            String msg;
            try {
                msg = outbound.poll(200L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (msg == null) continue;
            try {
                ws.sendText(msg, true).get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // frame drop; pending future will time out naturally
            }
        }
    }
}

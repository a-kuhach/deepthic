package com.freemaf.agent.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests: Chrome is not required. CdpWebSocket is constructed with
 * ws == null, so no writer thread runs; tests drive handleIncoming directly.
 */
class CdpWebSocketTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode node(String json) {
        try { return M.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void handleIncomingResolvesPendingByMatchingId() throws Exception {
        CdpWebSocket ws = new CdpWebSocket(null, 2000L);
        CompletableFuture<JsonNode> fut = ws.send("Test.method", null);
        ws.handleIncoming(node("{\"id\":1,\"result\":{\"value\":42}}"));
        JsonNode resolved = fut.get(500, TimeUnit.MILLISECONDS);
        assertNotNull(resolved);
        assertEquals(42, resolved.path("result").path("value").asInt());
        ws.close();
    }

    @Test
    void handleIncomingWithUnknownIdIsIgnored() {
        CdpWebSocket ws = new CdpWebSocket(null, 2000L);
        ws.handleIncoming(node("{\"id\":999,\"result\":{}}"));
        ws.close();
    }

    @Test
    void handleIncomingWithMethodDispatchesToHandler() {
        CdpWebSocket ws = new CdpWebSocket(null, 2000L);
        AtomicReference<JsonNode> received = new AtomicReference<>();
        ws.on("Test.event", n -> received.set(n));
        ws.handleIncoming(node("{\"method\":\"Test.event\",\"params\":{\"x\":1}}"));
        JsonNode got = received.get();
        assertNotNull(got, "handler was not invoked");
        assertEquals(1, got.path("params").path("x").asInt());
        ws.close();
    }

    @Test
    void handleIncomingWithUnknownMethodIsIgnored() {
        CdpWebSocket ws = new CdpWebSocket(null, 2000L);
        ws.handleIncoming(node("{\"method\":\"Unknown.event\",\"params\":{}}"));
        ws.close();
    }

    @Test
    void sendTimeoutCompletesFutureExceptionally() {
        CdpWebSocket ws = new CdpWebSocket(null, 50L);
        CompletableFuture<JsonNode> fut = ws.send("Test.timeout", Map.of("k", "v"));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> fut.get(2000, TimeUnit.MILLISECONDS));
        assertTrue(ex.getCause() instanceof TimeoutException,
                "expected TimeoutException cause, got: " + ex.getCause());
        ws.close();
    }

    @Test
    void closeIsIdempotent() {
        CdpWebSocket ws = new CdpWebSocket(null, 2000L);
        ws.close();
        ws.close();
    }
}


package com.freemaf.agent.cdp;



import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import org.mockito.Mockito;



import java.lang.reflect.Field;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.Map;

import java.util.concurrent.CompletableFuture;

import java.util.function.Consumer;



import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.doAnswer;

import static org.mockito.Mockito.times;

import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.when;



class CdpAttachFallbackTest {



    private static final ObjectMapper MAPPER = new ObjectMapper();



    @Test

    void attachFileUsesChooserFallbackWhenInputMissing() throws Exception {

        CdpSession session = Mockito.mock(CdpSession.class);

        LoginFallbackHandler login = Mockito.mock(LoginFallbackHandler.class);

        TabSession tab = Mockito.mock(TabSession.class);



        // DOM.getDocument -> root nodeId=1

        ObjectNode rootResp = MAPPER.createObjectNode();

        rootResp.putObject("result").putObject("root").put("nodeId", 1);

        when(tab.send(eq("DOM.getDocument"), any()))

                .thenReturn(rootResp);



        // DOM.querySelector -> nodeId=0 (forces fallback path)

        ObjectNode qResp = MAPPER.createObjectNode();

        qResp.putObject("result").put("nodeId", 0);

        when(tab.send(eq("DOM.querySelector"), any()))

                .thenReturn(qResp);



        // Success responses for set-files and file chooser intercept

        ObjectNode ok = MAPPER.createObjectNode();

        ok.putObject("result");

        when(tab.send(eq("DOM.setFileInputFiles"), any()))

                .thenReturn(ok);

        when(tab.send(eq("Page.setInterceptFileChooserDialog"), any()))

                .thenReturn(ok);



        // Runtime.evaluate: attachment polling returns "1"; click-attach returns ""

        when(tab.send(eq("Runtime.evaluate"), any())).thenAnswer(inv -> {

            @SuppressWarnings("unchecked")

            Map<String, Object> params = (Map<String, Object>) inv.getArgument(1);

            String expr = String.valueOf(params.get("expression"));

            String val = expr.contains("querySelectorAll") ? "1" : "";

            ObjectNode resp = MAPPER.createObjectNode();

            resp.putObject("result").put("value", val);

            return resp;

        });



        // Page.fileChooserOpened handler fires immediately with backendNodeId=42

        doAnswer(inv -> {

            @SuppressWarnings("unchecked")

            Consumer<JsonNode> h = (Consumer<JsonNode>) inv.getArgument(1);

            ObjectNode ev = MAPPER.createObjectNode();

            ev.put("method", "Page.fileChooserOpened");

            ev.putObject("params").put("backendNodeId", 42);

            h.accept(ev);

            return null;

        }).when(tab).on(eq("Page.fileChooserOpened"), any());



        CdpDeepSeek deepSeek = new CdpDeepSeek(session, login);

        Field f = CdpDeepSeek.class.getDeclaredField("activeTab");

        f.setAccessible(true);

        f.set(deepSeek, tab);



        Path file = Files.createTempFile("s5-attach-fallback", ".txt");

        Files.writeString(file, "x");

        try {

            deepSeek.attachFile(file);

        } finally {

            Files.deleteIfExists(file);

        }



        // Verify DOM.setFileInputFiles called with backendNodeId=42

        @SuppressWarnings("unchecked")

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);

        verify(tab).send(eq("DOM.setFileInputFiles"), cap.capture());

        assertEquals(42, cap.getValue().get("backendNodeId"));



        // Verify intercept toggled: enabled=true then enabled=false (finally)

        @SuppressWarnings("unchecked")

        ArgumentCaptor<Map<String, Object>> ic = ArgumentCaptor.forClass(Map.class);

        verify(tab, times(2)).send(eq("Page.setInterceptFileChooserDialog"), ic.capture());

        assertEquals(Boolean.TRUE, ic.getAllValues().get(0).get("enabled"));

        assertEquals(Boolean.FALSE, ic.getAllValues().get(1).get("enabled"));

    }

}

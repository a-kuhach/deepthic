
package com.freemaf.agent.cdp;



import com.freemaf.agent.ClipboardService;

import com.freemaf.agent.Config;

import com.freemaf.agent.DeepSeekSession;

import com.freemaf.agent.InputSimulator;

import com.freemaf.agent.PixelColorService;

import com.freemaf.agent.WindowManager;

import org.junit.jupiter.api.Test;

import org.mockito.Mockito;



import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.verifyNoInteractions;

import static org.mockito.Mockito.when;



class DeepSeekSessionCdpModeTest {



    @Test

    void cdpModeSendPromptCallsSendMessage() throws Exception {

        Config config = Mockito.mock(Config.class);

        InputSimulator inputSimulator = Mockito.mock(InputSimulator.class);

        ClipboardService clipboardService = Mockito.mock(ClipboardService.class);

        PixelColorService pixelColorService = Mockito.mock(PixelColorService.class);

        WindowManager windowManager = Mockito.mock(WindowManager.class);

        CdpBootstrap cdpBootstrap = Mockito.mock(CdpBootstrap.class);

        CdpDeepSeek cdpDeepSeek = Mockito.mock(CdpDeepSeek.class);



        when(cdpBootstrap.executor()).thenReturn(cdpDeepSeek);



        DeepSeekSession session = new DeepSeekSession(config, inputSimulator, clipboardService,

                pixelColorService, windowManager, cdpBootstrap);

        session.sendPrompt("hello");

        verify(cdpDeepSeek).sendMessage("hello");

        verifyNoInteractions(inputSimulator);

    }



    @Test

    void legacyModeSendPromptUsesInputSimulator() throws Exception {

        Config config = Mockito.mock(Config.class);

        InputSimulator inputSimulator = Mockito.mock(InputSimulator.class);

        ClipboardService clipboardService = Mockito.mock(ClipboardService.class);

        PixelColorService pixelColorService = Mockito.mock(PixelColorService.class);

        WindowManager windowManager = Mockito.mock(WindowManager.class);



        when(windowManager.isWindowValid()).thenReturn(true);

        when(windowManager.validateWindowRect()).thenReturn(true);



        DeepSeekSession session = new DeepSeekSession(config, inputSimulator, clipboardService,

                pixelColorService, windowManager);

        session.sendPrompt("hello");

        verify(inputSimulator).typeTextViaClipboard("hello");

    }



    @Test

    void isCdpModeReflectsBootstrap() {

        Config config = Mockito.mock(Config.class);

        InputSimulator inputSimulator = Mockito.mock(InputSimulator.class);

        ClipboardService clipboardService = Mockito.mock(ClipboardService.class);

        PixelColorService pixelColorService = Mockito.mock(PixelColorService.class);

        WindowManager windowManager = Mockito.mock(WindowManager.class);

        CdpBootstrap cdpBootstrap = Mockito.mock(CdpBootstrap.class);



        DeepSeekSession withCdp = new DeepSeekSession(config, inputSimulator, clipboardService,

                pixelColorService, windowManager, cdpBootstrap);

        DeepSeekSession legacy = new DeepSeekSession(config, inputSimulator, clipboardService,

                pixelColorService, windowManager);

        assertTrue(withCdp.isCdpMode());

        assertFalse(legacy.isCdpMode());

    }



    @Test

    void setCdpBootstrapFlipsMode() {

        Config config = Mockito.mock(Config.class);

        InputSimulator inputSimulator = Mockito.mock(InputSimulator.class);

        ClipboardService clipboardService = Mockito.mock(ClipboardService.class);

        PixelColorService pixelColorService = Mockito.mock(PixelColorService.class);

        WindowManager windowManager = Mockito.mock(WindowManager.class);

        CdpBootstrap cdpBootstrap = Mockito.mock(CdpBootstrap.class);



        DeepSeekSession session = new DeepSeekSession(config, inputSimulator, clipboardService,

                pixelColorService, windowManager);

        assertFalse(session.isCdpMode());

        session.setCdpBootstrap(cdpBootstrap);

        assertTrue(session.isCdpMode());

    }




    @Test

    void sendPromptWithFilesCallsAttachFileForEach() throws Exception {

        Config config = Mockito.mock(Config.class);

        InputSimulator inputSimulator = Mockito.mock(InputSimulator.class);

        ClipboardService clipboardService = Mockito.mock(ClipboardService.class);

        PixelColorService pixelColorService = Mockito.mock(PixelColorService.class);

        WindowManager windowManager = Mockito.mock(WindowManager.class);

        CdpBootstrap cdpBootstrap = Mockito.mock(CdpBootstrap.class);

        CdpDeepSeek cdpDeepSeek = Mockito.mock(CdpDeepSeek.class);



        when(cdpBootstrap.executor()).thenReturn(cdpDeepSeek);



        DeepSeekSession session = new DeepSeekSession(config, inputSimulator, clipboardService,

                pixelColorService, windowManager, cdpBootstrap);



        java.io.File f1 = java.io.File.createTempFile("s5a", ".txt");

        java.io.File f2 = java.io.File.createTempFile("s5b", ".txt");

        try {

            session.sendPrompt("hello", java.util.List.of(f1, f2));

            org.mockito.InOrder inOrder = Mockito.inOrder(cdpDeepSeek);

            inOrder.verify(cdpDeepSeek).attachFile(f1.toPath());

            inOrder.verify(cdpDeepSeek).attachFile(f2.toPath());

            inOrder.verify(cdpDeepSeek).sendMessage("hello");

        } finally {

            f1.delete();

            f2.delete();

        }

    }
}

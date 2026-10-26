package com.freemaf.agent;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatConsoleTest {

    @Test
    void needsEdtDispatchFromTestThread() {
        assertTrue(ChatConsole.needsEdtDispatch());
    }

    @Test
    void needsEdtDispatchFromEdt() throws Exception {
        final boolean[] seen = new boolean[1];
        SwingUtilities.invokeAndWait(() -> seen[0] = ChatConsole.needsEdtDispatch());
        assertFalse(seen[0]);
    }

    @Test
    void appendRawFromNonEdtDoesNotThrow() throws Exception {
        final ChatConsole[] holder = new ChatConsole[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new ChatConsole());
        ChatConsole cc = holder[0];
        Method m = ChatConsole.class.getDeclaredMethod("appendRaw", String.class, Color.class);
        m.setAccessible(true);
        m.invoke(cc, "hello-from-test-thread", Color.RED);
        // flush pending EDT dispatches
        SwingUtilities.invokeAndWait(() -> { });
        final int[] len = new int[1];
        SwingUtilities.invokeAndWait(() -> len[0] = cc.documentLengthForTest());
        assertTrue(len[0] > 0, "document should contain dispatched line, length=" + len[0]);
    }

    @Test
    void appendRawFromEdtDoesNotThrow() throws Exception {
        final ChatConsole[] holder = new ChatConsole[1];
        final AtomicBoolean threw = new AtomicBoolean(false);
        final int[] lenAfter = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                holder[0] = new ChatConsole();
                Method m = ChatConsole.class.getDeclaredMethod("appendRaw", String.class, Color.class);
                m.setAccessible(true);
                m.invoke(holder[0], "hello-from-edt", Color.BLUE);
                lenAfter[0] = holder[0].documentLengthForTest();
            } catch (Throwable t) {
                threw.set(true);
            }
        });
        assertFalse(threw.get(), "appendRaw on EDT must not throw");
        assertTrue(lenAfter[0] > 0, "document should grow synchronously on EDT, length=" + lenAfter[0]);
    }
}

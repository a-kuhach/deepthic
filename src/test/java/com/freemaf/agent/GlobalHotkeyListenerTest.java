package com.freemaf.agent;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalHotkeyListenerTest {
    @Test
    void vkF12ConstantIsPrivateStaticFinalInt() throws Exception {
        Field f = GlobalHotkeyListener.class.getDeclaredField("VK_F12");
        f.setAccessible(true);
        assertTrue(Modifier.isStatic(f.getModifiers()));
        assertTrue(Modifier.isFinal(f.getModifiers()));
        assertTrue(Modifier.isPrivate(f.getModifiers()));
        assertEquals(int.class, f.getType());
        assertEquals(0x7B, (int) f.get(null));
    }
}

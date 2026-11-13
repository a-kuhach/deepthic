
package com.freemaf.agent;



import org.junit.jupiter.api.Test;



import java.lang.reflect.Method;



import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertTrue;



class AutomationLoopStripUserMsgTest {



    private static String callStrip(String stdout) throws Exception {

        Method m = AutomationLoop.class.getDeclaredMethod("stripUserMsgLines", String.class);

        m.setAccessible(true);

        return (String) m.invoke(null, stdout);

    }



    @Test

    void nullInputReturnsNull() throws Exception {

        assertNull(callStrip(null));

    }



    @Test

    void userMsgOnlyBecomesEmpty() throws Exception {

        String result = callStrip("USER_MSG: hello");

        assertEquals("", result);

    }



    @Test

    void userMsgAndBodyKeepsOnlyBody() throws Exception {

        String result = callStrip("USER_MSG: drop" + System.lineSeparator() + "body line" + System.lineSeparator() + "another body");

        assertTrue(result != null);

        assertEquals(false, result.contains("USER_MSG:"));

        assertTrue(result.contains("body line"));

        assertTrue(result.contains("another body"));

    }



    @Test

    void multipleUserMsgAndGrepKeepsGrep() throws Exception {

        String result = callStrip("USER_MSG: 1) q1" + System.lineSeparator()

                + "USER_MSG: 2) q2" + System.lineSeparator()

                + "Found file: profile_setup.ahk" + System.lineSeparator()

                + "305: pass");

        assertTrue(result != null);

        assertEquals(false, result.contains("USER_MSG:"));

        assertTrue(result.contains("Found file"));

        assertTrue(result.contains("305: pass"));

    }

}

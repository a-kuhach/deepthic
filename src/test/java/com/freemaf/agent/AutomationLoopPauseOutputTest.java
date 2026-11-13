
package com.freemaf.agent;



import org.junit.jupiter.api.Test;



import java.lang.reflect.Method;



import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertTrue;



class AutomationLoopPauseOutputTest {



    private static String callFilter(String log) throws Exception {

        Method m = AutomationLoop.class.getDeclaredMethod("filterPauseOutput", String.class);

        m.setAccessible(true);

        return (String) m.invoke(null, log);

    }



    @Test

    void keepsUserMsgAndContinuationLines() throws Exception {

        // S2.5.x-fix7 (N-122): USER_MSG must reach pendingPauseOutput so the agent

        // sees its own questions in the next prompt.

        String log = "USER_MSG: 1) q1" + System.lineSeparator()

                + "2) q2" + System.lineSeparator()

                + "AGENT_PAUSE: wait";

        String result = callFilter(log);

        assertTrue(result != null, "expected non-null filtered output");

        assertTrue(result.contains("USER_MSG: 1) q1"), "USER_MSG line must be kept, got: " + result);

        assertTrue(result.contains("2) q2"), "continuation line must be kept, got: " + result);

        assertFalse(result.contains("AGENT_PAUSE:"), "AGENT_PAUSE must be filtered out, got: " + result);

    }



    @Test

    void dropsAgentStopAndDone() throws Exception {

        String log = "AGENT_STOP: exit" + System.lineSeparator() + "AGENT_DONE: finished";

        String result = callFilter(log);

        assertNull(result, "expected null when only filtered lines present, got: " + result);

    }



    @Test

    void mixedKeepsOnlyInformativeLines() throws Exception {

        String log = "USER_MSG: keep" + System.lineSeparator()

                + "AGENT_PAUSE: drop1" + System.lineSeparator()

                + "body text" + System.lineSeparator()

                + "AGENT_STOP: drop2" + System.lineSeparator()

                + "AGENT_DONE: drop3";

        String result = callFilter(log);

        assertTrue(result != null);

        assertTrue(result.contains("USER_MSG: keep"), "got: " + result);

        assertTrue(result.contains("body text"), "got: " + result);

        assertFalse(result.contains("AGENT_PAUSE:"), "got: " + result);

        assertFalse(result.contains("AGENT_STOP:"), "got: " + result);

        assertFalse(result.contains("AGENT_DONE:"), "got: " + result);

    }

}

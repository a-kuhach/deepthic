
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

    void dropsUserMsgAndKeepsBody() throws Exception {

        // v0.3.156 (N-124): USER_MSG is Chat-only; strip from pendingPauseOutput too.

        // Body lines (without marker prefix) survive and reach the agent.

        String log = "USER_MSG: 1) q1" + System.lineSeparator()

                + "body note" + System.lineSeparator()

                + "AGENT_PAUSE: wait";

        String result = callFilter(log);

        assertTrue(result != null, "expected non-null filtered output");

        assertFalse(result.contains("USER_MSG:"), "USER_MSG must be filtered out, got: " + result);

        assertTrue(result.contains("body note"), "body line must be kept, got: " + result);

        assertFalse(result.contains("AGENT_PAUSE:"), "AGENT_PAUSE must be filtered out, got: " + result);

    }



    @Test

    void dropsAgentStopAndDone() throws Exception {

        String log = "AGENT_STOP: exit" + System.lineSeparator() + "AGENT_DONE: finished";

        String result = callFilter(log);

        assertNull(result, "expected null when only filtered lines present, got: " + result);

    }



    @Test

    void mixedDropsMarkersAndUserMsg() throws Exception {

        String log = "USER_MSG: drop0" + System.lineSeparator()

                + "AGENT_PAUSE: drop1" + System.lineSeparator()

                + "body text" + System.lineSeparator()

                + "AGENT_STOP: drop2" + System.lineSeparator()

                + "AGENT_DONE: drop3";

        String result = callFilter(log);

        assertTrue(result != null);

        assertFalse(result.contains("USER_MSG:"), "got: " + result);

        assertTrue(result.contains("body text"), "got: " + result);

        assertFalse(result.contains("AGENT_PAUSE:"), "got: " + result);

        assertFalse(result.contains("AGENT_STOP:"), "got: " + result);

        assertFalse(result.contains("AGENT_DONE:"), "got: " + result);

    }

}

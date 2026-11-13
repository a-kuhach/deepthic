
package com.freemaf.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerParserTest {

    @Test

    void parsesUserMsg() {

        List<MarkerEvent> events = MarkerParser.parse("USER_MSG: hello");

        assertEquals(1, events.size());

        assertEquals(MarkerType.USER_MSG, events.get(0).type());

        assertEquals("hello", events.get(0).message());

    }

    @Test

    void parsesRole() {

        List<MarkerEvent> events = MarkerParser.parse("ROLE: EXECUTOR");

        assertEquals(1, events.size());

        assertEquals(MarkerType.ROLE, events.get(0).type());

        assertEquals("EXECUTOR", events.get(0).message());

    }

    @Test

    void parsesStop() {

        List<MarkerEvent> events = MarkerParser.parse("AGENT_STOP: bye");

        assertEquals(1, events.size());

        assertEquals(MarkerType.AGENT_STOP, events.get(0).type());

    }

    @Test

    void parsesDone() {

        List<MarkerEvent> events = MarkerParser.parse("AGENT_DONE: done");

        assertEquals(1, events.size());

        assertEquals(MarkerType.AGENT_DONE, events.get(0).type());

    }

    @Test

    void parsesPause() {

        List<MarkerEvent> events = MarkerParser.parse("AGENT_PAUSE: waiting");

        assertEquals(1, events.size());

        assertEquals(MarkerType.AGENT_PAUSE, events.get(0).type());

    }

    @Test

    void ignoresUnknownLines() {

        List<MarkerEvent> events = MarkerParser.parse("just some text");

        assertTrue(events.isEmpty());

    }


    @Test
    void parsesSessionMigrateStart() {
        List<MarkerEvent> events = MarkerParser.parse("AGENT_SESSION_MIGRATE_START: prepare");
        assertEquals(1, events.size());
        assertEquals(MarkerType.AGENT_SESSION_MIGRATE_START, events.get(0).type());
        assertEquals("prepare", events.get(0).message());
    }

    @Test
    void parsesSessionMigrateConfirm() {
        List<MarkerEvent> events = MarkerParser.parse("AGENT_SESSION_MIGRATE_CONFIRM: done");
        assertEquals(1, events.size());
        assertEquals(MarkerType.AGENT_SESSION_MIGRATE_CONFIRM, events.get(0).type());
        assertEquals("done", events.get(0).message());
    }

    @Test

    void multilineUserMsgCaptured() {

        // S2.5.x-fix7 (N-121): Write-Output ("USER_MSG: " + $multiLine) emits one string with embedded newlines;

        // MarkerParser must attach continuation lines (no marker prefix) to the preceding USER_MSG.

        String stdout = "USER_MSG: 1) q1" + System.lineSeparator()

                + "2) q2" + System.lineSeparator()

                + "3) q3" + System.lineSeparator()

                + "AGENT_PAUSE: wait";

        List<MarkerEvent> events = MarkerParser.parse(stdout);

        assertEquals(2, events.size());

        assertEquals(MarkerType.USER_MSG, events.get(0).type());

        String msg = events.get(0).message();

        assertTrue(msg.contains("1) q1"), "USER_MSG must retain first line, got: " + msg);

        assertTrue(msg.contains("2) q2"), "USER_MSG must retain continuation line 2, got: " + msg);

        assertTrue(msg.contains("3) q3"), "USER_MSG must retain continuation line 3, got: " + msg);

        assertEquals(MarkerType.AGENT_PAUSE, events.get(1).type());

    }

    @Test

    void separateUserMsgsStaySeparate() {

        String stdout = "USER_MSG: first" + System.lineSeparator() + "USER_MSG: second";

        List<MarkerEvent> events = MarkerParser.parse(stdout);

        assertEquals(2, events.size());

        assertEquals("first", events.get(0).message());

        assertEquals("second", events.get(1).message());

    }

    @Test
    void ignoresMigrateMarkersInsideWriteOutputString() {
        String stdout = "Write-Output \"AGENT_SESSION_MIGRATE_START: prepare\"" + System.lineSeparator()
                + "Write-Output \"AGENT_SESSION_MIGRATE_CONFIRM: done\"";
        List<MarkerEvent> events = MarkerParser.parse(stdout);
        assertTrue(events.isEmpty());
    }
}

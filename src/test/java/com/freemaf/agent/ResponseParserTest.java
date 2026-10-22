
package com.freemaf.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseParserTest {

    @Test

    void extractsRole() {

        assertEquals("EXECUTOR", ResponseParser.extractRole("[EXECUTOR]\n```\ncode\n```"));

        assertEquals("", ResponseParser.extractRole("no role here"));

    }

    @Test

    void detectsUserChat() {

        assertTrue(ResponseParser.isUserChat("USER_CHAT: hello"));

        assertFalse(ResponseParser.isUserChat("```\ncode\n```"));

    }

    @Test

    void extractsUserChat() {

        // Original extractUserChat strips the raw and returns the tail after USER_CHAT:

        String r = ResponseParser.extractUserChat("USER_CHAT: hello\nworld");

        assertEquals("hello\nworld", r);

    }

    @Test

    void detectsValidScript() {

        assertTrue(ResponseParser.isValidScript("```\nWrite-Output \"ROLE: EXECUTOR\"\n```"));

    }

    @Test

    void rejectsScriptWithRawMarker() {

        assertFalse(ResponseParser.isValidScript("```\nUSER_MSG: hi\n```"));

    }

    @Test

    void rejectsScriptWithSingleFence() {

        assertFalse(ResponseParser.isValidScript("```\nonly open"));

    }

    @Test

    void extractsScript() {

        String s = ResponseParser.extractScript("[EXECUTOR]\n```\nWrite-Output 1\n```");

        assertTrue(s.contains("Write-Output 1"));

    }

    @Test

    void nullInputIsSafe() {

        assertEquals("", ResponseParser.extractRole(null));

        assertFalse(ResponseParser.isUserChat(null));

        assertFalse(ResponseParser.isValidScript(null));

        assertThrows(IllegalArgumentException.class, () -> ResponseParser.extractUserChat(null));

    }

    @Test

    void userChatIgnoresRole() {

        // Original isUserChat checks only raw.strip().startsWith("USER_CHAT:") — role prefix breaks it.

        String r = "[ANALYST]" + System.lineSeparator() + "USER_CHAT: question";

        assertFalse(ResponseParser.isUserChat(r));

    }

    @Test

    void scriptWithRoleInsideDoesNotTripRawMarkerCheck() {

        assertTrue(ResponseParser.isValidScript("[EXECUTOR]\n```\nWrite-Output \"ROLE: EXECUTOR\"\n```"));

    }

    @Test

    void emptyFencedBlockIsAcceptedByOriginal() {

        // Original isValidScript("```\n```") returns true (no content check when exactly two fences present).

        assertTrue(ResponseParser.isValidScript("```\n```"));

    }


    @Test
    void migrationConfirmAcceptedWithoutFence() {
        assertTrue(ResponseParser.isValidMigrationConfirm("AGENT_SESSION_MIGRATE_CONFIRM: ok"));
    }

    @Test
    void migrationConfirmRejectedWhenFencePresent() {
        assertFalse(ResponseParser.isValidMigrationConfirm("```" + System.lineSeparator()
                + "AGENT_SESSION_MIGRATE_CONFIRM: ok" + System.lineSeparator() + "```"));
    }

    @Test
    void migrationConfirmRejectedWhenStartAlsoPresent() {
        String r = "AGENT_SESSION_MIGRATE_START: x" + System.lineSeparator()
                + "AGENT_SESSION_MIGRATE_CONFIRM: y";
        assertFalse(ResponseParser.isValidMigrationConfirm(r));
    }

    @Test
    void migrationConfirmRejectedWhenMultipleConfirms() {
        String r = "AGENT_SESSION_MIGRATE_CONFIRM: a" + System.lineSeparator()
                + "AGENT_SESSION_MIGRATE_CONFIRM: b";
        assertFalse(ResponseParser.isValidMigrationConfirm(r));
    }

    @Test
    void migrationConfirmNullIsSafe() {
        assertFalse(ResponseParser.isValidMigrationConfirm(null));
        assertFalse(ResponseParser.isValidMigrationConfirm(""));
    }

    @Test
    void isValidScriptRejectsRawMigrationStart() {
        assertFalse(ResponseParser.isValidScript("AGENT_SESSION_MIGRATE_START: prep"));
    }

    @Test
    void isValidScriptRejectsRawMigrationConfirm() {
        assertFalse(ResponseParser.isValidScript("AGENT_SESSION_MIGRATE_CONFIRM: done"));
    }

    @Test
    void rawMarkerInsideSingleHereStringIsExempt() {
        String r = "```" + System.lineSeparator()
                + "@'" + System.lineSeparator()
                + "USER_MSG: inside" + System.lineSeparator()
                + "'@" + System.lineSeparator()
                + "```";
        assertTrue(ResponseParser.isValidScript(r));
    }

    @Test
    void rawMarkerInsideDoubleHereStringIsExempt() {
        String r = "```" + System.lineSeparator()
                + "@\"" + System.lineSeparator()
                + "AGENT_STOP: inside" + System.lineSeparator()
                + "\"@" + System.lineSeparator()
                + "```";
        assertTrue(ResponseParser.isValidScript(r));
    }

    @Test
    void rawMarkerInsideSingleLineCommentIsExempt() {
        String r = "```" + System.lineSeparator()
                + "# USER_MSG: note" + System.lineSeparator()
                + "Write-Output 1" + System.lineSeparator()
                + "```";
        assertTrue(ResponseParser.isValidScript(r));
    }

    @Test
    void rawMarkerOnSameLineAsBlockCommentIsNotDetected() {
        String r = "```" + System.lineSeparator()
                + "<# USER_MSG: hidden #>" + System.lineSeparator()
                + "Write-Output 1" + System.lineSeparator()
                + "```";
        assertTrue(ResponseParser.isValidScript(r));
    }

    @Test
    void rawMarkerOnOwnLineInsideBlockCommentIsDetected() {
        String r = "```" + System.lineSeparator()
                + "<#" + System.lineSeparator()
                + "USER_MSG: visible" + System.lineSeparator()
                + "#>" + System.lineSeparator()
                + "```";
        assertFalse(ResponseParser.isValidScript(r));
    }

    @Test
    void bareUserMsgMarkerIsDetected() {
        assertFalse(ResponseParser.isValidScript("USER_MSG: ready"));
    }

    @Test
    void writeOutputUserMsgMarkerIsNotRawMarker() {
        String r = "```" + System.lineSeparator()
                + "Write-Output \"USER_MSG: ready\"" + System.lineSeparator()
                + "```";
        assertTrue(ResponseParser.isValidScript(r));
    }

    @Test
    void unclosedHereStringIsExemptToEof() {
        String r = "```" + System.lineSeparator()
                + "@'" + System.lineSeparator()
                + "USER_MSG: never closed" + System.lineSeparator()
                + "```";
        assertTrue(ResponseParser.isValidScript(r));
    }

    @Test
    void hereStringNotClosedWithLeadingWhitespace() {
        String r = "```" + System.lineSeparator()
                + "@'" + System.lineSeparator()
                + "USER_MSG: inside" + System.lineSeparator()
                + "  '@" + System.lineSeparator()
                + "```";
        assertTrue(ResponseParser.isValidScript(r));
    }

    @Test
    void midlineAtQuoteDoesNotOpenHereString() {
        String r = "```" + System.lineSeparator()
                + "Write-Output \"use @' symbol\"" + System.lineSeparator()
                + "USER_MSG: ready" + System.lineSeparator()
                + "```";
        assertFalse(ResponseParser.isValidScript(r));
    }

    @Test
    void bareAgentStopMarkerIsDetected() {
        assertFalse(ResponseParser.isValidScript("AGENT_STOP: done"));
    }
}



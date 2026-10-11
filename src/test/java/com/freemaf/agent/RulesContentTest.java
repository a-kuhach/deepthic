package com.freemaf.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RulesContentTest {

    @Test
    void executorRulesMentionMemoryAndProtection() throws IOException {
        String content = Files.readString(Path.of("rules/EXECUTOR.md"), StandardCharsets.UTF_8);
        assertTrue(content.contains("MEMORY_MD_AND_EXECUTOR_DIRECTIVES"));
        assertTrue(content.contains("agent/project/MEMORY.md"));
        assertTrue(content.contains("EXECUTOR_DIRECTIVES"));
        assertTrue(content.contains("agent/rules/*.md"));
        assertTrue(content.contains("agent/AGENT_INSTRUCTIONS.md"));
    }

    @Test
    void judgeRulesHaveDirectiveFileProtection() throws IOException {
        String content = Files.readString(Path.of("rules/JUDGE.md"), StandardCharsets.UTF_8);
        assertTrue(content.contains("DIRECTIVE_FILE_PROTECTION"));
        assertTrue(content.contains("agent/rules/*.md"));
        assertTrue(content.contains("agent/AGENT_INSTRUCTIONS.md"));
    }

    @Test
    void codeReviewerRulesHaveDirectiveFileProtection() throws IOException {
        String content = Files.readString(Path.of("rules/CODE_REVIEWER.md"), StandardCharsets.UTF_8);
        assertTrue(content.contains("DIRECTIVE_FILE_PROTECTION"));
        assertTrue(content.contains("agent/rules/*.md"));
        assertTrue(content.contains("agent/AGENT_INSTRUCTIONS.md"));
    }

    @Test
    void noSeedMemoryMdInSourceRepo() {
        assertFalse(Files.exists(Path.of("MEMORY.md")));
        assertFalse(Files.exists(Path.of("agent/project/MEMORY.md")));
    }
@Test
    void executorRulesHaveBlockingQuestionPause() throws IOException {
        String content = Files.readString(Path.of("rules/EXECUTOR.md"), StandardCharsets.UTF_8);
        assertTrue(content.contains("BLOCKING_QUESTION_PAUSE"));
        assertTrue(content.contains("USER_MSG"));
        assertTrue(content.contains("AGENT_PAUSE"));
    }

    @Test
    void codeReviewerRulesHaveBlockingQuestionPauseCheck() throws IOException {
        String content = Files.readString(Path.of("rules/CODE_REVIEWER.md"), StandardCharsets.UTF_8);
        assertTrue(content.contains("BLOCKING_QUESTION_PAUSE_CHECK"));
        assertTrue(content.contains("USER_MSG"));
        assertTrue(content.contains("AGENT_PAUSE"));
    }
@Test
    void executorRulesHaveNoBroadProcessKill() throws IOException {
        String content = Files.readString(Path.of("rules/EXECUTOR.md"), StandardCharsets.UTF_8);
        assertTrue(content.contains("NO_BROAD_PROCESS_KILL"));
        assertTrue(content.contains("Stop-Process -Name"));
        assertTrue(content.contains("taskkill /IM"));
    }

    @Test
    void codeReviewerRulesHaveNoBroadProcessKillCheck() throws IOException {
        String content = Files.readString(Path.of("rules/CODE_REVIEWER.md"), StandardCharsets.UTF_8);
        assertTrue(content.contains("NO_BROAD_PROCESS_KILL_CHECK"));
        assertTrue(content.contains("Stop-Process -Name"));
    }
}
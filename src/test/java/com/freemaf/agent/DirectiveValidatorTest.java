package com.freemaf.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DirectiveValidatorTest {

    @Test
    void allowedWriteToOrdinarySourceFile() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Content -Path src/Main.java -Value 'x'");
        assertTrue(r.allowed());
    }

    @Test
    void allowedWriteToMemoryMd() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Content -Path agent/project/MEMORY.md -Value 'x'");
        assertTrue(r.allowed());
    }

    @Test
    void allowedAddContentWithDotSlashPrefix() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Add-Content ./agent/project/MEMORY.md 'x'");
        assertTrue(r.allowed());
    }

    @Test
    void allowedReadOfProtectedFile() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Get-Content -Raw agent/rules/EXECUTOR.md");
        assertTrue(r.allowed());
    }

    @Test
    void allowedMentionOfProtectedPathInComment() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("# see agent/rules/EXECUTOR.md for details");
        assertTrue(r.allowed());
    }

    @Test
    void blockedSetContentExecutorRuntime() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Content -Path agent/rules/EXECUTOR.md -Value 'x'");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/executor.md"));
    }

    @Test
    void blockedAddContentJudge() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Add-Content agent/rules/JUDGE.md 'x'");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/judge.md"));
    }

    @Test
    void blockedOutFileInstructions() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Out-File agent/AGENT_INSTRUCTIONS.md");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("agent_instructions.md"));
    }

    @Test
    void blockedBackslashPath() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Content .\\agent\\rules\\CODE_REVIEWER.md 'x'");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/code_reviewer.md"));
    }

    @Test
    void blockedLowercasePath() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Content agent/rules/code_reviewer.md 'x'");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/code_reviewer.md"));
    }

    @Test
    void blockedRedirection() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("'x' > agent/rules/EXECUTOR.md");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/executor.md"));
    }

    @Test
    void blockedSourceLayoutWithoutPrefix() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Content rules/EXECUTOR.md -Value 'x'");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/executor.md"));
    }

    @Test
    void blockedSourceLayoutInstructions() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Content AGENT_INSTRUCTIONS.md -Value 'x'");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("agent_instructions.md"));
    }
@Test
    void blockedStopProcessByName() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate(
                "Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue");
        assertFalse(r.allowed());
        assertTrue(r.reason().toLowerCase(java.util.Locale.ROOT).contains("broad"));
    }

    @Test
    void blockedStopProcessNameDirect() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Stop-Process -Name javaw -Force");
        assertFalse(r.allowed());
    }

    @Test
    void blockedGetProcessPipeStopProcess() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Get-Process java | Stop-Process");
        assertFalse(r.allowed());
    }

    @Test
    void blockedTaskkillByImage() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("taskkill /F /IM java.exe");
        assertFalse(r.allowed());
    }

    @Test
    void blockedPkill() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("pkill -f java");
        assertFalse(r.allowed());
    }

    @Test
    void allowedStopProcessByIdPid() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Stop-Process -Id 1234 -Force");
        assertTrue(r.allowed());
    }

    @Test
    void allowedTaskkillByPid() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("taskkill /F /T /PID 1234");
        assertTrue(r.allowed());
    }

    @Test
    void allowedGetProcessListing() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Get-Process -Name java");
        assertTrue(r.allowed());
    }

    @Test
    void blockedWriteToMinimalFormatSourceLayout() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Content rules/minimal_format.md 'x'");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/minimal_format.md"));
    }

    @Test
    void blockedWriteToMinimalFormatRuntimeLayout() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Content agent/rules/MINIMAL_FORMAT.md 'x'");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/minimal_format.md"));
    }

    @Test
    void allowedWriteToPlanMd() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Content -Path agent/project/PLAN.md -Value 'x'");
        assertTrue(r.allowed());
    }

    @Test
    void allowedWriteToWipMd() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Add-Content agent/project/WIP.md 'x'");
        assertTrue(r.allowed());
    }

    @Test
    void allowedWriteToProjectStateMd() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Content agent/project/PROJECT_STATE.md 'x'");
        assertTrue(r.allowed());
    }

    @Test
    void blockedClearContentToExecutor() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Clear-Content rules/executor.md");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/executor.md"));
    }

    @Test
    void blockedSetItemToExecutor() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Set-Item rules/executor.md 'x'");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/executor.md"));
    }

    @Test
    void blockedTeeObjectToExecutor() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("'x' | Tee-Object -FilePath rules/executor.md");
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/executor.md"));
    }

    @Test
    void blockedIoFileReplaceToExecutor() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("[io.file]::Replace('rules/executor.md','a','b')");
        assertFalse(r.allowed());
    }

    @Test
    void blockedMultiLineWriteToExecutor() {
        String script = "Set-Content `\nrules/executor.md `\n\"x\"";
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate(script);
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("rules/executor.md"));
    }

    @Test
    void blockedMultiLineKill() {
        String script = "Get-Process java;`\nStop-Process";
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate(script);
        assertFalse(r.allowed());
    }

    @Test
    void blockedVariablePathAlias() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("$p = \"rules/executor.md\"; Set-Content $p \"x\"");
        assertFalse(r.allowed());
    }

    @Test
    void blockedVariablePathShortName() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("$x = \"rules/executor.md\"; Set-Content $x \"x\"");
        assertFalse(r.allowed());
    }

    @Test
    void allowedBareNameIsStringLiteral() {
        String script = "$x = \"rules/executor.md\"" + System.lineSeparator()
                + "Set-Content x \"x\"";
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate(script);
        assertTrue(r.allowed());
    }

    @Test
    void allowedVariablePathNonProtected() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("$p = \"notes.txt\"; Set-Content $p \"x\"");
        assertTrue(r.allowed());
    }

    @Test
    void blockedVariablePathNamedArg() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("$p = \"agent/rules/judge.md\"; Set-Content -Path $p -Value \"x\"");
        assertFalse(r.allowed());
    }

    @Test
    void blockedJoinPathWithProtectedSegment() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Join-Path $env:USERPROFILE \"rules\"; Set-Content \"$x\" \"y\"");
        assertFalse(r.allowed());
        assertTrue(r.reason().toLowerCase(java.util.Locale.ROOT).contains("join-path"));
    }

    @Test
    void blockedPositionalGetProcessJava() {
        DirectiveValidator.ValidationResult r = DirectiveValidator.validate("Get-Process java");
        assertFalse(r.allowed());
    }
}


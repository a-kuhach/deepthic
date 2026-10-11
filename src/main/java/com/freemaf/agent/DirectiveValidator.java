package com.freemaf.agent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class DirectiveValidator {

    private static final List<String> PROTECTED_PATHS = List.of(
            "rules/code_reviewer.md",
            "rules/executor.md",
            "rules/judge.md",
            "agent/rules/code_reviewer.md",
            "agent/rules/executor.md",
            "agent/rules/judge.md",
            "agent_instructions.md",
            "agent/agent_instructions.md");

    private static final List<String> WRITE_COMMANDS = List.of(
            "set-content",
            "add-content",
            "out-file",
            "new-item",
            "remove-item",
            "move-item",
            "copy-item",
            "rename-item",
            "[system.io.file]::write",
            "[system.io.file]::append",
            "[system.io.file]::create",
            "[system.io.file]::delete",
            "[system.io.file]::copy",
            "[system.io.file]::move");

    private static final Pattern REDIRECT = Pattern.compile("(?<![<>=!-])>{1,2}(?!=)");

    private DirectiveValidator() {
    }

    public static ValidationResult validate(String scriptContent) {
        if (scriptContent == null || scriptContent.isEmpty()) {
            return new ValidationResult(true, "");
        }
        String[] lines = scriptContent.split("\\r?\\n", -1);
        for (String line : lines) {
            String normalized = normalize(line);
            if (hasForbiddenKillIntent(normalized)) {
                return new ValidationResult(false,
                        "Script blocked: broad process termination by name is forbidden. "
                                + "Framework A runs as a java process; a name-based kill terminates the framework. "
                                + "Use Stop-Process -Id <pid> or taskkill /F /T /PID <pid> with a PID produced by the same script (Start-Process -PassThru).");
            }
            if (!hasWriteIntent(normalized)) {
                continue;
            }
            for (String protectedPath : PROTECTED_PATHS) {
                if (normalized.contains(protectedPath)) {
                    String reason = "Script blocked: write to protected directive file '"
                            + protectedPath
                            + "' is forbidden. Among directive files only agent/project/MEMORY.md may be modified.";
                    return new ValidationResult(false, reason);
                }
            }
        }
        return new ValidationResult(true, "");
    }

    private static boolean hasWriteIntent(String normalizedLine) {
        for (String command : WRITE_COMMANDS) {
            if (normalizedLine.contains(command)) {
                return true;
            }
        }
        return REDIRECT.matcher(normalizedLine).find();
    }


    private static boolean hasForbiddenKillIntent(String normalizedLine) {
        boolean hasStopProcess = normalizedLine.contains("stop-process");
        boolean hasGetProcess = normalizedLine.contains("get-process");
        boolean hasNameArg = normalizedLine.contains("-name");
        boolean hasPipe = normalizedLine.contains("|");
        boolean hasTaskkill = normalizedLine.contains("taskkill");
        boolean hasImageFlag = normalizedLine.contains("/im");
        boolean hasPkill = normalizedLine.contains("pkill");
        if (hasStopProcess && hasNameArg) {
            return true;
        }
        if (hasGetProcess && hasPipe && hasStopProcess) {
            return true;
        }
        if (hasTaskkill && hasImageFlag) {
            return true;
        }
        if (hasPkill) {
            return true;
        }
        return false;
    }
    static String normalize(String value) {
        String replaced = value.replace('\\', '/');
        if (replaced.startsWith("./")) {
            replaced = replaced.substring(2);
        }
        return replaced.toLowerCase(Locale.ROOT);
    }

    public record ValidationResult(boolean allowed, String reason) {
    }
}

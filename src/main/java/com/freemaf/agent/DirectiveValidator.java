package com.freemaf.agent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class DirectiveValidator {


    // AUD-19 classification: agent/project/PLAN.md is intentionally NOT in PROTECTED_PATHS.
    // PLANNER is a sub-role of EXECUTOR and writes the plan through the same PowerShellExecutor;
    // technically blocking this path breaks the mandatory PLANNER flow. The approval contract
    // is enforced at the rules layer (rules/EXECUTOR.md, rules/JUDGE.md, rules/CODE_REVIEWER.md)
    // in Stage S3 (MASTER_PLAN Set 3.5). See WIP.md AUD-19.
    private static final List<String> PROTECTED_PATHS = List.of(
            "rules/code_reviewer.md",
            "rules/executor.md",
            "rules/judge.md",
            "agent/rules/code_reviewer.md",
            "agent/rules/executor.md",
            "agent/rules/judge.md",
            "agent_instructions.md",
            "agent/agent_instructions.md",
            "rules/minimal_format.md",
            "agent/rules/minimal_format.md");

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
            "[system.io.file]::move",
            "clear-content",
            "set-item",
            "tee-object",
            "[io.file]::replace",
            "[system.io.file]::replace");

    private static final Pattern REDIRECT = Pattern.compile("(?<![<>=!-])>{1,2}(?!=)");

    private DirectiveValidator() {
    }

    /**
     * Validates the script content against protected directive paths and forbidden kill intents.
     *
     * <p>Before any scanning, PowerShell line-continuation sequences (a backtick immediately
     * followed by CRLF or LF) are normalized to a single space. This normalization is applied
     * to the ENTIRE input, including the content of quoted strings. For the purposes of this
     * validator this is equivalent to PowerShell line-continuation and is safe: it cannot turn
     * an allowed script into a blocked one, and it closes the multi-line bypass (AUD-02).</p>
     */
    public static ValidationResult validate(String scriptContent) {
        if (scriptContent == null || scriptContent.isEmpty()) {
            return new ValidationResult(true, "");
        }
        String normalizedInput = scriptContent;
        int joinCount = 0;
        int idx;
        while ((idx = normalizedInput.indexOf("`\r\n")) >= 0) {
            normalizedInput = normalizedInput.substring(0, idx) + " " + normalizedInput.substring(idx + 3);
            joinCount++;
        }
        while ((idx = normalizedInput.indexOf("`\n")) >= 0) {
            normalizedInput = normalizedInput.substring(0, idx) + " " + normalizedInput.substring(idx + 2);
            joinCount++;
        }
        if (joinCount > 0) {
            AppLogger.debug("DirectiveValidator: normalized " + joinCount + " line-continuation sequence(s)");
        }
        String[] lines = normalizedInput.split("\\r?\\n", -1);
        java.util.Set<String> protectedAliases = collectProtectedAliases(normalizedInput);
        if (hasJoinPathProtectedSegment(normalizedInput)) {
            return new ValidationResult(false, "Script blocked: Join-Path with protected segment (rules|agent) is forbidden.");
        }
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
            if (lineTargetsProtectedAlias(normalized, protectedAliases)) {
                return new ValidationResult(false, "Script blocked: write to a variable that resolves to a protected directive path is forbidden.");
            }
        }
        return new ValidationResult(true, "");
    }

    private static java.util.Set<String> collectProtectedAliases(String input) {
        java.util.Set<String> aliases = new java.util.HashSet<>();
        Pattern p = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
        java.util.regex.Matcher m = p.matcher(input);
        while (m.find()) {
            String literal = m.group(2) != null ? m.group(2) : m.group(3);
            if (literal == null) continue;
            String lower = literal.toLowerCase(Locale.ROOT);
            if (lower.contains("rules/") || lower.contains("agent/rules/")
                    || lower.contains("agent_instructions.md") || lower.contains("agent/project/plan.md")) {
                aliases.add(m.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return aliases;
    }

    private static boolean hasJoinPathProtectedSegment(String input) {
        if (!Pattern.compile("Join-Path", Pattern.CASE_INSENSITIVE).matcher(input).find()) {
            return false;
        }
        Pattern literal = Pattern.compile("\"([^\"]*)\"|'([^']*)'");
        java.util.regex.Matcher m = literal.matcher(input);
        while (m.find()) {
            String lit = m.group(1) != null ? m.group(1) : m.group(2);
            if (lit == null) continue;
            String t = lit.strip().toLowerCase(Locale.ROOT);
            if (t.equals("rules") || t.equals("agent")) return true;
        }
        return false;
    }

    private static boolean lineTargetsProtectedAlias(String normalizedLine, java.util.Set<String> aliases) {
        if (aliases.isEmpty()) return false;
        for (String alias : aliases) {
            Pattern simple = Pattern.compile("\\$" + Pattern.quote(alias) + "(?![a-zA-Z0-9_])(?!\\s*=)");
            if (simple.matcher(normalizedLine).find()) return true;
            Pattern braced = Pattern.compile("\\$\\{" + Pattern.quote(alias) + "\\}(?!\\s*=)");
            if (braced.matcher(normalizedLine).find()) return true;
        }
        return false;
    }

    private static boolean hasWriteIntent(String normalizedLine) {
        for (String command : WRITE_COMMANDS) {
            if (normalizedLine.contains(command)) {
                return true;
            }
        }
        return REDIRECT.matcher(normalizedLine).find();
    }



    private static final Pattern STOP_PROCESS_BY_NAME =
            Pattern.compile("\\bstop-process\\b[^|;]*?-name\\b");
    private static final Pattern POSITIONAL_GET_PROCESS_JAVA =
            Pattern.compile("\\bget-process\\s+(java|javaw)(\\.exe)?\\b");

    private static boolean hasForbiddenKillIntent(String normalizedLine) {
        boolean hasStopProcess = normalizedLine.contains("stop-process");
        boolean hasGetProcess = normalizedLine.contains("get-process");
        boolean hasTaskkill = normalizedLine.contains("taskkill");
        boolean hasImageFlag = normalizedLine.contains("/im");
        boolean hasPkill = normalizedLine.contains("pkill");
        boolean hasJava = normalizedLine.contains("java");
        if (hasStopProcess && STOP_PROCESS_BY_NAME.matcher(normalizedLine).find()) {
            return true;
        }
        if (hasGetProcess && hasStopProcess) {
            return true;
        }
        if (POSITIONAL_GET_PROCESS_JAVA.matcher(normalizedLine).find()) {
            return true;
        }
        if (hasTaskkill && hasImageFlag && hasJava) {
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








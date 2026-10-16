package com.freemaf.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SystemInstructionProvider {

    private static final Path RULES_DIR = Path.of("agent", "rules");

    private static final List<String> REQUIRED_FILES = List.of(
            "MINIMAL_FORMAT.md",
            "EXECUTOR.md",
            "JUDGE.md",
            "CODE_REVIEWER.md");

    private SystemInstructionProvider() {}

    public static String getMinimalFormatRules() {
        return RuleProvider.readRuleFile(RULES_DIR.resolve("MINIMAL_FORMAT.md"));
    }

    public static String getExecutorRules() {
        return RuleProvider.readRuleFile(RULES_DIR.resolve("EXECUTOR.md"));
    }

    public static String getReviewerRules(String role) {
        String file = switch (role) {
            case "JUDGE" -> "JUDGE.md";
            case "CODE_REVIEWER" -> "CODE_REVIEWER.md";
            default -> throw new IllegalArgumentException("Unknown reviewer role: " + role);
        };
        return RuleProvider.readRuleFile(RULES_DIR.resolve(file));
    }

    /**
     * Returns paths of required rule files that are missing or empty.
     * Empty list means the framework is safe to start.
     */
    public static List<String> missingRequiredFiles() {
        List<String> missing = new ArrayList<>();
        for (String name : REQUIRED_FILES) {
            Path p = RULES_DIR.resolve(name);
            if (!Files.isRegularFile(p)) {
                missing.add(p.toString());
                continue;
            }
            try {
                if (Files.size(p) == 0L) missing.add(p.toString());
            } catch (IOException e) {
                missing.add(p.toString());
            }
        }
        return missing;
    }

    public static String getFormatReminder() {
        return PromptBuilder.FORMAT_REMINDER;
    }
}

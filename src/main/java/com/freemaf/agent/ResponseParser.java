
package com.freemaf.agent;

public final class ResponseParser {

    private static final String FENCE = "\u0060\u0060\u0060";

    private static final String ROLE_TAG_PATTERN = "^\\[([A-Z_]+)\\]\\s*(\\r?\\n)?";

    private ResponseParser() {}

    public static String extractRole(String raw) {

        if (raw == null) return "";

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(ROLE_TAG_PATTERN)

                .matcher(raw.strip());

        if (m.find()) return m.group(1);

        return "";

    }

    public static String stripRoleTag(String raw) {

        if (raw == null) return null;

        return raw.replaceFirst(ROLE_TAG_PATTERN, "");

    }

    public static boolean isUserChat(String raw) {

        if (raw == null) return false;

        return raw.strip().startsWith("USER_CHAT:");

    }

    public static String extractUserChat(String raw) {

        if (!isUserChat(raw)) throw new IllegalArgumentException("Ответ не является USER_CHAT");

        return raw.strip().substring("USER_CHAT:".length()).strip();

    }

    public static boolean isValidScript(String raw) {

        if (raw == null || raw.isBlank()) return false;

        String stripped = stripRoleTag(raw);

        if (stripped == null) return false;

        String trimmed = stripped.strip();

        if (trimmed.isEmpty()) return false;

        if (trimmed.startsWith("### ")) return false;

        if (containsRawMarkers(trimmed)) return false;

        long fenceCount = trimmed.lines()

                .filter(line -> line.strip().startsWith(FENCE)).count();

        if (fenceCount == 2L) return trimmed.startsWith(FENCE) && trimmed.endsWith(FENCE);

        if (fenceCount != 0L) return false;

        return looksLikePowerShell(trimmed);

    }

    public static String extractScript(String raw) {

        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Пустой ответ агента");

        String stripped = stripRoleTag(raw);

        if (stripped == null) stripped = raw;

        String trimmed = stripped.strip();

        if (!trimmed.startsWith(FENCE)) return trimmed;

        int firstNewline = trimmed.indexOf(10);

        if (firstNewline < 0) return "";

        int lastFence = trimmed.lastIndexOf(FENCE);

        if (lastFence <= firstNewline) return "";

        return trimmed.substring(firstNewline + 1, lastFence).strip();

    }

    public static boolean isValidMigrationConfirm(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String stripped = stripRoleTag(raw);
        if (stripped == null) return false;
        String trimmed = stripped.strip();
        if (trimmed.isEmpty()) return false;
        long fenceCount = trimmed.lines()
                .filter(line -> line.strip().startsWith(FENCE)).count();
        if (fenceCount != 0L) return false;
        int startCount = 0;
        int confirmCount = 0;
        for (String line : trimmed.split("\\R", -1)) {
            String t = line.strip();
            if (t.startsWith("AGENT_SESSION_MIGRATE_START:") || t.equals("AGENT_SESSION_MIGRATE_START")) startCount++;
            if (t.startsWith("AGENT_SESSION_MIGRATE_CONFIRM:") || t.equals("AGENT_SESSION_MIGRATE_CONFIRM")) confirmCount++;
        }
        return confirmCount == 1 && startCount == 0;
    }

    /**
     * True if the response contains an AGENT_SESSION_MIGRATE_CONFIRM marker,
     * either as a bare line or wrapped in a Write-Output "..." / '...' statement.
     */
    public static boolean containsMigrationConfirm(String raw) {
        if (raw == null || raw.isBlank()) return false;
        for (String line : raw.split("\\R", -1)) {
            String t = line.strip();
            if (t.equals("AGENT_SESSION_MIGRATE_CONFIRM") || t.startsWith("AGENT_SESSION_MIGRATE_CONFIRM:")) return true;
            String s2 = t;
            if (s2.toLowerCase().startsWith("write-output")) {
                s2 = s2.substring("write-output".length()).strip();
            } else {
                continue;
            }
            while (s2.startsWith("\"") || s2.startsWith("'")) s2 = s2.substring(1);
            while (s2.endsWith("\"") || s2.endsWith("'")) s2 = s2.substring(0, s2.length() - 1);
            s2 = s2.strip();
            if (s2.equals("AGENT_SESSION_MIGRATE_CONFIRM") || s2.startsWith("AGENT_SESSION_MIGRATE_CONFIRM:")) return true;
        }
        return false;
    }
    private static boolean containsRawMarkers(String text) {

        for (String line : text.split("\\R", -1)) {

            String t = line.strip();

            if (t.startsWith("USER_MSG:") || t.startsWith("AGENT_PAUSE:")

                    || t.startsWith("AGENT_STOP:") || t.startsWith("AGENT_DONE:")
                    || t.startsWith("AGENT_SESSION_MIGRATE_START:")
                    || t.startsWith("AGENT_SESSION_MIGRATE_CONFIRM:")) {

                return true;

            }

        }

        return false;

    }

    private static boolean looksLikePowerShell(String text) {

        if (text.length() < 3) return false;

        if (text.indexOf(36) >= 0) return true;

        String[] tokens = {

                "Write-", "Read-", "Get-", "Set-", "New-", "Remove-", "Add-", "Test-",

                "Start-", "Stop-", "Invoke-", "Import-", "Export-",

                "param(", "param (", "function ", "[CmdletBinding",

                "foreach ", "foreach(", "if (", "if(", "for (", "for(", "while ", "while(",

                "try {", "catch", "finally ", "return ", "throw ", "echo ",

                "function:", "switch ",

        };

        for (String token : tokens) if (text.contains(token)) return true;

        return text.length() > 40 && text.contains(System.lineSeparator());

    }

}

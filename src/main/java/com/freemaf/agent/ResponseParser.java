
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

    /**
     * Detects top-level raw marker lines (USER_MSG:/AGENT_PAUSE:/AGENT_STOP:/AGENT_DONE:/
     * AGENT_SESSION_MIGRATE_START/AGENT_SESSION_MIGRATE_CONFIRM) that are not escaped by
     * PowerShell here-strings or single-line comments.
     *
     * <p>Detection rule (unchanged from the pre-S1 implementation): a stripped line starts
     * with any marker key. The only behavioral change is the exempt-line filter applied
     * BEFORE the detection rule:</p>
     * <ul>
     *   <li>Here-string openers: a line whose LAST occurrence of {@code @'} or {@code @"}
     *       is at end-of-operator position (nothing but whitespace after it) opens a here-string.
     *       Mid-line occurrences (e.g. inside a double-quoted string) do NOT open a here-string.</li>
     *   <li>Here-string terminators: a line whose FIRST two chars are {@code '@} (single-here)
     *       or {@code "@} (double-here) closes the current here-string. Leading whitespace
     *       prevents termination.</li>
     *   <li>Content of an open here-string (including its terminator line) is exempt from
     *       detection. An unclosed here-string is exempt to EOF.</li>
     *   <li>Single-line comment: a stripped line starting with {@code #} is exempt.</li>
     *   <li>Block comment {@code <# ... #>} is NOT exempt by state; however, its own {@code <#}
     *       prefix means a marker embedded on the same line does not start with the marker,
     *       so startsWith detection does not fire. A multi-line block comment whose marker
     *       line begins at column 0 WILL fire (detection is startsWith).</li>
     * </ul>
     */
    private static boolean containsRawMarkers(String text) {
        if (text == null) return false;
        int hereState = 0;
        for (String line : text.split("\\R", -1)) {
            String stripped = line.strip();
            if (hereState != 0) {
                if (hereState == 1 && line.startsWith("'@")) {
                    hereState = 0;
                } else if (hereState == 2 && line.startsWith("\"@")) {
                    hereState = 0;
                }
                continue;
            }
            if (stripped.startsWith("#")) {
                continue;
            }
            int p1 = line.lastIndexOf("@'");
            int p2 = line.lastIndexOf("@\"");
            int pos = Math.max(p1, p2);
            if (pos >= 0 && line.substring(pos + 2).strip().isEmpty()) {
                hereState = line.charAt(pos + 1) == '\'' ? 1 : 2;
            }
            if (stripped.startsWith("USER_MSG:") || stripped.startsWith("AGENT_PAUSE:")
                    || stripped.startsWith("AGENT_STOP:") || stripped.startsWith("AGENT_DONE:")
                    || stripped.startsWith("AGENT_SESSION_MIGRATE_START")
                    || stripped.startsWith("AGENT_SESSION_MIGRATE_CONFIRM")) {
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


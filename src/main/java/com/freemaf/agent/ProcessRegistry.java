
package com.freemaf.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class ProcessRegistry {
    private static final Path REGISTRY_FILE = Path.of("agent", "cache", "process-registry.txt");
    private ProcessRegistry() {}

    public static synchronized void register(Process parent) {
        if (parent == null) return;
        try {
            long pid = parent.pid();
            String children = parent.descendants().map(ProcessHandle::pid).map(String::valueOf).collect(Collectors.joining(","));
            long startMs = System.currentTimeMillis();
            String line = "parentPid=" + pid + "|children=" + children + "|start=" + startMs;
            Files.createDirectories(REGISTRY_FILE.getParent());
            Files.writeString(REGISTRY_FILE, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AppLogger.warn("ProcessRegistry.register failed: " + e.getMessage());
        }
    }

    public static synchronized void unregister(Process parent) {
        if (parent == null) return;
        long pid = parent.pid();
        try {
            if (!Files.exists(REGISTRY_FILE)) return;
            String prefix = "parentPid=" + pid + "|";
            List<String> kept = new ArrayList<>();
            for (String line : Files.readAllLines(REGISTRY_FILE, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                if (line.startsWith(prefix)) continue;
                kept.add(line);
            }
            Files.write(REGISTRY_FILE, kept, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AppLogger.warn("ProcessRegistry.unregister failed: " + e.getMessage());
        }
    }

    /**
     * Removes stale registry entries.
     *
     * A record is stale when the PID is dead, or when the PID has been
     * recycled by a different process. Start-time tolerance is 2000 ms;
     * the comparison uses ProcessHandle.Info.startInstant().toEpochMilli()
     * against the stored start=epochMillis value.
     *
     * When startInstant is unavailable (Optional.empty), the method logs a
     * WARN and falls back to kill-if-alive.
     */
    public static synchronized int cleanupStale() {
        return cleanupStale(new ProcessManager());
    }

    static synchronized int cleanupStale(ProcessManager pm) {
        if (!Files.exists(REGISTRY_FILE)) return 0;
        int cleaned = 0;
        try {
            List<String> kept = new ArrayList<>();
            for (String line : Files.readAllLines(REGISTRY_FILE, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                long pid = parsePid(line);
                if (pid <= 0L) continue;
                long storedStart = parseStart(line);
                Optional<ProcessHandle> handleOpt = ProcessHandle.of(pid);
                if (handleOpt.isEmpty()) {
                    try { pm.killProcessTreeByPid(pid); } catch (RuntimeException e) {
                        AppLogger.debug("cleanupStale taskkill failed pid=" + pid + ": " + e.getMessage());
                    }
                    cleaned++;
                    continue;
                }
                ProcessHandle handle = handleOpt.get();
                if (!handle.isAlive()) {
                    try { pm.killProcessTreeByPid(pid); } catch (RuntimeException e) {
                        AppLogger.debug("cleanupStale taskkill failed pid=" + pid + ": " + e.getMessage());
                    }
                    cleaned++;
                    continue;
                }
                Optional<Instant> actualStart = handle.info().startInstant();
                if (actualStart.isEmpty()) {
                    AppLogger.warn("ProcessRegistry.cleanupStale: startInstant unavailable for pid=" + pid + "; falling back to kill-if-alive");
                    try { pm.killProcessTreeByPid(pid); } catch (RuntimeException e) {
                        AppLogger.debug("cleanupStale taskkill failed pid=" + pid + ": " + e.getMessage());
                    }
                    cleaned++;
                    continue;
                }
                long delta = Math.abs(actualStart.get().toEpochMilli() - storedStart);
                if (delta > 2000L) {
                    AppLogger.warn("ProcessRegistry.cleanupStale: pid=" + pid
                            + " recycled (storedStart=" + storedStart
                            + ", actualStart=" + actualStart.get().toEpochMilli()
                            + ", delta=" + delta + "ms); removing entry without kill");
                    cleaned++;
                    continue;
                }
                kept.add(line);
            }
            Files.write(REGISTRY_FILE, kept, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AppLogger.warn("ProcessRegistry.cleanupStale failed: " + e.getMessage());
        }
        return cleaned;
    }

    private static long parsePid(String line) {
        String prefix = "parentPid=";
        int idx = line.indexOf(prefix);
        if (idx < 0) return -1L;
        int start = idx + prefix.length();
        int end = line.indexOf(0x7C, start);
        if (end < 0) end = line.length();
        try {
            return Long.parseLong(line.substring(start, end));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static long parseStart(String line) {
        String prefix = "start=";
        int idx = line.indexOf(prefix);
        if (idx < 0) return -1L;
        int start = idx + prefix.length();
        int end = line.indexOf(0x7C, start);
        if (end < 0) end = line.length();
        try {
            return Long.parseLong(line.substring(start, end));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}

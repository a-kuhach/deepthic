
package com.freemaf.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessRegistryTest {
    private static final Path REGISTRY = Path.of("agent", "cache", "process-registry.txt");

    @BeforeEach
    void clearRegistry() throws IOException {
        Files.deleteIfExists(REGISTRY);
    }

    @AfterEach
    void dropRegistry() throws IOException {
        Files.deleteIfExists(REGISTRY);
    }

    @Test
    void registerWritesLine() throws Exception {
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 5").start();
        try {
            ProcessRegistry.register(p);
            assertTrue(Files.exists(REGISTRY), "registry file must exist after register");
            String content = Files.readString(REGISTRY, StandardCharsets.UTF_8);
            assertTrue(content.contains("parentPid=" + p.pid() + "|"), "registry must contain parent pid");
        } finally {
            if (p.isAlive()) p.destroyForcibly();
            ProcessRegistry.unregister(p);
        }
    }

    @Test
    void unregisterRemovesLine() throws Exception {
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 5").start();
        try {
            ProcessRegistry.register(p);
            ProcessRegistry.unregister(p);
            String content = Files.exists(REGISTRY) ? Files.readString(REGISTRY, StandardCharsets.UTF_8) : "";
            assertFalse(content.contains("parentPid=" + p.pid() + "|"), "registry must not contain unregistered pid");
        } finally {
            if (p.isAlive()) p.destroyForcibly();
        }
    }

    @Test
    void cleanupStaleRemovesDeadRecords() throws Exception {
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 30").start();
        ProcessRegistry.register(p);
        p.destroyForcibly();
        p.waitFor(5, TimeUnit.SECONDS);
        int cleaned = ProcessRegistry.cleanupStale();
        assertTrue(cleaned >= 1, "cleanup must report at least one stale record, got " + cleaned);
        String content = Files.exists(REGISTRY) ? Files.readString(REGISTRY, StandardCharsets.UTF_8) : "";
        assertFalse(content.contains("parentPid=" + p.pid() + "|"), "dead record must be removed");
    }

    @Test
    void cleanupStaleMatchingStartKeepsAliveEntry() throws Exception {
        long pid = 12346L;
        long storedStart = 10000L;
        long actualStart = 10500L; // delta 500 <= 2000
        ProcessManager pm = mock(ProcessManager.class);
        ProcessHandle handle = mock(ProcessHandle.class);
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(handle.isAlive()).thenReturn(true);
        when(handle.info()).thenReturn(info);
        when(info.startInstant()).thenReturn(Optional.of(Instant.ofEpochMilli(actualStart)));
        try (MockedStatic<ProcessHandle> ph = mockStatic(ProcessHandle.class)) {
            ph.when(() -> ProcessHandle.of(pid)).thenReturn(Optional.of(handle));
            Files.createDirectories(REGISTRY.getParent());
            Files.writeString(REGISTRY, "parentPid=" + pid + "|children=|start=" + storedStart + System.lineSeparator());
            int cleaned = ProcessRegistry.cleanupStale(pm);
            assertEquals(0, cleaned);
            verify(pm, never()).killProcessTreeByPid(pid);
            String content = Files.readString(REGISTRY);
            assertTrue(content.contains("parentPid=" + pid + "|"));
        }
    }

    @Test
    void cleanupStaleRecycledPidRemovesEntryWithoutKill() throws Exception {
        long pid = 12345L;
        long storedStart = 1000L;
        long actualStart = 5000L; // delta 4000 > 2000
        ProcessManager pm = mock(ProcessManager.class);
        ProcessHandle handle = mock(ProcessHandle.class);
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(handle.isAlive()).thenReturn(true);
        when(handle.info()).thenReturn(info);
        when(info.startInstant()).thenReturn(Optional.of(Instant.ofEpochMilli(actualStart)));
        try (MockedStatic<ProcessHandle> ph = mockStatic(ProcessHandle.class)) {
            ph.when(() -> ProcessHandle.of(pid)).thenReturn(Optional.of(handle));
            Files.createDirectories(REGISTRY.getParent());
            Files.writeString(REGISTRY, "parentPid=" + pid + "|children=|start=" + storedStart + System.lineSeparator());
            int cleaned = ProcessRegistry.cleanupStale(pm);
            assertEquals(1, cleaned);
            verify(pm, never()).killProcessTreeByPid(pid);
            String content = Files.exists(REGISTRY) ? Files.readString(REGISTRY) : "";
            assertFalse(content.contains("parentPid=" + pid + "|"));
        }
    }

    @Test
    void cleanupStaleEmptyStartInstantFallsBackToKill() throws Exception {
        long pid = 12347L;
        ProcessManager pm = mock(ProcessManager.class);
        ProcessHandle handle = mock(ProcessHandle.class);
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(handle.isAlive()).thenReturn(true);
        when(handle.info()).thenReturn(info);
        when(info.startInstant()).thenReturn(Optional.empty());
        try (MockedStatic<ProcessHandle> ph = mockStatic(ProcessHandle.class)) {
            ph.when(() -> ProcessHandle.of(pid)).thenReturn(Optional.of(handle));
            Files.createDirectories(REGISTRY.getParent());
            Files.writeString(REGISTRY, "parentPid=" + pid + "|children=|start=1000" + System.lineSeparator());
            int cleaned = ProcessRegistry.cleanupStale(pm);
            assertEquals(1, cleaned);
            verify(pm).killProcessTreeByPid(pid);
        }
    }
}

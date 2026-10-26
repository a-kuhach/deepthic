package com.freemaf.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextPackagerTest {

    @TempDir
    Path tempDir;

    @Test
    void buildContextDoesNotThrow() {
        ContextPackager cp = new ContextPackager();
        assertNotNull(cp.buildContext());
    }

    @Test
    void listExistingFilesDoesNotThrow() {
        ContextPackager cp = new ContextPackager();
        assertNotNull(cp.listExistingFiles());
    }

    @Test
    void underCap_returnsFullConcatenation() throws Exception {
        Files.writeString(tempDir.resolve("MEMORY.md"), "abc");
        Files.writeString(tempDir.resolve("WIP.md"), "def");
        Files.writeString(tempDir.resolve("PLAN.md"), "ghi");
        ContextPackager cp = new ContextPackager(tempDir);
        String result = cp.buildContext(1000);
        assertTrue(result.contains("abc"));
        assertTrue(result.contains("def"));
        assertTrue(result.contains("ghi"));
        assertFalse(result.contains("TRUNCATED"));
        assertFalse(result.contains("omitted"));
        int wip = result.indexOf("=== WIP.md ===");
        int mem = result.indexOf("=== MEMORY.md ===");
        int plan = result.indexOf("=== PLAN.md ===");
        assertTrue(wip >= 0 && mem > wip && plan > mem, "header order must be WIP, MEMORY, PLAN");
    }

    @Test
    void overCap_planOmittedWhenOverBudget() throws Exception {
        String bigA = "A".repeat(40000);
        String bigB = "B".repeat(40000);
        String bigC = "C".repeat(40000);
        Files.writeString(tempDir.resolve("MEMORY.md"), bigA);
        Files.writeString(tempDir.resolve("WIP.md"), bigB);
        Files.writeString(tempDir.resolve("PLAN.md"), bigC);
        ContextPackager cp = new ContextPackager(tempDir);
        final String[] holder = new String[1];
        String out = captureStdout(() -> holder[0] = cp.buildContext(100000));
        String result = holder[0];
        assertTrue(result.length() <= 100000, "result length " + result.length() + " must be <= 100000");
        assertTrue(result.contains(bigA));
        assertTrue(result.contains(bigB));
        assertTrue(result.contains("=== PLAN.md ==="));
        assertFalse(result.contains(bigC));
        assertTrue(result.contains(ContextPackager.PLAN_OMIT_MARKER_PREFIX));
        assertTrue(out.contains("PLAN omitted"));
    }

    @Test
    void memoryAloneOver_truncatedToBudget() throws Exception {
        Files.writeString(tempDir.resolve("MEMORY.md"), "A".repeat(105000));
        Files.writeString(tempDir.resolve("WIP.md"), "");
        Files.writeString(tempDir.resolve("PLAN.md"), "");
        ContextPackager cp = new ContextPackager(tempDir);
        final String[] holder = new String[1];
        String out = captureStdout(() -> holder[0] = cp.buildContext(100000));
        String result = holder[0];
        assertTrue(result.length() <= 100000, "result length " + result.length() + " must be <= 100000");
        assertTrue(result.contains("[...TRUNCATED (dropped head"));
        assertTrue(result.contains("=== WIP.md ==="));
        assertTrue(result.contains("=== PLAN.md ==="));
        assertTrue(out.contains("MEMORY alone over cap"));
    }

    @Test
    void planAloneOver_omittedWithMarker() throws Exception {
        Files.writeString(tempDir.resolve("MEMORY.md"), "");
        Files.writeString(tempDir.resolve("WIP.md"), "");
        Files.writeString(tempDir.resolve("PLAN.md"), "C".repeat(105000));
        ContextPackager cp = new ContextPackager(tempDir);
        final String[] holder = new String[1];
        String out = captureStdout(() -> holder[0] = cp.buildContext(100000));
        String result = holder[0];
        assertTrue(result.length() <= 100000, "result length " + result.length() + " must be <= 100000");
        assertTrue(result.contains("=== MEMORY.md ==="));
        assertTrue(result.contains("=== WIP.md ==="));
        assertTrue(result.contains("=== PLAN.md ==="));
        assertTrue(result.contains(ContextPackager.PLAN_OMIT_MARKER_PREFIX));
        assertFalse(result.contains("C".repeat(100)));
        assertFalse(result.contains("TRUNCATED"));
        assertTrue(out.contains("PLAN omitted"));
    }

    @Test
    void allEmpty_filesExistEmptyBodies() throws Exception {
        Files.writeString(tempDir.resolve("MEMORY.md"), "");
        Files.writeString(tempDir.resolve("WIP.md"), "");
        Files.writeString(tempDir.resolve("PLAN.md"), "");
        ContextPackager cp = new ContextPackager(tempDir);
        String result = cp.buildContext(100000);
        assertTrue(result.contains("=== MEMORY.md ==="));
        assertTrue(result.contains("=== WIP.md ==="));
        assertTrue(result.contains("=== PLAN.md ==="));
        assertFalse(result.contains("TRUNCATED"));
        assertFalse(result.contains("omitted"));
        assertTrue(result.length() <= 100000);
    }

    @Test
    void defaultConstructorStillWorks() {
        ContextPackager cp = new ContextPackager();
        String result = cp.buildContext();
        assertNotNull(result);
    }

    @Test
    void defaultMaxCharsIs100000() {
        assertEquals(100_000, ContextPackager.DEFAULT_MAX_CHARS);
    }

    private static String captureStdout(Runnable r) {
        PrintStream orig = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
            r.run();
        } finally {
            System.setOut(orig);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}

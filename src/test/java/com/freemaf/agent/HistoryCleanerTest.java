package com.freemaf.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryCleanerTest {

    @TempDir
    Path tempDir;

    @Test
    void trimKeepsNewestByMtime() throws IOException {
        Path dir = tempDir.resolve("logs");
        Files.createDirectories(dir);
        long base = 1_700_000_000_000L;
        for (int i = 0; i < 5; i++) {
            Path f = dir.resolve("f" + i + ".txt");
            Files.writeString(f, "x");
            Files.setLastModifiedTime(f, FileTime.fromMillis(base + i * 2000L));
        }
        HistoryCleaner.trim(dir, 3);
        try (var s = Files.list(dir)) {
            List<String> remaining = s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            assertEquals(3, remaining.size());
            assertEquals(List.of("f2.txt", "f3.txt", "f4.txt"), remaining);
        }
    }

    @Test
    void trimHandlesMissingDir() {
        assertDoesNotThrow(() -> HistoryCleaner.trim(tempDir.resolve("nonexistent"), 3));
    }

    @Test
    void trimHandlesNullDir() {
        assertDoesNotThrow(() -> HistoryCleaner.trim(null, 3));
    }

    @Test
    void trimHandlesEmptyDir() throws IOException {
        Path dir = tempDir.resolve("empty");
        Files.createDirectories(dir);
        assertDoesNotThrow(() -> HistoryCleaner.trim(dir, 3));
    }
}

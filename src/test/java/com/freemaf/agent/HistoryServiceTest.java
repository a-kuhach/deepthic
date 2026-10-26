package com.freemaf.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryServiceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        HistoryService.setMaxFilesPerSubfolder(100);
    }

    @AfterEach
    void tearDown() {
        HistoryService.setMaxFilesPerSubfolder(100);
    }

    private static int countFiles(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return (int) s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void writeTriggersTrim() throws IOException {
        HistoryService.setMaxFilesPerSubfolder(50);
        for (int i = 0; i < 60; i++) {
            HistoryService.writeTo(tempDir, "prompts", "p" + i);
        }
        assertEquals(50, countFiles(tempDir.resolve("prompts")));
    }

    @Test
    void underCapKeepsAll() throws IOException {
        HistoryService.setMaxFilesPerSubfolder(50);
        for (int i = 0; i < 30; i++) {
            HistoryService.writeTo(tempDir, "prompts", "p" + i);
        }
        assertEquals(30, countFiles(tempDir.resolve("prompts")));
    }

    @Test
    void isolationBetweenSubfolders() throws IOException {
        HistoryService.setMaxFilesPerSubfolder(50);
        for (int i = 0; i < 60; i++) {
            HistoryService.writeTo(tempDir, "prompts", "p" + i);
        }
        for (int i = 0; i < 30; i++) {
            HistoryService.writeTo(tempDir, "scripts", "s" + i);
        }
        assertEquals(50, countFiles(tempDir.resolve("prompts")));
        assertEquals(30, countFiles(tempDir.resolve("scripts")));
    }

    @Test
    void defaultCapIs100() throws IOException {
        for (int i = 0; i < 105; i++) {
            HistoryService.writeTo(tempDir, "defaults", "d" + i);
        }
        assertEquals(100, countFiles(tempDir.resolve("defaults")));
    }

    @Test
    void setterRejectsZero() throws IOException {
        HistoryService.setMaxFilesPerSubfolder(50);
        for (int i = 0; i < 60; i++) {
            HistoryService.writeTo(tempDir, "zero", "z" + i);
        }
        HistoryService.setMaxFilesPerSubfolder(0);
        for (int i = 0; i < 60; i++) {
            HistoryService.writeTo(tempDir, "zero", "z" + i);
        }
        assertEquals(50, countFiles(tempDir.resolve("zero")));
    }

    @Test
    void setterRejectsNegative() throws IOException {
        HistoryService.setMaxFilesPerSubfolder(50);
        for (int i = 0; i < 60; i++) {
            HistoryService.writeTo(tempDir, "neg", "n" + i);
        }
        HistoryService.setMaxFilesPerSubfolder(-5);
        for (int i = 0; i < 60; i++) {
            HistoryService.writeTo(tempDir, "neg", "n" + i);
        }
        assertEquals(50, countFiles(tempDir.resolve("neg")));
    }
}

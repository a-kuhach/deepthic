package com.freemaf.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class HistoryService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final Path BASE_DIR = Path.of("agent", "history");

    private static volatile int maxFilesPerSubfolder = 100;

    private HistoryService() {
    }

    public static void savePrompt(String prompt) {
        write("prompts", prompt);
    }

    public static void saveResponse(String response) {
        write("responses", response);
    }

    public static void setMaxFilesPerSubfolder(int value) {
        if (value >= 1) maxFilesPerSubfolder = value;
    }

    static void write(String subfolder, String content) {
        writeTo(BASE_DIR, subfolder, content);
    }

    static void writeTo(Path baseDir, String subfolder, String content) {
        Path dir = baseDir.resolve(subfolder);
        try {
            Files.createDirectories(dir);
            String fileName = LocalDateTime.now().format(TIMESTAMP) + "_" + System.nanoTime() + ".txt";
            Path file = dir.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            HistoryCleaner.trim(dir, maxFilesPerSubfolder);
        } catch (IOException e) {
            AppLogger.warn("HistoryService write failed: " + e.getMessage());
        }
    }
}

package com.freemaf.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ContextPackager {

    static final int DEFAULT_MAX_CHARS = 100_000;

    static final String PLAN_OMIT_MARKER_PREFIX = "[PLAN omitted: over-budget";

    private static final List<String> CONTEXT_FILES = List.of("WIP.md", "MEMORY.md", "PLAN.md");

    private final Path projectDir;

    public ContextPackager() {
        this(Path.of("agent", "project"));
    }

    public ContextPackager(Path projectDir) {
        this.projectDir = projectDir;
    }

    public List<Path> listExistingFiles() {
        List<Path> existing = new ArrayList<>();
        for (String fileName : CONTEXT_FILES) {
            Path file = projectDir.resolve(fileName);
            if (Files.isRegularFile(file)) existing.add(file);
        }
        return existing;
    }

    public String buildContext() {
        return buildContext(DEFAULT_MAX_CHARS);
    }

    public String buildContext(int maxChars) {
        List<Path> existing = listExistingFiles();
        int sepLen = System.lineSeparator().length();
        int headerCost = 0;
        for (Path f : existing) headerCost += ("=== " + f.getFileName() + " ===").length() + sepLen;
        int trailingCost = existing.size() * sepLen;
        int initialBodyBudget = maxChars - headerCost - trailingCost;
        if (initialBodyBudget < 0) {
            AppLogger.warn("ContextPackager: cap=" + maxChars + " < headerCost=" + headerCost
                    + " + trailing=" + trailingCost + "; emitting headers only");
            StringBuilder sb = new StringBuilder();
            for (Path f : existing) {
                sb.append("=== ").append(f.getFileName()).append(" ===").append(System.lineSeparator());
                sb.append(System.lineSeparator());
            }
            return sb.toString();
        }

        String memoryBody = "";
        String wipBody = "";
        String planBody = "";
        for (Path f : existing) {
            String name = f.getFileName().toString();
            if ("MEMORY.md".equals(name)) memoryBody = readOrEmpty(f);
            else if ("WIP.md".equals(name)) wipBody = readOrEmpty(f);
            else if ("PLAN.md".equals(name)) planBody = readOrEmpty(f);
        }
        int memOrig = memoryBody.length();
        int wipOrig = wipBody.length();
        int planOrig = planBody.length();

        int bodyBudget = initialBodyBudget;

        int memMarkerReserve = truncationMarkerHead(memOrig).length();
        int memKept = Math.min(memOrig, Math.max(0, bodyBudget - memMarkerReserve));
        boolean memoryTruncated = memKept < memOrig;
        String memKeptText = memoryTruncated
                ? truncationMarkerHead(memOrig - memKept) + memoryBody.substring(memOrig - memKept)
                : memoryBody;
        bodyBudget -= memKeptText.length();

        int wipMarkerReserve = truncationMarkerHead(wipOrig).length();
        int wipKept = Math.min(wipOrig, Math.max(0, bodyBudget - wipMarkerReserve));
        boolean wipTruncated = wipKept < wipOrig;
        String wipKeptText = wipTruncated
                ? truncationMarkerHead(wipOrig - wipKept) + wipBody.substring(wipOrig - wipKept)
                : wipBody;
        bodyBudget -= wipKeptText.length();

        boolean planOmitted = planOrig > bodyBudget;
        String planKeptText = planOmitted ? planOmitMarker(planOrig, bodyBudget) : planBody;

        String sep = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        for (Path f : existing) {
            String name = f.getFileName().toString();
            sb.append("=== ").append(name).append(" ===").append(sep);
            if ("MEMORY.md".equals(name)) sb.append(memKeptText);
            else if ("WIP.md".equals(name)) sb.append(wipKeptText);
            else if ("PLAN.md".equals(name)) sb.append(planKeptText);
            sb.append(sep);
        }

        if (memoryTruncated || wipTruncated || planOmitted) {
            int totalOrig = memOrig + wipOrig + planOrig;
            int totalKept = memKeptText.length() + wipKeptText.length() + planKeptText.length();
            StringBuilder warn = new StringBuilder();
            warn.append("ContextPackager: cap=").append(maxChars)
                    .append(", MEMORY=").append(memOrig).append("->").append(memKeptText.length())
                    .append(", WIP=").append(wipOrig).append("->").append(wipKeptText.length())
                    .append(", PLAN=").append(planOrig).append("->").append(planOmitted ? "OMITTED" : String.valueOf(planKeptText.length()))
                    .append(", total=").append(totalOrig).append("->").append(totalKept);
            if (memoryTruncated && memOrig > initialBodyBudget) warn.append(" (MEMORY alone over cap)");
            if (planOmitted) warn.append(" (PLAN omitted)");
            AppLogger.warn(warn.toString());
        }

        return sb.toString();
    }

    static String truncationMarkerHead(int dropped) {
        return "[...TRUNCATED (dropped head " + dropped + " chars)...]";
    }

    static String truncationMarkerTail(int dropped) {
        return "[...TRUNCATED (dropped tail " + dropped + " chars)...]";
    }

    static String planOmitMarker(int originalLen, int remainingBudget) {
        return "[PLAN omitted: over-budget (" + originalLen + " chars > remaining " + remainingBudget + ")]";
    }

    private String readOrEmpty(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AppLogger.warn("ContextPackager read failed: " + e.getMessage());
            return "";
        }
    }
}

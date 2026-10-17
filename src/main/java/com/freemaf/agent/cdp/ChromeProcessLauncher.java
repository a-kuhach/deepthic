package com.freemaf.agent.cdp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ChromeProcessLauncher {

    private static final String PROFILE_DIR = "agent/cache/cdp-profile";

    private ChromeProcessLauncher() {}

    public static Path resolveProfilePath() {
        return Path.of(PROFILE_DIR).toAbsolutePath().normalize();
    }

    public static Process start(boolean headless, int port) throws IOException {
        Path profilePath = resolveProfilePath();
        Files.createDirectories(profilePath);
        List<String> args = new ArrayList<>();
        args.add(resolveChromePath());
        args.add("--remote-debugging-port=" + port);
        args.add("--remote-allow-origins=*");
        args.add("--user-data-dir=" + profilePath);
        args.add("--no-startup-window");
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add("--disable-sync");
        args.add("--disable-backgrounding-occluded-windows");
        args.add("--disable-renderer-backgrounding");
        args.add("--disable-features=CalculateNativeWinOcclusion");
        args.add("--hide-crash-restore-bubble");
        args.add("--disable-session-crashed-bubble");
        if (headless) args.add("--headless=new");
        return new ProcessBuilder(args).start();
    }

    public static void stop(Process p) {
        if (p == null) return;
        try {
            p.destroy();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                try {
                    new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(p.pid()))
                            .start()
                            .waitFor(5, TimeUnit.SECONDS);
                } catch (Exception ignored) { }
                if (p.isAlive()) p.destroyForcibly();
            }
        } catch (Exception ignored) { }
    }

    public static String resolveChromePath() {
        String local = System.getenv("LOCALAPPDATA");
        String[] candidates = {
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                local == null ? null : local + "\\Google\\Chrome\\Application\\chrome.exe"
        };
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) return c;
        }
        throw new IllegalStateException("chrome.exe not found in standard locations");
    }

    public static void killPidTree(long pid) {
        try {
            new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
    }

    public static PortOwner inspectPort(int port) {
        String script =
                "$conn = Get-NetTCPConnection -LocalPort " + port + " -ErrorAction SilentlyContinue | Select-Object -First 1;" +
                "if (-not $conn) { Write-Output 'FREE'; exit }" +
                "$p = $conn.OwningProcess;" +
                "$proc = Get-CimInstance Win32_Process -Filter \"ProcessId=$p\" -ErrorAction SilentlyContinue;" +
                "if (-not $proc) { Write-Output 'FREE'; exit }" +
                "$cmd = $proc.CommandLine;" +
                "Write-Output ('PID=' + $p);" +
                "Write-Output ('CMD=' + $cmd)";
        List<String> out;
        try { out = runPowerShell(script); } catch (IOException e) { return PortOwner.free(); }
        long pid = 0L; String cmd = "";
        for (String line : out) {
            String t = line.trim();
            if (t.startsWith("PID=")) pid = parseLong(t.substring(4), 0L);
            else if (t.startsWith("CMD=")) cmd = t.substring(4);
            else if (t.equals("FREE")) return PortOwner.free();
        }
        if (pid == 0L) return PortOwner.free();
        Path profile = resolveProfilePath();
        boolean isOurs = cmd.toLowerCase().contains("chrome.exe") && cmd.contains("--user-data-dir=" + profile);
        return isOurs ? PortOwner.framework(pid, cmd) : PortOwner.foreign(pid, cmd);
    }

    public static int killFrameworkChromiumProcesses(Path profilePath, long exceptPid) {
        if (profilePath == null) return 0;
        String profile = profilePath.toAbsolutePath().normalize().toString();
        String script =
                "$profile = '" + profile + "';" +
                "$except = " + exceptPid + ";" +
                "$killed = 0;" +
                "Get-CimInstance Win32_Process -Filter \"name='chrome.exe'\" -ErrorAction SilentlyContinue | ForEach-Object {" +
                "  if ($except -ne 0 -and $_.ProcessId -eq $except) { return }" +
                "  if ($_.CommandLine -and $_.CommandLine.Contains('--user-data-dir=' + $profile)) {" +
                "    taskkill /F /T /PID $_.ProcessId 2>$null | Out-Null;" +
                "    $killed++;" +
                "  }" +
                "}" +
                "Write-Output ('KILLED=' + $killed)";
        try {
            for (String line : runPowerShell(script)) {
                String t = line.trim();
                if (t.startsWith("KILLED=")) return (int) parseLong(t.substring(7), 0L);
            }
        } catch (IOException ignored) { }
        return 0;
    }

    private static List<String> runPowerShell(String script) throws IOException {
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        try { p.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        return lines;
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    public static final class PortOwner {
        public enum Kind { FREE, FRAMEWORK, FOREIGN }
        private final Kind kind; private final long pid; private final String commandLine;
        private PortOwner(Kind kind, long pid, String commandLine) { this.kind = kind; this.pid = pid; this.commandLine = commandLine; }
        public static PortOwner free() { return new PortOwner(Kind.FREE, 0L, ""); }
        public static PortOwner framework(long pid, String cmd) { return new PortOwner(Kind.FRAMEWORK, pid, cmd); }
        public static PortOwner foreign(long pid, String cmd) { return new PortOwner(Kind.FOREIGN, pid, cmd); }
        public Kind kind() { return kind; }
        public long pid() { return pid; }
        public String commandLine() { return commandLine; }
    }

    public static final class PortBusyException extends IOException {
        private final long pid;
        private final String commandLine;
        public PortBusyException(String message, long pid, String commandLine) {
            super(message); this.pid = pid; this.commandLine = commandLine;
        }
        public long pid() { return pid; }
        public String commandLine() { return commandLine; }
    }
}

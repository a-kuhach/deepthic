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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinUser;
import com.freemaf.agent.AppLogger;

public final class ChromeProcessLauncher {

    private static final String PROFILE_DIR = "agent/cache/cdp-profile";

    private ChromeProcessLauncher() {}

    public static Path resolveProfilePath() {
        return Path.of(PROFILE_DIR).toAbsolutePath().normalize();
    }


    // FIX36: reliable test-mode detection via Surefire system property.
    // FIX38: check env var first (more reliable than surefire system properties).
    private static boolean isTestMode() {
        if ("true".equalsIgnoreCase(System.getenv("DEEPTHIC_TEST"))) return true;
        if ("true".equalsIgnoreCase(System.getProperty("deepthic.test", "false"))) return true;
        return false;
    }


    public static Process start(boolean headless, int port) throws IOException {

        Path profilePath = resolveProfilePath();

        AppLogger.info("ChromeProcessLauncher.start: headless=" + headless + ", isTestMode=" + isTestMode() + ", deepthic.test=" + System.getProperty("deepthic.test", "not-set"));

        Files.createDirectories(profilePath);

        if (!headless && isTestMode()) {

            headless = true;

            AppLogger.info("FIX44: test mode, forcing headless");

        }

        List<String> args = new ArrayList<>();

        args.add(resolveChromePath());

        args.add("--remote-debugging-port=" + port);

        args.add("--remote-allow-origins=*");

        args.add("--user-data-dir=" + profilePath);

        args.add("--no-first-run");

        args.add("--no-default-browser-check");

        args.add("--disable-sync");

        args.add("--disable-backgrounding-occluded-windows");

        args.add("--disable-renderer-backgrounding");

        args.add("--disable-features=CalculateNativeWinOcclusion");

        args.add("--hide-crash-restore-bubble");

        args.add("--disable-session-crashed-bubble");

        args.add("--disable-blink-features=AutomationControlled");

        args.add("--user-agent=" + buildBrowserUserAgent());








        if (headless) {
            // FIX82: use --headless=old which does not create a native Chrome_WidgetWin_1 window.
            args.add("--headless=old");
            args.add("--disable-gpu");
            args.add("--no-sandbox");
            AppLogger.info("FIX82: chrome.exe --headless=old (no window)");
        } else {

            args.add("--new-window");

        }








        ProcessBuilder pb = new ProcessBuilder(args);

        AppLogger.info("FIX44: chrome cmd = " + String.join(" ", args));

        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process p = pb.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            try {

                if (p.isAlive()) {

                    new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(p.pid())).start().waitFor(5, TimeUnit.SECONDS);

                    p.destroyForcibly();

                }

            } catch (Exception ignored) { }

        }, "chrome-shutdown-" + port));


        return p;

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


    private static volatile String cachedVersion = null;



    /** FIX30: returns Chrome major version (e.g. "131") from `chrome.exe --version`; falls back to "131". */

    public static String resolveChromeVersion() {

        if (cachedVersion != null) return cachedVersion;

        try {

            Process p = new ProcessBuilder(resolveChromePath(), "--version")

                    .redirectErrorStream(true).start();

            String line = "";

            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {

                line = br.readLine();

            }

            try { p.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            if (line != null) {

                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.").matcher(line);

                if (m.find()) { cachedVersion = m.group(1); return cachedVersion; }

            }

        } catch (Exception ignored) { }

        cachedVersion = "131";

        return cachedVersion;

    }



    /** FIX30: build a real-looking Chrome User-Agent (no "HeadlessChrome"). */

    public static String buildBrowserUserAgent() {

        String v = resolveChromeVersion();

        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + v + ".0.0.0 Safari/537.36";

    }


    // FIX39: chrome-headless-shell.exe is a separate binary (no window at all).
    // It is shipped with Chrome for Testing 122+; not present in standard Chrome install.
    // We probe next to chrome.exe; if missing, use --headless=old (Chrome <132).
    // FIX40: chrome-headless-shell.exe is the only window-less binary for Chrome 131+.
    // Auto-download from Chrome for Testing if not present.

    // FIX68: download chrome-headless-shell matching installed Chrome major,

    // unpack ALL files (icudtl.dat), and verify icudtl.dat exists.

    public static synchronized String resolveHeadlessShellPath() {

        AppLogger.info("FIX68: resolveHeadlessShellPath called");

        try {

            String chrome = resolveChromePath();

            java.io.File chromeFile = new java.io.File(chrome);

            java.io.File dir = chromeFile.getParentFile();

            if (dir != null) {

                java.io.File shell = new java.io.File(dir, "chrome-headless-shell.exe");

                if (shell.isFile()) return shell.getAbsolutePath();

            }

            java.io.File targetDir = new java.io.File("agent/cache/chrome-headless-shell");

            java.io.File cacheShell = new java.io.File(targetDir, "chrome-headless-shell.exe");

            java.io.File icuFile = new java.io.File(targetDir, "icudtl.dat");

            if (cacheShell.isFile() && icuFile.isFile()) return cacheShell.getAbsolutePath();

            // If shell exists but icudtl.dat missing, delete and re-download.

            if (targetDir.isDirectory()) {

                for (java.io.File f : targetDir.listFiles()) { f.delete(); }

            }



            String major = resolveChromeVersion();

            AppLogger.info("FIX68: installed Chrome major = " + major);

            String fullVersion = findFullVersionForMajor(major);

            AppLogger.info("FIX68: Chrome for Testing fullVersion = " + fullVersion);

            if (fullVersion == null || fullVersion.isEmpty()) {

                AppLogger.warn("FIX68: no Chrome for Testing build for major " + major);

                return null;

            }

            String url = "https://storage.googleapis.com/chrome-for-testing-public/" + fullVersion + "/win64/chrome-headless-shell-win64.zip";

            targetDir.mkdirs();

            java.io.File zipFile = new java.io.File(targetDir, "chrome-headless-shell.zip");

            AppLogger.info("FIX68: downloading chrome-headless-shell from " + url);

            try (java.io.InputStream in = java.net.URI.create(url).toURL().openStream();

                 java.io.FileOutputStream out = new java.io.FileOutputStream(zipFile)) {

                byte[] buf = new byte[8192];

                int n;

                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);

            }

            AppLogger.info("FIX68: downloaded " + zipFile.length() + " bytes; extracting");

            try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile))) {

                java.util.zip.ZipEntry e;

                while ((e = zin.getNextEntry()) != null) {

                    java.io.File target = new java.io.File(targetDir, e.getName());

                    if (e.isDirectory()) { target.mkdirs(); continue; }

                    target.getParentFile().mkdirs();

                    try (java.io.FileOutputStream fout = new java.io.FileOutputStream(target)) {

                        byte[] b = new byte[8192];

                        int m;

                        while ((m = zin.read(b)) > 0) fout.write(b, 0, m);

                    }

                }

            }

            // Move all files from subfolder to targetDir.

            java.io.File sub = new java.io.File(targetDir, "chrome-headless-shell-win64");

            if (sub.isDirectory()) {

                for (java.io.File f : sub.listFiles()) {

                    java.io.File dest = new java.io.File(targetDir, f.getName());

                    if (!dest.exists()) f.renameTo(dest);

                }

            }

            // Log directory contents.

            java.io.File[] files = targetDir.listFiles();

            if (files != null) {

                StringBuilder sb = new StringBuilder();

                for (java.io.File f : files) sb.append(f.getName()).append(" ");

                AppLogger.info("FIX68: shell dir files: " + sb.toString());

            }

            java.io.File exe = new java.io.File(targetDir, "chrome-headless-shell.exe");

            if (exe.isFile() && new java.io.File(targetDir, "icudtl.dat").isFile()) return exe.getAbsolutePath();

            AppLogger.warn("FIX68: shell exe or icudtl.dat missing after unpack");

            return null;

        } catch (Exception e) {

            AppLogger.warn("FIX68: headless-shell download failed: " + e.getMessage());

            return null;

        }

    }



    private static String findFullVersionForMajor(String major) {

        try {

            String json = new String(java.net.URI.create(

                "https://googlechromelabs.github.io/chrome-for-testing/known-good-versions-with-downloads.json")

                .toURL().openStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            java.util.regex.Matcher m = java.util.regex.Pattern

                .compile("\"version\"\\s*:\\s*\"(" + major + "\\.[0-9]+\\.[0-9]+\\.[0-9]+)\"")

                .matcher(json);

            String last = null;

            while (m.find()) last = m.group(1);

            return last;

        } catch (Exception e) {

            AppLogger.warn("FIX68: findFullVersionForMajor failed: " + e.getMessage());

            return null;

        }

    }


    // FIX44: chrome.exe --version is GUI; stdout suppressed on Windows. Use PowerShell.
    private static String resolveFullChromeVersion() {
        try {
            String chrome = resolveChromePath();
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-Item '" + chrome + "').VersionInfo.FileVersion")
                    .redirectErrorStream(true).start();
            String line = "";
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                line = br.readLine();
            }
            try { p.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (line != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+)").matcher(line);
                if (m.find()) return m.group(1);
            }
        } catch (Exception e) {
            AppLogger.warn("FIX44: resolveFullChromeVersion failed: " + e.getMessage());
        }
        return null;
    }


    // FIX72: launch with CREATE_NO_WINDOW | DETACHED_PROCESS and inherited handles.

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
                "Get-CimInstance Win32_Process -Filter \"name='chrome.exe' or name='chrome-headless-shell.exe'\" -ErrorAction SilentlyContinue | ForEach-Object {" +
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

    // FIX44: hide all top-level windows of the given PID (fallback path only).








}

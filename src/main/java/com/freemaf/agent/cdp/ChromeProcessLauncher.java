package com.freemaf.agent.cdp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Own Chrome launcher with full control over the argument list.
 *
 * ChromeLauncher from cdt-java-client is unsuitable because it overrides
 * --user-data-dir with a temporary profile, breaking session persistence.
 */
public final class ChromeProcessLauncher {

    private static final String PROFILE_DIR = "agent/cache/cdp-profile";

    private ChromeProcessLauncher() {}

    public static Process start(boolean headless, int port) throws IOException {
        Path profilePath = Path.of(PROFILE_DIR).toAbsolutePath();
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
        if (headless) {
            args.add("--headless=new");
        }
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
                } catch (Exception ignored) {
                    // fall through to destroyForcibly
                }
                if (p.isAlive()) p.destroyForcibly();
            }
        } catch (Exception ignored) {
            // best effort
        }
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
}

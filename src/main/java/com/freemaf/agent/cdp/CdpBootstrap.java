package com.freemaf.agent.cdp;

import com.freemaf.agent.AppLogger;
import com.freemaf.agent.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * CDP bootstrap orchestrator: owns one Chrome process, one browser-level
 * CdpSession, and one CdpDeepSeek per role (executor / reviewer).
 *
 * Tab lifecycle invariants (S4 MASTER_PLAN):
 *  - at most one executor tab and one reviewer tab exist simultaneously;
 *  - openExecutor()/openReviewer() close the previous tab of that role first;
 *  - activateExecutor()/activateReviewer() switch the active target;
 *  - stop() closes every remaining deepseek.com tab before shutting the session down.
 *
 * Used when Config.isCdpEnabled() == true. The legacy WinAPI pipeline is
 * not touched by this class.
 */
public final class CdpBootstrap {

    public static final int DEFAULT_PORT = 9222;
    private static final long PORT_WAIT_MS  = 30_000L;
    private static final long LOGIN_WAIT_MS = 5L * 60L * 1000L;
    private static final long INPUT_WAIT_MS = 30_000L;
    private static final String PROFILE_PATH = "agent/cache/cdp-profile";
    private static final String DEEPSEEK_HOST = "deepseek.com";

    private final Config config;
    private final int port;

    private Process chromeProcess;
    private CdpSession session;
    private CdpDeepSeek executor;
    private CdpDeepSeek reviewer;
    private CdpTabInfo executorTabInfo;
    private CdpTabInfo reviewerTabInfo;
    private LoginFallbackHandler loginHandler;

    public CdpBootstrap(Config config) {
        this(config, DEFAULT_PORT);
    }

    public CdpBootstrap(Config config, int port) {
        if (config == null) throw new IllegalArgumentException("config is null");
        if (port <= 0) throw new IllegalArgumentException("port must be > 0");
        this.config = config;
        this.port = port;
    }

    public Config config() { return config; }
    public int port() { return port; }

    public synchronized void start(boolean headless) throws IOException {
        if (chromeProcess != null && chromeProcess.isAlive()) {
            return;
        }
        killOrphan(port);
        chromeProcess = ChromeProcessLauncher.start(headless, port);
        CdpHttpClient http = new CdpHttpClient(port);
        if (!http.waitForPort(PORT_WAIT_MS)) {
            throw new IOException("CDP port " + port + " did not open within " + PORT_WAIT_MS + " ms");
        }
        this.session = new CdpSession(port);
        this.loginHandler = new LoginFallbackHandler(port, PROFILE_PATH);
    }

    public synchronized CdpDeepSeek openExecutor() throws Exception {
        ensureStarted();
        if (executor != null || executorTabInfo != null) {
            closeExecutor();
        }
        executor = openRole();
        executorTabInfo = executor.activeTabInfo();
        if (executorTabInfo != null) {
            session.activateTab(executorTabInfo);
        }
        return executor;
    }

    public synchronized CdpDeepSeek openReviewer() throws Exception {
        ensureStarted();
        if (reviewer != null || reviewerTabInfo != null) {
            closeReviewer();
        }
        reviewer = openRole();
        reviewerTabInfo = reviewer.activeTabInfo();
        if (reviewerTabInfo != null) {
            session.activateTab(reviewerTabInfo);
        }
        return reviewer;
    }

    private CdpDeepSeek openRole() throws Exception {
        CdpDeepSeek ds = new CdpDeepSeek(session, loginHandler);
        ds.open();
        ds.ensureSession(LOGIN_WAIT_MS);
        ds.waitForInput(INPUT_WAIT_MS);
        return ds;
    }

    public synchronized void closeExecutor() {
        CdpTabInfo tab = executorTabInfo;
        executorTabInfo = null;
        CdpDeepSeek ex = executor;
        executor = null;
        if (tab != null && session != null) {
            try { session.closeTab(tab); } catch (Exception ignored) { }
        }
        if (ex != null) {
            try { ex.closeActiveTab(); } catch (Exception ignored) { }
        }
    }

    public synchronized void closeReviewer() {
        CdpTabInfo tab = reviewerTabInfo;
        reviewerTabInfo = null;
        CdpDeepSeek rev = reviewer;
        reviewer = null;
        if (tab != null && session != null) {
            try { session.closeTab(tab); } catch (Exception ignored) { }
        }
        if (rev != null) {
            try { rev.closeActiveTab(); } catch (Exception ignored) { }
        }
    }

    public synchronized void stop() {
        try {
            closeReviewer();
        } catch (Exception ignored) { }
        try {
            closeExecutor();
        } catch (Exception ignored) { }
        try {
            if (session != null) {
                CdpHttpClient http = new CdpHttpClient(port);
                int forcedClosed = 0;
                List<CdpTabInfo> remaining = http.list();
                for (CdpTabInfo t : remaining) {
                    String url = t.url() == null ? "" : t.url();
                    if (url.contains(DEEPSEEK_HOST)) {
                        if (http.closeTab(t.id())) forcedClosed++;
                    }
                }
                if (forcedClosed > 0) {
                    AppLogger.info("CdpBootstrap.stop: force-closed " + forcedClosed + " leftover deepseek tab(s)");
                } else {
                    AppLogger.info("CdpBootstrap.stop: no leftover deepseek tabs");
                }
            }
        } catch (Exception e) {
            AppLogger.warn("CdpBootstrap.stop: leftover scan failed: " + e.getMessage());
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) { }
                session = null;
            }
            if (chromeProcess != null) {
                try { ChromeProcessLauncher.stop(chromeProcess); } catch (Exception ignored) { }
                chromeProcess = null;
            }
            killOrphan(port);
        }
    }

    public synchronized boolean isReviewerTabOpen() {
        if (reviewerTabInfo == null || session == null) return false;
        String id = reviewerTabInfo.id();
        for (CdpTabInfo t : session.listTabs()) {
            if (id.equals(t.id())) return true;
        }
        return false;
    }

    public synchronized boolean isExecutorTabOpen() {
        if (executorTabInfo == null || session == null) return false;
        String id = executorTabInfo.id();
        for (CdpTabInfo t : session.listTabs()) {
            if (id.equals(t.id())) return true;
        }
        return false;
    }

    public synchronized void activateExecutor() {
        if (executorTabInfo != null && session != null) {
            session.activateTab(executorTabInfo);
        }
    }

    public synchronized void activateReviewer() {
        if (reviewerTabInfo != null && session != null) {
            session.activateTab(reviewerTabInfo);
        }
    }

    public CdpDeepSeek executor() { return executor; }
    public CdpDeepSeek reviewer() { return reviewer; }

    public synchronized CdpTabInfo executorTabInfo() { return executorTabInfo; }
    public synchronized CdpTabInfo reviewerTabInfo() { return reviewerTabInfo; }

    private void ensureStarted() throws IOException {
        if (chromeProcess == null || !chromeProcess.isAlive() || session == null) {
            throw new IOException("CdpBootstrap is not started; call start() first");
        }
    }

    /**
     * Kills any process currently LISTENING on the given debug port.
     * Best-effort: failures are swallowed.
     */
    public static void killOrphan(int port) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "netstat -ano | findstr :" + port)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(5, TimeUnit.SECONDS);
            Set<Long> pids = new LinkedHashSet<>();
            for (String line : sb.toString().split("\\R")) {
                String t = line.trim();
                if (!t.contains("LISTENING")) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 5) continue;
                try { pids.add(Long.parseLong(parts[parts.length - 1])); }
                catch (NumberFormatException ignored) { }
            }
            for (Long pid : pids) {
                try {
                    new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                            .redirectErrorStream(true)
                            .start()
                            .waitFor(5, TimeUnit.SECONDS);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }
}

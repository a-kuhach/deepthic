package com.freemaf.agent.cdp;

import com.freemaf.agent.AppLogger;
import com.freemaf.agent.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.freemaf.agent.cdp.ChromeProcessLauncher.PortBusyException;
import com.freemaf.agent.cdp.ChromeProcessLauncher.PortOwner;

public final class CdpBootstrap {

    public static final int DEFAULT_PORT = 9222;
    private static final long PORT_WAIT_MS  = 30_000L;
    private static final long LOGIN_WAIT_MS = 5L * 60L * 1000L;
    private static final long INPUT_WAIT_MS = 30_000L;
    private static final String DEEPSEEK_HOST = "deepseek.com";
    private static final String LOCK_FILE = "agent/cache/cdp.lock";

    private final Config config;
    private final int port;

    private Process chromeProcess;
    private CdpSession session;
    private CdpDeepSeek executor;
    private CdpDeepSeek reviewer;
    private CdpTabInfo executorTabInfo;
    private CdpTabInfo reviewerTabInfo;
    private LoginFallbackHandler loginHandler;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean allowReuseSession = false;

    public CdpBootstrap(Config config) { this(config, DEFAULT_PORT); }
    public CdpBootstrap(Config config, int port) {
        if (config == null) throw new IllegalArgumentException("config is null");
        if (port <= 0) throw new IllegalArgumentException("port must be > 0");
        this.config = config;
        this.port = port;
        Runtime.getRuntime().addShutdownHook(new Thread(this::releaseLock, "cdp-lock-release"));
    }

    public Config config() { return config; }
    public int port() { return port; }

    public synchronized void start(boolean headless) throws IOException { start(headless, false, false); }

    public synchronized void start(boolean headless, boolean ensureLogin) throws IOException { start(headless, ensureLogin, false); }

    public synchronized void start(boolean headless, boolean ensureLogin, boolean allowReuse) throws IOException {
        this.allowReuseSession = allowReuse;
        if (chromeProcess != null && chromeProcess.isAlive()) return;
        if (lock == null) acquireLock();
        try { doStart(headless, ensureLogin, allowReuse); }
        catch (IOException ex) { releaseLock(); throw ex; }
    }

    private void doStart(boolean headless, boolean ensureLogin, boolean allowReuse) throws IOException {
        Path profile = ChromeProcessLauncher.resolveProfilePath();
        String activeExec = config.getCdpExecutorTabId();
        String activeRev = config.getCdpReviewerTabId();
        boolean hasStored = !activeExec.isEmpty() || !activeRev.isEmpty();

        PortOwner owner = ChromeProcessLauncher.inspectPort(port);

        if (allowReuse && hasStored && owner.kind() == PortOwner.Kind.FRAMEWORK) {
            CdpTabInfo storedTab = findTabOnPort(activeExec, activeRev);
            if (storedTab != null && probeAdoptedLogin(storedTab)) {
                AppLogger.info("CdpBootstrap.start: reusing existing framework Chrome (stored active tab present)");
                this.chromeProcess = null;
                this.session = new CdpSession(port);
                this.loginHandler = new LoginFallbackHandler(port, profile.toString());
                ChromeProcessLauncher.killFrameworkChromiumProcesses(profile, owner.pid());
                return;
            }
            if (storedTab != null) AppLogger.info("CdpBootstrap.start: adopted Chrome not logged in; restarting fresh");
            config.setCdpExecutorTabId("");
            config.setCdpReviewerTabId("");
        }

        if (owner.kind() == PortOwner.Kind.FOREIGN) {
            throw new PortBusyException(
                    "Port " + port + " held by non-framework process (PID " + owner.pid() + ")",
                    owner.pid(), owner.commandLine());
        }

        int killed = ChromeProcessLauncher.killFrameworkChromiumProcesses(profile, 0L);
        if (killed > 0) AppLogger.info("CdpBootstrap.start: killed " + killed + " orphan framework chrome process(es)");
        try { Thread.sleep(500L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        owner = ChromeProcessLauncher.inspectPort(port);
        if (owner.kind() == PortOwner.Kind.FOREIGN) {
            throw new PortBusyException(
                    "Port " + port + " held by non-framework process (PID " + owner.pid() + ")",
                    owner.pid(), owner.commandLine());
        }
        if (owner.kind() == PortOwner.Kind.FRAMEWORK) {
            ChromeProcessLauncher.killPidTree(owner.pid());
            try { Thread.sleep(500L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        chromeProcess = ChromeProcessLauncher.start(headless, port);
        CdpHttpClient http = new CdpHttpClient(port);
        if (!http.waitForPort(PORT_WAIT_MS)) throw new IOException("CDP port " + port + " did not open within " + PORT_WAIT_MS + " ms");
        this.session = new CdpSession(port);
        this.loginHandler = new LoginFallbackHandler(port, profile.toString());
        if (headless && ensureLogin) ensureLoggedInOrRelogin();
    }

    private CdpTabInfo findTabOnPort(String execId, String revId) {
        try {
            CdpHttpClient http = new CdpHttpClient(port);
            for (CdpTabInfo t : http.list()) {
                if (execId != null && !execId.isEmpty() && execId.equals(t.id())) return t;
                if (revId != null && !revId.isEmpty() && revId.equals(t.id())) return t;
            }
        } catch (Exception e) { AppLogger.warn("CdpBootstrap.findTabOnPort: " + e.getMessage()); }
        return null;
    }

    private boolean probeAdoptedLogin(CdpTabInfo tab) {
        CdpSession probeSession = null;
        CdpDeepSeek probe = null;
        try {
            probeSession = new CdpSession(port);
            probe = new CdpDeepSeek(probeSession, null, tab);
            return probe.checkLoggedIn();
        } catch (Exception e) {
            AppLogger.warn("CdpBootstrap.probeAdoptedLogin: " + e.getMessage());
            return false;
        } finally {
            if (probe != null) {
                TabSession ts = probe.activeTab();
                if (ts != null) { try { ts.close(); } catch (Exception ignored) { } }
            }
            if (probeSession != null) { try { probeSession.close(); } catch (Exception ignored) { } }
        }
    }

    private synchronized void ensureLoggedInOrRelogin() throws IOException {
        try {
            CdpDeepSeek probe = new CdpDeepSeek(session, loginHandler);
            probe.open();
            boolean loggedIn = probe.checkLoggedIn();
            try { probe.closeActiveTab(); } catch (Exception ignored) { }
            if (loggedIn) {
                AppLogger.info("CdpBootstrap: session valid, no relogin needed");
                return;
            }
            AppLogger.info("CdpBootstrap: session missing, restarting headful Chrome for login (port " + port + ")");
            stopChromeAndSession();
            PortOwner preHeadfulOwner = ChromeProcessLauncher.inspectPort(port);
            AppLogger.info("CdpBootstrap.ensureLoggedInOrRelogin: port owner before headful = " + preHeadfulOwner.kind() + " pid=" + preHeadfulOwner.pid());
            int hfKilled = ChromeProcessLauncher.killFrameworkChromiumProcesses(ChromeProcessLauncher.resolveProfilePath(), 0L);
            if (hfKilled > 0) AppLogger.info("CdpBootstrap.ensureLoggedInOrRelogin: extra kill before headful = " + hfKilled);
            AppLogger.info("CdpBootstrap.ensureLoggedInOrRelogin: starting headful Chrome (port " + port + ")");
            chromeProcess = ChromeProcessLauncher.start(false, port);
            CdpHttpClient http = new CdpHttpClient(port);
            if (!http.waitForPort(PORT_WAIT_MS)) throw new IOException("Headful Chrome did not open on port " + port);
            session = new CdpSession(port);
            CdpDeepSeek headfulProbe = new CdpDeepSeek(session, loginHandler);
            headfulProbe.open();
            long deadline = System.currentTimeMillis() + LOGIN_WAIT_MS;
            long lastLog = System.currentTimeMillis();
            boolean loggedInAfterWait = false;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5_000L);
                boolean stillOk = headfulProbe.checkLoggedIn();
                if (stillOk) { loggedInAfterWait = true; break; }
                long now = System.currentTimeMillis();
                if (now - lastLog >= 5_000L) {
                    AppLogger.info("CdpBootstrap: still waiting for login (port " + port + ") loggedIn=" + stillOk);
                    lastLog = now;
                }
            }
            stopChromeAndSession();
            if (!loggedInAfterWait) throw new IOException("Login wait timed out after " + LOGIN_WAIT_MS + " ms");
            AppLogger.info("CdpBootstrap: login restored, restarting headless Chrome (port " + port + ")");
            PortOwner preHeadlessOwner = ChromeProcessLauncher.inspectPort(port);
            AppLogger.info("CdpBootstrap.ensureLoggedInOrRelogin: port owner before headless restore = " + preHeadlessOwner.kind() + " pid=" + preHeadlessOwner.pid());
            int hlKilled = ChromeProcessLauncher.killFrameworkChromiumProcesses(ChromeProcessLauncher.resolveProfilePath(), 0L);
            if (hlKilled > 0) AppLogger.info("CdpBootstrap.ensureLoggedInOrRelogin: extra kill before headless restore = " + hlKilled);
            AppLogger.info("CdpBootstrap.ensureLoggedInOrRelogin: starting headless restore (port " + port + ")");
            chromeProcess = ChromeProcessLauncher.start(true, port);
            http = new CdpHttpClient(port);
            if (!http.waitForPort(PORT_WAIT_MS)) throw new IOException("Headless restart did not open port " + port);
            session = new CdpSession(port);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Login flow interrupted");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Login flow failed: " + e.getMessage(), e);
        }
    }

    private void stopChromeAndSession() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        long deadPid = 0L;
        try { if (chromeProcess != null) deadPid = chromeProcess.pid(); } catch (Exception ignored) { }
        try { if (chromeProcess != null) ChromeProcessLauncher.stop(chromeProcess); } catch (Exception ignored) { }
        chromeProcess = null;
        int remaining = ChromeProcessLauncher.killFrameworkChromiumProcesses(ChromeProcessLauncher.resolveProfilePath(), 0L);
        if (remaining > 0) {
            AppLogger.info("CdpBootstrap.stopChromeAndSession: killed " + remaining + " leftover chrome process(es) after stop (launcher pid=" + deadPid + ")");
        } else {
            AppLogger.info("CdpBootstrap.stopChromeAndSession: no leftover framework chrome (launcher pid=" + deadPid + ")");
        }
        Path lockPath = ChromeProcessLauncher.resolveProfilePath().resolve("SingletonLock");
        if (Files.exists(lockPath)) {
            AppLogger.info("CdpBootstrap.stopChromeAndSession: SingletonLock present before sleep; forcing delete");
            try { Files.deleteIfExists(lockPath); } catch (Exception ex) { AppLogger.warn("stopChromeAndSession: SingletonLock delete failed: " + ex.getMessage()); }
        } else {
            AppLogger.info("CdpBootstrap.stopChromeAndSession: SingletonLock clean");
        }
        try { Thread.sleep(2000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    public synchronized CdpDeepSeek openExecutor() throws Exception {
        ensureStarted();
        String stored = config.getCdpExecutorTabId();
        if (allowReuseSession && !stored.isEmpty()) {
            CdpTabInfo existing = findTabById(stored);
            if (existing != null) {
                AppLogger.info("CdpBootstrap.openExecutor: adopting stored tab " + stored);
                executor = new CdpDeepSeek(session, loginHandler, existing);
                executorTabInfo = existing;
                session.activateTab(existing);
                return executor;
            }
            config.setCdpExecutorTabId("");
        }
        if (executor != null || executorTabInfo != null) closeExecutor();
        executor = openRole();
        executorTabInfo = executor.activeTabInfo();
        if (executorTabInfo != null) {
            session.activateTab(executorTabInfo);
            config.setCdpExecutorTabId(executorTabInfo.id());
        }
        return executor;
    }

    public synchronized CdpDeepSeek openReviewer() throws Exception {
        ensureStarted();
        String stored = config.getCdpReviewerTabId();
        if (allowReuseSession && !stored.isEmpty()) {
            CdpTabInfo existing = findTabById(stored);
            if (existing != null) {
                AppLogger.info("CdpBootstrap.openReviewer: adopting stored tab " + stored);
                reviewer = new CdpDeepSeek(session, loginHandler, existing);
                reviewerTabInfo = existing;
                session.activateTab(existing);
                return reviewer;
            }
            config.setCdpReviewerTabId("");
        }
        if (reviewer != null || reviewerTabInfo != null) closeReviewer();
        reviewer = openRole();
        reviewerTabInfo = reviewer.activeTabInfo();
        if (reviewerTabInfo != null) {
            session.activateTab(reviewerTabInfo);
            config.setCdpReviewerTabId(reviewerTabInfo.id());
        }
        return reviewer;
    }

    private CdpTabInfo findTabById(String id) {
        if (id == null || id.isEmpty() || session == null) return null;
        for (CdpTabInfo t : session.listTabs()) if (id.equals(t.id())) return t;
        return null;
    }

    private CdpDeepSeek openRole() throws Exception {
        CdpDeepSeek ds = new CdpDeepSeek(session, loginHandler);
        ds.open();
        ds.waitForInput(INPUT_WAIT_MS);
        return ds;
    }

    public synchronized void closeExecutor() {
        CdpTabInfo tab = executorTabInfo; executorTabInfo = null;
        CdpDeepSeek ex = executor; executor = null;
        if (tab != null && session != null) { try { session.closeTab(tab); } catch (Exception ignored) { } }
        if (ex != null) { try { ex.closeActiveTab(); } catch (Exception ignored) { } }
        config.setCdpExecutorTabId("");
    }

    public synchronized void closeReviewer() {
        CdpTabInfo tab = reviewerTabInfo; reviewerTabInfo = null;
        CdpDeepSeek rev = reviewer; reviewer = null;
        if (tab != null && session != null) { try { session.closeTab(tab); } catch (Exception ignored) { } }
        if (rev != null) { try { rev.closeActiveTab(); } catch (Exception ignored) { } }
        config.setCdpReviewerTabId("");
    }

    public synchronized void stop() {
        try { closeReviewer(); } catch (Exception ignored) { }
        try { closeExecutor(); } catch (Exception ignored) { }
        try {
            if (session != null && chromeProcess != null) {
                CdpHttpClient http = new CdpHttpClient(port);
                int forcedClosed = 0;
                List<CdpTabInfo> remaining = http.list();
                for (CdpTabInfo t : remaining) {
                    String url = t.url() == null ? "" : t.url();
                    if (url.contains(DEEPSEEK_HOST)) { if (http.closeTab(t.id())) forcedClosed++; }
                }
                if (forcedClosed > 0) AppLogger.info("CdpBootstrap.stop: force-closed " + forcedClosed + " leftover deepseek tab(s)");
                else AppLogger.info("CdpBootstrap.stop: no leftover deepseek tabs");
            } else if (session != null) {
                AppLogger.info("CdpBootstrap.stop: adopted Chrome left running (no owned process)");
            }
        } catch (Exception e) {
            AppLogger.warn("CdpBootstrap.stop: leftover scan failed: " + e.getMessage());
        } finally {
            if (session != null) { try { session.close(); } catch (Exception ignored) { } session = null; }
            if (chromeProcess != null) { try { ChromeProcessLauncher.stop(chromeProcess); } catch (Exception ignored) { } chromeProcess = null; }
            releaseLock();
        }
    }

    public synchronized boolean isReviewerTabOpen() {
        if (reviewerTabInfo == null || session == null) return false;
        String id = reviewerTabInfo.id();
        for (CdpTabInfo t : session.listTabs()) if (id.equals(t.id())) return true;
        return false;
    }

    public synchronized boolean isExecutorTabOpen() {
        if (executorTabInfo == null || session == null) return false;
        String id = executorTabInfo.id();
        for (CdpTabInfo t : session.listTabs()) if (id.equals(t.id())) return true;
        return false;
    }

    public synchronized void activateExecutor() {
        if (executorTabInfo != null && session != null) session.activateTab(executorTabInfo);
    }

    public synchronized void activateReviewer() {
        if (reviewerTabInfo != null && session != null) session.activateTab(reviewerTabInfo);
    }

    public CdpDeepSeek executor() { return executor; }
    public CdpDeepSeek reviewer() { return reviewer; }

    public synchronized CdpTabInfo executorTabInfo() { return executorTabInfo; }
    public synchronized CdpTabInfo reviewerTabInfo() { return reviewerTabInfo; }

    private void ensureStarted() throws IOException {
        if (session == null) throw new IOException("CdpBootstrap is not started; call start() first");
    }

    private void acquireLock() throws IOException {
        Path lockPath = Path.of(LOCK_FILE).toAbsolutePath().normalize();
        Files.createDirectories(lockPath.getParent());
        FileChannel ch = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock l = ch.tryLock();
        if (l == null) {
            String holder = "";
            try { holder = Files.readString(lockPath).trim(); } catch (Exception ignored) { }
            try { ch.close(); } catch (Exception ignored) { }
            throw new IOException("Another Deepthic instance holds " + LOCK_FILE + " (PID " + holder + "). Close it first.");
        }
        ch.truncate(0L);
        ch.write(java.nio.ByteBuffer.wrap((String.valueOf(ProcessHandle.current().pid()) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)));
        ch.force(true);
        lockChannel = ch;
        lock = l;
    }

    private void releaseLock() {
        try { if (lock != null) lock.release(); } catch (Exception ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) { }
        try { Files.deleteIfExists(Path.of(LOCK_FILE)); } catch (Exception ignored) { }
        lock = null;
        lockChannel = null;
    }

    public static void killOrphan(int port) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "netstat -ano | findstr :" + port)
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append((char)10);
            }
            p.waitFor(5, TimeUnit.SECONDS);
            Set<Long> pids = new LinkedHashSet<>();
            for (String line : sb.toString().split("\\R")) {
                String t = line.trim();
                if (!t.contains("LISTENING")) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 5) continue;
                try { pids.add(Long.parseLong(parts[parts.length - 1])); } catch (NumberFormatException ignored) { }
            }
            for (Long pid : pids) {
                try { new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                        .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }
}

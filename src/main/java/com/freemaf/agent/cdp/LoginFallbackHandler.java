
package com.freemaf.agent.cdp;

import com.freemaf.agent.AppLogger;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Handles DeepSeek login fallback: when the persistent profile loses the
 * Google session, headless Chrome is stopped, a headful window is opened,
 * the user logs in manually, and headless is restored.
 */
public final class LoginFallbackHandler {

    static final long POLL_INTERVAL_MS = 5000L;
    private static final long PROGRESS_LOG_INTERVAL_MS = 30_000L;

    private final int port;
    private final String profilePath;
    private final ChromeLauncherFacade launcher;

    public LoginFallbackHandler(int port, String profilePath) {
        this(port, profilePath, new DefaultChromeLauncherFacade());
    }

    public LoginFallbackHandler(int port, String profilePath, ChromeLauncherFacade launcher) {
        this.port = port;
        this.profilePath = profilePath == null ? "" : profilePath;
        this.launcher = launcher == null ? new DefaultChromeLauncherFacade() : launcher;
    }

    public boolean ensureLoggedIn(Supplier<Boolean> checkLoggedIn, long loginWaitMs) throws Exception {
        if (checkLoggedIn == null) {
            throw new IllegalArgumentException("checkLoggedIn must not be null");
        }
        if (Boolean.TRUE.equals(checkLoggedIn.get())) {
            AppLogger.info("LoginFallbackHandler: session valid, no restart (port " + port + ")");
            return true;
        }
        AppLogger.info("LoginFallbackHandler: session missing, starting headful Chrome (port " + port + ")");
        Process headful = null;
        boolean loggedIn = false;
        try {
            headful = launcher.start(false, port);
            long deadline = System.currentTimeMillis() + loginWaitMs;
            long lastProgressLog = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS);
                if (Boolean.TRUE.equals(checkLoggedIn.get())) {
                    loggedIn = true;
                    break;
                }
                long now = System.currentTimeMillis();
                if (now - lastProgressLog >= PROGRESS_LOG_INTERVAL_MS) {
                    AppLogger.info("LoginFallbackHandler: still waiting for login (port " + port + ")");
                    lastProgressLog = now;
                }
            }
        } finally {
            if (headful != null) {
                launcher.stop(headful);
            }
        }
        if (!loggedIn) {
            AppLogger.warn("LoginFallbackHandler: login wait timed out (port " + port + ")");
            return false;
        }
        AppLogger.info("LoginFallbackHandler: login detected, restarting headless Chrome (port " + port + ")");
        try {
            launcher.start(true, port);
        } catch (IOException e) {
            AppLogger.warn("LoginFallbackHandler: headless restart failed: " + e.getMessage());
            return false;
        }
        return true;
    }

    public int port() {
        return port;
    }

    public String profilePath() {
        return profilePath;
    }
}

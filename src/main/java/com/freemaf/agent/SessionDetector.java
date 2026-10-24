
package com.freemaf.agent;

import com.freemaf.agent.winapi.WinApiService;
import java.util.List;

public final class SessionDetector {

    private SessionDetector() {}

    public enum Decision {
        OPEN_NEW_CHAT,
        CONTINUE_OLD_SESSION,
        RESUME_REVIEWER_SESSION,
        RECOVER_NEW_EXECUTOR
    }

    public record SessionDecision(
            Decision decision,
            WindowInfo activeWindow,
            WindowInfo executorWindow,
            WindowInfo reviewerWindow,
            String recoveryMessage) {

        public boolean isContinuation() {
            return decision == Decision.CONTINUE_OLD_SESSION;
        }
    }

    public static SessionDecision detect(String titleSubstring, Config config) {
        String lastPhase = config.getLastPhase();
        long lastManagedHwnd = config.getLastManagedHwnd();
        long lastReviewerHwnd = config.getLastReviewerHwnd();
        List<Long> ignored = config.getIgnoredHwnds();
        WindowInfo executorWindow = WindowFinder.findByHwnd(lastManagedHwnd, titleSubstring).orElse(null);
        WindowInfo reviewerWindow = WindowFinder.findByHwnd(lastReviewerHwnd, titleSubstring).orElse(null);
        if (reviewerWindow != null && ignored.contains(reviewerWindow.hwndValue())) {
            reviewerWindow = null;
        }
        boolean reviewerPhase = "JUDGE".equals(lastPhase) || "CODE_REVIEWER".equals(lastPhase);
        if (!reviewerPhase) {
            if (reviewerWindow != null) {
                AppLogger.info("SessionDetector: closing orphan reviewer hwnd=" + reviewerWindow.hwndValue());
                WinApiService.closeWindow(reviewerWindow.hwndValue());
                reviewerWindow = null;
            }
            if (executorWindow != null) {
                return new SessionDecision(Decision.CONTINUE_OLD_SESSION, executorWindow, executorWindow, null, null);
            }
            return new SessionDecision(Decision.OPEN_NEW_CHAT, null, null, null, null);
        }
        if (executorWindow != null && reviewerWindow != null) {
            String msg = buildResumeMessage(lastPhase);
            return new SessionDecision(Decision.RESUME_REVIEWER_SESSION, reviewerWindow, executorWindow, reviewerWindow, msg);
        }
        // RECOVER_NEW_EXECUTOR: close whichever window survived, to avoid orphan windows
        // (AUD-05 part 1). SessionDecision is intentionally NOT extended (N-20).
        if (reviewerWindow != null) {
            AppLogger.info("SessionDetector: RECOVER_NEW_EXECUTOR closing reviewer hwnd=" + reviewerWindow.hwndValue());
            WinApiService.closeWindow(reviewerWindow.hwndValue());
            reviewerWindow = null;
        }
        if (executorWindow != null) {
            AppLogger.info("SessionDetector: RECOVER_NEW_EXECUTOR closing surviving executor hwnd=" + executorWindow.hwndValue());
            WinApiService.closeWindow(executorWindow.hwndValue());
        }
        return new SessionDecision(Decision.RECOVER_NEW_EXECUTOR, null, executorWindow, null, RECOVER_MESSAGE);
    }

    private static String buildResumeMessage(String phase) {
        if ("JUDGE".equals(phase)) {
            return "Предыдущая сессия была прервана во время проверки (роль: JUDGE). Окно проверяющего сохранено. Продолжи проверку с того же места либо полностью повтори последнее действие.";
        }
        return "Предыдущая сессия была прервана во время проверки (роль: CODE_REVIEWER). Окно проверяющего сохранено. Продолжи проверку с того же места либо полностью повтори последнее действие.";
    }

    private static final String RECOVER_MESSAGE =
            "Предыдущая сессия была прервана. Последний запрос проверки завершился ошибкой при выполнении, проверка не произошла. Ошибка не на твоей стороне, ничего срочно исправлять не нужно. Запроси проверку заново.";
}

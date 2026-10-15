
package com.freemaf.agent;



import com.freemaf.agent.winapi.WinApiService;
import com.sun.jna.Pointer;

import javax.swing.SwingUtilities;

import java.io.IOException;

import java.io.File;

import java.nio.charset.StandardCharsets;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.ArrayList;

import java.util.List;



public final class AutomationLoop implements Runnable {



    private final ChatConsole chatConsole;

    private final ConsolePanel psConsolePanel;

    private final StatusMarker statusMarker;

    private final PromptQueue promptQueue;

    private final DeepSeekSession deepSeekSession;

    private final PowerShellExecutor powerShellExecutor;

    private final Config config;

    private final WindowManager windowManager;

    private Pointer executorHwnd;

    private volatile boolean executorContextRequired = true;

    private Pointer reviewerHwnd;

    private String activeReviewerRole = null;

    private String pendingReviewerRole = null;

    private final List<Pointer> excludes = new ArrayList<>();

    private volatile boolean paused = false;

    private final Object pauseMonitor = new Object();

    private volatile boolean awaitingMigrationConfirm = false;
    private volatile boolean warningInjected = false;
    private volatile String pendingPauseOutput = null;



    public AutomationLoop(ChatConsole chatConsole, ConsolePanel psConsolePanel,

                          StatusMarker statusMarker, PromptQueue promptQueue,

                          DeepSeekSession deepSeekSession, PowerShellExecutor powerShellExecutor,

                          Config config, WindowManager windowManager) {

        this.chatConsole = chatConsole;

        this.psConsolePanel = psConsolePanel;

        this.statusMarker = statusMarker;

        this.promptQueue = promptQueue;

        this.deepSeekSession = deepSeekSession;

        this.powerShellExecutor = powerShellExecutor;

        this.config = config;

        this.windowManager = windowManager;

        this.executorHwnd = windowManager.hwnd();

        if (executorHwnd != null) excludes.add(executorHwnd);

    }



    public SessionDetector.SessionDecision bootstrapSession() throws Exception {

        SessionDetector.SessionDecision d = SessionDetector.detect(config.getWindowTitle(), config);

        switch (d.decision()) {

            case CONTINUE_OLD_SESSION -> {

                this.executorHwnd = d.executorWindow().hwnd();

                if (!excludes.contains(executorHwnd)) excludes.add(executorHwnd);

                windowManager.select(d.executorWindow());

                windowManager.activate();

                config.setLastPhase("EXECUTOR");

                debug("bootstrap: CONTINUE_OLD_SESSION hwnd=" + Pointer.nativeValue(executorHwnd));

            }

            case OPEN_NEW_CHAT -> {

                appendSystem("Opening new Chrome window for executor...");

                WindowInfo w = ChromeLauncher.launchNewWindow(config, excludes);

                this.executorHwnd = w.hwnd();

                if (!excludes.contains(executorHwnd)) excludes.add(executorHwnd);

                windowManager.select(w);
                w = applyWorkRectToNewWindow(w);
                this.executorHwnd = w.hwnd();
                if (!excludes.contains(this.executorHwnd)) excludes.add(this.executorHwnd);
                windowManager.activate();
                config.setLastManagedHwnd(w.hwndValue());

                config.setLastManagedTitle(w.title());

                config.setLastPhase("EXECUTOR");

                config.setLastReviewerHwnd(0);

                config.setLastReviewerTitle("");

                config.setExecutorMessageCount(0L);
                config.setShiftActiveForHwnd(w.hwndValue(), true);
                d = new SessionDetector.SessionDecision(SessionDetector.Decision.OPEN_NEW_CHAT, w, w, null, null);
                                debug("bootstrap: OPEN_NEW_CHAT hwnd=" + Pointer.nativeValue(executorHwnd));

            }

            case RESUME_REVIEWER_SESSION -> {

                this.reviewerHwnd = d.reviewerWindow().hwnd();

                if (!excludes.contains(reviewerHwnd)) excludes.add(reviewerHwnd);

                // executorHwnd restored from SessionDecision on RESUME_REVIEWER_SESSION
                if (d.executorWindow() != null) {
                    this.executorHwnd = d.executorWindow().hwnd();
                    if (!excludes.contains(executorHwnd)) excludes.add(executorHwnd);
                } else {
                    AppLogger.warn("bootstrap RESUME_REVIEWER_SESSION: executor window is missing in SessionDecision");
                }
                this.activeReviewerRole = config.getLastPhase();

                windowManager.select(d.reviewerWindow());

                windowManager.activate();

                promptQueue.add(d.recoveryMessage());

                config.setLastReviewerHwnd(d.reviewerWindow().hwndValue());

                config.setLastReviewerTitle(d.reviewerWindow().title());

                debug("bootstrap: RESUME_REVIEWER_SESSION role=" + activeReviewerRole);

            }

            case RECOVER_NEW_EXECUTOR -> {

                appendSystem("Opening new Chrome window for executor (session recovery)...");

                WindowInfo w = ChromeLauncher.launchNewWindow(config, excludes);

                this.executorHwnd = w.hwnd();

                if (!excludes.contains(executorHwnd)) excludes.add(executorHwnd);

                windowManager.select(w);
                w = applyWorkRectToNewWindow(w);
                this.executorHwnd = w.hwnd();
                if (!excludes.contains(this.executorHwnd)) excludes.add(this.executorHwnd);
                windowManager.activate();
                config.setLastManagedHwnd(w.hwndValue());

                config.setLastManagedTitle(w.title());

                config.setLastPhase("EXECUTOR");

                config.setLastReviewerHwnd(0);

                config.setLastReviewerTitle("");

                promptQueue.add(Prompt.user(d.recoveryMessage(), List.of()));

                config.setExecutorMessageCount(0L);
                config.setShiftActiveForHwnd(w.hwndValue(), true);
                d = new SessionDetector.SessionDecision(SessionDetector.Decision.RECOVER_NEW_EXECUTOR, w, w, null, d.recoveryMessage());
                                debug("bootstrap: RECOVER_NEW_EXECUTOR hwnd=" + Pointer.nativeValue(executorHwnd));

            }

        }

        return d;

    }



    public boolean isPaused() {

        return paused;

    }



    public void resumeFromPause() {

        if (!paused) {

            AppLogger.info("resumeFromPause: not paused, ignoring");

            return;

        }

        paused = false;

        String role = activeReviewerRole != null ? activeReviewerRole : "EXECUTOR";

        String msg = "### ROLE_TO_ACT: " + role + System.lineSeparator()
                + "Пользователь вернулся без ответа на твой вопрос (пустой ответ). Ты вправе переспросить или решить вопрос самостоятельно.";

        promptQueue.add(msg);

        statusMarker.setState(StatusMarker.State.WORKING);

        synchronized (pauseMonitor) { pauseMonitor.notifyAll(); }

        AppLogger.info("resumeFromPause: queued resume for role=" + role);


    }

    public void resumeAfterUserInput() {
        if (!paused) return;
        paused = false;
        statusMarker.setState(StatusMarker.State.WORKING);
        synchronized (pauseMonitor) { pauseMonitor.notifyAll(); }
        AppLogger.info("resumeAfterUserInput: unpaused without extra prompt");
    }



    public void performSessionMigration(String commitLog) {
        debug("performSessionMigration: begin (commitLog=" + (commitLog == null ? "none" : "present") + ")");
        try {
            long oldHwnd = config.getLastManagedHwnd();
            if (oldHwnd != 0L && WinApiService.isWindow(oldHwnd)) {
                List<Long> ignored = new ArrayList<>(config.getIgnoredHwnds());
                if (!ignored.contains(oldHwnd)) ignored.add(oldHwnd);
                config.setIgnoredHwnds(ignored);
                WinApiService.closeWindow(oldHwnd);
                long deadline = System.currentTimeMillis() + 5000L;
                while (System.currentTimeMillis() < deadline && WinApiService.isWindow(oldHwnd)) {
                    Thread.sleep(200L);
                }
            }
            long oldRevHwnd = config.getLastReviewerHwnd();
            if (oldRevHwnd != 0L && WinApiService.isWindow(oldRevHwnd)) {
                List<Long> ignored = new ArrayList<>(config.getIgnoredHwnds());
                if (!ignored.contains(oldRevHwnd)) ignored.add(oldRevHwnd);
                config.setIgnoredHwnds(ignored);
                WinApiService.closeWindow(oldRevHwnd);
                long deadline = System.currentTimeMillis() + 5000L;
                while (System.currentTimeMillis() < deadline && WinApiService.isWindow(oldRevHwnd)) {
                    Thread.sleep(200L);
                }
            }
            this.reviewerHwnd = null;
            this.activeReviewerRole = null;
            this.pendingReviewerRole = null;
            this.excludes.clear();
            appendSystem("Opening new Chrome window for executor (session migration)...");
            WindowInfo w = ChromeLauncher.launchNewWindow(config, excludes);
            this.executorHwnd = w.hwnd();
            this.excludes.add(w.hwnd());
            windowManager.select(w);
            windowManager.activate();
            int wxm = config.getInt("window.work.x", 50);
            int wym = config.getInt("window.work.y", 50);
            int wwm = config.getInt("window.work.width", 731);
            int whm = config.getInt("window.work.height", 508);
            if (!windowManager.setRect(wxm, wym, wwm, whm)) {
                AppLogger.warn("performSessionMigration: setRect failed for new window");
            } else {
                AppLogger.info("performSessionMigration: window rect set to " + wxm + "," + wym + " " + wwm + "x" + whm);
            }
            Thread.sleep(200L);
            WindowInfo refreshedM = WindowFinder.findByHwnd(w.hwndValue(), config.getWindowTitle()).orElse(null);
            if (refreshedM != null) {
                w = refreshedM;
                this.executorHwnd = refreshedM.hwnd();
                AppLogger.info("performSessionMigration: geometry refreshed to "
                        + w.left() + "," + w.top() + " "
                        + (w.right() - w.left()) + "x" + (w.bottom() - w.top()));
            } else {
                AppLogger.warn("performSessionMigration: could not refresh window geometry, using stale rect");
            }
            config.setLastManagedHwnd(w.hwndValue());
            config.setLastManagedTitle(w.title());
            config.setLastPhase("EXECUTOR");
            config.setLastReviewerHwnd(0);
            config.setLastReviewerTitle("");
            config.setExecutorMessageCount(0);
            config.setShiftActiveForHwnd(w.hwndValue(), true);
            StringBuilder init = new StringBuilder();
            if (commitLog != null && !commitLog.isBlank()) {
                init.append("Результат завершающего коммита:").append(System.lineSeparator());
                init.append(commitLog).append(System.lineSeparator()).append(System.lineSeparator());
            }
            init.append("Продолжи работу по WIP.md и MEMORY.md. Контекст передан в пакете.");
            promptQueue.add(Prompt.user(init.toString(), List.of()));
            awaitingMigrationConfirm = false;
            warningInjected = false;
            executorContextRequired = true;
            debug("performSessionMigration: done, new hwnd=" + w.hwndValue());
        } catch (Exception e) {
            AppLogger.warn("performSessionMigration failed: " + e.getMessage());
            appendSystem("Session migration failed: " + e.getMessage());
            statusMarker.setState(StatusMarker.State.STOPPED);
        }
    }


    long touchExecutorCounterIfActive() {
        long current = config.getExecutorMessageCount();
        if (activeReviewerRole != null) return current;
        long next = current + 1;
        config.setExecutorMessageCount(next);
        return next;
    }

    String injectMigrationWarningIfNeeded(String prompt) {
        if (activeReviewerRole != null) return prompt;
        if (awaitingMigrationConfirm) return prompt;
        if (config.getExecutorMessageCount() < 100) return prompt;
        if (warningInjected) return prompt;
        String warn = "Счётчик сообщений Executor достиг 100. Пора начинать миграцию в новую сессию: подготовь WIP.md, отправь PS-скрипт с одной строкой: Write-Output \"AGENT_SESSION_MIGRATE_START: <текст>\", затем дождись лога и отправь ТОЛЬКО строку маркера: AGENT_SESSION_MIGRATE_CONFIRM: <текст> без скрипта и без обёртки.";
        warningInjected = true;
        debug("injected migration warning (counter=" + config.getExecutorMessageCount() + ")");
        return warn + System.lineSeparator() + System.lineSeparator() + prompt;
    }

    boolean detectMigrationHalt(List<MarkerEvent> events, String responseRole) {
        boolean isAgentStop = events.stream().anyMatch(ev -> ev.type() == MarkerType.AGENT_STOP);
        return isAgentStop
                && "EXECUTOR".equals(responseRole)
                && config.getExecutorMessageCount() >= 100
                && warningInjected;
    }

    void recognizeMigrationStart(List<MarkerEvent> events, String responseRole) {
        boolean startEvent = events.stream().anyMatch(ev -> ev.type() == MarkerType.AGENT_SESSION_MIGRATE_START);
        if (!startEvent) return;
        if ("EXECUTOR".equals(responseRole)) {
            awaitingMigrationConfirm = true;
            debug("AGENT_SESSION_MIGRATE_START recognized, awaiting CONFIRM");
        } else {
            AppLogger.warn("AGENT_SESSION_MIGRATE_START from non-EXECUTOR role [" + responseRole + "], ignored");
        }
    }

    boolean isAwaitingMigrationConfirm() { return awaitingMigrationConfirm; }

    boolean isWarningInjected() { return warningInjected; }
    private WindowInfo applyWorkRectToNewWindow(WindowInfo w) throws InterruptedException {
        int wx = config.getInt("window.work.x", 50);
        int wy = config.getInt("window.work.y", 50);
        int ww = config.getInt("window.work.width", 731);
        int wh = config.getInt("window.work.height", 508);
        if (!windowManager.setRect(wx, wy, ww, wh)) {
            AppLogger.warn("applyWorkRectToNewWindow: setRect failed for hwnd=" + w.hwndValue());
        } else {
            AppLogger.info("applyWorkRectToNewWindow: rect set to " + wx + "," + wy + " " + ww + "x" + wh);
        }
        Thread.sleep(200L);
        WindowInfo refreshed = WindowFinder.findByHwnd(w.hwndValue(), config.getWindowTitle()).orElse(null);
        if (refreshed != null) {
            AppLogger.info("applyWorkRectToNewWindow: geometry refreshed to "
                    + refreshed.left() + "," + refreshed.top() + " "
                    + (refreshed.right() - refreshed.left()) + "x"
                    + (refreshed.bottom() - refreshed.top()));
            return refreshed;
        }
        AppLogger.warn("applyWorkRectToNewWindow: could not refresh geometry, using stale rect");
        return w;
    }
    private void persistExecutorState() {

        if (executorHwnd == null) return;

        long h = Pointer.nativeValue(executorHwnd);

        config.setLastManagedHwnd(h);

        WindowFinder.findByHwnd(h, config.getWindowTitle()).ifPresent(w -> config.setLastManagedTitle(w.title()));

    }



    private void persistReviewerState(String role) {

        config.setLastPhase(role);

        if (reviewerHwnd != null) {

            long h = Pointer.nativeValue(reviewerHwnd);

            config.setLastReviewerHwnd(h);

            WindowFinder.findByHwnd(h, config.getWindowTitle()).ifPresent(w -> config.setLastReviewerTitle(w.title()));

        }

    }



    private void persistExecutorPhase() {

        config.setLastPhase("EXECUTOR");

        config.setLastReviewerHwnd(0);

        config.setLastReviewerTitle("");

    }



    private void debug(String line) {

        AppLogger.info(line);

        if (config.getBoolean("debug.show_in_chat", true)) {

            SwingUtilities.invokeLater(() -> chatConsole.appendDebug(line));

        }

    }



    public void resetExecutorContext() {

        // executorHwnd refreshed from WindowManager after a recovery handoff
        Pointer currentExecutor = windowManager.hwnd();
        if (currentExecutor != null) {
            this.executorHwnd = currentExecutor;
            if (!excludes.contains(currentExecutor)) excludes.add(currentExecutor);
            AppLogger.info("AutomationLoop: executorHwnd refreshed from WindowManager hwnd=" + Pointer.nativeValue(currentExecutor));
        }
        this.executorContextRequired = true;

        AppLogger.info("AutomationLoop: executorContextRequired reset to true");

    }



    private void deleteDeliveryScript() {

        try {

            boolean deleted = Files.deleteIfExists(Path.of("script.ps1"));

            if (deleted) AppLogger.info("script.ps1 deleted after execution");

        } catch (IOException e) {

            AppLogger.warn("Failed to delete script.ps1: " + e.getMessage());

        }

    }



    private void closeReviewerIfOpen() throws InterruptedException {

        if (reviewerHwnd == null) return;

        Pointer target = reviewerHwnd;

        reviewerHwnd = null;

        excludes.remove(target);

        WindowInfo wi = new WindowInfo(target, "", 0, 0, 0, 0);

        ChromeLauncher.closeWindow(wi);

        persistExecutorPhase();

    }



    private void switchToExecutor() throws InterruptedException {

        closeReviewerIfOpen();

        if (executorHwnd != null) {

            windowManager.selectByHwnd(executorHwnd);

            windowManager.activate();

            Thread.sleep(200L);

        }

    }



    private void openReviewerWindow(String role) throws Exception {

        closeReviewerIfOpen();

        appendSystem("Opening new Chrome window for review role...");

        WindowInfo win = deepSeekSession.openNewWindow(excludes);

        reviewerHwnd = win.hwnd();

        excludes.add(reviewerHwnd);
        config.setShiftActiveForHwnd(win.hwndValue(), true);

        debug("opened reviewer window hwnd=" + Pointer.nativeValue(reviewerHwnd)

                + " title=" + win.title());

        persistReviewerState(role);

    }



    @Override

    public void run() {

        debug("AutomationLoop started, executorHwnd="

                + (executorHwnd == null ? "null" : Pointer.nativeValue(executorHwnd)));

        while (!EmergencyStop.isStopped()) {

            synchronized (pauseMonitor) {

                while (paused && !EmergencyStop.isStopped()) {

                    try {

                        pauseMonitor.wait(500L);

                    } catch (InterruptedException e) {

                        Thread.currentThread().interrupt();

                        return;

                    }

                }

            }

            if (EmergencyStop.isStopped()) return;



            Prompt incoming;

            try {

                incoming = promptQueue.take();

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                return;

            }



            String prompt = incoming.text();

            List<File> incomingFiles = incoming.files();

            String savedPause = pendingPauseOutput;
            if (savedPause != null && !savedPause.isBlank()) {
                AppLogger.info("prepending paused-script output (" + savedPause.length() + " chars) to the next prompt");
                prompt = savedPause + System.lineSeparator() + System.lineSeparator() + prompt;
                pendingPauseOutput = null;
            }



            try {

                statusMarker.setState(StatusMarker.State.WORKING);



                if (pendingReviewerRole != null) {

                    openReviewerWindow(pendingReviewerRole);

                    activeReviewerRole = pendingReviewerRole;

                    pendingReviewerRole = null;

                    prompt = SystemInstructionProvider.getExecutorRules()

                            + System.lineSeparator() + System.lineSeparator() + prompt;

                } else if (activeReviewerRole == null) {

                    switchToExecutor();

                }



                if (incoming.userInitiated()) {

                    StringBuilder wrapped = new StringBuilder();

                    if (activeReviewerRole == null && executorContextRequired) {

                        wrapped.append(SystemInstructionProvider.getExecutorRules());

                        wrapped.append(System.lineSeparator()).append(System.lineSeparator())

                                .append("### CONTEXT").append(System.lineSeparator());

                        wrapped.append(new ContextPackager().buildContext());

                        wrapped.append(System.lineSeparator()).append(System.lineSeparator());

                        executorContextRequired = false;

                    }

                    wrapped.append(prompt);

                    wrapped.append(System.lineSeparator()).append(System.lineSeparator())

                            .append(SystemInstructionProvider.getFormatReminder());

                    prompt = wrapped.toString();

                    debug("wrapped user prompt (files=" + incoming.files().size() + ")");

                }



                debug("send prompt (executorHwnd="

                        + (executorHwnd == null ? "null" : Pointer.nativeValue(executorHwnd))

                        + ", reviewerHwnd="

                        + (reviewerHwnd == null ? "null" : Pointer.nativeValue(reviewerHwnd))

                        + ") length=" + prompt.length());

                prompt = injectMigrationWarningIfNeeded(prompt);
                deepSeekSession.setAwaitingMigrationConfirm(awaitingMigrationConfirm);
                AgentResponse response = deepSeekSession.getValidResponse(prompt, incomingFiles);

                String responseRole = response.role();

                debug("response role=[" + responseRole + "] type=" + response.type());



                if (response.type() == AgentResponse.Type.USER_CHAT
                        && "AGENT_SESSION_MIGRATE_CONFIRM_ACK".equals(response.content())) {
                    if (!awaitingMigrationConfirm) {
                        debug("ignoring AGENT_SESSION_MIGRATE_CONFIRM without preceding START");
                        continue;
                    }
                    debug("AGENT_SESSION_MIGRATE_CONFIRM accepted, performing migration");
                    awaitingMigrationConfirm = false;
                    performSessionMigration(null);
                    continue;
                }

                touchExecutorCounterIfActive();
                if (response.type() == AgentResponse.Type.USER_CHAT) {

                    chatConsole.appendAgent("[" + responseRole + "] " + response.content());

                    statusMarker.setState(StatusMarker.State.PAUSED);

                    continue;

                }



                String psScript = response.content();

                try {

                    ScriptSaver.save(psScript);

                } catch (IOException e) {

                    chatConsole.appendSystem("Failed to save script.ps1 - " + e.getMessage());

                    promptQueue.add(buildDiagnostic("Could not save script.ps1: " + e.getMessage()));

                    continue;

                }



                psConsolePanel.append("[run] script.ps1");

                ExecutionResult result;

                try {

                    result = powerShellExecutor.execute(Path.of("script.ps1"),

                            line -> SwingUtilities.invokeLater(() -> psConsolePanel.append(line)));

                } catch (IOException e) {

                    deleteDeliveryScript();

                    chatConsole.appendSystem("Failed to run script.ps1 - " + e.getMessage());

                    promptQueue.add(buildDiagnostic("Could not execute script.ps1: " + e.getMessage()));

                    continue;

                }



                deleteDeliveryScript();

                if (EmergencyStop.isStopped()) {

                    debug("EmergencyStop detected after script execution; skipping further processing");

                    statusMarker.setState(StatusMarker.State.STOPPED);

                    return;

                }



                List<MarkerEvent> events = MarkerParser.parse(result.stdout());

                String scriptRole = MarkerProcessor.extractRole(events);

                if (!scriptRole.isEmpty()) responseRole = scriptRole;

                debug("effective role=[" + responseRole + "]");



                // === awaitingMigrationConfirm takes absolute precedence ===
                if (awaitingMigrationConfirm) {
                    boolean confirmEvent2 = events.stream().anyMatch(ev -> ev.type() == MarkerType.AGENT_SESSION_MIGRATE_CONFIRM);
                    if (confirmEvent2) {
                        debug("AGENT_SESSION_MIGRATE_CONFIRM recognized in script output, performing migration");
                        awaitingMigrationConfirm = false;
                        warningInjected = false;
                        performSessionMigration(null);
                        continue;
                    }
                    debug("awaitingMigrationConfirm: response has no CONFIRM, re-requesting");
                    StringBuilder nag = new StringBuilder();
                    nag.append("### ROLE_TO_ACT: EXECUTOR").append(System.lineSeparator());
                    nag.append("Ты в состоянии ожидания подтверждения миграции сессии.").append(System.lineSeparator());
                    nag.append("Ответь ровно ОДНИМ fenced PowerShell-блоком. Внутри блока — ровно одна строка:").append(System.lineSeparator());
                    nag.append("Write-Output \"AGENT_SESSION_MIGRATE_CONFIRM: <короткий текст>\"").append(System.lineSeparator());
                    nag.append("Ничего до блока и после блока. Никаких USER_MSG и AGENT_PAUSE. Без fenced-блока ответ не копируется и не принимается.").append(System.lineSeparator());
                    promptQueue.add(nag.toString());
                    continue;
                }
                MarkerProcessResult mp = MarkerProcessor.process(events, chatConsole, statusMarker);

                recognizeMigrationStart(events, responseRole);

                boolean confirmEvent = events.stream().anyMatch(ev -> ev.type() == MarkerType.AGENT_SESSION_MIGRATE_CONFIRM);
                if (confirmEvent && config.getExecutorMessageCount() >= 100) {
                    debug("AGENT_SESSION_MIGRATE_CONFIRM recognized in script output, performing migration");
                    warningInjected = false;
                    awaitingMigrationConfirm = false;
                    performSessionMigration(null);
                    continue;
                }



                boolean analystDone = "ANALYST".equals(responseRole)

                        && mp == MarkerProcessResult.HALT

                        && events.stream().anyMatch(ev -> ev.type() == MarkerType.AGENT_DONE);



                if (analystDone) {

                    statusMarker.setState(StatusMarker.State.WORKING);

                    StringBuilder plp = new StringBuilder();

                    plp.append("### ROLE_TO_ACT: PLANNER").append(System.lineSeparator());

                    plp.append("ANALYST завершил анализ, окружение подготовлено.").append(System.lineSeparator());

                    plp.append("Первая строка ответа: [PLANNER]. Первая строка PS-блока: Write-Output \"ROLE: PLANNER\" в кавычках.").append(System.lineSeparator());

                    plp.append("Составь план в agent/project/PLAN.md через PS-скрипт, шаг за шагом.").append(System.lineSeparator());

                    plp.append("Не используй AGENT_PAUSE. В конце — Write-Output \"USER_MSG: План сохранён\".").append(System.lineSeparator());

                    promptQueue.add(plp.toString());

                    debug("routed ANALYST(AGENT_DONE) -> PLANNER");

                    continue;

                }

                if ("ANALYST".equals(responseRole)
                        && mp != MarkerProcessResult.HALT
                        && mp != MarkerProcessResult.PAUSE) {
                    StringBuilder anp = new StringBuilder();
                    anp.append("### ROLE_TO_ACT: ANALYST").append(System.lineSeparator());
                    anp.append("Твой ответ не содержал ни AGENT_DONE, ни AGENT_PAUSE.").append(System.lineSeparator());
                    anp.append("Определись:").append(System.lineSeparator());
                    anp.append("- Если анализ завершён, отправь: Write-Output \"AGENT_DONE: <итог>\".").append(System.lineSeparator());
                    anp.append("- Если нужен ответ пользователя, отправь: Write-Output \"USER_MSG: <вопрос>\" затем Write-Output \"AGENT_PAUSE: ожидание ответа\".").append(System.lineSeparator());
                    anp.append("Не продолжай работу без одного из этих маркеров.").append(System.lineSeparator());
                    promptQueue.add(anp.toString());
                    debug("routed ANALYST(no DONE/PAUSE) -> self with instruction");
                    continue;
                }


                if (mp == MarkerProcessResult.HALT) {

                    boolean isMigrationHalt = detectMigrationHalt(events, responseRole);
                    if (isMigrationHalt) {
                        debug("absorbing old AGENT_STOP, migrating at counter=" + config.getExecutorMessageCount());
                        warningInjected = false;
                        awaitingMigrationConfirm = false;
                        String commitLog = LogReporter.build(result);
                        performSessionMigration(commitLog);
                        continue;
                    }
                    try { switchToExecutor(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    return;
                }



                String userMsgs = joinUserMsgs(events);

                int outputLimit = config.getInt("ps.output.limit_chars", 60000);

                boolean outputTooLarge = result.stdout() != null && result.stdout().length() > outputLimit;

                String scriptLog;

                if (outputTooLarge) {

                    appendSystem("PS output exceeded limit " + outputLimit + " chars (stdout="

                            + result.stdout().length() + "); notifying agent, log suppressed.");

                    scriptLog = buildOutputLimitWarning(result, outputLimit);

                } else {

                    scriptLog = LogReporter.build(result);

                }

                scriptLog = formatFollowUp(result, scriptLog);



                                if (mp == MarkerProcessResult.PAUSE) {
                    String filteredPause = filterPauseOutput(scriptLog);
                    if (filteredPause != null && !filteredPause.isBlank()) {
                        pendingPauseOutput = filteredPause;
                        AppLogger.info("AGENT_PAUSE: saved filtered script output (" + filteredPause.length() + " chars) for next prompt");
                    }
                    appendSystem("Agent paused: " + pauseMessage(events));
                    paused = true;
                    statusMarker.setState(StatusMarker.State.PAUSED);
                    synchronized (pauseMonitor) { pauseMonitor.notifyAll(); }
                    debug("honoring AGENT_PAUSE for role " + responseRole + " (unconditional)");
                    continue;
                }



                if ("PLANNER".equals(responseRole)) {

                    String planContent = readFileOrEmpty("agent/project/PLAN.md");

                    StringBuilder jp = new StringBuilder();

                    jp.append("### ROLE_TO_ACT: JUDGE").append(System.lineSeparator());

                    jp.append("PLANNER закончил. Проверь план.").append(System.lineSeparator());

                    jp.append("Первая строка ответа: [JUDGE]. Первая строка PS-блока: Write-Output ROLE: JUDGE в кавычках.").append(System.lineSeparator());

                    jp.append("В USER_MSG выведи либо PLAN_APPROVED, либо PLAN_REJECTED: причина.").append(System.lineSeparator());

                    jp.append("PLAN.md приведён ниже в этом же промпте. Используй его напрямую. Если нужны другие файлы — сгенерируй PS-скрипт с Get-Content -Raw -Encoding UTF8 и прочитай их. Не отвечай \"не вижу контента\".").append(System.lineSeparator());

                    jp.append(System.lineSeparator());

                    jp.append("### PLAN.md").append(System.lineSeparator());

                    jp.append(planContent);

                    promptQueue.add(jp.toString());

                    pendingReviewerRole = "JUDGE";

                    debug("routed PLANNER -> JUDGE(plan), pendingReviewerRole=JUDGE");

                    continue;

                }



                if ("JUDGE".equals(responseRole)) {

                    boolean hasPlanVerdict = userMsgs.contains("PLAN_APPROVED") || userMsgs.contains("PLAN_REJECTED");

                    boolean hasResultVerdict = userMsgs.contains("RESULT_APPROVED") || userMsgs.contains("RESULT_REJECTED");

                    if (!hasPlanVerdict && !hasResultVerdict) {

                        StringBuilder cont = new StringBuilder();

                        cont.append("### ROLE_TO_ACT: JUDGE").append(System.lineSeparator());

                        cont.append("Продолжи проверку. Ниже — вывод твоего предыдущего PS-скрипта.").append(System.lineSeparator());

                        cont.append("Если нужно — сгенерируй следующий PS-скрипт. Когда закончишь, вынеси вердикт в USER_MSG строго с токеном PLAN_APPROVED/PLAN_REJECTED/RESULT_APPROVED/RESULT_REJECTED.").append(System.lineSeparator());

                        cont.append(System.lineSeparator());

                        cont.append("### EXECUTION_LOG").append(System.lineSeparator()).append(scriptLog);

                        promptQueue.add(cont.toString());

                        debug("JUDGE exploration round, keeping reviewer window");

                        continue;

                    }

                    activeReviewerRole = null;

                    String verdictTag = hasPlanVerdict ? "JUDGE_VERDICT_PLAN" : "JUDGE_VERDICT_RESULT";

                    StringBuilder ep = new StringBuilder();

                    ep.append("### ROLE_TO_ACT: EXECUTOR").append(System.lineSeparator());

                    ep.append("### ").append(verdictTag).append(System.lineSeparator());

                    ep.append(userMsgs).append(System.lineSeparator()).append(System.lineSeparator());

                    ep.append("Ты — EXECUTOR. Первая строка ответа: [EXECUTOR]. Первая строка PS-блока: Write-Output ROLE: EXECUTOR в кавычках.").append(System.lineSeparator());

                    ep.append("Действуй по вердикту:").append(System.lineSeparator());

                    ep.append("- PLAN_REJECTED: перепиши план в agent/project/PLAN.md, затем USER_MSG: READY_FOR_PLAN_REVIEW.").append(System.lineSeparator());

                    ep.append("- PLAN_APPROVED: приступай к выполнению плана.").append(System.lineSeparator());

                    ep.append("- RESULT_REJECTED: исправь ошибки, запусти сборку, затем USER_MSG: READY_FOR_RESULT_REVIEW.").append(System.lineSeparator());

                    ep.append("- RESULT_APPROVED: USER_MSG: READY_FOR_CODE_REVIEW.").append(System.lineSeparator());

                    ep.append(System.lineSeparator());

                    ep.append("### EXECUTION_LOG").append(System.lineSeparator()).append(scriptLog);

                    promptQueue.add(ep.toString());

                    debug("routed JUDGE -> EXECUTOR (" + verdictTag + ")");

                    continue;

                }



                if ("CODE_REVIEWER".equals(responseRole)) {

                    boolean hasCodeVerdict = userMsgs.contains("CODE_APPROVED") || userMsgs.contains("CODE_REJECTED");

                    if (!hasCodeVerdict) {

                        StringBuilder cont = new StringBuilder();

                        cont.append("### ROLE_TO_ACT: CODE_REVIEWER").append(System.lineSeparator());

                        cont.append("Продолжи проверку. Ниже — вывод твоего предыдущего PS-скрипта.").append(System.lineSeparator());

                        cont.append("Если нужно — сгенерируй следующий PS-скрипт. Когда закончишь, вынеси вердикт в USER_MSG строго с токеном CODE_APPROVED/CODE_REJECTED.").append(System.lineSeparator());

                        cont.append(System.lineSeparator());

                        cont.append("### EXECUTION_LOG").append(System.lineSeparator()).append(scriptLog);

                        promptQueue.add(cont.toString());

                        debug("CODE_REVIEWER exploration round, keeping reviewer window");

                        continue;

                    }

                    activeReviewerRole = null;

                    StringBuilder ep = new StringBuilder();

                    ep.append("### ROLE_TO_ACT: EXECUTOR").append(System.lineSeparator());

                    ep.append("### CODE_REVIEW_VERDICT").append(System.lineSeparator());

                    ep.append(userMsgs).append(System.lineSeparator()).append(System.lineSeparator());

                    ep.append("Ты — EXECUTOR. Первая строка ответа: [EXECUTOR]. Первая строка PS-блока: Write-Output ROLE: EXECUTOR в кавычках.").append(System.lineSeparator());

                    ep.append("Действуй по вердикту:").append(System.lineSeparator());

                    ep.append("- CODE_REJECTED: исправь и снова USER_MSG: READY_FOR_CODE_REVIEW.").append(System.lineSeparator());

                    ep.append("- CODE_APPROVED: задай пользователю вопрос о приёмке и коммите:").append(System.lineSeparator());

                    ep.append("  Write-Output USER_MSG: Работа готова. Принимаете? Разрешаете коммит изменений в git?").append(System.lineSeparator());

                    ep.append("  Write-Output AGENT_PAUSE: ожидание решения пользователя о приёмке и коммите.").append(System.lineSeparator());

                    ep.append(System.lineSeparator());

                    ep.append("### EXECUTION_LOG").append(System.lineSeparator()).append(scriptLog);

                    promptQueue.add(ep.toString());

                    debug("routed CODE_REVIEWER -> EXECUTOR");

                    continue;

                }



                if ("EXECUTOR".equals(responseRole)) {

                    if (userMsgs.contains("READY_FOR_RESULT_REVIEW")) {

                        StringBuilder jp = new StringBuilder();

                        jp.append("### ROLE_TO_ACT: JUDGE").append(System.lineSeparator());

                        jp.append("EXECUTOR завершил этап. Проверь результат.").append(System.lineSeparator());

                        jp.append("Первая строка ответа: [JUDGE]. Первая строка PS-блока: Write-Output ROLE: JUDGE в кавычках.").append(System.lineSeparator());

                        jp.append("В USER_MSG выведи либо RESULT_APPROVED, либо RESULT_REJECTED: причина.").append(System.lineSeparator());

                        jp.append("Если для проверки тебе нужны PLAN.md, WIP.md или список файлов — сгенерируй PS-скрипт с Get-Content -Raw -Encoding UTF8 и прочитай их сам в следующей итерации. Не отвечай \"не вижу контента\" — сгенерируй PS-скрипт.").append(System.lineSeparator());

                        jp.append(System.lineSeparator());

                        jp.append("### EXECUTION_LOG").append(System.lineSeparator()).append(scriptLog);

                        promptQueue.add(jp.toString());

                        pendingReviewerRole = "JUDGE";

                        debug("routed EXECUTOR(READY_FOR_RESULT_REVIEW) -> JUDGE(result), pendingReviewerRole=JUDGE");

                        continue;

                    }

                    if (userMsgs.contains("READY_FOR_CODE_REVIEW")) {

                        StringBuilder cp = new StringBuilder();

                        cp.append("### ROLE_TO_ACT: CODE_REVIEWER").append(System.lineSeparator());

                        cp.append("Проведи код-ревью. Опирайся на git-diff, а не на слова исполнителя.").append(System.lineSeparator());

                        cp.append("Первая строка ответа: [CODE_REVIEWER]. Первая строка PS-блока: Write-Output ROLE: CODE_REVIEWER в кавычках.").append(System.lineSeparator());

                        cp.append("Проверяй ТОЛЬКО файлы проекта пользователя. script.ps1 — временный контейнер доставки, приложение удаляет его сразу после выполнения; НИКОГДА не упоминай его в ревью, не открывай, не ищи в git status.").append(System.lineSeparator());

                        cp.append("Если репозиторий инициализирован: git log --oneline, git status --short, git diff HEAD.").append(System.lineSeparator());

                        cp.append("Если нет — читай ключевые файлы исходников через Get-Content -Raw -Encoding UTF8 и оценивай код как написанный с нуля.").append(System.lineSeparator());

                        cp.append("Если для оценки нужно прочитать дополнительные файлы — сгенерируй PS-скрипт с Get-Content. Не отвечай \"не вижу контента\".").append(System.lineSeparator());

                        cp.append("В USER_MSG выведи либо CODE_APPROVED, либо CODE_REJECTED: причина.").append(System.lineSeparator());

                        cp.append(System.lineSeparator());

                        cp.append("### EXECUTION_LOG").append(System.lineSeparator()).append(scriptLog);

                        promptQueue.add(cp.toString());

                        pendingReviewerRole = "CODE_REVIEWER";

                        debug("routed EXECUTOR(READY_FOR_CODE_REVIEW) -> CODE_REVIEWER, pendingReviewerRole=CODE_REVIEWER");

                        continue;

                    }

                    if (userMsgs.contains("READY_FOR_PLAN_REVIEW")) {

                        String planContent = readFileOrEmpty("agent/project/PLAN.md");

                        StringBuilder jp = new StringBuilder();

                        jp.append("### ROLE_TO_ACT: JUDGE").append(System.lineSeparator());

                        jp.append("PLANNER закончил. Проверь план.").append(System.lineSeparator());

                        jp.append("Первая строка ответа: [JUDGE]. Первая строка PS-блока: Write-Output ROLE: JUDGE в кавычках.").append(System.lineSeparator());

                        jp.append("В USER_MSG выведи либо PLAN_APPROVED, либо PLAN_REJECTED: причина.").append(System.lineSeparator());

                        jp.append("PLAN.md приведён ниже в этом же промпте. Используй его напрямую. Если нужны другие файлы — сгенерируй PS-скрипт с Get-Content -Raw -Encoding UTF8. Не отвечай \"не вижу контента\".").append(System.lineSeparator());

                        jp.append(System.lineSeparator());

                        jp.append("### PLAN.md").append(System.lineSeparator()).append(planContent);

                        promptQueue.add(jp.toString());

                        pendingReviewerRole = "JUDGE";

                        debug("routed EXECUTOR(READY_FOR_PLAN_REVIEW) -> JUDGE(plan), pendingReviewerRole=JUDGE");

                        continue;

                    }

                }



                String followUp = PromptBuilder.buildFollowUp(

                        SystemInstructionProvider.getFormatReminder(), scriptLog);

                if (awaitingMigrationConfirm) {
                    followUp = followUp + System.lineSeparator() + System.lineSeparator()
                            + "После проверки лога отправь ровно ОДИН fenced PowerShell-блок, внутри ровно одна строка: Write-Output \"AGENT_SESSION_MIGRATE_CONFIRM: <короткий текст>\". Ничего до блока и после блока. Никаких USER_MSG и AGENT_PAUSE. Без fenced-блока ответ не копируется и не принимается.";
                }
                promptQueue.add(followUp);

                if ("EXECUTOR".equals(responseRole)) persistExecutorState();

                debug("routed " + responseRole + " -> EXECUTOR");



            } catch (IllegalStateException e) {
                if (EmergencyStop.isStopped()) {
                    statusMarker.setState(StatusMarker.State.STOPPED);
                    return;
                }
                if (awaitingMigrationConfirm) {
                    debug("getValidResponse failed while awaiting CONFIRM: " + e.getMessage() + " - re-requesting");
                    StringBuilder nag2 = new StringBuilder();
                    nag2.append("### ROLE_TO_ACT: EXECUTOR").append(System.lineSeparator());
                    nag2.append("Ты в состоянии ожидания подтверждения миграции сессии.").append(System.lineSeparator());
                    nag2.append("Ответь ровно ОДНИМ fenced PowerShell-блоком. Внутри блока — ровно одна строка:").append(System.lineSeparator());
                    nag2.append("Write-Output \"AGENT_SESSION_MIGRATE_CONFIRM: <короткий текст>\"").append(System.lineSeparator());
                    nag2.append("Ничего до блока и после блока. Никаких USER_MSG и AGENT_PAUSE. Без fenced-блока ответ не копируется и не принимается.").append(System.lineSeparator());
                    promptQueue.add(nag2.toString());
                    continue;
                }
                chatConsole.appendSystem("Agent paused: " + e.getMessage());
                statusMarker.setState(StatusMarker.State.PAUSED);
                continue;
            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                return;

            } catch (Exception e) {

                chatConsole.appendSystem("Error - " + e.getMessage());

                statusMarker.setState(StatusMarker.State.STOPPED);

                return;

            }

        }

    }



    private static String filterPauseOutput(String log) {
        if (log == null || log.isBlank()) return null;
        StringBuilder sb = new StringBuilder();
        for (String line : log.split("\\R")) {
            String t = line.stripLeading();
            if (t.startsWith("USER_MSG:") || t.startsWith("AGENT_PAUSE:")
                    || t.startsWith("AGENT_STOP:") || t.startsWith("AGENT_DONE:")) {
                continue;
            }
            if (sb.length() > 0) sb.append(System.lineSeparator());
            sb.append(line);
        }
        String out = sb.toString().strip();
        return out.isEmpty() ? null : out;
    }

    private static String pauseMessage(List<MarkerEvent> events) {

        for (MarkerEvent e : events) {

            if (e.type() == MarkerType.AGENT_PAUSE) return e.message();

        }

        return "";

    }



    private static String joinUserMsgs(List<MarkerEvent> events) {

        StringBuilder sb = new StringBuilder();

        for (MarkerEvent e : events) {

            if (e.type() == MarkerType.USER_MSG) {

                if (sb.length() > 0) sb.append(" | ");

                sb.append(e.message());

            }

        }

        return sb.toString();

    }



    private String readFileOrEmpty(String path) {

        try {

            return Files.readString(Path.of(path), StandardCharsets.UTF_8);

        } catch (IOException e) {

            AppLogger.warn("readFileOrEmpty failed for " + path + ": " + e.getMessage());

            return "(файл не найден: " + path + ")";

        }

    }



    private void appendSystem(String line) {

        SwingUtilities.invokeLater(() -> chatConsole.appendSystem(line));

    }



    private static String buildOutputLimitWarning(ExecutionResult r, int limit) {

        int so = r.stdout() == null ? 0 : r.stdout().length();

        return "### EXECUTION_OUTPUT_LIMIT_EXCEEDED" + System.lineSeparator()

                + "stdout=" + so + " chars, limit=" + limit + " chars." + System.lineSeparator()

                + "Разбей запрос вывода на несколько этапов или перепроверь корректность запроса." + System.lineSeparator()

                + "- Не запрашивай содержимое целых папок." + System.lineSeparator()

                + "- Сначала получи список файлов с размерами: Get-ChildItem -Recurse -File | Select-Object FullName, Length." + System.lineSeparator()

                + "- Затем выборочно читай нужные файлы (1-3 за раз) через Get-Content -Raw -Encoding UTF8 <путь>." + System.lineSeparator()

                + "- Файлы в папке agent/ недоступны агентам — не запрашивай их." + System.lineSeparator();

    }



    private static String buildDiagnostic(String reason) {

        return "### EXECUTION_ERROR" + System.lineSeparator() + reason + System.lineSeparator()

                + "### EXIT_CODE" + System.lineSeparator() + "-1";

    }



    private static final long FOLLOWUP_WARN_THRESHOLD_SECONDS = 1200L;

    private static final long FOLLOWUP_TIMEOUT_SECONDS = 1800L;



    static String formatFollowUp(ExecutionResult result, String log) {

        if (result.isTimedOut()) {

            return "Скрипт остановлен по лимиту 30 минут. Ниже лог выполнения до момента остановки:"

                    + System.lineSeparator() + log;

        }

        long elapsed = result.getElapsedSeconds();

        if (elapsed >= FOLLOWUP_WARN_THRESHOLD_SECONDS && elapsed <= FOLLOWUP_TIMEOUT_SECONDS) {

            return log + System.lineSeparator()

                    + "Напоминание: время выполнения одного скрипта не должно превышать 30 минут. Затратные задачи дробите на этапы.";

        }

        return log;

    }

}


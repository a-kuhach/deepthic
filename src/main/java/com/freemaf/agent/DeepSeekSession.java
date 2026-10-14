
package com.freemaf.agent;

import java.util.Random;

import java.io.File;

import java.util.List;

import java.util.function.Consumer;

public final class DeepSeekSession {

    private final Config config;

    private final InputSimulator inputSimulator;

    private final ClipboardService clipboardService;

    private final PixelColorService pixelColorService;

    private final WindowManager windowManager;

    private final Random random = new Random();

    private volatile Consumer<String> debugSink;

    private volatile WindowRecoveryHandler windowRecoveryHandler;

    private volatile boolean regenOffsetActive = false;

    private volatile boolean awaitingMigrationConfirm = false;

    public DeepSeekSession(Config config, InputSimulator inputSimulator, ClipboardService clipboardService,

                           PixelColorService pixelColorService, WindowManager windowManager) {

        this.config = config;

        this.inputSimulator = inputSimulator;

        this.clipboardService = clipboardService;

        this.pixelColorService = pixelColorService;

        this.windowManager = windowManager;

    }

    public void setDebugSink(Consumer<String> sink) { this.debugSink = sink; }

    public void setWindowRecoveryHandler(WindowRecoveryHandler handler) { this.windowRecoveryHandler = handler; }

    public void setAwaitingMigrationConfirm(boolean flag) { this.awaitingMigrationConfirm = flag; }

    private void debug(String line) {

        AppLogger.info(line);

        Consumer<String> sink = debugSink;

        if (sink != null) sink.accept(line);

    }

    public WindowInfo openNewWindow(java.util.List<com.sun.jna.Pointer> excludes) throws Exception {

        WindowInfo win = ChromeLauncher.launchNewWindow(config, excludes);

        windowManager.select(win);

        int wx = config.getInt("window.work.x", 50);

        int wy = config.getInt("window.work.y", 50);

        int ww = config.getInt("window.work.width", 731);

        int wh = config.getInt("window.work.height", 473);

        windowManager.setRect(wx, wy, ww, wh);

        AppLogger.info("openNewWindow: waiting for INPUT_FIELD (5 attempts x 10 s)");
        boolean inputReady = false;
        for (int attempt = 1; attempt <= 5 && !inputReady; attempt++) {
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline) {
                if (EmergencyStop.isStopped()) throw new IllegalStateException("Открытие нового окна прервано");
                AutoCalibrator.MarkerHit hit = AutoCalibrator.findInputField(win, pixelColorService);
                if (hit != null) {
                    AppLogger.info("openNewWindow: INPUT_FIELD found at " + hit.x() + "," + hit.y()
                            + " #" + hit.observedColorHex() + " on attempt " + attempt);
                    inputReady = true;
                    break;
                }
                Thread.sleep(500L);
            }
            if (!inputReady) {
                AppLogger.info("openNewWindow: INPUT_FIELD not detected in 10 s, refreshing page (attempt " + attempt + ")");
                inputSimulator.refreshPage();
                Thread.sleep(3000L);
            }
        }
        if (!inputReady) {
            ChromeLauncher.closeWindow(win);
            throw new IllegalStateException("Новый чат не удалось подготовить после 5 попыток");
        }
        ChromeLauncher.closeWindow(win);

        throw new IllegalStateException("Новый чат не удалось подготовить после 5 попыток");

    }

    public AgentResponse getValidResponse(String prompt) throws Exception {

        return getValidResponse(prompt, List.of());

    }

    public AgentResponse getValidResponse(String prompt, List<File> files) throws Exception {

        int maxCorrections = config.getInt("max_retries", 3);

        int maxRegenAttempts = 3;

        String originalPrompt = prompt;

        String currentPrompt = prompt;

        int regenCount = 0;

        int correctionCount = 0;

        boolean needToSend = true;

        boolean firstSend = true;

        while (true) {

            if (needToSend) {

                if (firstSend) {

                    sendPrompt(currentPrompt, files);

                    firstSend = false;

                } else {

                    sendPrompt(currentPrompt);

                }

            }

            String rawResponse = waitForGenerationAndCopy();

            HistoryService.savePrompt(currentPrompt);

            HistoryService.saveResponse(rawResponse);

            AgentResponse parsed = tryParse(rawResponse, originalPrompt);

            if (parsed != null) return parsed;

            if (regenCount < maxRegenAttempts) {

                regenCount++;

                debug("regenerate click " + regenCount + "/" + maxRegenAttempts);

                AutoCalibrator.MarkerHit regenHit = inputSimulator.requireHit(CalibrationMarker.REGENERATE);

                int regOffset = config.getInt("marker.regenerate.dx_offset", 103);

                int clickX = regenHit.x() + (regenOffsetActive ? regOffset : 0);

                inputSimulator.click(clickX, regenHit.y());

                regenOffsetActive = true;

                Thread.sleep(1000L);

                needToSend = false;

                continue;

            }

            correctionCount++;

            if (correctionCount > maxCorrections) break;

            debug("correction prompt attempt " + correctionCount + "/" + maxCorrections);

            currentPrompt = originalPrompt + System.lineSeparator() + System.lineSeparator()

                    + "### CORRECTION" + System.lineSeparator()

                    + "Предыдущий ответ был неверного формата." + System.lineSeparator()

                    + "Ответь заново на исходную задачу." + System.lineSeparator()

                    + PromptBuilder.FORMAT_REMINDER;

            regenCount = 0;

            needToSend = true;

        }

        throw new IllegalStateException("Ответ агента не распознан после " + maxCorrections + " корректирующих запросов");

    }

    private AgentResponse tryParse(String rawResponse, String originalPrompt) {

        if (rawResponse == null) return null;

        if (rawResponse.equals(originalPrompt)) {

            debug("format: clipboard returned the sent prompt, ignoring");

            return null;

        }

        String role = ResponseParser.extractRole(rawResponse);

        if (ResponseParser.isUserChat(rawResponse)) {

            return AgentResponse.userChat(role, ResponseParser.extractUserChat(rawResponse));

        }

        if (awaitingMigrationConfirm && ResponseParser.isValidMigrationConfirm(rawResponse)) {
            return AgentResponse.userChat(role, "AGENT_SESSION_MIGRATE_CONFIRM_ACK");
        }
        if (ResponseParser.isValidScript(rawResponse)) {

            return AgentResponse.script(role, ResponseParser.extractScript(rawResponse));

        }

        debug("format: response did not match accepted forms (role=" + role + ")");

        return null;

    }

    public void sendPrompt(String prompt) throws InterruptedException {

        sendPrompt(prompt, List.of());

    }

    public void sendPrompt(String prompt, List<File> files) throws InterruptedException {

        ensureWindow();

        regenOffsetActive = false;

        if (files != null && !files.isEmpty()) {

            inputSimulator.typeFilesAndTextViaClipboard(files, prompt);

        } else {

            inputSimulator.typeTextViaClipboard(prompt);

        }

        AppLogger.info("Prompt sent to DeepSeek Web (files=" + (files == null ? 0 : files.size()) + ")");

    }

    public void waitForGenerationCompletion() throws InterruptedException {

        Thread.sleep(1000L);

        AutoCalibrator.MarkerHit regenHit = inputSimulator.requireHit(CalibrationMarker.REGENERATE);

        int regX = regenHit.x();

        int regY = regenHit.y();

        int regExpected = ColorUtils.parseHex(regenHit.observedColorHex());

        long timeout = config.getLong("timeout.generation_ms", AgentConstants.GENERATION_TIMEOUT_MS);

        long deadline = System.currentTimeMillis() + timeout;

        int retryCount = 0;

        int retryMaxAttempts = config.getInt("marker.retry.max_attempts", 10);

        long nextRetryCheck = System.currentTimeMillis() + 5000L;

        while (!EmergencyStop.isStopped()) {

            if (System.currentTimeMillis() > deadline) {

                throw new IllegalStateException("Ожидание генерации превысило " + timeout + " мс. "

                            + "retry-кнопок перегрузки сработало: " + retryCount + ". "

                            + (retryCount == 0

                                ? "Кнопка перегрузки (marker.retry) не появлялась — вероятно, нет соединения с chat.deepseek.com или окно Chrome потеряло фокус."

                                : "Кнопка перегрузки появлялась, но ответ так и не был готов.")

                            + " Продолжите, отправив сообщение в чат.");

            }

            ensureWindow();

            inputSimulator.scrollDown(3);

            if (System.currentTimeMillis() >= nextRetryCheck) {

                nextRetryCheck = System.currentTimeMillis() + 5000L;

                long hwndVal = com.sun.jna.Pointer.nativeValue(windowManager.hwnd());

                WindowInfo win = WindowFinder.findByHwnd(hwndVal, config.getWindowTitle()).orElse(null);

                if (win != null) {

                    AutoCalibrator.MarkerHit retryHit;

                    try (PixelColorService svc = new PixelColorService()) {

                        retryHit = AutoCalibrator.findRetry(win, svc);

                    }

                    if (retryHit != null) {

                        retryCount++;

                        if (retryCount > retryMaxAttempts) {

                            throw new IllegalStateException("Лимит кнопок перегрузки DeepSeek (marker.retry) исчерпан (" + retryMaxAttempts + ").");

                        }

                        long waitMs = computeRetryWaitMs(retryCount);

                        debug("Retry button detected at " + retryHit.x() + "," + retryHit.y() + " (n=" + retryCount + "), sleeping " + waitMs + " ms");

                        sleepInterruptible(waitMs);

                        if (EmergencyStop.isStopped()) throw new IllegalStateException("Ожидание прервано");

                        inputSimulator.click(retryHit.x(), retryHit.y());

                        deadline = System.currentTimeMillis() + timeout;

                        Thread.sleep(1000L);

                        continue;

                    }

                }

            }

            int regOffset = config.getInt("marker.regenerate.dx_offset", 103);

            int currentRegX = regX + (regenOffsetActive ? regOffset : 0);

            int regActual = pixelColorService.readPixel(currentRegX, regY);

            if (ColorUtils.matches(regActual, regExpected)) {

                Thread.sleep(500L);

                inputSimulator.scrollDown(5);

                Thread.sleep(500L);

                return;

            }

            Thread.sleep(150L);

        }

        throw new IllegalStateException("Ожидание генерации прервано");

    }

    private long computeRetryWaitMs(int retryCount) {

        long base = config.getLong("timeout.retry_base_ms", 300_000L);

        long jitter = config.getLong("timeout.retry_base_jitter_ms", 100_000L);

        long incBase = config.getLong("timeout.retry_increment_base_ms", 300_000L);

        long incStep = config.getLong("timeout.retry_increment_step_ms", 100_000L);

        long wait = base + (long) (random.nextDouble() * jitter);

        for (int i = 1; i <= retryCount; i++) {

            wait += incBase + incStep * i;

        }

        return wait;

    }

    private void sleepInterruptible(long ms) throws InterruptedException {

        long end = System.currentTimeMillis() + ms;

        while (System.currentTimeMillis() < end) {

            if (EmergencyStop.isStopped()) return;

            long remaining = end - System.currentTimeMillis();

            Thread.sleep(Math.max(1L, Math.min(1000L, remaining)));

        }

    }

    public String copyLastResponse() throws Exception {

        int maxAttempts = config.getInt("max_copy_retries", 3);

        AutoCalibrator.MarkerHit copyHit = inputSimulator.requireHit(CalibrationMarker.CODE_COPY);

        int copyX = copyHit.x();

        int copyY = copyHit.y();

        int copyExpected = ColorUtils.parseHex(copyHit.observedColorHex());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {

            if (EmergencyStop.isStopped()) throw new IllegalStateException("Stopped by user");

            ensureWindow();

            inputSimulator.scrollDown(5);

            Thread.sleep(500L);

            int copyActual = pixelColorService.readPixel(copyX, copyY);

            debug("copy[" + attempt + "]: copy_last=#" + ColorUtils.toHex(copyActual)

                    + " expected=#" + ColorUtils.toHex(copyExpected));

            if (!ColorUtils.matches(copyActual, copyExpected)) {

                debug("copy[" + attempt + "]: copy_last absent, clicking regenerate");

                AutoCalibrator.MarkerHit regenHit = inputSimulator.requireHit(CalibrationMarker.REGENERATE);

                int regOffset = config.getInt("marker.regenerate.dx_offset", 103);

                int clickX = regenHit.x() + (regenOffsetActive ? regOffset : 0);

                inputSimulator.click(clickX, regenHit.y());

                regenOffsetActive = true;

                Thread.sleep(1000L);

                waitForGenerationCompletion();

                continue;

            }

            String sentinel = "__FREEMAF_SENTINEL_" + System.nanoTime() + "__";

            clipboardService.setText(sentinel);

            Thread.sleep(200L);

            String pre = clipboardService.getText();

            if (!sentinel.equals(pre)) {

                debug("copy[" + attempt + "]: sentinel set failed, retrying");

                continue;

            }

            inputSimulator.click(copyX, copyY);

            Thread.sleep(500L);

            try {

                String text = clipboardService.getTextWithRetry();

                if (text != null && !text.isBlank() && !sentinel.equals(text)) return text;

            } catch (Exception e) {

                debug("copy[" + attempt + "]: read failed: " + e.getMessage());

            }

        }

        throw new IllegalStateException("Не удалось скопировать ответ после " + maxAttempts + " попыток");

    }

    public String waitForGenerationAndCopy() throws Exception {

        waitForGenerationCompletion();

        return copyLastResponse();

    }

    public void ensureWindow() throws InterruptedException {

        if (!windowManager.isWindowValid()) {

            WindowRecoveryHandler handler = windowRecoveryHandler;

            boolean recovered = handler != null && handler.recover();

            if (!recovered) throw new IllegalStateException("Окно DeepSeek Web потеряно, работа остановлена");

        }

        if (!windowManager.validateWindowRect()) {

            if (!windowManager.restoreWindowRect()) {

                throw new IllegalStateException("Окно DeepSeek Web не удалось вернуть в рабочее положение");

            }

        }

    }

}

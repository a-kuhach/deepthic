
package com.freemaf.agent;

import com.freemaf.agent.winapi.WinApiService;

import java.io.File;

import java.util.List;

public final class InputSimulator {

    private final java.util.Random speedRandom = new java.util.Random();

    private volatile SpeedMode speedMode = SpeedMode.INSTANT;

    private volatile long lastEnterAt = 0L;

    public static final int VK_CONTROL = 0x11;

    public static final int VK_A = 0x41;

    public static final int VK_C = 0x43;

    public static final int VK_V = 0x56;

    public static final int VK_ENTER = 0x0D;

    public static final int VK_F5 = 0x74;

    private final Config config;

    private final ClipboardService clipboardService;

    private WindowManager windowManager;

    private PixelColorService pixelColorService;

    private volatile WaitRetryCallback waitRetryCallback;

    public InputSimulator(Config config, ClipboardService clipboardService) {

        this.config = config;

        this.clipboardService = clipboardService;

    }

    public void setWindowManager(WindowManager windowManager) { this.windowManager = windowManager; }

    public void setPixelColorService(PixelColorService pixelColorService) { this.pixelColorService = pixelColorService; }

    public void setSpeedMode(SpeedMode m) { this.speedMode = m; }

    public SpeedMode getSpeedMode() { return speedMode; }

    public void moveMouse(int x, int y) {

        RateLimiter.pause();

        moveMouseRaw(x, y);

    }

    public void moveMouseRaw(int x, int y) {

        int sw = WinApiService.USER32.GetSystemMetrics(WinApiService.SM_CXSCREEN);

        int sh = WinApiService.USER32.GetSystemMetrics(WinApiService.SM_CYSCREEN);

        int dx = Math.max(0, Math.min(65535, (int) ((long) x * 65535 / Math.max(1, sw - 1))));

        int dy = Math.max(0, Math.min(65535, (int) ((long) y * 65535 / Math.max(1, sh - 1))));

        WinApiService.MOUSEINPUT mi = new WinApiService.MOUSEINPUT();

        mi.dx = dx; mi.dy = dy; mi.mouseData = 0;

        mi.dwFlags = WinApiService.MOUSEEVENTF_MOVE | WinApiService.MOUSEEVENTF_ABSOLUTE;

        mi.time = 0; mi.dwExtraInfo = null;

        sendMouse(mi);

    }

    public void scrollDown(int notches) throws InterruptedException {

        if (windowManager == null) return;

        windowManager.activate();

        Thread.sleep(150L);

        int wx = config.getInt("window.work.x", 50);

        int wy = config.getInt("window.work.y", 50);

        int wh = config.getInt("window.work.height", 473);

        int cx = wx + 22;

        int cy = wy + wh / 2;

        moveMouseRaw(cx, cy);

        Thread.sleep(80L);

        moveMouseRaw(cx - 2, cy);

        Thread.sleep(40L);

        moveMouseRaw(cx, cy);

        Thread.sleep(80L);

        if (!windowManager.isForeground()) {

            AppLogger.info("scrollDown: chrome not foreground, re-activating");

            windowManager.activate();

            Thread.sleep(150L);

        }

        for (int i = 0; i < notches; i++) {

            sendWheel(-120);

            Thread.sleep(40L);

        }

    }

    private void sendWheel(int delta) {

        WinApiService.MOUSEINPUT mi = new WinApiService.MOUSEINPUT();

        mi.dx = 0; mi.dy = 0; mi.mouseData = delta;

        mi.dwFlags = WinApiService.MOUSEEVENTF_WHEEL;

        mi.time = 0; mi.dwExtraInfo = null;

        sendMouse(mi);

    }

    public void leftClick() {

        RateLimiter.pause();

        WinApiService.MOUSEINPUT down = new WinApiService.MOUSEINPUT();

        down.dwFlags = WinApiService.MOUSEEVENTF_LEFTDOWN; down.dx = 0; down.dy = 0;

        down.mouseData = 0; down.time = 0; down.dwExtraInfo = null;

        sendMouse(down);

        WinApiService.MOUSEINPUT up = new WinApiService.MOUSEINPUT();

        up.dwFlags = WinApiService.MOUSEEVENTF_LEFTUP; up.dx = 0; up.dy = 0;

        up.mouseData = 0; up.time = 0; up.dwExtraInfo = null;

        sendMouse(up);

    }

    public void click(int x, int y) {

        if (x <= 0 || y <= 0) {

            throw new IllegalStateException("Attempt to click at (x=" + x + ", y=" + y

                    + "). Marker has zero coordinates; run Calibration first.");

        }

        activateChrome();

        moveMouse(x, y);

        leftClick();

    }

    public void pressKeys(int... vkCodes) {

        RateLimiter.pause();

        for (int vk : vkCodes) sendKey((short) vk, false);

        for (int i = vkCodes.length - 1; i >= 0; i--) sendKey((short) vkCodes[i], true);

    }

    public void ctrlA() { pressKeys(VK_CONTROL, VK_A); }

    public void ctrlC() { pressKeys(VK_CONTROL, VK_C); }

    public void ctrlV() { pressKeys(VK_CONTROL, VK_V); }

    private int inputAreaY(String markerKey) {

        int base = config.getInt(markerKey + ".y", 0);

        if (config.getSendShiftActive()) {

            base += config.getInputAreaDyOffset();

        }

        return base;

    }

    public void enter() {

        pressKeys(VK_ENTER);

        try { config.setSendShiftActive(false); }

        catch (RuntimeException e) { AppLogger.warn("sendShiftActive persist failed: " + e.getMessage()); }

    }

    public void refreshPage() throws InterruptedException {

        activateChrome();

        pressKeys(VK_F5);

    }

    public interface WaitRetryCallback { boolean shouldRetry(String reason); }

    public void setWaitRetryCallback(WaitRetryCallback cb) { this.waitRetryCallback = cb; }

    public void typeTextViaClipboard(String text) throws InterruptedException {

        int x = config.getInt("marker.input_field.x", 0);

        int y = inputAreaY("marker.input_field");

        if (x <= 0 || y <= 0) {

            throw new IllegalStateException("Input field marker has zero coordinates (x=" + x + ", y=" + y

                    + "). Run Calibration before starting the cycle.");

        }

        activateChrome();

        clipboardService.setText(text);

        click(x, y);

        ctrlA(); ctrlV(); applySpeedDelay(); enter(); lastEnterAt = System.currentTimeMillis();

        Thread.sleep(300L);

        clipboardService.flushClipboard();

    }

    public void typeTextWithoutEnter(String text) throws InterruptedException {

        int x = config.getInt("marker.input_field.x", 0);

        int y = inputAreaY("marker.input_field");

        if (x <= 0 || y <= 0) {

            throw new IllegalStateException("Input field marker has zero coordinates (x=" + x + ", y=" + y + ").");

        }

        activateChrome();

        clipboardService.setText(text);

        click(x, y);

        ctrlA(); ctrlV();

        Thread.sleep(300L);

    }

    public void typeFilesAndTextViaClipboard(List<File> files, String text) throws InterruptedException {

        int x = config.getInt("marker.input_field.x", 0);

        int y = inputAreaY("marker.input_field");

        if (x <= 0 || y <= 0) {

            throw new IllegalStateException("Input field marker has zero coordinates (x=" + x + ", y=" + y + ").");

        }

        activateChrome();

        boolean hasFiles = files != null && !files.isEmpty();

        boolean hasText = text != null && !text.isBlank();

        if (hasFiles) {

            click(x, y);

            clipboardService.setFiles(files);

            ctrlV();

            Thread.sleep(120L);

            waitForSendButtonState(false, 30, 3000L);

            if (!waitForUploadWithRetry()) return;

        }

        if (hasText) {

            click(x, y);

            clipboardService.setText(text);

            ctrlA(); ctrlV();

            Thread.sleep(120L);

        }

        applySpeedDelay();

        enter();

        lastEnterAt = System.currentTimeMillis();

        Thread.sleep(300L);

        clipboardService.flushClipboard();

    }

    private boolean waitForUploadWithRetry() throws InterruptedException {
        String activeColor = config.get("marker.send.active_color", "");
        String disabledColor = config.get("marker.send.disabled_color", "");
        if (activeColor != null && disabledColor != null
                && !activeColor.isBlank() && activeColor.equalsIgnoreCase(disabledColor.trim())) {
            AppLogger.warn("waitForUploadWithRetry: active_color == disabled_color ("
                    + activeColor + "); SEND-state cannot be detected, skipping wait");
            return true;
        }

        while (!EmergencyStop.isStopped()) {

            if (waitForSendButtonState(true, 100, 120000L)) return true;

            WaitRetryCallback cb = waitRetryCallback;

            if (cb == null) return false;

            if (!cb.shouldRetry("Файлы не загрузились за 2 минуты.")) return false;

        }

        return false;

    }

    private boolean waitForSendButtonState(boolean expectActive, int pollMs, long maxMs) throws InterruptedException {

        String key = expectActive ? "marker.send.active_color" : "marker.send.disabled_color";

        String target = config.get(key, "");

        int x = config.getInt("marker.send.x", 0);

        int y = inputAreaY("marker.send");

        if (x <= 0 || y <= 0 || target == null || target.isBlank()) return false;

        long deadline = System.currentTimeMillis() + maxMs;

        while (System.currentTimeMillis() < deadline) {

            if (EmergencyStop.isStopped()) return false;

            int rgb = pixelColorService.readPixel(x, y);

            if (ColorUtils.toHex(rgb).equalsIgnoreCase(target.trim())) return true;

            Thread.sleep(pollMs);

        }

        return false;

    }

    private void applySpeedDelay() throws InterruptedException {

        SpeedMode m = speedMode;

        long min = m.getMinMs();

        long max = m.getMaxMs();

        if (min == 0L && max == 0L) return;

        if (lastEnterAt == 0L) return;

        long elapsed = System.currentTimeMillis() - lastEnterAt;

        long target = min + (long) (speedRandom.nextDouble() * Math.max(1L, max - min));

        long remaining = target - elapsed;

        if (remaining <= 0L) return;

        AppLogger.info("SpeedMode " + m + ": sleeping " + remaining + " ms before next Enter");

        long end = System.currentTimeMillis() + remaining;

        while (System.currentTimeMillis() < end) {

            if (EmergencyStop.isStopped()) return;

            long r = end - System.currentTimeMillis();

            Thread.sleep(Math.max(1L, Math.min(1000L, r)));

        }

    }

    private void activateChrome() {

        if (windowManager == null) return;

        windowManager.activate();

        try { Thread.sleep(200L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

    }

    private void sendMouse(WinApiService.MOUSEINPUT mi) {

        WinApiService.INPUT input = new WinApiService.INPUT();

        input.type = WinApiService.INPUT_MOUSE;

        input.input.setType(WinApiService.MOUSEINPUT.class);

        input.input.mi = mi;

        sendInput(input);

    }

    private void sendKey(short vk, boolean keyUp) {

        WinApiService.KEYBDINPUT ki = new WinApiService.KEYBDINPUT();

        ki.wVk = vk; ki.wScan = 0;

        ki.dwFlags = keyUp ? WinApiService.KEYEVENTF_KEYUP : 0;

        ki.time = 0; ki.dwExtraInfo = null;

        WinApiService.INPUT input = new WinApiService.INPUT();

        input.type = WinApiService.INPUT_KEYBOARD;

        input.input.setType(WinApiService.KEYBDINPUT.class);

        input.input.ki = ki;

        sendInput(input);

    }

    private void sendInput(WinApiService.INPUT input) {

        int size = input.size();

        boolean ok = WinApiService.USER32.SendInput(1, new WinApiService.INPUT[]{input}, size);

        if (!ok) AppLogger.warn("SendInput failed");

    }

}

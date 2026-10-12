package com.freemaf.agent;

import com.freemaf.agent.winapi.WinApiService;

/**
 * Runtime-реализация ChatScroller через SetCursorPos + SendInput mouse wheel.
 * Не зависит от InputSimulator / CalibrationResult.
 */
public final class WinApiChatScroller implements ChatScroller {

    private static final int WHEEL_DELTA = 120;
    private static final int WHEEL_CLICKS_PER_ITERATION = 3;
    private static final int MAX_ITERATIONS = 30;
    private static final long ITERATION_PAUSE_MS = 50L;

    @Override
    public void scrollToBottom(WindowInfo window) {
        if (window == null) return;
        int cx = window.left() + (window.right() - window.left()) / 2;
        int cy = window.top() + (window.bottom() - window.top()) / 2;
        int delta = -WHEEL_DELTA * WHEEL_CLICKS_PER_ITERATION;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            WinApiService.USER32.SetCursorPos(cx, cy);
            sendWheel(delta);
            try { Thread.sleep(ITERATION_PAUSE_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static void sendWheel(int delta) {
        WinApiService.MOUSEINPUT mi = new WinApiService.MOUSEINPUT();
        mi.dx = 0;
        mi.dy = 0;
        mi.mouseData = delta;
        mi.dwFlags = WinApiService.MOUSEEVENTF_WHEEL;
        mi.time = 0;
        mi.dwExtraInfo = null;
        WinApiService.INPUT input = new WinApiService.INPUT();
        input.type = WinApiService.INPUT_MOUSE;
        input.input.setType(WinApiService.MOUSEINPUT.class);
        input.input.mi = mi;
        int size = input.size();
        WinApiService.USER32.SendInput(1, new WinApiService.INPUT[]{input}, size);
    }
}

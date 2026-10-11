
package com.freemaf.agent;

import com.freemaf.agent.winapi.WinApiService;

public final class GlobalHotkeyListener implements Runnable {


    private static final int VK_SHIFT = 0x10;

    private static final int VK_S = 0x53;
    private static final int VK_F12 = 0x7B;

    private final Runnable onTrigger;

    private volatile boolean running = true;

    public GlobalHotkeyListener(Runnable onTrigger) {

        this.onTrigger = onTrigger;

    }

    public void stop() { running = false; }

    @Override

    public void run() {

        boolean wasPressed = false;

        while (running) {


            int shift = WinApiService.USER32.GetAsyncKeyState(VK_SHIFT);

            int f12 = WinApiService.USER32.GetAsyncKeyState(VK_F12);

            boolean pressed = ((shift & 0x8000) != 0) && ((f12 & 0x8000) != 0);

            if (pressed && !wasPressed) {

                AppLogger.info("Global hotkey Shift+F12 detected");

                try { onTrigger.run(); } catch (Exception e) {

                    AppLogger.warn("hotkey handler failed: " + e.getMessage());

                }

            }

            wasPressed = pressed;

            try { Thread.sleep(60L); }

            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }

        }

    }

}

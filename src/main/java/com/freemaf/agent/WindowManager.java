
package com.freemaf.agent;

import com.freemaf.agent.winapi.WinApiService;

import com.sun.jna.Pointer;

public final class WindowManager {

    private Pointer hwnd;

    private int left;

    private int top;

    private int right;

    private int bottom;

    public void select(WindowInfo window) {

        this.hwnd = window.hwnd();

        this.left = window.left();

        this.top = window.top();

        this.right = window.right();

        this.bottom = window.bottom();

    }

    public void selectByHwnd(Pointer hwnd) {

        this.hwnd = hwnd;

        if (hwnd == null) return;

        WinApiService.RECT r = new WinApiService.RECT();

        if (WinApiService.USER32.GetWindowRect(hwnd, r)) {

            this.left = r.left;

            this.top = r.top;

            this.right = r.right;

            this.bottom = r.bottom;

        }

    }

    public boolean isWindowValid() {

        if (hwnd == null) return false;

        return WinApiService.USER32.IsWindow(hwnd);

    }

    public boolean setRect(int x, int y, int width, int height) throws InterruptedException {

        if (!isWindowValid()) return false;

        unmaximizeIfNeeded();

        WinApiService.USER32.ShowWindow(hwnd, WinApiService.SW_SHOW);

        forceForeground();

        Thread.sleep(250L);

        boolean ok = WinApiService.USER32.SetWindowPos(hwnd, null, x, y, width, height, WinApiService.SWP_NOZORDER);

        Thread.sleep(250L);

        if (ok) {

            this.left = x; this.top = y; this.right = x + width; this.bottom = y + height;

        }

        return ok;

    }

    public boolean applySize(int width, int height) {
        if (!isWindowValid()) return false;
        if (width <= 0 || height <= 0) return false;
        WinApiService.RECT saved = new WinApiService.RECT();
        if (!WinApiService.USER32.GetWindowRect(hwnd, saved)) return false;
        int x = saved.left;
        int y = saved.top;
        int finalLeft = x, finalTop = y, finalRight = x + width, finalBottom = y + height;
        for (int attempt = 0; attempt < 3; attempt++) {
            unmaximizeIfNeeded();
            WinApiService.USER32.ShowWindow(hwnd, WinApiService.SW_SHOW);
            WinApiService.USER32.SetWindowPos(hwnd, null, x, y, width, height, WinApiService.SWP_NOZORDER);
            try { Thread.sleep(150L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            WinApiService.RECT actual = new WinApiService.RECT();
            if (!WinApiService.USER32.GetWindowRect(hwnd, actual)) continue;
            int dw = Math.abs((actual.right - actual.left) - width);
            int dh = Math.abs((actual.bottom - actual.top) - height);
            int dx = Math.abs(actual.left - x);
            int dy = Math.abs(actual.top - y);
            if (dw <= 2 && dh <= 2 && dx <= 2 && dy <= 2) {
                finalLeft = actual.left; finalTop = actual.top;
                finalRight = actual.right; finalBottom = actual.bottom;
                this.left = finalLeft; this.top = finalTop;
                this.right = finalRight; this.bottom = finalBottom;
                return true;
            }
        }
        WinApiService.USER32.SetWindowPos(hwnd, null, saved.left, saved.top,
                saved.right - saved.left, saved.bottom - saved.top, WinApiService.SWP_NOZORDER);
        try { Thread.sleep(150L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return false;
    }

    public void activate() {

        if (!isWindowValid()) return;

        unmaximizeIfNeeded();

        WinApiService.USER32.ShowWindow(hwnd, WinApiService.SW_SHOW);

        forceForeground();

    }

    public boolean isForeground() {

        if (!isWindowValid()) return false;

        Pointer fg = WinApiService.USER32.GetForegroundWindow();

        if (fg == null) return false;

        return Pointer.nativeValue(fg) == Pointer.nativeValue(hwnd);

    }

    private void forceForeground() {

        Pointer fg = WinApiService.USER32.GetForegroundWindow();

        int fgThread = fg != null ? WinApiService.USER32.GetWindowThreadProcessId(fg, null) : 0;

        int ourThread = WinApiService.KERNEL32.GetCurrentThreadId();

        boolean attached = false;

        if (fgThread != 0 && fgThread != ourThread) {

            attached = WinApiService.USER32.AttachThreadInput(ourThread, fgThread, true);

        }

        try {

            WinApiService.USER32.BringWindowToTop(hwnd);

            WinApiService.USER32.SetActiveWindow(hwnd);

            WinApiService.USER32.SetForegroundWindow(hwnd);

        } finally {

            if (attached) WinApiService.USER32.AttachThreadInput(ourThread, fgThread, false);

        }

    }

    public boolean validateWindowRect() {

        if (!isWindowValid()) return false;

        WinApiService.RECT rect = new WinApiService.RECT();

        if (!WinApiService.USER32.GetWindowRect(hwnd, rect)) return false;

        return rect.left == left && rect.top == top && rect.right == right && rect.bottom == bottom;

    }

    public boolean restoreWindowRect() throws InterruptedException {

        if (!isWindowValid()) return false;

        unmaximizeIfNeeded();

        boolean ok = WinApiService.USER32.SetWindowPos(hwnd, null, left, top, right - left, bottom - top, WinApiService.SWP_NOZORDER);

        Thread.sleep(200L);

        return ok;

    }

    private void unmaximizeIfNeeded() {

        if (WinApiService.USER32.IsZoomed(hwnd) || WinApiService.USER32.IsIconic(hwnd)) {

            WinApiService.USER32.ShowWindow(hwnd, WinApiService.SW_RESTORE);

            try { Thread.sleep(200L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        }

    }

    public Pointer hwnd() { return hwnd; }

    public int left() { return left; }

    public int top() { return top; }

}

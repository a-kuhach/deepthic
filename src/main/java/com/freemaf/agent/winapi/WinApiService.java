
package com.freemaf.agent.winapi;

import com.sun.jna.Native;

import com.sun.jna.Pointer;

import com.sun.jna.Structure;

import com.sun.jna.Union;

import com.sun.jna.win32.StdCallLibrary;

import com.sun.jna.win32.StdCallLibrary.StdCallCallback;

import java.util.List;

public final class WinApiService {

    public static final User32 USER32 = Native.load("user32", User32.class);

    public static final Gdi32 GDI32 = Native.load("gdi32", Gdi32.class);

    public static final Kernel32 KERNEL32 = Native.load("kernel32", Kernel32.class);

    public static final int INPUT_MOUSE = 0;

    public static final int INPUT_KEYBOARD = 1;

    public static final int MOUSEEVENTF_MOVE = 0x0001;

    public static final int MOUSEEVENTF_LEFTDOWN = 0x0002;

    public static final int MOUSEEVENTF_LEFTUP = 0x0004;

    public static final int MOUSEEVENTF_ABSOLUTE = 0x8000;

    public static final int MOUSEEVENTF_WHEEL = 0x0800;

    public static final int KEYEVENTF_KEYUP = 0x0002;

    public static final int SM_CXSCREEN = 0;

    public static final int SM_CYSCREEN = 1;

    public static final int SM_CXWORKAREA = 48;
    public static final int SM_CYWORKAREA = 49;
    public static final int MONITOR_DEFAULTTONEAREST = 2;

    public static final int SWP_NOZORDER = 0x0004;

    public static final int SWP_NOACTIVATE = 0x0010;

    public static final int SW_RESTORE = 9;

    public static final int SW_SHOW = 5;

    public static final int WM_CLOSE = 0x0010;

    public static final int WM_MOUSEWHEEL = 0x020A;

    public static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    public static final int CF_HDROP = 15;

    public static final int GMEM_MOVEABLE = 0x0002;

    
private WinApiService() {}

    public static WorkspaceBounds getWorkspaceBounds(Pointer hwnd) {
        try {
            if (hwnd != null && USER32.IsWindow(hwnd)) {
                Pointer hMon = USER32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
                if (hMon != null) {
                    MONITORINFO mi = new MONITORINFO();
                    mi.cbSize = mi.size();
                    if (USER32.GetMonitorInfoW(hMon, mi)) {
                        int w = mi.rcWork.right - mi.rcWork.left;
                        int h = mi.rcWork.bottom - mi.rcWork.top;
                        if (w > 0 && h > 0) {
                            return new WorkspaceBounds(mi.rcWork.left, mi.rcWork.top, w, h);
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        int fallbackW = USER32.GetSystemMetrics(SM_CXWORKAREA);
        int fallbackH = USER32.GetSystemMetrics(SM_CYWORKAREA);
        return new WorkspaceBounds(0, 0, Math.max(fallbackW, 0), Math.max(fallbackH, 0));
    }



    public static boolean isWindow(long hwndValue) {

        if (hwndValue == 0L) return false;

        return USER32.IsWindow(new Pointer(hwndValue));

    }



    public static void closeWindow(long hwndValue) {

        if (hwndValue == 0L) return;

        USER32.PostMessageW(new Pointer(hwndValue), WM_CLOSE, null, null);

    }


    public static int dpiToPercent(int dpi) {
        if (dpi <= 0) return 0;
        return (int) Math.round(dpi / 96.0 * 100.0);
    }

    public static int systemScalePercent() {
        try {
            int dpi = USER32.GetDpiForSystem();
            return dpiToPercent(dpi);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int windowScalePercent(Pointer hwnd) {
        if (hwnd == null || !USER32.IsWindow(hwnd)) return 0;
        try {
            int dpi = USER32.GetDpiForWindow(hwnd);
            return dpiToPercent(dpi);
        } catch (Throwable t) {
            return 0;
        }
    }

    public interface WndEnumProc extends StdCallCallback {

        boolean callback(Pointer hwnd, Pointer lParam);

    }

    public interface User32 extends StdCallLibrary {

        Pointer FindWindowW(String className, String windowName);

        boolean EnumWindows(WndEnumProc callback, Pointer lParam);

        int GetWindowTextW(Pointer hwnd, char[] text, int maxCount);

        int GetClassNameW(Pointer hwnd, char[] name, int maxCount);

        boolean IsWindow(Pointer hwnd);

        boolean IsWindowVisible(Pointer hwnd);

        boolean GetWindowRect(Pointer hwnd, RECT rect);

        boolean SetWindowPos(Pointer hwnd, Pointer hwndInsertAfter, int x, int y, int cx, int cy, int flags);

        boolean ShowWindow(Pointer hwnd, int nCmdShow);

        boolean SetForegroundWindow(Pointer hwnd);

        boolean BringWindowToTop(Pointer hwnd);

        Pointer SetActiveWindow(Pointer hwnd);

        Pointer GetForegroundWindow();

        boolean IsIconic(Pointer hwnd);

        boolean IsZoomed(Pointer hwnd);

        Pointer GetDC(Pointer hwnd);

        int ReleaseDC(Pointer hwnd, Pointer hdc);

        boolean SendInput(int nInputs, INPUT[] inputs, int cbSize);

        int GetSystemMetrics(int nIndex);

        int GetWindowThreadProcessId(Pointer hwnd, int[] lpdwProcessId);

        boolean PostMessageW(Pointer hwnd, int msg, Pointer wParam, Pointer lParam);

        boolean AttachThreadInput(int idAttach, int idAttachTo, boolean fAttach);

        int GetDoubleClickTime();

        int GetAsyncKeyState(int vKey);

        boolean OpenClipboard(Pointer hWndNewOwner);

        boolean CloseClipboard();

        boolean EmptyClipboard();

        Pointer SetClipboardData(int uFormat, Pointer hMem);

        boolean IsClipboardFormatAvailable(int format);

        int GetDpiForSystem();

        int GetDpiForWindow(Pointer hwnd);

        Pointer MonitorFromWindow(Pointer hwnd, int dwFlags);

        boolean GetMonitorInfoW(Pointer hMonitor, MONITORINFO lpmi);

    }

    public interface Gdi32 extends StdCallLibrary {

        int GetPixel(Pointer hdc, int x, int y);

    }

    public interface Kernel32 extends StdCallLibrary {

        Pointer OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);

        boolean CloseHandle(Pointer hObject);

        boolean QueryFullProcessImageNameW(Pointer hProcess, int dwFlags, char[] lpExeName, int[] lpdwSize);

        int GetCurrentThreadId();

        Pointer GlobalAlloc(int uFlags, int dwBytes);

        Pointer GlobalLock(Pointer hMem);

        boolean GlobalUnlock(Pointer hMem);

        Pointer GlobalFree(Pointer hMem);

    }

    public static class RECT extends Structure {

        public int left; public int top; public int right; public int bottom;

        @Override protected List<String> getFieldOrder() { return List.of("left","top","right","bottom"); }

    }

    public static class MONITORINFO extends Structure {
        public int cbSize;
        public RECT rcMonitor = new RECT();
        public RECT rcWork = new RECT();
        public int dwFlags;
        @Override protected List<String> getFieldOrder() { return List.of("cbSize","rcMonitor","rcWork","dwFlags"); }
    }

    public record WorkspaceBounds(int left, int top, int width, int height) { }

    public static class MOUSEINPUT extends Structure {

        public int dx; public int dy; public int mouseData; public int dwFlags; public int time; public Pointer dwExtraInfo;

        @Override protected List<String> getFieldOrder() { return List.of("dx","dy","mouseData","dwFlags","time","dwExtraInfo"); }

    }

    public static class KEYBDINPUT extends Structure {

        public short wVk; public short wScan; public int dwFlags; public int time; public Pointer dwExtraInfo;

        @Override protected List<String> getFieldOrder() { return List.of("wVk","wScan","dwFlags","time","dwExtraInfo"); }

    }

    public static class INPUTUNION extends Union { public MOUSEINPUT mi; public KEYBDINPUT ki; }

    public static class INPUT extends Structure {

        public int type; public INPUTUNION input;

        @Override protected List<String> getFieldOrder() { return List.of("type","input"); }

    }

}

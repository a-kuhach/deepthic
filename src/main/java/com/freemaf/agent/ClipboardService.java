
package com.freemaf.agent;

import java.awt.Toolkit;

import java.awt.datatransfer.Clipboard;

import java.awt.datatransfer.DataFlavor;

import java.awt.datatransfer.StringSelection;

import java.awt.datatransfer.Transferable;

import com.freemaf.agent.winapi.WinApiService;

import com.sun.jna.Pointer;

import java.io.ByteArrayOutputStream;

import java.io.File;

import java.util.List;

/**
 * Legacy clipboard-based input/output.
 *
 * In CDP mode (cdp.enabled=true) attachments use DOM.setFileInputFiles and
 * response reading uses innerText; ClipboardService is only reachable via
 * legacy WinAPI paths and will be removed after S6 MASTER_PLAN.
 */
@Deprecated
public final class ClipboardService {

    private static final long CLIPBOARD_TIMEOUT_MS = 2000L;

    public void setText(String text) {

        flushClipboard();

        getClipboard().setContents(new StringSelection(text), null);

    }

    public String getText() throws Exception {

        Transferable transferable = getClipboard().getContents(null);

        if (transferable != null && transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {

            return (String) transferable.getTransferData(DataFlavor.stringFlavor);

        }

        throw new IllegalStateException("Clipboard text unavailable");

    }

    public String getTextWithRetry() throws Exception {

        long deadline = System.currentTimeMillis() + CLIPBOARD_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {

            try { return getText(); }

            catch (IllegalStateException e) { Thread.sleep(100L); }

        }

        return getText();

    }

    public void flushClipboard() {

        try { getClipboard().setContents(new StringSelection(""), null); }

        catch (RuntimeException ignored) { }

    }

    private Clipboard getClipboard() {

        return Toolkit.getDefaultToolkit().getSystemClipboard();

    }

    public void setFiles(List<File> files) {

        if (files == null || files.isEmpty()) throw new IllegalArgumentException("files must not be empty");

        byte[] data = buildDropFilesData(files);

        Pointer hMem = WinApiService.KERNEL32.GlobalAlloc(WinApiService.GMEM_MOVEABLE, data.length);

        if (hMem == null) throw new IllegalStateException("GlobalAlloc failed");

        boolean locked = false;

        try {

            Pointer p = WinApiService.KERNEL32.GlobalLock(hMem);

            if (p == null) throw new IllegalStateException("GlobalLock failed");

            locked = true;

            p.write(0L, data, 0, data.length);

        } finally {

            if (locked) WinApiService.KERNEL32.GlobalUnlock(hMem);

        }

        if (!openClipboardWithRetry(20, 50L)) {

            WinApiService.KERNEL32.GlobalFree(hMem);

            throw new IllegalStateException("OpenClipboard failed");

        }

        try {

            WinApiService.USER32.EmptyClipboard();

            Pointer result = WinApiService.USER32.SetClipboardData(WinApiService.CF_HDROP, hMem);

            if (result == null) {

                WinApiService.KERNEL32.GlobalFree(hMem);

                throw new IllegalStateException("SetClipboardData failed");

            }

        } finally {

            WinApiService.USER32.CloseClipboard();

        }

    }

    private static boolean openClipboardWithRetry(int attempts, long delayMs) {

        for (int i = 0; i < attempts; i++) {

            if (WinApiService.USER32.OpenClipboard(null)) return true;

            try { Thread.sleep(delayMs); }

            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }

        }

        return false;

    }

    static byte[] buildDropFilesData(List<File> files) {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writeLEInt(out, 20);

        writeLEInt(out, 0);

        writeLEInt(out, 0);

        writeLEInt(out, 0);

        writeLEInt(out, 1);

        for (File f : files) {

            String path = f.getAbsolutePath();

            for (int i = 0; i < path.length(); i++) writeLEShort(out, (short) path.charAt(i));

            writeLEShort(out, (short) 0);

        }

        writeLEShort(out, (short) 0);

        return out.toByteArray();

    }

    private static void writeLEInt(ByteArrayOutputStream out, int v) {

        out.write(v & 0xFF);

        out.write((v >>> 8) & 0xFF);

        out.write((v >>> 16) & 0xFF);

        out.write((v >>> 24) & 0xFF);

    }

    private static void writeLEShort(ByteArrayOutputStream out, short v) {

        out.write(v & 0xFF);

        out.write((v >>> 8) & 0xFF);

    }

}

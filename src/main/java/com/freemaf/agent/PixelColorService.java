package com.freemaf.agent;

import com.freemaf.agent.winapi.WinApiService;
import com.sun.jna.Pointer;

/**
 * Pixel reader.
 * readPixel: DC открывается лениво и кэшируется. При CLR_INVALID DC пересоздаётся
 *   один раз и запрос повторяется; устойчивый сбой возвращает sentinel -1 вместо
 *   выброса исключения (это не должно останавливать пайплайн).
 * readRegion: BitBlt + GetDIBits за один проход. При сбое BitBlt возвращает массив -1,
 *   не бросает исключение.
 */
@Deprecated
public final class PixelColorService implements PixelReader, AutoCloseable {

    private static final int CLR_INVALID = 0xFFFFFFFF;

    private Pointer cachedDc;

    private Pointer obtainDc() {
        Pointer hdc = cachedDc;
        if (hdc == null) {
            hdc = WinApiService.USER32.GetDC(null);
            if (hdc != null) cachedDc = hdc;
        }
        return hdc;
    }

    private void discardDc() {
        Pointer hdc = cachedDc;
        cachedDc = null;
        if (hdc != null) {
            try { WinApiService.USER32.ReleaseDC(null, hdc); }
            catch (Throwable ignored) { }
        }
    }

    public int readPixel(int x, int y) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Pointer hdc = obtainDc();
            if (hdc == null) return -1;
            int colorRef;
            try {
                colorRef = WinApiService.GDI32.GetPixel(hdc, x, y);
            } catch (Throwable t) {
                AppLogger.warn("readPixel: GetPixel threw at " + x + "," + y + ": " + t.getMessage());
                discardDc();
                continue;
            }
            if (colorRef == CLR_INVALID) {
                AppLogger.warn("readPixel: CLR_INVALID at " + x + "," + y + " (attempt " + (attempt + 1) + "), recreating DC");
                discardDc();
                continue;
            }
            int r = colorRef & 0xFF;
            int g = (colorRef >> 8) & 0xFF;
            int b = (colorRef >> 16) & 0xFF;
            return (r << 16) | (g << 8) | b;
        }
        return -1;
    }

    @Override
    public int[] readRegion(int x0, int y0, int w, int h) {
        if (w <= 0 || h <= 0) return new int[0];
        int[] out = new int[w * h];
        Pointer screenDc = obtainDc();
        if (screenDc == null) {
            java.util.Arrays.fill(out, -1);
            return out;
        }
        Pointer memDc = WinApiService.GDI32.CreateCompatibleDC(screenDc);
        if (memDc == null) {
            java.util.Arrays.fill(out, -1);
            return out;
        }
        Pointer bmp = WinApiService.GDI32.CreateCompatibleBitmap(screenDc, w, h);
        if (bmp == null) {
            WinApiService.GDI32.DeleteDC(memDc);
            java.util.Arrays.fill(out, -1);
            return out;
        }
        Pointer oldBmp = WinApiService.GDI32.SelectObject(memDc, bmp);
        try {
            boolean ok = WinApiService.GDI32.BitBlt(memDc, 0, 0, w, h, screenDc, x0, y0, WinApiService.SRCCOPY);
            if (!ok) {
                AppLogger.warn("readRegion: BitBlt failed for " + x0 + "," + y0 + " " + w + "x" + h + "; recreating DC");
                discardDc();
                java.util.Arrays.fill(out, -1);
                return out;
            }
            WinApiService.BITMAPINFO info = new WinApiService.BITMAPINFO();
            info.bmiHeader.biSize = info.bmiHeader.size();
            info.bmiHeader.biWidth = w;
            info.bmiHeader.biHeight = -h;
            info.bmiHeader.biPlanes = 1;
            info.bmiHeader.biBitCount = 32;
            info.bmiHeader.biCompression = 0;
            info.bmiHeader.biSizeImage = w * h * 4;
            info.bmiHeader.biXPelsPerMeter = 0;
            info.bmiHeader.biYPelsPerMeter = 0;
            info.bmiHeader.biClrUsed = 0;
            info.bmiHeader.biClrImportant = 0;
            byte[] buf = new byte[w * h * 4];
            int lines = WinApiService.GDI32.GetDIBits(memDc, bmp, 0, h, buf, info, WinApiService.DIB_RGB_COLORS);
            if (lines <= 0) {
                AppLogger.warn("readRegion: GetDIBits returned " + lines);
                java.util.Arrays.fill(out, -1);
                return out;
            }
            int blackLocal = 0;
            for (int i = 0, p = 0; i < out.length; i++, p += 4) {
                int b = buf[p] & 0xFF;
                int g = buf[p + 1] & 0xFF;
                int r = buf[p + 2] & 0xFF;
                if (r == 0 && g == 0 && b == 0) blackLocal++;
                out[i] = (r << 16) | (g << 8) | b;
            }
            if (blackLocal * 2 > out.length) {
                AppLogger.warn("PixelColorService.readRegion: BLACK BIAS at " + x0 + "," + y0
                        + " " + w + "x" + h + " -> blackCount=" + blackLocal + "/" + out.length
                        + " (BitBlt/DWM capture failed). Falling back to per-pixel GetPixel path.");
                return PixelReader.super.readRegion(x0, y0, w, h);
            }
            return out;
        } finally {
            if (oldBmp != null) WinApiService.GDI32.SelectObject(memDc, oldBmp);
            WinApiService.GDI32.DeleteObject(bmp);
            WinApiService.GDI32.DeleteDC(memDc);
        }
    }

    @Override
    public void close() {
        discardDc();
    }
}

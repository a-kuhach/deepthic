package com.freemaf.agent;

@FunctionalInterface
public interface PixelReader {
    int readPixel(int x, int y);

    /**
     * Reads a rectangular region and returns pixels row-major (RGB-24 ints).
     * Default implementation falls back to repeated readPixel; runtime implementations
     * should override with a batched single-DC read.
     * Sentinel value -1 means the pixel could not be read.
     */
    default int[] readRegion(int x0, int y0, int w, int h) {
        if (w <= 0 || h <= 0) return new int[0];
        int[] out = new int[w * h];
        for (int dy = 0; dy < h; dy++) {
            int rowBase = dy * w;
            for (int dx = 0; dx < w; dx++) {
                out[rowBase + dx] = readPixel(x0 + dx, y0 + dy);
            }
        }
        return out;
    }
}

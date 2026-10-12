package com.freemaf.agent;

@FunctionalInterface
public interface PixelReader {
    int readPixel(int x, int y);
}

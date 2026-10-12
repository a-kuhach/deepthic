package com.freemaf.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowSizerTest {

    private static WindowInfo info(int w, int h) {
        return new WindowInfo(null, "test", 0, 0, w, h);
    }

    @Test
    void probeSucceedsImmediately() {
        WindowSizer sizer = new WindowSizer(
                (w, h) -> true,
                () -> info(100, 100),
                10, 0, 0, 2000, 2000);
        WindowSizeResult r = sizer.run(info(100, 100), (w) -> new ProbeResult(true, null, ""));
        assertEquals(WindowSizeResult.Status.FOUND, r.status());
        assertEquals(100, r.finalWidth());
        assertEquals(100, r.finalHeight());
    }

    @Test
    void probeSucceedsAfterNIterations() {
        int[] calls = {0};
        SizingProbe probe = (w) -> {
            calls[0]++;
            return new ProbeResult(calls[0] >= 4, null, "call " + calls[0]);
        };
        WindowSizer sizer = new WindowSizer(
                (w, h) -> true,
                () -> info(100 + 0, 100),
                10, 0, 0, 5000, 5000);
        WindowSizeResult r = sizer.run(info(100, 100), probe);
        assertEquals(WindowSizeResult.Status.FOUND, r.status());
        assertEquals(130, r.finalWidth());
        assertEquals(130, r.finalHeight());
        assertEquals(4, calls[0]);
    }

    @Test
    void probeNeverSucceedsRespectsExplicitMaxBounds() {
        WindowSizer sizer = new WindowSizer(
                (w, h) -> true,
                () -> info(700, 500),
                10, 700, 500, 2000, 2000);
        WindowSizeResult r = sizer.run(info(100, 100), (w) -> new ProbeResult(false, null, "nope"));
        assertEquals(WindowSizeResult.Status.EXHAUSTED, r.status());
        assertEquals(500, r.finalWidth());
        assertEquals(500, r.finalHeight());
        assertTrue(r.lastReason().contains("reached bounds"), r.lastReason());
    }

    @Test
    void probeNeverSucceedsRespectsWorkspaceBounds() {
        WindowSizer sizer = new WindowSizer(
                (w, h) -> true,
                () -> info(700, 500),
                10, 0, 0, 700, 500);
        WindowSizeResult r = sizer.run(info(100, 100), (w) -> new ProbeResult(false, null, "nope"));
        assertEquals(WindowSizeResult.Status.EXHAUSTED, r.status());
        assertEquals(500, r.finalWidth());
        assertEquals(500, r.finalHeight());
        assertTrue(r.lastReason().contains("reached bounds"), r.lastReason());
        assertTrue(r.lastReason().contains("effectiveMax=700x500"), r.lastReason());
    }

    @Test
    void applySizeFailureLeadsToExhausted() {
        AtomicInteger calls = new AtomicInteger(0);
        SizeApplier applier = (w, h) -> calls.incrementAndGet() <= 2;
        WindowSizer sizer = new WindowSizer(
                applier, () -> info(100, 100), 10, 0, 0, 5000, 5000);
        WindowSizeResult r = sizer.run(info(100, 100), (w) -> new ProbeResult(false, null, "nope"));
        assertEquals(WindowSizeResult.Status.EXHAUSTED, r.status());
        assertEquals(120, r.finalWidth());
        assertEquals(120, r.finalHeight());
        assertTrue(r.lastReason().startsWith("applySize failed"), r.lastReason());
    }

    @Test
    void windowInfoLostLeadsToExhausted() {
        AtomicInteger calls = new AtomicInteger(0);
        WindowSizer.WindowInfoSupplier supplier = () -> {
            int n = calls.incrementAndGet();
            if (n >= 3) return null;
            return info(100, 100);
        };
        WindowSizer sizer = new WindowSizer(
                (w, h) -> true, supplier, 10, 0, 0, 5000, 5000);
        WindowSizeResult r = sizer.run(info(100, 100), (w) -> new ProbeResult(false, null, "nope"));
        assertEquals(WindowSizeResult.Status.EXHAUSTED, r.status());
        assertEquals(120, r.finalWidth());
        assertEquals(120, r.finalHeight());
        assertTrue(r.lastReason().contains("window lost"), r.lastReason());
    }

    @Test
    void iterationCapReached() {
        WindowSizer sizer = new WindowSizer(
                (w, h) -> true,
                () -> info(100, 100),
                1, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 5);
        WindowSizeResult r = sizer.run(info(100, 100), (w) -> new ProbeResult(false, null, "nope"));
        assertEquals(WindowSizeResult.Status.EXHAUSTED, r.status());
        assertEquals("iteration cap reached (5)", r.lastReason());
    }

    @Test
    void negativeStepFallsBackToTen() {
        int[] calls = {0};
        SizingProbe probe = (w) -> {
            calls[0]++;
            return new ProbeResult(calls[0] >= 3, null, "call " + calls[0]);
        };
        WindowSizer sizer = new WindowSizer(
                (w, h) -> true,
                () -> info(100, 100),
                0, 0, 0, 5000, 5000);
        WindowSizeResult r = sizer.run(info(100, 100), probe);
        assertEquals(WindowSizeResult.Status.FOUND, r.status());
        assertEquals(120, r.finalWidth());
        assertEquals(120, r.finalHeight());
    }
}

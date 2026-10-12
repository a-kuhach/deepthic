package com.freemaf.agent;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

class CalibrateAndVerifyProbeTest {

    private static WindowInfo win() {
        return new WindowInfo(null, "t", 0, 0, 500, 500);
    }

    private static AutoCalibrator.CalibrationResult withAllRequired(AutoCalibrator.MarkerHit copy) {
        Map<CalibrationMarker, AutoCalibrator.MarkerHit> hits = new LinkedHashMap<>();
        for (CalibrationMarker m : AutoCalibrator.CalibrationResult.REQUIRED_FOR_CALIBRATION) {
            hits.put(m, new AutoCalibrator.MarkerHit(100, 400, "333335", false));
        }
        AutoCalibrator.CalibrationResult r = new AutoCalibrator.CalibrationResult(Map.of(), hits);
        return copy != null ? r.withCodeCopy(copy) : r;
    }

    private static AutoCalibrator.CalibrationResult missingRequired() {
        return new AutoCalibrator.CalibrationResult(Map.of());
    }

    @Test
    void probeSuccessWhenAllRequiredFoundAndCopyFound() throws IOException {
        WindowManager wm = Mockito.mock(WindowManager.class);
        PixelReader reader = (x, y) -> 0;
        ChatScroller scroller = Mockito.mock(ChatScroller.class);
        AutoCalibrator.MarkerHit copy = new AutoCalibrator.MarkerHit(200, 450, "DCDFE5", false);
        AutoCalibrator.CalibrationResult base = withAllRequired(null);
        try (MockedStatic<AutoCalibrator> ms = Mockito.mockStatic(AutoCalibrator.class)) {
            ms.when(() -> AutoCalibrator.calibrate(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenReturn(base);
            ms.when(() -> AutoCalibrator.findCodeCopy(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenReturn(Optional.of(copy));
            CalibrateAndVerifyProbe probe = new CalibrateAndVerifyProbe(wm, reader, scroller);
            ProbeResult r = probe.probe(win());
            assertTrue(r.success());
            assertTrue(r.payload() instanceof AutoCalibrator.CalibrationResult);
            AutoCalibrator.CalibrationResult got = (AutoCalibrator.CalibrationResult) r.payload();
            assertTrue(got.allFoundAndVisible());
            assertSame(got, probe.lastSuccessfulResult());
        }
    }

    @Test
    void probeFailWhenRequiredMarkerMissing() throws IOException {
        WindowManager wm = Mockito.mock(WindowManager.class);
        PixelReader reader = (x, y) -> 0;
        ChatScroller scroller = Mockito.mock(ChatScroller.class);
        AutoCalibrator.MarkerHit copy = new AutoCalibrator.MarkerHit(200, 450, "DCDFE5", false);
        AutoCalibrator.CalibrationResult base = missingRequired();
        try (MockedStatic<AutoCalibrator> ms = Mockito.mockStatic(AutoCalibrator.class)) {
            ms.when(() -> AutoCalibrator.calibrate(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenReturn(base);
            ms.when(() -> AutoCalibrator.findCodeCopy(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenReturn(Optional.of(copy));
            CalibrateAndVerifyProbe probe = new CalibrateAndVerifyProbe(wm, reader, scroller);
            ProbeResult r = probe.probe(win());
            assertFalse(r.success());
            assertTrue(r.diagnostic().contains("input_field"), "diagnostic: " + r.diagnostic());
            assertNull(probe.lastSuccessfulResult());
        }
    }

    @Test
    void probeFailWhenCopyMissing() throws IOException {
        WindowManager wm = Mockito.mock(WindowManager.class);
        PixelReader reader = (x, y) -> 0;
        ChatScroller scroller = Mockito.mock(ChatScroller.class);
        AutoCalibrator.CalibrationResult base = withAllRequired(null);
        try (MockedStatic<AutoCalibrator> ms = Mockito.mockStatic(AutoCalibrator.class)) {
            ms.when(() -> AutoCalibrator.calibrate(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenReturn(base);
            ms.when(() -> AutoCalibrator.findCodeCopy(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenReturn(Optional.empty());
            CalibrateAndVerifyProbe probe = new CalibrateAndVerifyProbe(wm, reader, scroller);
            ProbeResult r = probe.probe(win());
            assertFalse(r.success());
            assertTrue(r.diagnostic().contains("code_copy"), "diagnostic: " + r.diagnostic());
            assertNull(probe.lastSuccessfulResult());
        }
    }

    @Test
    void probeCallsScrollBeforeFindCodeCopy() throws IOException {
        WindowManager wm = Mockito.mock(WindowManager.class);
        PixelReader reader = (x, y) -> 0;
        ChatScroller scroller = Mockito.mock(ChatScroller.class);
        AutoCalibrator.MarkerHit copy = new AutoCalibrator.MarkerHit(200, 450, "DCDFE5", false);
        AutoCalibrator.CalibrationResult base = withAllRequired(null);
        AtomicInteger step = new AtomicInteger(0);
        try (MockedStatic<AutoCalibrator> ms = Mockito.mockStatic(AutoCalibrator.class)) {
            ms.when(() -> AutoCalibrator.calibrate(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenAnswer(inv -> { assertEquals(0, step.getAndIncrement()); return base; });
            Mockito.doAnswer(inv -> { assertEquals(1, step.getAndIncrement()); return null; })
                    .when(scroller).scrollToBottom(any(WindowInfo.class));
            ms.when(() -> AutoCalibrator.findCodeCopy(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenAnswer(inv -> { assertEquals(2, step.getAndIncrement()); return Optional.of(copy); });
            CalibrateAndVerifyProbe probe = new CalibrateAndVerifyProbe(wm, reader, scroller);
            ProbeResult r = probe.probe(win());
            assertTrue(r.success());
            assertEquals(3, step.get());
        }
    }

    @Test
    void probeOverwritesLastSuccessfulResultOnlyOnSuccess() throws IOException {
        WindowManager wm = Mockito.mock(WindowManager.class);
        PixelReader reader = (x, y) -> 0;
        ChatScroller scroller = Mockito.mock(ChatScroller.class);
        AutoCalibrator.MarkerHit copy = new AutoCalibrator.MarkerHit(200, 450, "DCDFE5", false);
        AutoCalibrator.CalibrationResult base = withAllRequired(null);
        try (MockedStatic<AutoCalibrator> ms = Mockito.mockStatic(AutoCalibrator.class)) {
            ms.when(() -> AutoCalibrator.calibrate(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenReturn(base);
            ms.when(() -> AutoCalibrator.findCodeCopy(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenReturn(Optional.of(copy));
            CalibrateAndVerifyProbe probe = new CalibrateAndVerifyProbe(wm, reader, scroller);
            ProbeResult r1 = probe.probe(win());
            assertTrue(r1.success());
            AutoCalibrator.CalibrationResult first = probe.lastSuccessfulResult();
            assertNotNull(first);

            ms.when(() -> AutoCalibrator.calibrate(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenReturn(missingRequired());
            ms.when(() -> AutoCalibrator.findCodeCopy(any(WindowInfo.class), any(WindowManager.class), any(PixelReader.class)))
                    .thenReturn(Optional.empty());
            ProbeResult r2 = probe.probe(win());
            assertFalse(r2.success());
            assertSame(first, probe.lastSuccessfulResult());
        }
    }
}

package com.freemaf.agent;

import com.freemaf.agent.winapi.WinApiService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CalibrationOrchestratorTest {

    private static final WindowInfo MANAGED = new WindowInfo(null, "DeepSeek", 0, 0, 100, 100);
    private static final PixelReader READER = (x, y) -> 0;
    private static final ChatScroller SCROLLER = w -> { };

    private static Config stubConfig() {
        Config c = mock(Config.class);
        when(c.getWindowGrowthStepPx()).thenReturn(10);
        when(c.getWindowSizeMaxWidth()).thenReturn(0);
        when(c.getWindowSizeMaxHeight()).thenReturn(0);
        return c;
    }

    private static AutoCalibrator.CalibrationResult fullResult() {
        Map<String, String> colors = new LinkedHashMap<>();
        Map<CalibrationMarker, AutoCalibrator.MarkerHit> hits = new LinkedHashMap<>();
        for (CalibrationMarker m : AutoCalibrator.CalibrationResult.REQUIRED_FOR_CALIBRATION) {
            colors.put(m.id(), "000000");
            hits.put(m, new AutoCalibrator.MarkerHit(10, 10, "000000", false));
        }
        AutoCalibrator.MarkerHit copy = new AutoCalibrator.MarkerHit(20, 20, "DCDFE5", false);
        return new AutoCalibrator.CalibrationResult(colors, hits, copy);
    }

    private static WinApiService.WorkspaceBounds bounds(int w, int h) {
        return new WinApiService.WorkspaceBounds(0, 0, w, h);
    }

    @Test
    void orchestratorReturnsIncompatiblePcOnPrecheckFail() {
        WindowManager wm = mock(WindowManager.class);
        Config cfg = stubConfig();
        try (MockedStatic<WinApiService> ws = mockStatic(WinApiService.class)) {
            ws.when(() -> WinApiService.getWorkspaceBounds(any())).thenReturn(bounds(1280, 720));
            CalibrationOrchestrator.Outcome out =
                    CalibrationOrchestrator.run(MANAGED, wm, READER, SCROLLER, cfg);
            assertEquals(CalibrationOrchestrator.Status.INCOMPATIBLE_PC, out.status());
            assertNull(out.calibration());
            assertNull(out.sizeResult());
            assertNotNull(out.compatibility());
            assertTrue(out.compatibility().diagnostic().contains("1920x1080"),
                    "diagnostic must mention minimum 1920x1080, got: " + out.compatibility().diagnostic());
        }
    }

    @Test
    void orchestratorReturnsReadyOnProbeSuccess() {
        WindowManager wm = mock(WindowManager.class);
        Config cfg = stubConfig();
        AutoCalibrator.CalibrationResult cal = fullResult();
        ProbeResult probeRes = new ProbeResult(true, cal, "");
        WindowSizeResult sizeRes = new WindowSizeResult(700, 500, WindowSizeResult.Status.FOUND, probeRes, "");
        try (MockedStatic<WinApiService> ws = mockStatic(WinApiService.class);
             MockedConstruction<WindowSizer> mc = mockConstruction(WindowSizer.class,
                     (mock, ctx) -> when(mock.run(any(WindowInfo.class), any(SizingProbe.class))).thenReturn(sizeRes))) {
            ws.when(() -> WinApiService.getWorkspaceBounds(any())).thenReturn(bounds(1920, 1080));
            CalibrationOrchestrator.Outcome out =
                    CalibrationOrchestrator.run(MANAGED, wm, READER, SCROLLER, cfg);
            assertEquals(CalibrationOrchestrator.Status.READY, out.status());
            assertSame(cal, out.calibration());
            assertNotNull(out.compatibility());
            assertEquals(CompatibilityChecker.CompatibilityResult.Status.COMPATIBLE, out.compatibility().status());
            assertSame(sizeRes, out.sizeResult());
        }
    }

    @Test
    void orchestratorReturnsIncompatiblePcOnExhausted() {
        WindowManager wm = mock(WindowManager.class);
        Config cfg = stubConfig();
        ProbeResult last = new ProbeResult(false, null, "still not visible");
        WindowSizeResult sizeRes = new WindowSizeResult(1900, 1060, WindowSizeResult.Status.EXHAUSTED, last, "bounds reached");
        try (MockedStatic<WinApiService> ws = mockStatic(WinApiService.class);
             MockedConstruction<WindowSizer> mc = mockConstruction(WindowSizer.class,
                     (mock, ctx) -> when(mock.run(any(WindowInfo.class), any(SizingProbe.class))).thenReturn(sizeRes))) {
            ws.when(() -> WinApiService.getWorkspaceBounds(any())).thenReturn(bounds(1920, 1080));
            CalibrationOrchestrator.Outcome out =
                    CalibrationOrchestrator.run(MANAGED, wm, READER, SCROLLER, cfg);
            assertEquals(CalibrationOrchestrator.Status.INCOMPATIBLE_PC, out.status());
            assertNull(out.calibration());
            assertNotNull(out.compatibility());
            assertTrue(out.compatibility().diagnostic().contains("1920x1080"));
            assertSame(sizeRes, out.sizeResult());
        }
    }

    @Test
    void orchestratorReturnsFailedOnUnexpectedPayloadType() {
        WindowManager wm = mock(WindowManager.class);
        Config cfg = stubConfig();
        ProbeResult probeRes = new ProbeResult(true, "not a CalibrationResult", "");
        WindowSizeResult sizeRes = new WindowSizeResult(700, 500, WindowSizeResult.Status.FOUND, probeRes, "");
        try (MockedStatic<WinApiService> ws = mockStatic(WinApiService.class);
             MockedConstruction<WindowSizer> mc = mockConstruction(WindowSizer.class,
                     (mock, ctx) -> when(mock.run(any(WindowInfo.class), any(SizingProbe.class))).thenReturn(sizeRes))) {
            ws.when(() -> WinApiService.getWorkspaceBounds(any())).thenReturn(bounds(1920, 1080));
            CalibrationOrchestrator.Outcome out =
                    CalibrationOrchestrator.run(MANAGED, wm, READER, SCROLLER, cfg);
            assertEquals(CalibrationOrchestrator.Status.FAILED, out.status());
            assertNull(out.calibration());
            assertNotNull(out.compatibility());
            assertSame(sizeRes, out.sizeResult());
        }
    }

    @Test
    void orchestratorPassesWorkspaceBoundsToSizer() {
        WindowManager wm = mock(WindowManager.class);
        Config cfg = stubConfig();
        ProbeResult last = new ProbeResult(false, null, "x");
        WindowSizeResult sizeRes = new WindowSizeResult(700, 500, WindowSizeResult.Status.EXHAUSTED, last, "x");
        final List<List<?>> capturedArgs = new ArrayList<>();
        try (MockedStatic<WinApiService> ws = mockStatic(WinApiService.class);
             MockedConstruction<WindowSizer> mc = mockConstruction(WindowSizer.class,
                     (mock, ctx) -> {
                         capturedArgs.add(new ArrayList<>(ctx.arguments()));
                         when(mock.run(any(WindowInfo.class), any(SizingProbe.class))).thenReturn(sizeRes);
                     })) {
            ws.when(() -> WinApiService.getWorkspaceBounds(any())).thenReturn(bounds(2560, 1440));
            CalibrationOrchestrator.run(MANAGED, wm, READER, SCROLLER, cfg);
            assertEquals(1, capturedArgs.size(), "WindowSizer must be constructed exactly once");
            List<?> args = capturedArgs.get(0);
            assertEquals(10, args.get(2), "stepPx");
            assertEquals(0, args.get(3), "maxWidth");
            assertEquals(0, args.get(4), "maxHeight");
            assertEquals(2560, args.get(5), "workspaceWidth");
            assertEquals(1440, args.get(6), "workspaceHeight");
        }
    }
}

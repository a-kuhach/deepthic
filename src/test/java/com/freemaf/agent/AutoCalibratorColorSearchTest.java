package com.freemaf.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCalibratorColorSearchTest {

    private static final int BLACK = 0x000000;
    private static final int INPUT_FIELD_RGB = 0x333335;
    private static final int DEEPTHINK_RGB = 0x6196F8;
    private static final int SEARCH_RGB = 0x5B82E6;
    private static final int SEND_ACTIVE_RGB = 0x5686FE;
    private static final int SEND_DISABLED_RGB = 0x3C5082;

    private static WindowInfo bigWindow() {
        return new WindowInfo(null, "test", 0, 0, 500, 500);
    }

    private static PixelReader sparse(int[] xs, int[] ys, int[] rgbs) {
        return (x, y) -> {
            for (int i = 0; i < xs.length; i++) {
                if (xs[i] == x && ys[i] == y) return rgbs[i];
            }
            return BLACK;
        };
    }

    @Test
    void singleMatchInArea() throws IOException {
        PixelReader r = sparse(new int[]{100}, new int[]{450}, new int[]{INPUT_FIELD_RGB});
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(bigWindow(), null, r);
        AutoCalibrator.MarkerHit hit = res.hit(CalibrationMarker.INPUT_FIELD).orElseThrow();
        assertEquals(100, hit.x());
        assertEquals(450, hit.y());
        assertFalse(hit.disabledObserved());
    }

    @Test
    void multipleMatchesSameColorCenterMatch() throws IOException {
        PixelReader r = sparse(
                new int[]{100, 200, 300},
                new int[]{400, 400, 400},
                new int[]{INPUT_FIELD_RGB, INPUT_FIELD_RGB, INPUT_FIELD_RGB});
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(bigWindow(), null, r);
        AutoCalibrator.MarkerHit hit = res.hit(CalibrationMarker.INPUT_FIELD).orElseThrow();
        assertEquals(200, hit.x());
        assertEquals(400, hit.y());
    }

    @Test
    void noMatchInArea() throws IOException {
        PixelReader r = (x, y) -> BLACK;
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(bigWindow(), null, r);
        assertTrue(res.hits().isEmpty());
        assertFalse(res.allRequiredFound());
        assertEquals(4, res.missing().size());
    }

    @Test
    void disabledColorMatchOnly() throws IOException {
        PixelReader r = sparse(new int[]{400}, new int[]{400}, new int[]{SEND_DISABLED_RGB});
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(bigWindow(), null, r);
        AutoCalibrator.MarkerHit hit = res.hit(CalibrationMarker.SEND).orElseThrow();
        assertEquals(400, hit.x());
        assertEquals(400, hit.y());
        assertTrue(hit.disabledObserved());
    }

    @Test
    void areaClippedToClientRect() throws IOException {
        WindowInfo small = new WindowInfo(null, "small", 0, 0, 100, 100);
        // INPUT_FIELD area is (0.0, 0.60, 1.0, 1.0) -> x 0..100, y 60..100
        // place one pixel inside (50,80) and one outside (50,50)
        PixelReader r = sparse(
                new int[]{50, 50},
                new int[]{80, 50},
                new int[]{INPUT_FIELD_RGB, INPUT_FIELD_RGB});
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(small, null, r);
        Optional<AutoCalibrator.MarkerHit> hit = res.hit(CalibrationMarker.INPUT_FIELD);
        assertTrue(hit.isPresent());
        assertEquals(80, hit.get().y());
    }

    @Test
    void calibrateReturnsResultWithAllRequiredFoundForSyntheticCompleteInput() throws IOException {
        PixelReader r = sparse(
                new int[]{100, 200, 400, 100},
                new int[]{450, 400, 400, 400},
                new int[]{INPUT_FIELD_RGB, SEARCH_RGB, SEND_ACTIVE_RGB, DEEPTHINK_RGB});
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(bigWindow(), null, r);
        assertTrue(res.allRequiredFound(), "missing: " + res.missing());
        assertTrue(res.found(CalibrationMarker.INPUT_FIELD));
        assertTrue(res.found(CalibrationMarker.DEEPTHINK));
        assertTrue(res.found(CalibrationMarker.SEARCH));
        assertTrue(res.found(CalibrationMarker.SEND));
    }

    @Test
    void findCodeCopyReturnsEmptyWhenNoMatch() {
        PixelReader r = (x, y) -> BLACK;
        Optional<AutoCalibrator.MarkerHit> hit = AutoCalibrator.findCodeCopy(bigWindow(), null, r);
        assertTrue(hit.isEmpty());
    }

    @Test
    void findCodeCopyReturnsHitWhenMatchInLowerArea() {
        // CODE_COPY area (0.0, 0.55, 1.0, 1.0) for 500x500 -> y in [275,500)
        PixelReader r = sparse(new int[]{300}, new int[]{450}, new int[]{0xDCDFE5});
        Optional<AutoCalibrator.MarkerHit> hit = AutoCalibrator.findCodeCopy(bigWindow(), null, r);
        assertTrue(hit.isPresent(), "code_copy hit expected");
        assertEquals(300, hit.get().x());
        assertEquals(450, hit.get().y());
    }

    @Test
    void findCodeCopyIgnoresMatchesOutsideSearchArea() {
        // y=100 is above 0.55*500=275, outside CODE_COPY area
        PixelReader r = sparse(new int[]{300}, new int[]{100}, new int[]{0xDCDFE5});
        Optional<AutoCalibrator.MarkerHit> hit = AutoCalibrator.findCodeCopy(bigWindow(), null, r);
        assertTrue(hit.isEmpty());
    }
}

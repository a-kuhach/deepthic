package com.freemaf.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coordinates used in these tests MUST fall on the scan grid used by AutoCalibrator.search:
 * scanning starts at (x0, y0) = (floor(leftRatio*W), floor(topRatio*H)) and advances by
 * step = 4 px on both axes. Pixels placed off-grid will not be sampled.
 */
class AutoCalibratorColorSearchTest {

    private static final int BLACK = 0x000000;
    private static final int INPUT_FIELD_RGB = 0x2C2C2E;
    private static final int SEND_ACTIVE_RGB = 0x5686FE;
    private static final int SEND_DISABLED_RGB = 0x3C5082;
    private static final int CODE_COPY_RGB = 0xC9CBD0;

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
        // INPUT_FIELD x0=0 y0=300; grid: (0+4i, 300+4j). (100, 352) is on-grid.
        PixelReader r = sparse(new int[]{100}, new int[]{352}, new int[]{INPUT_FIELD_RGB});
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(bigWindow(), null, r);
        AutoCalibrator.MarkerHit hit = res.hit(CalibrationMarker.INPUT_FIELD).orElseThrow();
        assertEquals(100, hit.x());
        assertEquals(352, hit.y());
        assertFalse(hit.disabledObserved());
    }

    @Test
    void multipleMatchesSameColorTopmostLeftReturnsFirst() throws IOException {
        // INPUT_FIELD uses TOPMOST_LEFT: top-down, left-right scan returns the first match, not the centroid.
        PixelReader r = sparse(
                new int[]{100, 200, 300},
                new int[]{352, 352, 352},
                new int[]{INPUT_FIELD_RGB, INPUT_FIELD_RGB, INPUT_FIELD_RGB});
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(bigWindow(), null, r);
        AutoCalibrator.MarkerHit hit = res.hit(CalibrationMarker.INPUT_FIELD).orElseThrow();
        assertEquals(100, hit.x());
        assertEquals(352, hit.y());
    }

    @Test
    void multipleMatchesSameColorCenterMatchOnSend() throws IOException {
        // SEND keeps CENTER_MATCH: with three matches on the same row the centroid is the middle one.
        PixelReader r = sparse(
                new int[]{320, 400, 480},
                new int[]{403, 403, 403},
                new int[]{SEND_ACTIVE_RGB, SEND_ACTIVE_RGB, SEND_ACTIVE_RGB});
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(bigWindow(), null, r);
        AutoCalibrator.MarkerHit hit = res.hit(CalibrationMarker.SEND).orElseThrow();
        assertEquals(400, hit.x());
        assertEquals(403, hit.y());
    }

    @Test
    void noMatchInArea() throws IOException {
        PixelReader r = (x, y) -> BLACK;
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(bigWindow(), null, r);
        assertTrue(res.hits().isEmpty());
        assertFalse(res.allRequiredFound());
        assertEquals(2, res.missing().size(), "REQUIRED = INPUT_FIELD + SEND");
    }

    @Test
    void disabledColorMatchOnly() throws IOException {
        // SEND x0=300 y0=275; (400, 403) is on-grid: 400-300=100 (x/4=25), 403-275=128 (y/4=32).
        PixelReader r = sparse(new int[]{400}, new int[]{403}, new int[]{SEND_DISABLED_RGB});
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(bigWindow(), null, r);
        AutoCalibrator.MarkerHit hit = res.hit(CalibrationMarker.SEND).orElseThrow();
        assertEquals(400, hit.x());
        assertEquals(403, hit.y());
        assertTrue(hit.disabledObserved());
    }

    @Test
    void areaClippedToClientRect() throws IOException {
        WindowInfo small = new WindowInfo(null, "small", 0, 0, 100, 100);
        // INPUT_FIELD area = (0.0, 0.60, 1.0, 0.78) -> x0=0, y0=60, x1=100, y1=78.
        // On-grid pixel inside: (52, 72). Off-area pixel: (52, 50).
        PixelReader r = sparse(
                new int[]{52, 52},
                new int[]{72, 50},
                new int[]{INPUT_FIELD_RGB, INPUT_FIELD_RGB});
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(small, null, r);
        Optional<AutoCalibrator.MarkerHit> hit = res.hit(CalibrationMarker.INPUT_FIELD);
        assertTrue(hit.isPresent());
        assertEquals(72, hit.get().y());
    }

    @Test
    void calibrateReturnsResultWithAllRequiredFoundForSyntheticCompleteInput() throws IOException {
        // INPUT_FIELD (100, 352) on-grid; SEND (400, 403) on-grid (active color).
        PixelReader r = sparse(
                new int[]{100, 400},
                new int[]{352, 403},
                new int[]{INPUT_FIELD_RGB, SEND_ACTIVE_RGB});
        AutoCalibrator.CalibrationResult res = AutoCalibrator.calibrate(bigWindow(), null, r);
        assertTrue(res.allRequiredFound(), "missing: " + res.missing());
        assertTrue(res.found(CalibrationMarker.INPUT_FIELD));
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
        // CODE_COPY area (0.0, 0.15, 1.0, 0.85) on 500x500 -> x0=0, y0=75, x1=500, y1=425.
        // On-grid pixel inside: (300, 155) -- 155-75=80 (multiple of step 4).
        PixelReader r = sparse(new int[]{300}, new int[]{155}, new int[]{CODE_COPY_RGB});
        Optional<AutoCalibrator.MarkerHit> hit = AutoCalibrator.findCodeCopy(bigWindow(), null, r);
        assertTrue(hit.isPresent(), "code_copy hit expected");
        assertEquals(300, hit.get().x());
        assertEquals(155, hit.get().y());
    }

    @Test
    void findCodeCopyIgnoresMatchesOutsideSearchArea() {
        // y=40 is above 0.15*500=75, outside CODE_COPY area.
        PixelReader r = sparse(new int[]{300}, new int[]{40}, new int[]{CODE_COPY_RGB});
        Optional<AutoCalibrator.MarkerHit> hit = AutoCalibrator.findCodeCopy(bigWindow(), null, r);
        assertTrue(hit.isEmpty());
    }

    @Test
    void findRegenerateFindsHitInColumn() {
        // bigWindow = (0,0,500,500); column scan range y in [200, 475).
        int xR = 125;
        PixelReader r = sparse(new int[]{xR}, new int[]{308}, new int[]{0x9A9FA4});
        AutoCalibrator.MarkerHit hit = AutoCalibrator.findRegenerate(bigWindow(), xR, r);
        assertNotNull(hit);
        assertEquals(xR, hit.x());
        assertEquals(308, hit.y());
    }

    @Test
    void findRegenerateReturnsNullWhenAnchorNotInColumn() {
        // Anchor placed in a different column (x=50), so scanning xR=125 must miss.
        PixelReader r = sparse(new int[]{50}, new int[]{308}, new int[]{0x9A9FA4});
        AutoCalibrator.MarkerHit hit = AutoCalibrator.findRegenerate(bigWindow(), 125, r);
        assertNull(hit);
    }

    @Test
    void findRegenerateReturnsNullWhenAnchorAboveScanRange() {
        // y=100 is above 0.40*500=200, outside the column scan range.
        PixelReader r = sparse(new int[]{125}, new int[]{100}, new int[]{0x9A9FA4});
        AutoCalibrator.MarkerHit hit = AutoCalibrator.findRegenerate(bigWindow(), 125, r);
        assertNull(hit);
    }
}

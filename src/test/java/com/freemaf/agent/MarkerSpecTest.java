package com.freemaf.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerSpecTest {

    @Test
    void forTypeReturnsSpecForEveryRequiredMarker() {
        for (CalibrationMarker marker : AutoCalibrator.CalibrationResult.REQUIRED_FOR_CALIBRATION) {
            MarkerSpec spec = MarkerSpec.forType(marker);
            assertNotNull(spec, "spec null for " + marker);
            assertFalse(spec.activeColorsHex().isEmpty(), "no active anchors for " + marker);
            MarkerSpec.SearchArea a = spec.area();
            assertTrue(a.leftRatio() >= 0.0 && a.leftRatio() <= 1.0);
            assertTrue(a.topRatio() >= 0.0 && a.topRatio() <= 1.0);
            assertTrue(a.rightRatio() >= 0.0 && a.rightRatio() <= 1.0);
            assertTrue(a.bottomRatio() >= 0.0 && a.bottomRatio() <= 1.0);
            assertTrue(a.leftRatio() < a.rightRatio());
            assertTrue(a.topRatio() < a.bottomRatio());
        }
    }

    @Test
    void searchAreaValidationRejectsBadRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> new MarkerSpec.SearchArea(-0.1, 0.0, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new MarkerSpec.SearchArea(0.0, 0.0, 1.5, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new MarkerSpec.SearchArea(0.6, 0.0, 0.4, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new MarkerSpec.SearchArea(0.0, 0.7, 0.5, 0.3));
    }

    @Test
    void forTypeRejectsAnchorsWithoutSpecInStageS3() {
        assertThrows(IllegalArgumentException.class, () -> MarkerSpec.forType(CalibrationMarker.REGENERATE));
        assertThrows(IllegalArgumentException.class, () -> MarkerSpec.forType(CalibrationMarker.COPY_LAST));
    }

    @Test
    void codeCopyAnchorExists() {
        MarkerSpec spec = MarkerSpec.forType(CalibrationMarker.CODE_COPY);
        assertNotNull(spec, "spec null for CODE_COPY");
        // Since S5, CODE_COPY uses dynamic light-gray matching (dynamicLightGray=true);
        // the fixed anchor list is intentionally empty.
        assertTrue(spec.dynamicLightGray() || !spec.activeColorsHex().isEmpty(),
                "code_copy must have either explicit anchors or dynamicLightGray=true");
        MarkerSpec.SearchArea a = spec.area();
        assertTrue(a.leftRatio() >= 0.0 && a.leftRatio() <= 1.0);
        assertTrue(a.topRatio() >= 0.0 && a.topRatio() <= 1.0);
        assertTrue(a.rightRatio() >= 0.0 && a.rightRatio() <= 1.0);
        assertTrue(a.bottomRatio() >= 0.0 && a.bottomRatio() <= 1.0);
        assertTrue(a.leftRatio() < a.rightRatio());
        assertTrue(a.topRatio() < a.bottomRatio());
        assertEquals(MarkerSpec.SelectionRule.FIRST_MATCH, spec.rule());
    }
}

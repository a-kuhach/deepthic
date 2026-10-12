package com.freemaf.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationResultTest {

    @Test
    void foundReturnsTrueOnlyForPopulatedHit() {
        Map<CalibrationMarker, AutoCalibrator.MarkerHit> hits = new LinkedHashMap<>();
        hits.put(CalibrationMarker.INPUT_FIELD,
                new AutoCalibrator.MarkerHit(100, 200, "333335", false));
        AutoCalibrator.CalibrationResult r = new AutoCalibrator.CalibrationResult(Map.of(), hits);
        assertTrue(r.found(CalibrationMarker.INPUT_FIELD));
        assertFalse(r.found(CalibrationMarker.SEND));
    }

    @Test
    void allRequiredFoundTrueOnlyWhenAllRequiredMarkersPresent() {
        Map<CalibrationMarker, AutoCalibrator.MarkerHit> hits = new LinkedHashMap<>();
        for (CalibrationMarker m : AutoCalibrator.CalibrationResult.REQUIRED_FOR_CALIBRATION) {
            hits.put(m, new AutoCalibrator.MarkerHit(10, 10, "AAAAAA", false));
        }
        AutoCalibrator.CalibrationResult full = new AutoCalibrator.CalibrationResult(Map.of(), hits);
        assertTrue(full.allRequiredFound());

        hits.remove(CalibrationMarker.SEND);
        AutoCalibrator.CalibrationResult partial = new AutoCalibrator.CalibrationResult(Map.of(), hits);
        assertFalse(partial.allRequiredFound());
    }

    @Test
    void missingReturnsAbsentRequiredMarkers() {
        Map<CalibrationMarker, AutoCalibrator.MarkerHit> hits = new LinkedHashMap<>();
        hits.put(CalibrationMarker.INPUT_FIELD, new AutoCalibrator.MarkerHit(1, 1, "0", false));
        hits.put(CalibrationMarker.SEARCH, new AutoCalibrator.MarkerHit(2, 2, "0", false));
        AutoCalibrator.CalibrationResult r = new AutoCalibrator.CalibrationResult(Map.of(), hits);
        List<CalibrationMarker> missing = r.missing();
        assertTrue(missing.contains(CalibrationMarker.DEEPTHINK));
        assertTrue(missing.contains(CalibrationMarker.SEND));
        assertFalse(missing.contains(CalibrationMarker.INPUT_FIELD));
        assertFalse(missing.contains(CalibrationMarker.SEARCH));
    }

    @Test
    void disabledObservedFlagIsPreserved() {
        Map<CalibrationMarker, AutoCalibrator.MarkerHit> hits = new LinkedHashMap<>();
        hits.put(CalibrationMarker.SEND,
                new AutoCalibrator.MarkerHit(400, 400, "3C5082", true));
        AutoCalibrator.CalibrationResult r = new AutoCalibrator.CalibrationResult(Map.of(), hits);
        AutoCalibrator.MarkerHit hit = r.hit(CalibrationMarker.SEND).orElseThrow();
        assertTrue(hit.disabledObserved());
        assertEquals("3C5082", hit.observedColorHex());
    }

    @Test
    void legacyColorsConstructorStillWorks() {
        AutoCalibrator.CalibrationResult r =
                new AutoCalibrator.CalibrationResult(Map.of("input_field", "333335"));
        assertEquals("333335", r.colors().get("input_field"));
        assertFalse(r.allRequiredFound());
        assertTrue(r.hits().isEmpty());
    }
}

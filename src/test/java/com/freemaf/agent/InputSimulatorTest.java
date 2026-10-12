package com.freemaf.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputSimulatorTest {

    @Test
    void missingCalibrationThrowsOnTypeTextWithoutEnter() {
        InputSimulator sim = new InputSimulator(new Config(), null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sim.typeTextWithoutEnter("hello"));
        assertEquals(true, ex.getMessage().toLowerCase().contains("calibration not set"));
    }

    @Test
    void missingMarkerInCalibrationThrows() {
        InputSimulator sim = new InputSimulator(new Config(), null);
        sim.setCalibration(new AutoCalibrator.CalibrationResult(Map.of()));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sim.requireHit(CalibrationMarker.INPUT_FIELD));
        assertEquals(true, ex.getMessage().contains("input_field"));
    }

    @Test
    void requireHitResolvesCoordinatesFromCalibration() {
        InputSimulator sim = new InputSimulator(new Config(), null);
        Map<CalibrationMarker, AutoCalibrator.MarkerHit> hits = new LinkedHashMap<>();
        hits.put(CalibrationMarker.INPUT_FIELD,
                new AutoCalibrator.MarkerHit(999, 888, "333335", false));
        sim.setCalibration(new AutoCalibrator.CalibrationResult(Map.of(), hits));
        AutoCalibrator.MarkerHit hit = sim.requireHit(CalibrationMarker.INPUT_FIELD);
        assertEquals(999, hit.x());
        assertEquals(888, hit.y());
    }

    @Test
    void requireHitThrowsForMarkerMissingFromPartialCalibration() {
        InputSimulator sim = new InputSimulator(new Config(), null);
        Map<CalibrationMarker, AutoCalibrator.MarkerHit> hits = new LinkedHashMap<>();
        hits.put(CalibrationMarker.INPUT_FIELD,
                new AutoCalibrator.MarkerHit(1, 1, "333335", false));
        sim.setCalibration(new AutoCalibrator.CalibrationResult(Map.of(), hits));
        assertThrows(IllegalStateException.class,
                () -> sim.requireHit(CalibrationMarker.SEND));
    }
}

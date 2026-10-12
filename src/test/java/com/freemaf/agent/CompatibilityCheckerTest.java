package com.freemaf.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityCheckerTest {

    @Test
    void precheck1920x1080Compatible() {
        CompatibilityChecker.CompatibilityResult r =
                CompatibilityChecker.precheck(1920, 1080);
        assertEquals(CompatibilityChecker.CompatibilityResult.Status.COMPATIBLE, r.status());
    }

    @Test
    void precheck1919x1080Incompatible() {
        CompatibilityChecker.CompatibilityResult r =
                CompatibilityChecker.precheck(1919, 1080);
        assertEquals(CompatibilityChecker.CompatibilityResult.Status.INCOMPATIBLE_PC, r.status());
        assertNotNull(r.diagnostic());
        assertTrue(r.diagnostic().contains("требуется минимум 1920x1080"), r.diagnostic());
    }

    @Test
    void precheck1920x1079Incompatible() {
        CompatibilityChecker.CompatibilityResult r =
                CompatibilityChecker.precheck(1920, 1079);
        assertEquals(CompatibilityChecker.CompatibilityResult.Status.INCOMPATIBLE_PC, r.status());
        assertTrue(r.diagnostic().contains("требуется минимум 1920x1080"), r.diagnostic());
    }

    @Test
    void precheckLargerCompatible() {
        CompatibilityChecker.CompatibilityResult r =
                CompatibilityChecker.precheck(2560, 1440);
        assertEquals(CompatibilityChecker.CompatibilityResult.Status.COMPATIBLE, r.status());
    }

    @Test
    void evaluateExhaustedDiagnostics() {
        WindowSizeResult exhausted = new WindowSizeResult(
                700, 500,
                WindowSizeResult.Status.EXHAUSTED,
                new ProbeResult(false, null, "not all markers visible"),
                "reached bounds: 700x500, effectiveMax=700x500, probe=not all markers visible");
        CompatibilityChecker.CompatibilityResult r =
                CompatibilityChecker.evaluateExhausted(exhausted, 700, 500);
        assertEquals(CompatibilityChecker.CompatibilityResult.Status.INCOMPATIBLE_PC, r.status());
        String d = r.diagnostic();
        assertNotNull(d);
        assertTrue(d.contains("700"), d);
        assertTrue(d.contains("500"), d);
        assertTrue(d.contains("workspace=700x500"), d);
        assertTrue(d.contains("reached bounds"), d);
    }
}

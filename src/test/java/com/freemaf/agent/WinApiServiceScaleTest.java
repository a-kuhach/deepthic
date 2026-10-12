package com.freemaf.agent;

import com.freemaf.agent.winapi.WinApiService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WinApiServiceScaleTest {

    @Test
    void dpiToPercent96Returns100() {
        assertEquals(100, WinApiService.dpiToPercent(96));
    }

    @Test
    void dpiToPercent120Returns125() {
        assertEquals(125, WinApiService.dpiToPercent(120));
    }

    @Test
    void dpiToPercent144Returns150() {
        assertEquals(150, WinApiService.dpiToPercent(144));
    }

    @Test
    void dpiToPercent216Returns225() {
        assertEquals(225, WinApiService.dpiToPercent(216));
    }

    @Test
    void dpiToPercentZeroReturnsZero() {
        assertEquals(0, WinApiService.dpiToPercent(0));
    }

    @Test
    void dpiToPercentNegativeReturnsZero() {
        assertEquals(0, WinApiService.dpiToPercent(-1));
    }

    @Test
    void windowScalePercentNullReturnsZero() {
        assertEquals(0, WinApiService.windowScalePercent(null));
    }

    @Test
    void systemScalePercentReturnsSupportedOrZero() {
        int v = WinApiService.systemScalePercent();
        assertTrue(v == 0 || v >= 50, "systemScalePercent returned: " + v);
    }
}

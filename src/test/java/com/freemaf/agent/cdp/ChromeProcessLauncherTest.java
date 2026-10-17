package com.freemaf.agent.cdp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromeProcessLauncherTest {

    @Test
    void resolveProfilePathIsAbsoluteAndNormalized() {
        Path p = ChromeProcessLauncher.resolveProfilePath();
        assertNotNull(p);
        assertTrue(p.isAbsolute(), "profile path must be absolute");
        assertEquals("cdp-profile", p.getFileName().toString());
    }
}

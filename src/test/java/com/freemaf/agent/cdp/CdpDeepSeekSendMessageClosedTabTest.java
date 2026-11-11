package com.freemaf.agent.cdp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdpDeepSeekSendMessageClosedTabTest {

    @Test
    void blankPromptStillRejectedFirst() {
        CdpSession sess = org.mockito.Mockito.mock(CdpSession.class);
        CdpDeepSeek ds = new CdpDeepSeek(sess, null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ds.sendMessage("  "));
        assertTrue(ex.getMessage().contains("blank"));
    }
}

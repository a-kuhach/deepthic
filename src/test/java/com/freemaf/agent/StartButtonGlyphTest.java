package com.freemaf.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StartButtonGlyphTest {
    private static final String PLAY = "\u25B6";
    private static final String SQUARE = "\u25A0";

    @Test
    void stoppedReturnsPlayGlyph() {
        assertEquals(PLAY, StartButtonGlyph.glyph(StatusMarker.State.STOPPED, false));
        assertEquals(PLAY, StartButtonGlyph.glyph(StatusMarker.State.STOPPED, true));
    }

    @Test
    void pausedReturnsPlayGlyph() {
        assertEquals(PLAY, StartButtonGlyph.glyph(StatusMarker.State.PAUSED, false));
        assertEquals(PLAY, StartButtonGlyph.glyph(StatusMarker.State.PAUSED, true));
    }

    @Test
    void workingNotUserPausedReturnsSquareGlyph() {
        assertEquals(SQUARE, StartButtonGlyph.glyph(StatusMarker.State.WORKING, false));
    }

    @Test
    void workingUserPausedReturnsPlayGlyph() {
        assertEquals(PLAY, StartButtonGlyph.glyph(StatusMarker.State.WORKING, true));
    }

    @Test
    void planningNotUserPausedReturnsSquareGlyph() {
        assertEquals(SQUARE, StartButtonGlyph.glyph(StatusMarker.State.PLANNING, false));
    }

    @Test
    void planningUserPausedReturnsPlayGlyph() {
        assertEquals(PLAY, StartButtonGlyph.glyph(StatusMarker.State.PLANNING, true));
    }
}

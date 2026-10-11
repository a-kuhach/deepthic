package com.freemaf.agent;
public final class StartButtonGlyph {
    private StartButtonGlyph() {
    }

    public static String glyph(StatusMarker.State state, boolean userPaused) {
        if (state != null
                && (state == StatusMarker.State.WORKING || state == StatusMarker.State.PLANNING)
                && !userPaused) {
            return "\u25A0";
        }
        return "\u25B6";
    }
}

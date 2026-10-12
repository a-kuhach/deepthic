package com.freemaf.agent;

public enum CalibrationMarker {
    INPUT_FIELD("input_field"),
    DEEPTHINK("deepthink"),
    SEARCH("search"),
    SEND("send"),
    REGENERATE("regenerate"),
    COPY_LAST("copy_last"),
    RETRY("retry");

    private final String id;

    CalibrationMarker(String id) { this.id = id; }

    public String id() { return id; }

    public static CalibrationMarker fromId(String id) {
        if (id == null) return null;
        for (CalibrationMarker m : values()) {
            if (m.id.equalsIgnoreCase(id)) return m;
        }
        return null;
    }
}

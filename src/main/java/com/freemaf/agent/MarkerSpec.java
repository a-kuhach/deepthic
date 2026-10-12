package com.freemaf.agent;

import java.util.List;

public record MarkerSpec(CalibrationMarker type,
                         List<String> activeColorsHex,
                         List<String> disabledColorsHex,
                         SearchArea area,
                         SelectionRule rule,
                         int tolerance,
                         int step) {

    public enum SelectionRule { FIRST_MATCH, CENTER_MATCH, TOPMOST_LEFT }

    public record SearchArea(double leftRatio, double topRatio, double rightRatio, double bottomRatio) {
        public SearchArea {
            if (leftRatio < 0.0 || leftRatio > 1.0) throw new IllegalArgumentException("leftRatio out of [0,1]: " + leftRatio);
            if (topRatio < 0.0 || topRatio > 1.0) throw new IllegalArgumentException("topRatio out of [0,1]: " + topRatio);
            if (rightRatio < 0.0 || rightRatio > 1.0) throw new IllegalArgumentException("rightRatio out of [0,1]: " + rightRatio);
            if (bottomRatio < 0.0 || bottomRatio > 1.0) throw new IllegalArgumentException("bottomRatio out of [0,1]: " + bottomRatio);
            if (leftRatio >= rightRatio) throw new IllegalArgumentException("leftRatio must be < rightRatio");
            if (topRatio >= bottomRatio) throw new IllegalArgumentException("topRatio must be < bottomRatio");
        }
    }

    public MarkerSpec {
        if (type == null) throw new IllegalArgumentException("type is null");
        if (area == null) throw new IllegalArgumentException("area is null");
        if (rule == null) throw new IllegalArgumentException("rule is null");
        if (tolerance < 0) throw new IllegalArgumentException("tolerance must be >= 0");
        if (step <= 0) throw new IllegalArgumentException("step must be >= 1");
    }

    /** Backward-compatible: exact match (tolerance=0), default step=4. */
    public MarkerSpec(CalibrationMarker type,
                      List<String> activeColorsHex,
                      List<String> disabledColorsHex,
                      SearchArea area,
                      SelectionRule rule) {
        this(type, activeColorsHex, disabledColorsHex, area, rule, 0, 4);
    }

    /** Backward-compatible: explicit tolerance, default step=4. */
    public MarkerSpec(CalibrationMarker type,
                      List<String> activeColorsHex,
                      List<String> disabledColorsHex,
                      SearchArea area,
                      SelectionRule rule,
                      int tolerance) {
        this(type, activeColorsHex, disabledColorsHex, area, rule, tolerance, 4);
    }

    public static MarkerSpec forType(CalibrationMarker type) {
        if (type == null) throw new IllegalArgumentException("type is null");
        return switch (type) {
            case INPUT_FIELD -> new MarkerSpec(type,
                    List.of("2C2C2E"), List.of(),
                    new SearchArea(0.0, 0.60, 1.0, 0.78),
                    SelectionRule.TOPMOST_LEFT);
            case SEND -> new MarkerSpec(type,
                    List.of("5686FE"), List.of("3C5082"),
                    new SearchArea(0.60, 0.55, 1.0, 1.0),
                    SelectionRule.CENTER_MATCH);
            case RETRY -> new MarkerSpec(type,
                    List.of("F5AC31"), List.of(),
                    new SearchArea(0.0, 0.25, 1.0, 0.85),
                    SelectionRule.FIRST_MATCH);
            case CODE_COPY -> new MarkerSpec(type,
                    List.of("C9CBD0"), List.of(),
                    new SearchArea(0.0, 0.30, 1.0, 0.55),
                    SelectionRule.FIRST_MATCH,
                    0,
                    1);
            case REGENERATE, COPY_LAST -> throw new IllegalArgumentException(
                    "No MarkerSpec anchors for " + type.id() + " in Stage S3");
        };
    }
}

package djmax;

import java.awt.Color;

/**
 * Alternate, more-distinguishable lane/judgment palettes for common color vision deficiencies.
 * This does not simulate a deficiency (that would make the game harder to read for the very
 * players it's meant to help) — it swaps in palettes chosen so the 4 lanes and 3 judgment tiers
 * stay visually distinct under each condition, the standard practical approach for game
 * accessibility (compare: colorblind-safe categorical palettes like Okabe-Ito).
 */
enum ColorVision {
    NORMAL, PROTANOPIA, DEUTERANOPIA, TRITANOPIA;

    static ColorVision fromName(String name, ColorVision fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** The 4 lane accent colors (lane 0..3) for this mode. NORMAL's are Niah's own key-art palette
     *  (hot pink, light pink, purple, deep magenta) — the earlier DJMAX-style set is preserved in
     *  the source backup taken before this retheme. The three colorblind palettes below are left
     *  untouched: they're chosen specifically for lane-to-lane distinguishability under each
     *  condition, not for matching a character's colors, so retheming them would work against
     *  their actual job. */
    Color[] laneColors() {
        return switch (this) {
            case NORMAL -> new Color[]{
                    new Color(255, 70, 150), new Color(255, 170, 205),
                    new Color(180, 120, 220), new Color(205, 40, 110)};
            // Okabe-Ito inspired: avoids red/green pairs, leans on blue/orange/yellow/purple contrast.
            case PROTANOPIA, DEUTERANOPIA -> new Color[]{
                    new Color(0, 114, 178), new Color(230, 159, 0),
                    new Color(240, 228, 66), new Color(204, 121, 167)};
            case TRITANOPIA -> new Color[]{
                    new Color(213, 94, 0), new Color(0, 158, 115),
                    new Color(230, 159, 0), new Color(204, 121, 167)};
        };
    }

    /** PERFECT/GREAT/GOOD judgment colors for this mode. */
    Color perfectColor() {
        return switch (this) {
            case PROTANOPIA, DEUTERANOPIA -> new Color(86, 180, 233);
            case TRITANOPIA -> new Color(240, 228, 66);
            default -> new Color(255, 45, 138);
        };
    }

    Color greatColor() {
        return switch (this) {
            case PROTANOPIA, DEUTERANOPIA -> new Color(0, 114, 178);
            case TRITANOPIA -> new Color(0, 158, 115);
            default -> new Color(190, 140, 230);
        };
    }

    Color goodColor() {
        return switch (this) {
            case PROTANOPIA, DEUTERANOPIA -> new Color(230, 159, 0);
            case TRITANOPIA -> new Color(213, 94, 0);
            default -> new Color(255, 180, 210);
        };
    }
}

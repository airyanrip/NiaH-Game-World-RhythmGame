package djmax;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * A single global scale factor for the Swing UI (Hub, Settings, chart editor, lane preview) driven
 * by the actual window size relative to a 960x680 baseline — so a bigger window (or fullscreen on
 * a big monitor) grows the interface instead of every label staying pinned at a fixed pixel size
 * no matter how much room there is. {@link RhythmPanel}'s own gameplay canvas already scales this
 * way through its own {@code Graphics2D} transform (see {@code updateRenderTransform}); this
 * covers the ordinary Swing screens around it, which don't have one — deriving a font there
 * doesn't touch how that one paints itself.
 */
final class UiScale {
    private static final String BASE_SIZE_KEY = "djmax.uiscale.baseFontSize";
    private static final int BASE_WIDTH = 960;
    private static final int BASE_HEIGHT = 680;
    private static final double MIN_SCALE = 0.85;
    private static final double MAX_SCALE = 1.6;

    private static double factor = 1.0;
    private static final List<Runnable> listeners = new ArrayList<>();

    private UiScale() {
    }

    static double factor() {
        return factor;
    }

    /** Scaled font size for a design-time point size, e.g. {@code UiScale.f(14f)}. */
    static float f(float baseSize) {
        return (float) (baseSize * factor);
    }

    /** Scaled pixel dimension — row height, thumbnail size, and the like. */
    static int px(int baseSize) {
        return (int) Math.round(baseSize * factor);
    }

    /** Recomputes the factor from an actual window size; notifies listeners only if it changed
     *  enough to matter (skips a rebuild for a 1px resize-drag jitter). Callers still need to call
     *  {@link #rescale} separately wherever a component tree's fonts should follow along — this
     *  only updates the number and tells listeners it moved. */
    static void update(int width, int height) {
        double raw = Math.min(width / (double) BASE_WIDTH, height / (double) BASE_HEIGHT);
        double next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, raw));
        if (Math.abs(next - factor) < 0.02) {
            return;
        }
        factor = next;
        for (Runnable r : new ArrayList<>(listeners)) {
            r.run();
        }
    }

    /** A persistent screen registers here for anything {@link #rescale} alone doesn't cover — e.g.
     *  the Hub's song list uses a fixed {@code JList} cell height, a plain pixel number with no
     *  font of its own to walk. Pair with {@link #removeListener} when that screen closes, or a
     *  closed screen's stale callback keeps firing (and doing pointless work) on every future
     *  resize for the rest of the process. */
    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    /** Rescales every font in {@code root}'s component tree (itself included) to the current
     *  factor, in place. Each component's original ("design") size is remembered via a client
     *  property the first time it's touched, so rescaling repeatedly (e.g. several resizes without
     *  a restart) always multiplies from that original size, never compounding on an already-
     *  scaled one. */
    static void rescale(Component root) {
        if (root instanceof JComponent jc) {
            Font font = jc.getFont();
            if (font != null) {
                Object stored = jc.getClientProperty(BASE_SIZE_KEY);
                float base = stored instanceof Float storedSize ? storedSize : font.getSize2D();
                if (!(stored instanceof Float)) {
                    jc.putClientProperty(BASE_SIZE_KEY, base);
                }
                float scaled = base * (float) factor;
                if (Math.abs(scaled - font.getSize2D()) > 0.01f) {
                    jc.setFont(font.deriveFont(scaled));
                }
            }
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                rescale(child);
            }
        }
    }
}

package djmax;

import java.awt.Rectangle;

/** The "push the desktop character(s) out of the way while a song plays, then bring them back"
 *  feature — {@link HubWindow} calls this without knowing or caring whether there's a real
 *  mascot on screen at all. {@link LittleLumiMascotIntegration} does the actual work against
 *  Little LUMI's {@code PluginContext}; the standalone build (no desktop character exists at all)
 *  uses {@link #NONE}, which does nothing. Kept as a plain interface with no Little LUMI SDK types
 *  in its signature so {@link HubWindow} compiles without that SDK on the classpath at all. */
interface MascotIntegration {
    /** No mascot to push aside — the standalone build's only implementation. */
    MascotIntegration NONE = new MascotIntegration() {
        @Override
        public void pushAside(Rectangle gameBounds) {
        }

        @Override
        public void release() {
        }
    };

    /** Called once, right as gameplay starts — {@code gameBounds} is the game window's current
     *  screen bounds, so an implementation knows what it's clearing space around. */
    void pushAside(Rectangle gameBounds);

    /** Called once, back at the Hub (song finished, quit, or the window closed) — undoes whatever
     *  {@link #pushAside} did. Safe to call even if {@link #pushAside} was never called. */
    void release();
}

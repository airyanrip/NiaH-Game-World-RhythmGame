package djmax;

import java.io.File;
import java.util.List;

/** A playable song: the source audio file, a display title, its length, and the generated notes. */
public final class Chart {
    public final File audioFile;
    public final String title;
    public final long lengthMs;
    public final List<Note> notes;
    /** Scroll-speed multiplier on top of the player's own note-speed setting — {@link ChartGenerator}
     *  raises this for fast/dense tracks so notes fall faster instead of just cramming closer
     *  together in time (see its {@code estimateSpeedMultiplier}). 1.0 (neutral) for hand-authored
     *  chart-editor charts, which are already timed by ear and don't need the adjustment. */
    public final double speedMultiplier;

    public Chart(File audioFile, String title, long lengthMs, List<Note> notes) {
        this(audioFile, title, lengthMs, notes, 1.0);
    }

    public Chart(File audioFile, String title, long lengthMs, List<Note> notes, double speedMultiplier) {
        this.audioFile = audioFile;
        this.title = title;
        this.lengthMs = lengthMs;
        this.notes = notes;
        this.speedMultiplier = speedMultiplier;
    }
}

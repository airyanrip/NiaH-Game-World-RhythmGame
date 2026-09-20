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

    /** A copy of {@code original} with every note's timing (and {@link #lengthMs}) divided by
     *  {@code speed} — what "노트 속도 빠르게 시작" actually needs: the audio itself plays back
     *  {@code speed}x faster (see {@code AudioResampler}), which compresses its own timeline by
     *  the same factor, so a note's hit time has to shrink to match or it drifts out of sync with
     *  the beat more and more as the song goes on. {@code speedMultiplier} (a separate, purely
     *  visual scroll-speed knob) is carried over unchanged. Returns {@code original} itself when
     *  {@code speed == 1.0} — no new list, no rounding noise, since there's nothing to adjust. */
    static Chart withSpeed(Chart original, double speed) {
        if (speed == 1.0) {
            return original;
        }
        List<Note> scaled = new java.util.ArrayList<>(original.notes.size());
        for (Note n : original.notes) {
            scaled.add(new Note(Math.round(n.timeMs / speed), Math.round(n.endTimeMs / speed), n.lane, n.kind));
        }
        long scaledLengthMs = Math.round(original.lengthMs / speed);
        return new Chart(original.audioFile, original.title, scaledLengthMs, scaled, original.speedMultiplier);
    }
}

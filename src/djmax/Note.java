package djmax;

/**
 * One note: when to press it, which of the 4 lanes it's in, and — for a long note —
 * when to release it. {@code endTimeMs == timeMs} means a normal tap note.
 */
public final class Note {
    /** NORMAL: one of the 4 regular lane keys. SIDE: DJMAX Respect V's "side track" note — a
     *  full-width bar judged by the dedicated side key (see {@link RhythmSettings#sideKey()}),
     *  not any lane key; {@link #lane} is meaningless for these (kept at -1). */
    public enum Kind { NORMAL, SIDE }

    public final long timeMs;
    public final long endTimeMs;
    public final int lane;
    public final Kind kind;

    /** The head has been resolved — hit (tap or long-note head) or auto-missed. */
    public boolean judged;
    /** Long note only: the head was hit successfully and the key is expected to stay down. */
    public boolean holding;
    /** Long note only: the whole note (head+tail) is fully resolved — nothing left to judge or draw. */
    public boolean tailJudged;
    /** Long note only: the hold was let go too early (or the head was missed) and never recovered. */
    public boolean holdFailed;
    /** Long note only: the head's own timing accuracy (0..100), banked here so the tail's judgment
     *  can later combine with it — the note's final accuracy is {@code min(head, tail)}, not
     *  whichever of the two happens to resolve last. -1 until the head is actually judged. */
    public double headAccuracy = -1;
    /** Long note only: the tier ("PERFECT"/"GREAT"/"GOOD") that produced {@link #headAccuracy} —
     *  kept alongside it so the tail's resolution can pick whichever of the two tiers actually
     *  belongs to the lower accuracy, without having to reverse-derive a tier from a raw number. */
    public String headTier;
    /** Long note only: chart-time ms ({@code RhythmPanel.nowMs()}) of the next tick-combo bump while
     *  correctly held — -1 before the head is hit. See {@code RhythmPanel}'s tick-combo loop. */
    public long nextTickAtMs = -1;
    /** Long note only: the key was let go before the tail's window — not an immediate BREAK, since
     *  a very fast re-press within {@code RhythmPanel}'s recovery grace window can still pick the
     *  hold back up with no penalty (see {@code RhythmPanel.tryRecoverHold}). Only actually fails
     *  (see {@link #holdFailed}) once that grace window expires without a re-press. */
    public boolean releasedEarly;
    /** Wall-clock ms ({@code System.currentTimeMillis()}) of the early release above — the grace
     *  window is measured from here. */
    public long releasedAtMs;

    public Note(long timeMs, int lane) {
        this(timeMs, timeMs, lane);
    }

    public Note(long timeMs, long endTimeMs, int lane) {
        this(timeMs, endTimeMs, lane, Kind.NORMAL);
    }

    public Note(long timeMs, long endTimeMs, int lane, Kind kind) {
        this.timeMs = timeMs;
        this.endTimeMs = Math.max(timeMs, endTimeMs);
        this.lane = lane;
        this.kind = kind;
    }

    public boolean isHold() {
        return endTimeMs > timeMs;
    }
}

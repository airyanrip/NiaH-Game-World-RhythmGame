package djmax;

/** Tunes {@link ChartGenerator}'s onset detector: higher sensitivity and a shorter minimum gap means more notes. */
public enum Difficulty {
    // chordThresholdRatio: how far an onset's overall energy has to spike above its trailing
    // average before a side-track note (see ChartGenerator, Note.Kind.SIDE) gets placed alongside
    // it — raised well above the lane-note thresholdRatio on Normal/Hard so a side note reads as a
    // rare, deliberate "big hit" moment, not something that shows up on every other beat. Easy
    // skips them entirely (Double.MAX_VALUE never clears the bar) — "쉬움에선 나오지 않게" — a
    // beginner chart never demands the extra side key on top of the 4 lanes. sideMinGapMs
    // additionally spaces side notes out in time from each other on Normal/Hard (lane notes already
    // get this via minGapMs, but that check is global, not per-kind, so it never applied to side
    // notes) — a player can also turn side notes off entirely regardless of difficulty from the
    // Hub (see RhythmSettings.sideNotesEnabled()).
    // minGapMs is the cross-lane pacing floor only — an earlier attempt also widened this per-song
    // for "fast/dense" tracks, but that widening was driven by a global note-count/tempo estimate
    // that couldn't tell "genuinely fast song" from "difficulty working as designed" and ended up
    // thinning out normal charts across the board. The actual "one lane getting spammed" problem is
    // instead handled locally in ChartGenerator.pickOnsets (see SAME_LANE_GAP_FACTOR), which only
    // widens spacing for the specific lane that would otherwise repeat too quickly — everything else
    // keeps this difficulty's original pacing/note count. Chart.speedMultiplier (see ChartGenerator)
    // separately speeds up fall for charts that end up denser than typical, derived from the actual
    // generated note count rather than a guess at the song's tempo.
    // scoreMultiplier: a deliberate per-difficulty scaling on every note's points (see
    // RhythmPanel.awardNote) — before this, every difficulty scored through the exact same formula,
    // so the only reason totals ever differed by difficulty was the incidental side effect of HARD
    // simply having more notes than EASY for the same song, not any real reward for the harder
    // pattern. This makes that difference deliberate instead: HARD is worth noticeably more per
    // note, EASY noticeably less, matching the usual DJMAX-style "harder chart, bigger score
    // ceiling" convention.
    EASY(1.60, 220, Double.MAX_VALUE, Long.MAX_VALUE, 0.8),
    NORMAL(1.35, 120, 3.2, 1100, 1.0),
    HARD(1.15, 70, 2.6, 800, 1.3),
    // "채보가 빠른 버전" — a denser, faster-paced chart at the song's own real tempo (nothing here
    // touches playback speed or pitch; an earlier attempt at "faster notes" resampled the audio
    // itself instead, which desynced from the beat and wasn't what was wanted). Continues the same
    // EASY→NORMAL→HARD step size one more notch, not an arbitrary jump.
    FAST(1.05, 50, 2.2, 600, 1.6);

    final double thresholdRatio;
    final long minGapMs;
    final double chordThresholdRatio;
    final long sideMinGapMs;
    final double scoreMultiplier;

    Difficulty(double thresholdRatio, long minGapMs, double chordThresholdRatio, long sideMinGapMs, double scoreMultiplier) {
        this.thresholdRatio = thresholdRatio;
        this.minGapMs = minGapMs;
        this.chordThresholdRatio = chordThresholdRatio;
        this.sideMinGapMs = sideMinGapMs;
        this.scoreMultiplier = scoreMultiplier;
    }

    static Difficulty fromName(String name, Difficulty fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}

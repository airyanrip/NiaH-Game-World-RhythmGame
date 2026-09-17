package djmax;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a playable {@link Chart} from a WAV file using simple energy-based onset detection:
 * short-time energy per window, compared against a trailing local average. Not beat-accurate
 * like a real charting tool — it is an MVP auto-chart, good enough to turn "some rhythmic audio"
 * into "something playable", not a substitute for a hand-authored chart.
 * <p>
 * Lane choice is frequency-aware rather than round-robin: the signal is split into 4 crude bands
 * (bass / low-mid / high-mid / treble) with cascaded one-pole filters, and each onset picks the
 * lane whose band is loudest relative to its own trailing average at that moment — so a kick tends
 * to land on one lane and a hi-hat on another, DJMAX-style, instead of cycling mechanically.
 * <p>
 * Only 16-bit PCM WAV is supported (the common case); anything else is rejected with a clear
 * message rather than misread.
 */
public final class ChartGenerator {

    private static final int WINDOW = 1024;
    private static final int HOP = 512;
    private static final double AVG_SPAN_SECONDS = 1.5;  // how far back the trailing average looks

    // A note becomes a long note when its own band's energy stays elevated after the onset — a
    // held chord or sustained vocal note, not a sharp hit. Ratio is relative to the onset's own
    // peak, not the trailing average, so this only fires on genuinely sustained sound.
    private static final double SUSTAIN_RATIO = 0.35;
    private static final long MIN_HOLD_MS = 320;
    private static final long MAX_HOLD_MS = 2500;

    // Crossover points splitting the signal into 4 bands (Hz).
    private static final double CUTOFF_LOW = 250;
    private static final double CUTOFF_MID = 1000;
    private static final double CUTOFF_HIGH = 4000;

    private ChartGenerator() {
    }

    public static Chart fromWav(File wavFile) throws Exception {
        return fromWav(wavFile, titleFrom(wavFile), Difficulty.NORMAL);
    }

    public static Chart fromWav(File wavFile, String title) throws Exception {
        return fromWav(wavFile, title, Difficulty.NORMAL);
    }

    public static Chart fromWav(File wavFile, String title, Difficulty difficulty) throws Exception {
        AudioFormat format;
        byte[] bytes;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wavFile)) {
            format = in.getFormat();
            if (format.getSampleSizeInBits() != 16 || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                throw new IllegalArgumentException("16비트 PCM WAV만 지원합니다 (현재 형식: " + format + ")");
            }
            bytes = in.readAllBytes();
        }

        boolean bigEndian = format.isBigEndian();
        int channels = format.getChannels();
        float sampleRate = format.getSampleRate();
        int bytesPerFrame = 2 * channels;
        int frameCount = bytes.length / bytesPerFrame;

        double[] samples = downmixToMono(bytes, frameCount, channels, bytesPerFrame, bigEndian);
        double[] energy = windowEnergy(samples, frameCount);
        double[][] bandEnergy = bandEnergies(samples, frameCount, sampleRate);
        long lengthMs = (long) (frameCount / (double) sampleRate * 1000);

        List<Note> notes = pickOnsets(energy, bandEnergy, sampleRate, difficulty);
        if (notes.isEmpty()) {
            throw new IllegalStateException("노트를 하나도 못 찾았습니다 — 좀 더 리듬감 있는(타격감 있는) 곡으로 시도해보세요.");
        }
        notes.addAll(pickSideOnsets(energy, bandEnergy, sampleRate, difficulty, notes));
        notes.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));

        // Speed is derived from the CHART ITSELF (how many notes/sec it actually ended up with) —
        // not from an independent audio-tempo guess. Two earlier attempts at guessing tempo straight
        // from the waveform (raw energy vs. its trailing average, then a stricter onset-interval
        // BPM estimate) both looked fine on clean synthetic clicks but fell apart on real, normally
        // mixed songs: continuously loud passages kept re-triggering the "above average" test
        // regardless of actual tempo, so nearly every real song — slow or fast — came out reading as
        // fast. Reusing the note count this method just produced sidesteps that entirely: it's exact
        // (not a guess), and it's already true difficulty-appropriate density (see pickOnsets).
        double notesPerSecond = notes.size() / (lengthMs / 1000.0);
        double baselineNotesPerSecond = (1000.0 / difficulty.minGapMs) * SPEED_BASELINE_FRACTION_OF_CEILING;
        // Floored at 1.0, not allowed below: a song at or under the difficulty's own typical density
        // (most ballads) falls exactly as fast as it always did — settings.noteSpeed() alone decides
        // that. Only a chart denser than typical for its difficulty (a genuinely fast/busy song)
        // speeds the fall up on top of that, and only up to MAX_SPEED_MULTIPLIER.
        double speedMultiplier = clamp(notesPerSecond / baselineNotesPerSecond, MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER);

        return new Chart(wavFile, title, lengthMs, notes, speedMultiplier);
    }

    // What fraction of a difficulty's theoretical max note rate (1000/minGapMs) a "typical" song
    // actually reaches — measured against this mod's own sample library (13 real songs across
    // several genres): per-difficulty median note rates landed at ~40-48% of that ceiling. Deriving
    // the baseline from minGapMs instead of hardcoding one number per difficulty keeps it consistent
    // if minGapMs is ever retuned.
    private static final double SPEED_BASELINE_FRACTION_OF_CEILING = 0.42;
    private static final double MIN_SPEED_MULTIPLIER = 1.0;
    private static final double MAX_SPEED_MULTIPLIER = 1.6;

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String titleFrom(File wavFile) {
        String name = wavFile.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static double[] downmixToMono(byte[] bytes, int frameCount, int channels, int bytesPerFrame, boolean bigEndian) {
        double[] samples = new double[frameCount];
        for (int i = 0; i < frameCount; i++) {
            int base = i * bytesPerFrame;
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int off = base + c * 2;
                short s = bigEndian
                        ? (short) (((bytes[off] & 0xFF) << 8) | (bytes[off + 1] & 0xFF))
                        : (short) (((bytes[off + 1] & 0xFF) << 8) | (bytes[off] & 0xFF));
                sum += s;
            }
            samples[i] = (sum / (double) channels) / 32768.0;
        }
        return samples;
    }

    private static double[] windowEnergy(double[] samples, int frameCount) {
        int windows = Math.max(0, (frameCount - WINDOW) / HOP);
        double[] energy = new double[windows];
        for (int w = 0; w < windows; w++) {
            int start = w * HOP;
            double e = 0;
            for (int i = 0; i < WINDOW; i++) {
                double v = samples[start + i];
                e += v * v;
            }
            energy[w] = e / WINDOW;
        }
        return energy;
    }

    /** 4 crude bands (low, low-mid, high-mid, high) via cascaded one-pole low-pass filters, each windowed into energy. */
    private static double[][] bandEnergies(double[] samples, int frameCount, float sampleRate) {
        double[] lpLow = onePoleLowPass(samples, CUTOFF_LOW, sampleRate);
        double[] lpMid = onePoleLowPass(samples, CUTOFF_MID, sampleRate);
        double[] lpHigh = onePoleLowPass(samples, CUTOFF_HIGH, sampleRate);

        double[] band0 = lpLow;                       // bass
        double[] band1 = subtract(lpMid, lpLow);       // low-mid
        double[] band2 = subtract(lpHigh, lpMid);      // high-mid
        double[] band3 = subtract(samples, lpHigh);    // treble

        return new double[][]{
                windowEnergy(band0, frameCount),
                windowEnergy(band1, frameCount),
                windowEnergy(band2, frameCount),
                windowEnergy(band3, frameCount),
        };
    }

    private static double[] onePoleLowPass(double[] samples, double cutoffHz, float sampleRate) {
        double dt = 1.0 / sampleRate;
        double rc = 1.0 / (2 * Math.PI * cutoffHz);
        double alpha = dt / (rc + dt);
        double[] out = new double[samples.length];
        double y = 0;
        for (int i = 0; i < samples.length; i++) {
            y += alpha * (samples[i] - y);
            out[i] = y;
        }
        return out;
    }

    private static double[] subtract(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] - b[i];
        return out;
    }

    // How much longer a LANE must rest compared to the plain cross-lane minGapMs before it can be
    // picked again — only engages when the frequency-band analysis would otherwise hand the very
    // same lane several hits in a tight row (a "burst"); it's a no-op whenever notes are already
    // alternating across lanes normally, since this cooldown never delays anything except a repeat
    // pick of the SAME lane. This is deliberately narrow — unlike an earlier attempt that widened
    // every note's spacing song-wide, this only ever touches the specific lane that would otherwise
    // spam, leaving everything else at the difficulty's normal pacing/note count.
    private static final double SAME_LANE_GAP_FACTOR = 2.0;

    // A floor on how often a chord (see pickOnsets) can repeat, on top of clearing
    // chordThresholdRatio — without it, a continuously loud/dense track (chordThresholdRatio alone
    // doesn't care how BUSY a song is, only how loud one instant is relative to its own recent
    // average) ended up with a chord every second or so, which reads as "this difficulty is just
    // harder" rather than "an occasional highlight moment" — real-song testing against this mod's
    // own library showed some tracks hitting 40-60 chords/minute uncapped, dropping to a still-
    // noticeable-but-actually-occasional handful per minute with this in place.
    private static final long CHORD_COOLDOWN_MS = 2000;

    private static List<Note> pickOnsets(double[] energy, double[][] bandEnergy, float sampleRate, Difficulty difficulty) {
        double thresholdRatio = difficulty.thresholdRatio;
        long minGapMs = difficulty.minGapMs;
        long sameLaneMinGapMs = Math.round(minGapMs * SAME_LANE_GAP_FACTOR);
        int avgSpanWindows = Math.max(1, (int) (AVG_SPAN_SECONDS * sampleRate / HOP));
        List<Note> notes = new ArrayList<>();
        // Not Long.MIN_VALUE: timeMs - lastNoteMs would overflow and wrap negative, so the very
        // first onset (and, since lastNoteMs would then never leave that sentinel, every onset
        // after it) would fail the MIN_GAP_MS check forever. Long.MIN_VALUE / 2 is still "no
        // previous note" for any realistic timeMs, without overflowing the subtraction.
        long lastNoteMs = Long.MIN_VALUE / 2;
        int lastLane = -1;
        long lastChordMs = Long.MIN_VALUE / 2; // see CHORD_COOLDOWN_MS
        int lanes = bandEnergy.length;
        // Per-lane "busy until" time: while a long note's hold is still active in a lane, the
        // player's finger is physically still down on that key, so a second note can't land there
        // until the hold (plus a small release buffer) ends — otherwise it's unhittable. Also reused
        // as the same-lane burst cooldown above for plain taps (see SAME_LANE_GAP_FACTOR).
        long[] laneBusyUntil = new long[lanes];
        java.util.Arrays.fill(laneBusyUntil, Long.MIN_VALUE / 2);
        for (int w = 0; w < energy.length; w++) {
            int lo = Math.max(0, w - avgSpanWindows);
            double avg = 0;
            int span = w - lo;
            if (span > 0) {
                for (int k = lo; k < w; k++) avg += energy[k];
                avg /= span;
            }
            long timeMs = (long) (w * (double) HOP / sampleRate * 1000.0);
            boolean isOnset = avg > 1e-9 && energy[w] > avg * thresholdRatio && (timeMs - lastNoteMs) >= minGapMs;
            if (isOnset) {
                int[] laneChoice = dominantBand(bandEnergy, w, lo, span, lastLane, laneBusyUntil, timeMs);
                int lane = laneChoice[0];
                if (lane < 0) {
                    continue; // every lane is still mid-hold/cooling down — drop this onset rather than stack an unhittable note
                }
                long holdMs = sustainMs(bandEnergy[lane], w, sampleRate);
                notes.add(new Note(timeMs, timeMs + holdMs, lane));
                laneBusyUntil[lane] = holdMs > 0 ? (timeMs + holdMs + minGapMs) : (timeMs + sameLaneMinGapMs);

                // A genuinely climactic hit (loud enough to also qualify as a side-note moment — see
                // chordThresholdRatio's own doc, "a rare, deliberate 'big hit' moment") presses a
                // second lane at the very same instant instead of just another single note in the
                // staircase — "하이라이트급 큰 음이 나오는 타이밍에 동시에 여러 라인을 입력하는". Reusing
                // chordThresholdRatio (rather than a new knob) keeps this at the same "how loud is
                // big" bar already tuned per difficulty, and EASY's Double.MAX_VALUE means it never
                // qualifies there either — a beginner chart has no two-key-at-once moments.
                int secondLane = laneChoice[1];
                if (secondLane >= 0 && energy[w] > avg * difficulty.chordThresholdRatio
                        && (timeMs - lastChordMs) >= CHORD_COOLDOWN_MS) {
                    long secondHoldMs = sustainMs(bandEnergy[secondLane], w, sampleRate);
                    notes.add(new Note(timeMs, timeMs + secondHoldMs, secondLane));
                    laneBusyUntil[secondLane] = secondHoldMs > 0
                            ? (timeMs + secondHoldMs + minGapMs) : (timeMs + sameLaneMinGapMs);
                    lastChordMs = timeMs;
                }

                lastLane = lane;
                lastNoteMs = timeMs;
            }
        }
        return notes;
    }

    // A side note and a lane note both fall at the same constant speed, so two notes that start
    // out N pixels apart in time stay exactly N pixels apart the whole way down — the gap never
    // closes. ~40px (a note's own ~28-32px height plus a little breathing room) is enough that
    // their rendered shapes never touch; converting that to milliseconds at the slowest allowed
    // note speed (RhythmSettings.noteSpeed's 0.5x floor — half of RhythmPanel.BASE_PIXELS_PER_MS,
    // since ChartGenerator has no runtime speed to go on) gives a clearance that holds at every
    // speed the player might actually pick, without demanding an unrealistically empty stretch of
    // song (lane notes alone can already sit as close as ~70ms apart on Hard).
    private static final long SIDE_NOTE_CLEARANCE_MS = 180;

    /** Side-track notes ({@link Note.Kind#SIDE}), picked as their own independent pass over the
     *  song's overall energy — not attached to any particular lane note's onset — so a side note's
     *  {@code timeMs} (and, for a hold-style one, its whole {@code [timeMs, endTimeMs]} span) never
     *  lands within {@link #SIDE_NOTE_CLEARANCE_MS} of a lane note's: the two would otherwise fall
     *  down the screen locked to the same y position the entire time, the smaller lane note
     *  effectively invisible against the much bigger side bar.
     *  <p>
     *  This can't reuse the old "must clear {@code chordThresholdRatio}" bar: that threshold is
     *  tuned against the same energy signal the (much more permissive) lane-note detector already
     *  sweeps first, so almost every moment loud enough to qualify already has a lane note sitting
     *  on or right next to it — clear-of-lanes and chordThresholdRatio-strong turn out to be nearly
     *  mutually exclusive in practice, which is exactly the overlap this method exists to prevent.
     *  Instead: rank every clear-of-lanes, non-silent moment by how loud it is relative to its own
     *  trailing average, and greedily take the loudest ones first (subject to {@code sideMinGapMs}
     *  spacing between picks), capped at roughly a tenth of the lane note count so a quiet stretch
     *  with nothing but small, clear gaps doesn't get filled with side notes just because it's
     *  available — a side note should still read as an occasional highlight, not a filler note. */
    private static List<Note> pickSideOnsets(double[] energy, double[][] bandEnergy, float sampleRate,
                                              Difficulty difficulty, List<Note> laneNotes) {
        List<Note> sideNotes = new ArrayList<>();
        if (difficulty.chordThresholdRatio == Double.MAX_VALUE) {
            return sideNotes; // Easy: no side notes at all
        }
        long sideMinGapMs = difficulty.sideMinGapMs;
        int avgSpanWindows = Math.max(1, (int) (AVG_SPAN_SECONDS * sampleRate / HOP));
        List<int[]> candidateWindows = new ArrayList<>(); // {w} — kept as a list so it can be sorted
        List<Double> candidateRatios = new ArrayList<>();
        for (int w = 0; w < energy.length; w++) {
            int lo = Math.max(0, w - avgSpanWindows);
            double avg = 0;
            int span = w - lo;
            if (span > 0) {
                for (int k = lo; k < w; k++) avg += energy[k];
                avg /= span;
            }
            if (avg <= 1e-9) {
                continue;
            }
            double ratio = energy[w] / avg;
            if (ratio <= 1.0) {
                continue; // must at least beat its own trailing average — not just any silence
            }
            long timeMs = (long) (w * (double) HOP / sampleRate * 1000.0);
            if (tooCloseToAnyNote(timeMs, laneNotes, SIDE_NOTE_CLEARANCE_MS)) {
                continue;
            }
            candidateWindows.add(new int[]{w});
            candidateRatios.add(ratio);
        }

        Integer[] order = new Integer[candidateWindows.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(candidateRatios.get(b), candidateRatios.get(a)));

        int maxSideNotes = laneNotes.size() / 10;
        for (int idx : order) {
            if (sideNotes.size() >= maxSideNotes) {
                break;
            }
            int w = candidateWindows.get(idx)[0];
            long timeMs = (long) (w * (double) HOP / sampleRate * 1000.0);
            boolean tooCloseToPicked = false;
            for (Note s : sideNotes) {
                if (Math.abs(timeMs - s.timeMs) < sideMinGapMs) {
                    tooCloseToPicked = true;
                    break;
                }
            }
            if (tooCloseToPicked) {
                continue;
            }
            int lo = Math.max(0, w - avgSpanWindows);
            int span = w - lo;
            int band = loudestBand(bandEnergy, w, lo, span);
            long holdMs = sustainMs(bandEnergy[band], w, sampleRate);
            sideNotes.add(new Note(timeMs, timeMs + holdMs, -1, Note.Kind.SIDE));
        }
        sideNotes.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return sideNotes;
    }

    private static boolean tooCloseToAnyNote(long timeMs, List<Note> notes, long clearanceMs) {
        for (Note n : notes) {
            if (timeMs >= n.timeMs - clearanceMs && timeMs <= n.endTimeMs + clearanceMs) {
                return true;
            }
        }
        return false;
    }

    /** The band whose energy most exceeds its own trailing average right now — used only to pick a
     *  hold duration ({@link #sustainMs}) for a side note; unlike {@link #dominantBand}, a side note
     *  doesn't occupy any of the 4 lanes, so there's no lane-exclusivity or "avoid repeating" logic
     *  to apply here. */
    private static int loudestBand(double[][] bandEnergy, int w, int lo, int span) {
        int best = 0;
        double bestRatio = -1;
        for (int band = 0; band < bandEnergy.length; band++) {
            double[] be = bandEnergy[band];
            double bavg = 0;
            if (span > 0) {
                for (int k = lo; k < w; k++) bavg += be[k];
                bavg /= span;
            }
            double ratio = be[w] / (bavg + 1e-9);
            if (ratio > bestRatio) {
                bestRatio = ratio;
                best = band;
            }
        }
        return best;
    }

    /** How long (ms) {@code bandEnergy} stays above {@link #SUSTAIN_RATIO} of its own value at
     *  {@code startWindow} — 0 (a plain tap note) if that's under {@link #MIN_HOLD_MS}. */
    private static long sustainMs(double[] bandEnergy, int startWindow, float sampleRate) {
        double threshold = bandEnergy[startWindow] * SUSTAIN_RATIO;
        int maxWindows = (int) (MAX_HOLD_MS * sampleRate / 1000.0 / HOP);
        int endWindow = startWindow;
        int w = startWindow + 1;
        while (w < bandEnergy.length && (w - startWindow) <= maxWindows && bandEnergy[w] > threshold) {
            endWindow = w;
            w++;
        }
        long durationMs = (long) ((endWindow - startWindow) * (double) HOP / sampleRate * 1000.0);
        return durationMs >= MIN_HOLD_MS ? durationMs : 0;
    }

    /** The band whose energy most exceeds its own trailing average right now, restricted to lanes
     *  that aren't still mid-hold at {@code timeMs}; ties broken away from {@code avoidLane}.
     *  @return {@code {primary, secondary}} lane indices — {@code primary} is -1 if every lane is
     *  currently busy holding a long note; {@code secondary} (the runner-up band) only feeds the
     *  "prefer variety" swap below, not returned for the caller's own use. */
    private static int[] dominantBand(double[][] bandEnergy, int w, int lo, int span, int avoidLane,
                                       long[] laneBusyUntil, long timeMs) {
        double bestRatio = -1;
        double secondRatio = -1;
        int bestLane = -1;
        int secondLane = -1;
        for (int band = 0; band < bandEnergy.length; band++) {
            if (laneBusyUntil[band] > timeMs) {
                continue;
            }
            double[] be = bandEnergy[band];
            double bavg = 0;
            if (span > 0) {
                for (int k = lo; k < w; k++) bavg += be[k];
                bavg /= span;
            }
            double ratio = be[w] / (bavg + 1e-9);
            if (ratio > bestRatio) {
                secondRatio = bestRatio;
                secondLane = bestLane;
                bestRatio = ratio;
                bestLane = band;
            } else if (ratio > secondRatio) {
                secondRatio = ratio;
                secondLane = band;
            }
        }
        if (bestLane < 0) {
            return new int[]{-1, -1};
        }
        // Prefer variety: if the top pick repeats the last lane and a close runner-up exists, take that instead.
        if (bestLane == avoidLane && secondLane >= 0 && secondRatio > bestRatio * 0.6) {
            int swapLane = bestLane;
            bestLane = secondLane;
            secondLane = swapLane;
        }
        return new int[]{bestLane, secondLane};
    }
}

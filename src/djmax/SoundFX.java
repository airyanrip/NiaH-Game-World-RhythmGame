package djmax;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.ByteArrayInputStream;

/**
 * Hit-feel audio: a punchy short tick per judgment tier (pitched differently so PERFECT feels
 * crisper than GOOD — see {@link Tone#punch}) and a dull thud for a miss, all synthesized in
 * memory. Each sound keeps a small round-robin pool of {@link Clip}s so two hits half a beat
 * apart don't cut each other off.
 */
final class SoundFX {
    private static final AudioFormat FORMAT = new AudioFormat(Tone.SAMPLE_RATE, 16, 1, true, false);
    private static final int POOL = 4;

    private final Clip[] perfect = new Clip[POOL];
    private final Clip[] great = new Clip[POOL];
    private final Clip[] good = new Clip[POOL];
    private final Clip[] miss = new Clip[POOL];
    private int pi, gi, gdi, mi;

    SoundFX(String outputMixerName) {
        // Longer than the old 15-18ms plain sines — enough room for Tone.punch's exponential
        // decay + sub-bass thump to actually be heard, while staying tight/percussive rather than
        // a sustained note. Miss stays a plain, un-punchy synth() thud — it's a "that was wrong"
        // cue, not something that should feel satisfying to trigger.
        fill(perfect, Tone.punch(1300, 45, 0.6), outputMixerName);
        fill(great, Tone.punch(950, 42, 0.55), outputMixerName);
        fill(good, Tone.punch(720, 38, 0.5), outputMixerName);
        fill(miss, Tone.synth(150, 55, 0.4), outputMixerName);
    }

    void setVolume(double volume) {
        for (Clip[] pool : new Clip[][]{perfect, great, good, miss}) {
            for (Clip c : pool) {
                applyGain(c, volume);
            }
        }
    }

    void playPerfect() {
        play(perfect, pi = (pi + 1) % POOL);
    }

    void playGreat() {
        play(great, gi = (gi + 1) % POOL);
    }

    void playGood() {
        play(good, gdi = (gdi + 1) % POOL);
    }

    void playMiss() {
        play(miss, mi = (mi + 1) % POOL);
    }

    void close() {
        for (Clip[] pool : new Clip[][]{perfect, great, good, miss}) {
            for (Clip c : pool) {
                if (c != null) {
                    c.close();
                }
            }
        }
    }

    private static void fill(Clip[] pool, byte[] pcm, String outputMixerName) {
        for (int i = 0; i < pool.length; i++) {
            pool[i] = tryOpen(pcm, outputMixerName);
        }
    }

    private static Clip tryOpen(byte[] pcm, String outputMixerName) {
        try {
            Clip c = AudioDevices.openClip(outputMixerName);
            try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), FORMAT, pcm.length / 2)) {
                c.open(in);
            }
            return c;
        } catch (Exception e) {
            return null; // sound effects are a nice-to-have; a failure here must not break gameplay
        }
    }

    private static void applyGain(Clip c, double volume) {
        if (c == null || !c.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gain = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
        float dB = volume <= 0.0001 ? gain.getMinimum() : (float) (20.0 * Math.log10(volume));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
    }

    private static void play(Clip[] pool, int idx) {
        Clip c = pool[idx];
        if (c == null) {
            return;
        }
        c.stop();
        c.setFramePosition(0);
        c.start();
    }
}

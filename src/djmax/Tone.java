package djmax;

import java.util.Random;

/** Synthesizes short 16-bit mono PCM hit sounds — no asset files needed. */
final class Tone {
    static final float SAMPLE_RATE = 44100f;

    private Tone() {
    }

    /** The original plain sine burst with a linear fade-out — kept for {@link SoundFX}'s miss
     *  sound, which is meant to read as a dull, un-punchy "wrong" thud, not a hit. */
    static byte[] synth(double freqHz, int durationMs, double amplitude) {
        int frames = (int) (SAMPLE_RATE * durationMs / 1000.0);
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            double t = i / SAMPLE_RATE;
            double envelope = 1.0 - (i / (double) frames);
            double sample = Math.sin(2 * Math.PI * freqHz * t) * envelope * amplitude;
            short s = (short) Math.round(sample * Short.MAX_VALUE);
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    /** A punchier hit sound for {@link SoundFX}'s PERFECT/GREAT/GOOD tiers — three things layered
     *  on top of the plain sine {@link #synth} never had, each addressing a different part of
     *  "타격감" (impact feel):
     *  <ul>
     *    <li>Exponential decay instead of a linear fade — reads as a struck/plucked hit, not a
     *        fading whistle.</li>
     *    <li>A brief downward pitch glide right at the attack (settling to {@code freqHz} within
     *        ~12ms) — the classic percussive "thock" trick.</li>
     *    <li>A fixed low-frequency sub-thump and a very short noise burst at the very onset,
     *        mixed underneath the main tone — the felt "weight" and the percussive click a pure
     *        sine can't give on its own, the way a real drumstick strike has both a tone and a
     *        transient.</li>
     *  </ul>
     *  Deterministic (a fixed noise seed) since this only ever runs once per tier, at {@link
     *  SoundFX}'s own construction, not per hit — every pooled clip for a tier is the identical
     *  buffer already, same as {@link #synth}. */
    static byte[] punch(double freqHz, int durationMs, double amplitude) {
        int frames = (int) (SAMPLE_RATE * durationMs / 1000.0);
        byte[] pcm = new byte[frames * 2];
        double subFreq = 90;
        double phase = 0;
        double subPhase = 0;
        Random noise = new Random(0);
        int noiseFrames = (int) (SAMPLE_RATE * 0.004); // ~4ms percussive attack transient
        for (int i = 0; i < frames; i++) {
            double tSec = i / SAMPLE_RATE;
            double envelope = Math.exp(-3.0 * i / (double) frames);
            double glide = 1.0 + 0.35 * Math.exp(-tSec / 0.012);
            phase += 2 * Math.PI * (freqHz * glide) / SAMPLE_RATE;
            double tone = Math.sin(phase) * envelope;

            double subEnvelope = Math.exp(-14.0 * i / (double) frames);
            subPhase += 2 * Math.PI * subFreq / SAMPLE_RATE;
            double sub = Math.sin(subPhase) * subEnvelope * 0.5;

            double click = 0;
            if (i < noiseFrames) {
                double clickEnvelope = 1.0 - (i / (double) noiseFrames);
                click = (noise.nextDouble() * 2 - 1) * clickEnvelope * 0.35;
            }

            double sample = (tone + sub + click) * amplitude;
            sample = Math.max(-1.0, Math.min(1.0, sample)); // guard against clipping from the layered mix
            short s = (short) Math.round(sample * Short.MAX_VALUE);
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) (s >> 8);
        }
        return pcm;
    }
}

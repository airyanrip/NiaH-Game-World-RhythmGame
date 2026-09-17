package djmax;

/** Synthesizes a short 16-bit mono PCM sine burst with a linear fade-out — no asset files needed. */
final class Tone {
    static final float SAMPLE_RATE = 44100f;

    private Tone() {
    }

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
}

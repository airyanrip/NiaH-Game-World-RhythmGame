package djmax;

import javax.sound.sampled.AudioFormat;

/**
 * Changes playback speed by linearly resampling 16-bit PCM frames in time — speed &gt; 1 plays
 * faster (and higher-pitched), speed &lt; 1 slower (and lower-pitched), exactly like changing a
 * tape/turntable's speed. There is no pitch-preserving time-stretch here (that needs a real
 * algorithm, e.g. SoundTouch); this is the simple trade-off the practice-speed feature accepted.
 */
final class AudioResampler {
    private AudioResampler() {
    }

    /** @return a new interleaved 16-bit PCM byte array, same format/channel count, {@code 1/speed} the frame count. */
    static byte[] resample(byte[] pcmBytes, AudioFormat format, double speed) {
        if (speed <= 0) {
            throw new IllegalArgumentException("speed must be positive");
        }
        int channels = format.getChannels();
        boolean bigEndian = format.isBigEndian();
        int bytesPerFrame = 2 * channels;
        int frameCount = pcmBytes.length / bytesPerFrame;
        if (frameCount < 2 || speed == 1.0) {
            return pcmBytes;
        }

        int outFrames = Math.max(1, (int) Math.round(frameCount / speed));
        byte[] out = new byte[outFrames * bytesPerFrame];

        for (int outIdx = 0; outIdx < outFrames; outIdx++) {
            double srcPos = outIdx * speed;
            int i0 = (int) Math.floor(srcPos);
            int i1 = Math.min(i0 + 1, frameCount - 1);
            if (i0 >= frameCount) {
                i0 = frameCount - 1;
            }
            double frac = srcPos - i0;

            for (int c = 0; c < channels; c++) {
                short s0 = readSample(pcmBytes, i0, c, bytesPerFrame, bigEndian);
                short s1 = readSample(pcmBytes, i1, c, bytesPerFrame, bigEndian);
                short mixed = (short) Math.round(s0 + (s1 - s0) * frac);
                writeSample(out, outIdx, c, bytesPerFrame, bigEndian, mixed);
            }
        }
        return out;
    }

    private static short readSample(byte[] bytes, int frame, int channel, int bytesPerFrame, boolean bigEndian) {
        int off = frame * bytesPerFrame + channel * 2;
        return bigEndian
                ? (short) (((bytes[off] & 0xFF) << 8) | (bytes[off + 1] & 0xFF))
                : (short) (((bytes[off + 1] & 0xFF) << 8) | (bytes[off] & 0xFF));
    }

    private static void writeSample(byte[] bytes, int frame, int channel, int bytesPerFrame, boolean bigEndian, short value) {
        int off = frame * bytesPerFrame + channel * 2;
        byte hi = (byte) (value >> 8);
        byte lo = (byte) (value & 0xFF);
        if (bigEndian) {
            bytes[off] = hi;
            bytes[off + 1] = lo;
        } else {
            bytes[off] = lo;
            bytes[off + 1] = hi;
        }
    }
}

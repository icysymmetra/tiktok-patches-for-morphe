/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

/**
 * Moves interleaved 16-bit PCM from one sample rate to another, a buffer at a time.
 *
 * <p>Android's Opus encoder takes 8, 12, 16, 24 or 48 kHz and nothing else, and TikTok's sound
 * decodes at 44.1 kHz, so the sound has to change rate on the way between the two codecs, and
 * neither codec will do it. Each output sample is a Catmull-Rom curve through the four input
 * samples around it. Straight-line interpolation would be shorter, but it takes about 3 dB off
 * the top of the range a song is still bright in.
 *
 * <p>Output sample k sits at input position k * inRate / outRate, worked out in whole numbers
 * so a long sound can't drift. A buffer gives out only the samples whose four neighbours have
 * arrived, which is what makes the result the same however the input is cut into buffers.
 */
final class PcmResampler {
    private final int inRate;
    private final int outRate;
    private final int channels;
    /** Input frames still needed, from one before the next output's position onwards. */
    private short[] pending = new short[0];
    private int pendingFrames;
    /** The input frame number of {@code pending}'s first frame. */
    private long pendingStart;
    private long received;
    private long produced;

    PcmResampler(int inRate, int outRate, int channels) {
        if (inRate <= 0 || outRate <= 0 || channels < 1) {
            throw new IllegalArgumentException(channels + " channels from " + inRate + " Hz to " + outRate + " Hz");
        }
        this.inRate = inRate;
        this.outRate = outRate;
        this.channels = channels;
    }

    /**
     * The output for {@code frames} more input frames from {@code input}. With {@code end} set
     * this also gives out the last few samples, which stood waiting for neighbours that will
     * never come, and the sound's own last sample stands in for them.
     */
    short[] process(short[] input, int frames, boolean end) {
        if (inRate == outRate) {
            short[] copy = new short[frames * channels];
            System.arraycopy(input, 0, copy, 0, copy.length);
            received += frames;
            produced += frames;
            return copy;
        }
        append(input, frames);
        received += frames;

        // How many outputs there are room for now, counted first so the array is sized once.
        long first = produced, last = produced;
        while (ready(last, end)) last++;
        short[] output = new short[(int) (last - first) * channels];
        for (long k = first; k < last; k++) {
            long scaled = k * inRate;
            long n = scaled / outRate;
            double t = (double) (scaled % outRate) / outRate;
            int at = (int) (k - first) * channels;
            for (int c = 0; c < channels; c++) {
                output[at + c] = curve(sample(n - 1, c), sample(n, c), sample(n + 1, c), sample(n + 2, c), t);
            }
        }
        produced = last;
        drop();
        return output;
    }

    /** Output frames written so far. */
    long produced() {
        return produced;
    }

    private boolean ready(long k, boolean end) {
        long n = k * inRate / outRate;
        return end ? n < received : n + 2 < received;
    }

    private short sample(long frame, int channel) {
        long clamped = Math.max(0, Math.min(received - 1, frame));
        return pending[(int) (clamped - pendingStart) * channels + channel];
    }

    private static short curve(int p0, int p1, int p2, int p3, double t) {
        double value = 0.5 * (2 * p1 + (p2 - p0) * t
                + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                + (3 * (p1 - p2) + p3 - p0) * t * t * t);
        long rounded = Math.round(value);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, rounded));
    }

    private void append(short[] input, int frames) {
        int needed = (pendingFrames + frames) * channels;
        if (needed > pending.length) {
            short[] grown = new short[Math.max(needed, pending.length * 2)];
            System.arraycopy(pending, 0, grown, 0, pendingFrames * channels);
            pending = grown;
        }
        System.arraycopy(input, 0, pending, pendingFrames * channels, frames * channels);
        pendingFrames += frames;
    }

    /** Lets go of every frame before the one the next output looks back to. */
    private void drop() {
        long keepFrom = Math.max(pendingStart, produced * inRate / outRate - 1);
        int gone = (int) Math.min(pendingFrames, keepFrom - pendingStart);
        if (gone <= 0) return;
        System.arraycopy(pending, gone * channels, pending, 0, (pendingFrames - gone) * channels);
        pendingFrames -= gone;
        pendingStart += gone;
    }
}

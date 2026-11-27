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
        writeRange(first, last, output, 0, Integer.MAX_VALUE);
        produced = last;
        drop();
        return output;
    }

    /**
     * Resamples directly into {@code target} starting at {@code targetOffset}, up to {@code maxSamples}
     * samples, without allocating a temporary output array per decoded frame.
     */
    int processInto(short[] input, int frames, short[] target, int targetOffset, int maxSamples) {
        if (maxSamples <= 0) return 0;
        if (inRate == outRate) {
            int count = Math.min(frames * channels, maxSamples);
            System.arraycopy(input, 0, target, targetOffset, count);
            received += frames;
            produced += frames;
            return count;
        }
        append(input, frames);
        received += frames;
        long first = produced, last = produced;
        while (ready(last, false)) last++;
        int written = writeRange(first, last, target, targetOffset, maxSamples);
        produced = last;
        drop();
        return written;
    }

    private int writeRange(long first, long last, short[] output, int offset, int maxSamples) {
        final int inR = this.inRate;
        final int outR = this.outRate;
        final double invOutR = 1.0 / outR;
        final int ch = this.channels;
        final short[] pend = this.pending;
        final long pStart = this.pendingStart;
        final long rec = this.received;
        int totalSamples = (int) (last - first) * ch;
        int limitSamples = Math.min(totalSamples, maxSamples);
        long limitK = first + limitSamples / ch;
        if (ch == 1) {
            for (long k = first; k < limitK; k++) {
                long scaled = k * inR;
                long n = scaled / outR;
                double t = (scaled % outR) * invOutR;
                int at = offset + (int) (k - first);
                if (n >= 1 && n + 2 < rec) {
                    int base = (int) (n - pStart);
                    output[at] = curve(pend[base - 1], pend[base], pend[base + 1], pend[base + 2], t);
                } else {
                    output[at] = curve(sample(n - 1, 0), sample(n, 0), sample(n + 1, 0), sample(n + 2, 0), t);
                }
            }
        } else {
            for (long k = first; k < limitK; k++) {
                long scaled = k * inR;
                long n = scaled / outR;
                double t = (scaled % outR) * invOutR;
                int at = offset + (int) (k - first) * ch;
                if (n >= 1 && n + 2 < rec) {
                    int base = (int) (n - pStart) * ch;
                    for (int c = 0; c < ch; c++) {
                        output[at + c] = curve(pend[base - ch + c], pend[base + c], pend[base + ch + c], pend[base + 2 * ch + c], t);
                    }
                } else {
                    for (int c = 0; c < ch; c++) {
                        output[at + c] = curve(sample(n - 1, c), sample(n, c), sample(n + 1, c), sample(n + 2, c), t);
                    }
                }
            }
        }
        return (int) (limitK - first) * ch;
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

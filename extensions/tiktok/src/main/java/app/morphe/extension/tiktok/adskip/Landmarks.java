/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

/**
 * Audio landmarks in the way Shazam describes them (Wang, 2003): the loudest points of a
 * spectrogram, paired with the points that follow them, each pair packed into one hash.
 *
 * <p>A peak is a time-frequency cell louder than every other cell in a block around it, so it
 * survives noise, a different volume, a new encode or a phone microphone in the street: noise
 * fills the quiet cells, but rarely beats the loudest one in a block. A hash holds two peaks'
 * frequencies and the time between them, never an absolute time, so it means the same thing
 * wherever the sound turns up in another video. Matching is then a vote over time offsets in
 * {@link AdMatcher}.
 *
 * <p>Mono 16-bit PCM at {@link #SAMPLE_RATE} in, one frame every {@link #HOP} samples (32 ms).
 */
final class Landmarks {
    static final int SAMPLE_RATE = 8000;
    static final int WINDOW = 1024;
    static final int HOP = 256;
    /** 62.5 Hz to 3.5 kHz: where voices, jingles and music carry their energy. */
    static final int LOW_BIN = 8;
    static final int HIGH_BIN = 448;
    static final int BINS = HIGH_BIN - LOW_BIN;

    /** The block a peak has to win: about 190 ms either side and 70 Hz either way. */
    static final int TIME_RADIUS = 6;
    static final int FREQ_RADIUS = 9;
    static final float FLOOR_DB = -12f;
    static final float ABOVE_MEAN_DB = 6f;
    static final int FRAMES_PER_SECOND = SAMPLE_RATE / HOP; // 31
    static final int MAX_PEAKS_PER_SECOND = 30;

    /** Each peak pairs with up to this many of the peaks that follow it. */
    static final int FAN_OUT = 6;
    /** dt is 6 bits: up to 2 s ahead. */
    static final int MAX_DT = 63;
    static final int MAX_DF = 120;

    private Landmarks() {}

    static double frameMs() {
        return HOP * 1000.0 / SAMPLE_RATE;
    }

    static int msToFrame(long ms) {
        return (int) Math.round(ms / frameMs());
    }

    static long frameToMs(long frame) {
        return Math.round(frame * frameMs());
    }

    static int hash(int f1, int f2, int dt) {
        return (f1 << 15) | (f2 << 6) | dt;
    }

    static int dtOf(int hash) {
        return hash & 63;
    }

    /** Hashes with the frame of their first peak, in time order. */
    static final class Print {
        final int[] hashes;
        final int[] times;
        final int count;
        final int frames;
        final int peaks;

        Print(int[] hashes, int[] times, int count, int frames, int peaks) {
            this.hashes = hashes;
            this.times = times;
            this.count = count;
            this.frames = frames;
            this.peaks = peaks;
        }

        long durationMs() {
            return frameToMs(frames);
        }

        /**
         * The hashes of [startFrame, endFrame), both peaks inside, with times counted from
         * startFrame. Cutting from the whole video's print rather than fingerprinting the cut
         * keeps the peaks exactly the ones a later scan of another video will find.
         */
        Print slice(int startFrame, int endFrame) {
            int n = 0;
            for (int i = 0; i < count; i++) {
                int t = times[i];
                if (t >= startFrame && t + dtOf(hashes[i]) < endFrame) n++;
            }
            int[] h = new int[n];
            int[] t = new int[n];
            int k = 0;
            for (int i = 0; i < count; i++) {
                int at = times[i];
                if (at >= startFrame && at + dtOf(hashes[i]) < endFrame) {
                    h[k] = hashes[i];
                    t[k] = at - startFrame;
                    k++;
                }
            }
            return new Print(h, t, n, Math.max(0, endFrame - startFrame), -1);
        }
    }

    static Print compute(short[] pcm, int length) {
        int frames = length < WINDOW ? 0 : (length - WINDOW) / HOP + 1;
        if (frames <= 0) return new Print(new int[0], new int[0], 0, 0, 0);

        float[] hann = new float[WINDOW];
        for (int i = 0; i < WINDOW; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW - 1)));
        }
        Fft fft = new Fft(WINDOW);
        float[] re = new float[WINDOW];
        float[] im = new float[WINDOW];

        int ring = 2 * TIME_RADIUS + 1;
        float[][] spec = new float[ring][BINS];
        float[][] fmax = new float[ring][BINS];
        float[] mean = new float[ring];

        // Candidates in time order: frame, bin, loudness.
        IntList candT = new IntList(frames * 4);
        IntList candF = new IntList(frames * 4);
        FloatList candV = new FloatList(frames * 4);

        for (int fi = 0; fi < frames + TIME_RADIUS; fi++) {
            int slot = fi % ring;
            float[] row = spec[slot];
            if (fi < frames) {
                int start = fi * HOP;
                for (int i = 0; i < WINDOW; i++) {
                    re[i] = pcm[start + i] / 32768f * hann[i];
                    im[i] = 0f;
                }
                fft.transform(re, im);
                double sum = 0;
                for (int b = 0; b < BINS; b++) {
                    int k = b + LOW_BIN;
                    float power = re[k] * re[k] + im[k] * im[k];
                    float db = (float) (10.0 * Math.log10(power + 1e-12));
                    row[b] = db;
                    sum += db;
                }
                mean[slot] = (float) (sum / BINS);
            } else {
                java.util.Arrays.fill(row, Float.NEGATIVE_INFINITY);
                mean[slot] = Float.POSITIVE_INFINITY;
            }
            float[] maxRow = fmax[slot];
            for (int b = 0; b < BINS; b++) {
                float best = Float.NEGATIVE_INFINITY;
                int lo = Math.max(0, b - FREQ_RADIUS);
                int hi = Math.min(BINS - 1, b + FREQ_RADIUS);
                for (int j = lo; j <= hi; j++) if (row[j] > best) best = row[j];
                maxRow[b] = best;
            }

            int center = fi - TIME_RADIUS;
            if (center < 0) continue;
            int cSlot = center % ring;
            float[] cRow = spec[cSlot];
            float threshold = Math.max(FLOOR_DB, mean[cSlot] + ABOVE_MEAN_DB);
            int first = Math.max(0, center - TIME_RADIUS);
            int last = Math.min(frames - 1, center + TIME_RADIUS);
            for (int b = 0; b < BINS; b++) {
                float v = cRow[b];
                if (v < threshold) continue;
                boolean peak = true;
                for (int t = first; t <= last && peak; t++) {
                    if (fmax[t % ring][b] > v) peak = false;
                }
                if (!peak) continue;
                candT.add(center);
                candF.add(b);
                candV.add(v);
            }
        }

        // At most MAX_PEAKS_PER_SECOND of the loudest peaks in each second, so a dense passage
        // can't flood the hashes and a quiet one still keeps its own.
        int n = candT.size;
        boolean[] keep = new boolean[n];
        int blockStart = 0;
        while (blockStart < n) {
            int block = candT.data[blockStart] / FRAMES_PER_SECOND;
            int blockEnd = blockStart;
            while (blockEnd < n && candT.data[blockEnd] / FRAMES_PER_SECOND == block) blockEnd++;
            int size = blockEnd - blockStart;
            if (size <= MAX_PEAKS_PER_SECOND) {
                for (int i = blockStart; i < blockEnd; i++) keep[i] = true;
            } else {
                Integer[] order = new Integer[size];
                for (int i = 0; i < size; i++) order[i] = blockStart + i;
                final float[] v = candV.data;
                java.util.Arrays.sort(order, (a, b) -> Float.compare(v[b], v[a]));
                for (int i = 0; i < MAX_PEAKS_PER_SECOND; i++) keep[order[i]] = true;
            }
            blockStart = blockEnd;
        }
        int peaks = 0;
        for (boolean k : keep) if (k) peaks++;
        int[] pt = new int[peaks];
        int[] pf = new int[peaks];
        int p = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            pt[p] = candT.data[i];
            pf[p] = candF.data[i];
            p++;
        }

        IntList hashes = new IntList(peaks * FAN_OUT);
        IntList times = new IntList(peaks * FAN_OUT);
        for (int i = 0; i < peaks; i++) {
            int paired = 0;
            for (int j = i + 1; j < peaks && paired < FAN_OUT; j++) {
                int dt = pt[j] - pt[i];
                if (dt <= 0) continue;
                if (dt > MAX_DT) break;
                if (Math.abs(pf[j] - pf[i]) > MAX_DF) continue;
                hashes.add(hash(pf[i], pf[j], dt));
                times.add(pt[i]);
                paired++;
            }
        }
        return new Print(hashes.data, times.data, hashes.size, frames, peaks);
    }

    /** In-place iterative radix-2 FFT with precomputed tables. */
    static final class Fft {
        private final int n;
        private final int[] reverse;
        private final float[] cos;
        private final float[] sin;

        Fft(int n) {
            this.n = n;
            int bits = Integer.numberOfTrailingZeros(n);
            reverse = new int[n];
            for (int i = 0; i < n; i++) reverse[i] = Integer.reverse(i) >>> (32 - bits);
            cos = new float[n / 2];
            sin = new float[n / 2];
            for (int i = 0; i < n / 2; i++) {
                cos[i] = (float) Math.cos(-2 * Math.PI * i / n);
                sin[i] = (float) Math.sin(-2 * Math.PI * i / n);
            }
        }

        void transform(float[] re, float[] im) {
            for (int i = 0; i < n; i++) {
                int j = reverse[i];
                if (j > i) {
                    float t = re[i]; re[i] = re[j]; re[j] = t;
                    t = im[i]; im[i] = im[j]; im[j] = t;
                }
            }
            for (int size = 2; size <= n; size <<= 1) {
                int half = size >> 1;
                int step = n / size;
                for (int start = 0; start < n; start += size) {
                    for (int k = 0; k < half; k++) {
                        float wr = cos[k * step];
                        float wi = sin[k * step];
                        int a = start + k;
                        int b = a + half;
                        float xr = re[b] * wr - im[b] * wi;
                        float xi = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - xr;
                        im[b] = im[a] - xi;
                        re[a] += xr;
                        im[a] += xi;
                    }
                }
            }
        }
    }

    static final class IntList {
        int[] data;
        int size;

        IntList(int capacity) {
            data = new int[Math.max(16, capacity)];
        }

        void add(int value) {
            if (size == data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[size++] = value;
        }
    }

    static final class FloatList {
        float[] data;
        int size;

        FloatList(int capacity) {
            data = new float[Math.max(16, capacity)];
        }

        void add(float value) {
            if (size == data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[size++] = value;
        }
    }
}

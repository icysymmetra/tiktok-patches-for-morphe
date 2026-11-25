/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The matcher on synthetic sound: an "ad" (a jingle of notes with a rhythm) inside unrelated
 * "content" (another melody plus noise), at another volume, under loud noise and talk, and
 * sped up. Each case checks the ad is found at the right place and that clean content alone
 * never matches.
 */
public class AdMatcherTest {
    private static final int SR = Landmarks.SAMPLE_RATE;

    private static short[] melody(long seed, int seconds, double gain) {
        Random random = new Random(seed);
        int n = seconds * SR;
        double[] out = new double[n];
        int at = 0;
        while (at < n) {
            int len = (int) (SR * (0.12 + random.nextDouble() * 0.35));
            int voices = 1 + random.nextInt(3);
            double[] freqs = new double[voices];
            for (int v = 0; v < voices; v++) freqs[v] = 150 * Math.pow(2, random.nextInt(36) / 12.0);
            for (int i = 0; i < len && at + i < n; i++) {
                double env = Math.min(1, i / 200.0) * Math.exp(-3.0 * i / len);
                double s = 0;
                for (int v = 0; v < voices; v++) {
                    s += Math.sin(2 * Math.PI * freqs[v] * i / SR) + 0.4 * Math.sin(4 * Math.PI * freqs[v] * i / SR);
                }
                out[at + i] += env * s / voices;
            }
            at += len;
        }
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) pcm[i] = (short) Math.max(-32768, Math.min(32767, out[i] * gain * 9000));
        return pcm;
    }

    private static short[] noise(long seed, int length, double level) {
        Random random = new Random(seed);
        short[] out = new short[length];
        double prev = 0;
        for (int i = 0; i < length; i++) {
            prev = 0.7 * prev + 0.3 * random.nextGaussian();
            out[i] = (short) Math.max(-32768, Math.min(32767, prev * level * 9000));
        }
        return out;
    }

    private static short[] mix(short[] base, short[] add, int offset) {
        short[] out = base.clone();
        for (int i = 0; i < add.length && offset + i < out.length; i++) {
            if (offset + i < 0) continue;
            out[offset + i] = (short) Math.max(-32768, Math.min(32767, out[offset + i] + add[i]));
        }
        return out;
    }

    private static short[] place(short[] content, short[] ad, int offsetSamples) {
        short[] out = new short[Math.max(content.length, offsetSamples + ad.length)];
        System.arraycopy(content, 0, out, 0, content.length);
        // The ad replaces the content where it plays.
        System.arraycopy(ad, 0, out, offsetSamples, ad.length);
        return out;
    }

    private static AdSegment segment(short[] ad) {
        Landmarks.Print print = Landmarks.compute(ad, ad.length);
        return new AdSegment(1, "jingle", 0, ad.length * 1000L / SR, null,
                java.util.Arrays.copyOf(print.hashes, print.count),
                java.util.Arrays.copyOf(print.times, print.count), print.frames, true, 0, 0);
    }

    private static List<AdMatcher.Match> find(AdSegment segment, short[] video) {
        AdMatcher.Index index = AdMatcher.Index.build(Collections.singletonList(segment));
        return AdMatcher.match(index, Landmarks.compute(video, video.length), AdMatcher.Strictness.NORMAL);
    }

    private static void assertFoundAt(List<AdMatcher.Match> matches, long startMs, long endMs, long slackMs) {
        assertEquals("one match: " + matches, 1, matches.size());
        AdMatcher.Match m = matches.get(0);
        assertTrue("start " + m.startMs + " vs " + startMs, Math.abs(m.startMs - startMs) <= slackMs);
        assertTrue("end " + m.endMs + " vs " + endMs, Math.abs(m.endMs - endMs) <= slackMs + 1000);
    }

    @Test
    public void findsTheAdInsideOtherContent() {
        short[] ad = melody(7, 12, 1.0);
        short[] video = place(melody(99, 40, 1.0), ad, 17 * SR);
        assertFoundAt(find(segment(ad), video), 17_000, 29_000, 300);
    }

    @Test
    public void findsItQuieterUnderLoudNoiseAndTalk() {
        short[] ad = melody(7, 12, 1.0);
        short[] quiet = melody(7, 12, 0.35);
        short[] video = place(melody(99, 40, 1.0), quiet, 5 * SR);
        video = mix(video, noise(3, video.length, 0.45), 0);
        video = mix(video, melody(1234, 40, 0.5), 0); // someone talking over it
        assertFoundAt(find(segment(ad), video), 5_000, 17_000, 400);
    }

    @Test
    public void findsAPartOfTheAd() {
        short[] ad = melody(7, 15, 1.0);
        short[] half = java.util.Arrays.copyOfRange(ad, 0, 7 * SR);
        short[] video = place(melody(99, 30, 1.0), half, 10 * SR);
        List<AdMatcher.Match> matches = find(segment(ad), video);
        assertEquals(1, matches.size());
        AdMatcher.Match m = matches.get(0);
        assertTrue(m.toString(), Math.abs(m.startMs - 10_000) <= 400);
        // Only the half that is there is skipped, not 15 s.
        assertTrue(m.toString(), m.endMs <= 18_500 && m.endMs >= 16_000);
    }

    @Test
    public void findsTwoCopies() {
        short[] ad = melody(7, 8, 1.0);
        short[] video = place(place(melody(99, 60, 1.0), ad, 5 * SR), ad, 40 * SR);
        List<AdMatcher.Match> matches = find(segment(ad), video);
        assertEquals(matches.toString(), 2, matches.size());
    }

    @Test
    public void findsASpedUpCopyThroughTheRetimedPass() {
        short[] ad = melody(7, 12, 1.0);
        short[] fast = AdScanner.stretch(ad, ad.length, 1 / 1.05f);
        short[] video = place(melody(99, 40, 1.0), fast, 10 * SR);
        AdSegment segment = segment(ad);
        AdMatcher.Index index = AdMatcher.Index.build(Collections.singletonList(segment));
        List<AdMatcher.Match> plain = AdMatcher.match(index, Landmarks.compute(video, video.length), AdMatcher.Strictness.NORMAL);
        List<AdMatcher.Match> found = new ArrayList<>(plain);
        if (found.isEmpty()) {
            short[] slowed = AdScanner.stretch(video, video.length, 1.05f);
            for (AdMatcher.Match m : AdMatcher.match(index, Landmarks.compute(slowed, slowed.length), AdMatcher.Strictness.NORMAL)) {
                found.add(new AdMatcher.Match(m.segment, Math.round(m.startMs / 1.05f), Math.round(m.endMs / 1.05f), m.votes, m.partial));
            }
        }
        assertEquals(found.toString(), 1, found.size());
        assertTrue(found.toString(), Math.abs(found.get(0).startMs - 10_000) <= 600);
    }

    @Test
    public void neverMatchesUnrelatedVideos() {
        short[] ad = melody(7, 12, 1.0);
        AdSegment segment = segment(ad);
        for (long seed = 100; seed < 130; seed++) {
            short[] video = mix(melody(seed, 45, 1.0), noise(seed, 45 * SR, 0.3), 0);
            List<AdMatcher.Match> matches = find(segment, video);
            assertTrue("false match in video " + seed + ": " + matches, matches.isEmpty());
        }
    }

    @Test
    public void sliceKeepsTheSamePeaks() {
        short[] video = melody(11, 30, 1.0);
        Landmarks.Print full = Landmarks.compute(video, video.length);
        Landmarks.Print cut = full.slice(Landmarks.msToFrame(8000), Landmarks.msToFrame(20000));
        AdSegment segment = new AdSegment(2, "cut", 0, 12000, null, cut.hashes, cut.times, cut.frames, true, 0, 0);
        List<AdMatcher.Match> matches = AdMatcher.match(AdMatcher.Index.build(Collections.singletonList(segment)),
                full, AdMatcher.Strictness.STRICT);
        assertFoundAt(matches, 8000, 20000, 100);
    }
}

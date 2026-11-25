/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds marked ads in a video's landmarks the way Shazam finds a song: every hash the two
 * share votes for the time offset between them, and the ad is there when one offset collects
 * far more votes than chance gives any offset.
 *
 * <p>Chance is small. A one-minute video carries about 10,000 hashes and a 15 second ad about
 * 2,500, out of millions of possible hash values, so a random offset collects well under one
 * vote. A real ad, even under a voice-over or street noise, collects dozens to hundreds.
 */
final class AdMatcher {
    /** How sure a match has to be. */
    enum Strictness {
        LOOSE(8, 0.006f, 25), NORMAL(12, 0.015f, 40), STRICT(20, 0.040f, 80);

        final int minVotes;
        final float ratio;
        /** The cap on the ratio part, so a long ad present only in part can still be found. */
        final int maxVotes;

        Strictness(int minVotes, float ratio, int maxVotes) {
            this.minVotes = minVotes;
            this.ratio = ratio;
            this.maxVotes = maxVotes;
        }

        static Strictness of(String value) {
            if ("loose".equals(value)) return LOOSE;
            if ("strict".equals(value)) return STRICT;
            return NORMAL;
        }
    }

    /** One second of frames: the slack around the coverage edges. */
    private static final int SECOND = Landmarks.FRAMES_PER_SECOND;
    /**
     * A hash that appears more often than this in one ad is a drone or a steady tone, which
     * matches any sound that holds it, so it is left out of the index.
     */
    private static final int MAX_REPEATS = 8;

    static final class Match {
        final AdSegment segment;
        final long startMs;
        final long endMs;
        final int votes;
        final boolean partial;

        Match(AdSegment segment, long startMs, long endMs, int votes, boolean partial) {
            this.segment = segment;
            this.startMs = startMs;
            this.endMs = endMs;
            this.votes = votes;
            this.partial = partial;
        }

        @Override
        public String toString() {
            return segment.name + " " + startMs + "-" + endMs + "ms votes=" + votes + (partial ? " partial" : "");
        }
    }

    /** Every enabled segment's hashes, leaving out the ones the reader voted down, sorted, for a binary search per query hash. */
    static final class Index {
        final AdSegment[] segments;
        /** hash << 32 | segment << 16 | frame. */
        final long[] entries;

        private Index(AdSegment[] segments, long[] entries) {
            this.segments = segments;
            this.entries = entries;
        }

        static Index build(List<AdSegment> all) {
            List<AdSegment> used = new ArrayList<>();
            int total = 0;
            for (AdSegment segment : all) {
                if (!segment.enabled || segment.hashes.length == 0) continue;
                if (SharedDb.myVote(segment.sharedServerId) < 0) continue;
                if (used.size() >= 0xFFFF) break;
                used.add(segment);
                total += segment.hashes.length;
            }
            long[] entries = new long[total];
            int k = 0;
            for (int s = 0; s < used.size(); s++) {
                AdSegment segment = used.get(s);
                Map<Integer, Integer> repeats = new HashMap<>();
                for (int hash : segment.hashes) repeats.merge(hash, 1, Integer::sum);
                for (int i = 0; i < segment.hashes.length; i++) {
                    int hash = segment.hashes[i];
                    if (repeats.get(hash) > MAX_REPEATS) continue;
                    int time = Math.min(0xFFFF, segment.times[i]);
                    entries[k++] = ((long) hash << 32) | ((long) s << 16) | time;
                }
            }
            Arrays.sort(entries, 0, k);
            return new Index(used.toArray(new AdSegment[0]), Arrays.copyOf(entries, k));
        }

        boolean isEmpty() {
            return segments.length == 0;
        }

        /** The first entry with this hash, or -1. */
        int first(int hash) {
            long key = (long) hash << 32;
            int lo = 0, hi = entries.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (entries[mid] < key) lo = mid + 1;
                else hi = mid;
            }
            return lo < entries.length && (int) (entries[lo] >>> 32) == hash ? lo : -1;
        }
    }

    private AdMatcher() {}

    static List<Match> match(Index index, Landmarks.Print video, Strictness strictness) {
        if (index == null || index.isEmpty() || video == null || video.count == 0) return Collections.emptyList();
        int segmentCount = index.segments.length;
        int[][] votes = new int[segmentCount][];
        int[] base = new int[segmentCount];
        for (int s = 0; s < segmentCount; s++) base[s] = index.segments[s].frames + 1;

        long[] entries = index.entries;
        for (int q = 0; q < video.count; q++) {
            int at = index.first(video.hashes[q]);
            if (at < 0) continue;
            int hash = video.hashes[q];
            int tq = video.times[q];
            for (int e = at; e < entries.length && (int) (entries[e] >>> 32) == hash; e++) {
                int s = (int) ((entries[e] >>> 16) & 0xFFFF);
                int ts = (int) (entries[e] & 0xFFFF);
                int[] histogram = votes[s];
                if (histogram == null) {
                    histogram = votes[s] = new int[video.frames + base[s] + 2];
                }
                int slot = tq - ts + base[s];
                if (slot >= 0 && slot < histogram.length) histogram[slot]++;
            }
        }

        List<Match> found = new ArrayList<>();
        for (int s = 0; s < segmentCount; s++) {
            int[] histogram = votes[s];
            if (histogram == null) continue;
            AdSegment segment = index.segments[s];
            int required = Math.max(strictness.minVotes,
                    Math.min(strictness.maxVotes, Math.round(strictness.ratio * segment.hashes.length)));
            int n = histogram.length;
            int[] smooth = new int[n];
            for (int i = 0; i < n; i++) {
                smooth[i] = histogram[i] + (i > 0 ? histogram[i - 1] : 0) + (i + 1 < n ? histogram[i + 1] : 0);
            }
            // The same ad can play twice in one video; each copy is a separate peak at least
            // half the ad's length from the others.
            int suppress = Math.max(SECOND, segment.frames / 2);
            for (int occurrence = 0; occurrence < 4; occurrence++) {
                int best = -1, bestVotes = 0;
                for (int i = 0; i < n; i++) {
                    if (smooth[i] > bestVotes) {
                        bestVotes = smooth[i];
                        best = i;
                    }
                }
                if (best < 0 || bestVotes < required) break;
                Match match = describe(segment, s, index, video, best - base[s], bestVotes);
                if (match != null) found.add(match);
                int from = Math.max(0, best - suppress), to = Math.min(n - 1, best + suppress);
                for (int i = from; i <= to; i++) smooth[i] = 0;
            }
        }
        Collections.sort(found, (a, b) -> Long.compare(a.startMs, b.startMs));
        return merge(found);
    }

    /**
     * Where in the video the ad sits at this offset. The hashes that voted for it say which part
     * of the ad is really there: a video that keeps only the ad's first half is skipped to the
     * end of that half, not on into whatever follows it.
     */
    private static Match describe(AdSegment segment, int s, Index index, Landmarks.Print video,
            int offset, int votes) {
        int coverStart = Integer.MAX_VALUE, coverEnd = Integer.MIN_VALUE, count = 0;
        long weighted = 0;
        long[] entries = index.entries;
        for (int q = 0; q < video.count; q++) {
            int hash = video.hashes[q];
            int at = index.first(hash);
            if (at < 0) continue;
            int tq = video.times[q];
            for (int e = at; e < entries.length && (int) (entries[e] >>> 32) == hash; e++) {
                if ((int) ((entries[e] >>> 16) & 0xFFFF) != s) continue;
                int ts = (int) (entries[e] & 0xFFFF);
                int delta = tq - ts - offset;
                if (delta < -1 || delta > 1) continue;
                count++;
                weighted += delta;
                coverStart = Math.min(coverStart, ts);
                coverEnd = Math.max(coverEnd, ts + Landmarks.dtOf(hash));
            }
        }
        if (count == 0) return null;
        double exactOffset = offset + (double) weighted / count;
        int frames = segment.frames;
        boolean partialStart = coverStart > Math.max(SECOND, frames / 4);
        boolean partialEnd = coverEnd < frames - Math.max(SECOND, frames / 4);
        double startFrame = exactOffset + (partialStart ? Math.max(0, coverStart - SECOND / 2) : 0);
        double endFrame = exactOffset + (partialEnd ? coverEnd + SECOND : frames);
        startFrame = Math.max(0, startFrame);
        endFrame = Math.min(video.frames + Landmarks.WINDOW / (double) Landmarks.HOP, endFrame);
        if (endFrame - startFrame < SECOND / 2.0) return null;
        return new Match(segment, Landmarks.frameToMs(Math.round(startFrame)),
                Landmarks.frameToMs(Math.round(endFrame)), votes, partialStart || partialEnd);
    }

    /**
     * Overlapping matches, from two marked copies of one ad, become one span covering both, so a
     * longer cut of the ad always wins its extra seconds. The span is named after the copy that
     * covers the most of it.
     */
    private static List<Match> merge(List<Match> sorted) {
        if (sorted.size() < 2) return sorted;
        List<Match> merged = new ArrayList<>();
        Match current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Match next = sorted.get(i);
            if (next.startMs <= current.endMs + 300) {
                long nextSpan = next.endMs - next.startMs, currentSpan = current.endMs - current.startMs;
                AdSegment segment = nextSpan > currentSpan || (nextSpan == currentSpan && next.votes > current.votes)
                        ? next.segment : current.segment;
                current = new Match(segment, current.startMs, Math.max(current.endMs, next.endMs),
                        Math.max(current.votes, next.votes), current.partial && next.partial);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }
}

/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Matches marked ad segments against video audio landmarks by time-offset voting. */
final class AdMatcher {
    enum Strictness {
        LOOSE(20, 0.040f, 80, 20, 0.040f, 80, 12),
        NORMAL(28, 0.055f, 110, 30, 0.060f, 120, 18),
        STRICT(40, 0.080f, 160, 44, 0.090f, 180, 25);

        final int minVotes;
        final float ratio;
        final int maxVotes;
        final int denseMinVotes;
        final float denseRatio;
        final int denseMaxVotes;
        /** Minimum -log10(p) score required for sub-threshold matches. */
        final int chanceScore;

        Strictness(int minVotes, float ratio, int maxVotes, int denseMinVotes, float denseRatio, int denseMaxVotes,
                int chanceScore) {
            this.minVotes = minVotes;
            this.ratio = ratio;
            this.maxVotes = maxVotes;
            this.denseMinVotes = denseMinVotes;
            this.denseRatio = denseRatio;
            this.denseMaxVotes = denseMaxVotes;
            this.chanceScore = chanceScore;
        }

        static Strictness of(String value) {
            if ("strict".equals(value)) return NORMAL;
            if ("verystrict".equals(value)) return STRICT;
            return LOOSE;
        }
    }

    private static final int SECOND = Landmarks.FRAMES_PER_SECOND;
    /** Max occurrences of a single hash per segment before it is excluded as a steady tone. */
    private static final int MAX_REPEATS = 8;
    private static final int MIN_CHANCE_VOTES = 7;
    private static final int SURE_VOTES = 20;

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

    /** Sorted hash index of all enabled, non-downvoted segments. */
    static final class Index {
        final AdSegment[] segments;
        /** Packed as: hash << 32 | segmentIndex << 16 | frame. */
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
                for (int hash : segment.hashes) {
                    Integer prev = repeats.get(hash);
                    repeats.put(hash, prev == null ? 1 : prev + 1);
                }
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

    /** Matches both sparse and dense landmark prints and merges overlapping spans. */
    static List<Match> find(Index index, short[] pcm, int length, Strictness strictness) {
        Landmarks.Print[] prints = Landmarks.computeBoth(pcm, length);
        List<Match> all = new ArrayList<>(match(index, prints[0], strictness, false));
        for (Match d : match(index, prints[1], strictness, true)) {
            boolean known = false;
            for (int i = 0; i < all.size(); i++) {
                Match m = all.get(i);
                if (m.segment != d.segment || m.startMs >= d.endMs || d.startMs >= m.endMs) continue;
                all.set(i, new Match(m.segment, Math.min(m.startMs, d.startMs), Math.max(m.endMs, d.endMs),
                        Math.max(m.votes, d.votes), m.partial && d.partial));
                known = true;
                break;
            }
            if (!known) all.add(d);
        }
        Collections.sort(all, (x, y) -> Long.compare(x.startMs, y.startMs));
        return merge(all);
    }

    static List<Match> match(Index index, Landmarks.Print video, Strictness strictness) {
        return match(index, video, strictness, false);
    }

    static List<Match> match(Index index, Landmarks.Print video, Strictness strictness, boolean dense) {
        if (index == null || index.isEmpty() || video == null || video.count == 0) return Collections.emptyList();
        int segmentCount = index.segments.length;
        int[][] votes = new int[segmentCount][];
        int[] base = new int[segmentCount];
        for (int s = 0; s < segmentCount; s++) base[s] = index.segments[s].frames + 1;

        long[] entries = index.entries;
        int[] required = new int[segmentCount];
        for (int s = 0; s < segmentCount; s++) {
            int length = index.segments[s].hashes.length;
            required[s] = dense
                    ? Math.max(strictness.denseMinVotes, Math.min(strictness.denseMaxVotes, Math.round(strictness.denseRatio * length)))
                    : Math.max(strictness.minVotes, Math.min(strictness.maxVotes, Math.round(strictness.ratio * length)));
        }

        // Pre-count shared hashes per segment so histograms are allocated only for viable candidates.
        int[] firsts = new int[video.count];
        int[] totals = new int[segmentCount];
        for (int q = 0; q < video.count; q++) {
            int hash = video.hashes[q];
            int at = firsts[q] = index.first(hash);
            if (at < 0) continue;
            for (int e = at; e < entries.length && (int) (entries[e] >>> 32) == hash; e++) {
                totals[(int) ((entries[e] >>> 16) & 0xFFFF)]++;
            }
        }
        boolean any = false;
        for (int s = 0; s < segmentCount; s++) {
            if (totals[s] >= Math.min(required[s], MIN_CHANCE_VOTES)) {
                votes[s] = new int[video.frames + base[s] + 2];
                any = true;
            }
        }
        if (!any) return Collections.emptyList();

        for (int q = 0; q < video.count; q++) {
            int at = firsts[q];
            if (at < 0) continue;
            int hash = video.hashes[q];
            int tq = video.times[q];
            for (int e = at; e < entries.length && (int) (entries[e] >>> 32) == hash; e++) {
                int s = (int) ((entries[e] >>> 16) & 0xFFFF);
                int[] histogram = votes[s];
                if (histogram == null) continue;
                int ts = (int) (entries[e] & 0xFFFF);
                int slot = tq - ts + base[s];
                if (slot >= 0 && slot < histogram.length) histogram[slot]++;
            }
        }

        List<Match> found = new ArrayList<>();
        for (int s = 0; s < segmentCount; s++) {
            int[] histogram = votes[s];
            if (histogram == null) continue;
            AdSegment segment = index.segments[s];
            int n = histogram.length;
            int[] smooth = new int[n];
            for (int i = 0; i < n; i++) {
                smooth[i] = histogram[i] + (i > 0 ? histogram[i - 1] : 0) + (i + 1 < n ? histogram[i + 1] : 0);
            }
            int suppress = Math.max(SECOND, segment.frames / 2);
            for (int occurrence = 0; occurrence < 4; occurrence++) {
                int best = -1, bestVotes = 0;
                for (int i = 0; i < n; i++) {
                    if (smooth[i] > bestVotes) {
                        bestVotes = smooth[i];
                        best = i;
                    }
                }
                if (best < 0 || bestVotes < MIN_CHANCE_VOTES) break;
                int from = Math.max(0, best - suppress), to = Math.min(n - 1, best + suppress);
                int second = 0;
                for (int i = 0; i < n; i++) {
                    if ((i < from || i > to) && smooth[i] > second) second = smooth[i];
                }
                if (!accepts(bestVotes, second, required[s], totals[s], n, segmentCount, strictness.chanceScore)) break;
                Match match = describe(segment, s, index, video, firsts, best - base[s], bestVotes);
                if (match != null) found.add(match);
                for (int i = from; i <= to; i++) smooth[i] = 0;
            }
        }
        Collections.sort(found, (a, b) -> Long.compare(a.startMs, b.startMs));
        return merge(found);
    }

    /** Validates an offset peak against the vote threshold, secondary-peak ratio, and Poisson chance score. */
    static boolean accepts(int votes, int second, int required, int shared, int offsets, int ads, int chanceScore) {
        if (votes < SURE_VOTES && second * 5 > votes * 3) return false;
        if (votes >= required) return true;
        if (votes < MIN_CHANCE_VOTES) return false;
        return chanceScore(votes, shared, offsets, ads) >= chanceScore;
    }

    /** Returns -log10 of the Poisson probability of reaching {@code votes} by chance across all offsets and ads. */
    static double chanceScore(int votes, int shared, int offsets, int ads) {
        double lambda = Math.max(1e-6, 3.0 * shared / Math.max(1, offsets));
        double logFactorial = 0;
        for (int i = 2; i <= votes; i++) logFactorial += Math.log(i);
        double logHead = -lambda + votes * Math.log(lambda) - logFactorial;
        double tail = 1, term = 1;
        for (int i = 1; i < 80; i++) {
            term *= lambda / (votes + i);
            tail += term;
            if (term < 1e-12) break;
        }
        return -(logHead + Math.log(tail)) / Math.log(10) - Math.log10(Math.max(1, offsets)) - Math.log10(Math.max(1, ads));
    }

    /** Computes the matched time span in the video from the voting hashes around {@code offset}. */
    private static Match describe(AdSegment segment, int s, Index index, Landmarks.Print video,
            int[] firsts, int offset, int votes) {
        int coverStart = Integer.MAX_VALUE, coverEnd = Integer.MIN_VALUE, count = 0;
        long weighted = 0;
        long[] entries = index.entries;
        for (int q = 0; q < video.count; q++) {
            int at = firsts[q];
            if (at < 0) continue;
            int hash = video.hashes[q];
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

    /** Merges overlapping or adjacent (< 300 ms) matches into a single span. */
    static List<Match> merge(List<Match> sorted) {
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

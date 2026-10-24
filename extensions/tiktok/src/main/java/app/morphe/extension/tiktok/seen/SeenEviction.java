/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seen;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps the in-memory seen record at its cap without going through all of it for every video
 * watched (#84).
 *
 * <p>Once the record is full, each video watched pushes the oldest one out. Finding the oldest
 * used to take a pass over every row, once per video. Here one pass lines up the {@link #BATCH}
 * oldest rows, oldest first, and the next evictions take them from the front of that line, so a
 * pass is made once every {@link #BATCH} videos. A row that was seen again, or went, since it was
 * lined up is skipped when its turn comes: it's only removed while it still holds the time it was
 * lined up with.
 *
 * <p>The line stays exact because rows only ever arrive newer than everything in it: a sighting
 * is stamped now. A row that arrives at or before the newest time in the line (a merge from disk,
 * an import, a clock set back) could belong ahead of rows already lined up, so it drops the line
 * and the next eviction makes a new pass. A trim of more than one batch at once (a bulk merge)
 * sorts once instead.
 *
 * <p>Not thread safe: the caller holds the history lock around every call, and around every
 * change to the map other than a removal.
 */
final class SeenEviction {
    /** How many of the oldest rows one pass lines up. */
    static final int BATCH = 256;

    /** Oldest first; at the same time the larger id goes first, as the record always did. */
    static final Comparator<Map.Entry<String, Long>> OLDEST_FIRST = (left, right) -> {
        int byTime = Long.compare(left.getValue(), right.getValue());
        return byTime != 0 ? byTime : right.getKey().compareTo(left.getKey());
    };

    private final ArrayDeque<Map.Entry<String, Long>> line = new ArrayDeque<>();
    /** The newest time in the line; nothing lined up when the line is empty. */
    private long newestLined = Long.MIN_VALUE;
    /** Passes over the whole map so far, for the tests. */
    int passes;

    /** A row went in, or moved, to this time. */
    void added(long timestamp) {
        if (!line.isEmpty() && timestamp <= newestLined) drop();
    }

    /** Forgets the line, as after the map was emptied or replaced. */
    void drop() {
        line.clear();
        newestLined = Long.MIN_VALUE;
    }

    /** Takes rows out, oldest first, until at most {@code cap} are left. */
    void trim(ConcurrentMap<String, Long> rows, int cap) {
        int excess = rows.size() - cap;
        if (excess <= 0) return;
        if (excess > BATCH) {
            drop();
            trimAll(rows, excess);
            return;
        }
        while (rows.size() > cap) {
            if (line.isEmpty() && !lineUp(rows)) return;
            Map.Entry<String, Long> oldest = line.pollFirst();
            if (line.isEmpty()) newestLined = Long.MIN_VALUE;
            rows.remove(oldest.getKey(), oldest.getValue());
        }
    }

    /** One pass keeping the {@link #BATCH} oldest rows. False when there are none. */
    private boolean lineUp(ConcurrentMap<String, Long> rows) {
        passes++;
        // The newest of the oldest so far sits on top, so a row older than it replaces it.
        PriorityQueue<Map.Entry<String, Long>> oldest =
                new PriorityQueue<>(BATCH + 1, Collections.reverseOrder(OLDEST_FIRST));
        for (Map.Entry<String, Long> row : rows.entrySet()) {
            if (oldest.size() < BATCH) {
                oldest.add(copy(row));
            } else if (OLDEST_FIRST.compare(row, oldest.peek()) < 0) {
                oldest.poll();
                oldest.add(copy(row));
            }
        }
        if (oldest.isEmpty()) return false;
        newestLined = oldest.peek().getValue();
        while (!oldest.isEmpty()) line.addFirst(oldest.poll());
        return true;
    }

    /** A bulk trim: one sort, then the oldest {@code excess} go. */
    private void trimAll(ConcurrentMap<String, Long> rows, int excess) {
        passes++;
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(rows.size());
        for (Map.Entry<String, Long> row : rows.entrySet()) ordered.add(copy(row));
        Collections.sort(ordered, OLDEST_FIRST);
        for (int index = 0; index < excess && index < ordered.size(); index++) {
            Map.Entry<String, Long> row = ordered.get(index);
            rows.remove(row.getKey(), row.getValue());
        }
    }

    private static Map.Entry<String, Long> copy(Map.Entry<String, Long> row) {
        return new AbstractMap.SimpleImmutableEntry<>(row.getKey(), row.getValue());
    }
}

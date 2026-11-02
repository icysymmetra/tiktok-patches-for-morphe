/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

/**
 * The seen record's eviction at the cap (#84): the same rows go as a full sort would take out,
 * one pass over the record per {@link SeenEviction#BATCH} videos instead of one per video, a row
 * from disk older than the line goes next, and a row seen again keeps its place. The last test
 * measures 10,000, 50,000 and 100,000 rows and prints the times; it asserts only the pass count,
 * which doesn't depend on the machine.
 */
public class SeenEvictionTest {
    /** What the record must hold: the newest {@code cap} rows, ties broken as OLDEST_FIRST does. */
    private static Map<String, Long> reference(Map<String, Long> rows, int cap) {
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(rows.entrySet());
        Collections.sort(ordered, SeenEviction.OLDEST_FIRST);
        Map<String, Long> kept = new HashMap<>();
        for (int index = Math.max(0, ordered.size() - cap); index < ordered.size(); index++) {
            kept.put(ordered.get(index).getKey(), ordered.get(index).getValue());
        }
        return kept;
    }

    private static void put(ConcurrentHashMap<String, Long> rows, SeenEviction eviction, String id, long time) {
        rows.put(id, time);
        eviction.added(time);
    }

    @Test public void videosWatchedAtTheCapTakeOutWhatAFullSortWould() {
        int cap = 1_000;
        Random random = new Random(84);
        ConcurrentHashMap<String, Long> rows = new ConcurrentHashMap<>();
        SeenEviction eviction = new SeenEviction();
        for (int index = 0; index < cap; index++) rows.put("v" + index, (long) random.nextInt(500));
        long now = 1_000;
        for (int video = 0; video < 3_000; video++) {
            // Mostly new videos, some watched again.
            String id = random.nextInt(5) == 0 ? "v" + random.nextInt(cap) : "n" + video;
            Map<String, Long> expected = new HashMap<>(rows);
            expected.put(id, now);
            put(rows, eviction, id, now++);
            eviction.trim(rows, cap);
            assertEquals("after video " + video, reference(expected, cap), new HashMap<>(rows));
        }
        // Each pass lines up BATCH rows; a row watched again is skipped in line, so allow twice that.
        assertTrue("a pass per video: " + eviction.passes, eviction.passes <= 2 * (3_000 / SeenEviction.BATCH) + 2);
    }

    @Test public void aRowFromDiskOlderThanTheLineGoesNext() {
        int cap = 300;
        ConcurrentHashMap<String, Long> rows = new ConcurrentHashMap<>();
        SeenEviction eviction = new SeenEviction();
        for (int index = 0; index < cap; index++) rows.put("v" + index, 1_000L + index);
        put(rows, eviction, "new1", 2_000);
        eviction.trim(rows, cap);
        assertFalse(rows.containsKey("v0"));

        // A merge from disk brings in a row older than everything lined up. Without a new line,
        // v1 would go and the older row would stay.
        put(rows, eviction, "old", 5);
        eviction.trim(rows, cap);
        assertFalse("the older row jumped the line and stayed", rows.containsKey("old"));
        assertTrue("a newer row went in its place", rows.containsKey("v1"));
        assertEquals(cap, rows.size());
    }

    @Test public void aRowWatchedAgainKeepsItsPlace() {
        int cap = 300;
        ConcurrentHashMap<String, Long> rows = new ConcurrentHashMap<>();
        SeenEviction eviction = new SeenEviction();
        for (int index = 0; index < cap; index++) rows.put("v" + index, 1_000L + index);
        put(rows, eviction, "new1", 2_000);
        eviction.trim(rows, cap);

        // v1 is next in line, then it's watched again: it's the newest now, so v2 goes instead.
        put(rows, eviction, "v1", 2_001);
        put(rows, eviction, "new2", 2_002);
        eviction.trim(rows, cap);
        assertTrue("a row watched again went for its old time", rows.containsKey("v1"));
        assertFalse(rows.containsKey("v2"));
        assertEquals(1, eviction.passes);
    }

    @Test public void atTheSameTimeTheLargerIdGoesFirst() {
        ConcurrentHashMap<String, Long> rows = new ConcurrentHashMap<>();
        SeenEviction eviction = new SeenEviction();
        rows.put("a", 1L);
        rows.put("b", 1L);
        rows.put("c", 2L);
        eviction.trim(rows, 2);
        assertFalse(rows.containsKey("b"));
        assertTrue(rows.containsKey("a"));
    }

    @Test public void aBulkMergeSortsOnce() {
        int cap = 1_000;
        Random random = new Random(7);
        ConcurrentHashMap<String, Long> rows = new ConcurrentHashMap<>();
        SeenEviction eviction = new SeenEviction();
        for (int index = 0; index < cap + 5_000; index++) put(rows, eviction, "v" + index, random.nextInt(10_000));
        Map<String, Long> expected = reference(rows, cap);
        eviction.trim(rows, cap);
        assertEquals(expected, new HashMap<>(rows));
        assertEquals(1, eviction.passes);
    }

    @Test public void anEmptiedRecordStartsANewLine() {
        ConcurrentHashMap<String, Long> rows = new ConcurrentHashMap<>();
        SeenEviction eviction = new SeenEviction();
        for (int index = 0; index < 10; index++) rows.put("v" + index, (long) index);
        put(rows, eviction, "n", 100);
        eviction.trim(rows, 10);
        rows.clear();
        eviction.drop();
        for (int index = 0; index < 10; index++) rows.put("w" + index, 200L + index);
        put(rows, eviction, "m", 300);
        eviction.trim(rows, 10);
        assertFalse(rows.containsKey("w0"));
        assertEquals(10, rows.size());
    }

    /**
     * The numbers for #84: at each cap, how long the record takes to fill from a newest-first
     * read (the in-memory half of a cold load), how long a video watched at the cap takes with
     * this eviction and with the old pass per video, and how long a feed lookup takes. Printed
     * for the report; only the pass count is asserted.
     */
    @Test public void theRecordStaysBoundedAtEachCap() {
        for (int cap : new int[]{10_000, 50_000, 100_000}) {
            Random random = new Random(cap);
            List<String> ids = new ArrayList<>(cap);
            for (int index = 0; index < cap; index++) {
                ids.add(String.format(Locale.ROOT, "7%018d", (random.nextLong() & Long.MAX_VALUE) % 1_000_000_000_000_000_000L));
            }
            ConcurrentHashMap<String, Long> rows = new ConcurrentHashMap<>();
            SeenEviction eviction = new SeenEviction();
            Object lock = new Object();

            long loadStart = System.nanoTime();
            long time = 2_000_000_000_000L;
            for (String id : ids) {
                synchronized (lock) {
                    if (rows.putIfAbsent(id, time) == null) eviction.added(time);
                    time--;
                }
            }
            synchronized (lock) {
                eviction.trim(rows, cap);
            }
            long loadNs = System.nanoTime() - loadStart;

            int videos = 2_000;
            long now = 3_000_000_000_000L;
            long evictStart = System.nanoTime();
            for (int video = 0; video < videos; video++) {
                synchronized (lock) {
                    put(rows, eviction, "n" + video, now++);
                    eviction.trim(rows, cap);
                }
            }
            long evictNs = System.nanoTime() - evictStart;
            assertEquals(cap, rows.size());
            assertTrue(cap + ": " + eviction.passes + " passes for " + videos + " videos",
                    eviction.passes <= videos / SeenEviction.BATCH + 2);

            // The old way: a pass over the whole record for every video.
            int oldVideos = 200;
            long oldStart = System.nanoTime();
            for (int video = 0; video < oldVideos; video++) {
                rows.put("o" + video, now++);
                Map.Entry<String, Long> oldest = null;
                for (Map.Entry<String, Long> row : rows.entrySet()) {
                    if (oldest == null || SeenEviction.OLDEST_FIRST.compare(row, oldest) < 0) oldest = row;
                }
                rows.remove(oldest.getKey());
            }
            long oldNs = System.nanoTime() - oldStart;

            int lookups = 200_000;
            int hits = 0;
            long lookupStart = System.nanoTime();
            for (int index = 0; index < lookups; index++) {
                if (rows.get(ids.get(index % ids.size())) != null) hits++;
            }
            long lookupNs = System.nanoTime() - lookupStart;

            System.out.println(String.format(Locale.ROOT,
                    "seen record cap=%d: fill %.1f ms, video at cap %.1f us (old pass %.1f us), lookup %.0f ns, passes %d, hits %d",
                    cap, loadNs / 1e6, evictNs / 1e3 / videos, oldNs / 1e3 / oldVideos,
                    (double) lookupNs / lookups, eviction.passes, hits));
        }
    }
}

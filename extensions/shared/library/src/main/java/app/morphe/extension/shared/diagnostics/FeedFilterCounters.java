/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How many lists each feed filter route has been handed, and how much it took out of them.
 *
 * <p>Every ad report so far has arrived without a single line saying which route delivered the
 * ad, because the filter only writes its lines while diagnostic logging is on and a reporter has
 * no reason to know that switch exists. Three different routes can put a video on a profile
 * page, and a screenshot cannot tell them apart. These counters are always on, cost an add per
 * list, and turn "an ad got through" into "the profile grid route ran 4 times over 56 items and
 * removed none", which names the route without anyone having to ask.
 *
 * <p>A route with no line has never run, and that is the more useful half: a hook that never
 * fired looks exactly like a filter that decided to keep everything.
 */
public final class FeedFilterCounters {
    /**
     * Sources are a fixed set plus one per late-insertion name, so this is well clear of what
     * the app produces. The cap is here because a source string arrives from the host.
     */
    private static final int MAX_SOURCES = 64;

    /**
     * Distinct element kinds kept per route before the rest are counted together. A kind names a
     * shape, never content, and the set the host produces is small; the cap is the same kind of
     * guard as the one on sources.
     */
    static final int MAX_KINDS = 12;
    static final String OTHER_KINDS = "other";

    private static final class Counter {
        final AtomicLong lists = new AtomicLong();
        final AtomicLong itemsIn = new AtomicLong();
        final AtomicLong removed = new AtomicLong();
        /** Elements handed over that were not videos at all, so no rule ever saw them. */
        final AtomicLong unreadable = new AtomicLong();
        /** Lists this route handed back with nothing left in them. */
        final AtomicLong emptied = new AtomicLong();
        volatile String lastReason;
        /** The one filter that wiped list after list on this route, and the longest such run. */
        volatile String suspect;
        final AtomicLong suspectLists = new AtomicLong();
        /** How many elements of each shape this route was handed, for routes that name them. */
        final ConcurrentHashMap<String, AtomicLong> kinds = new ConcurrentHashMap<>();
    }

    private static final ConcurrentHashMap<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    /** Sources in the order the app first reached them; the map does not keep that. */
    private static final List<String> SEEN = new CopyOnWriteArrayList<>();
    private static final Object STATE_LOCK = new Object();

    private FeedFilterCounters() {
    }

    /** A deep copy for the diagnostic clear row's one-tap undo. */
    public static final class Snapshot {
        private final List<Line> lines;

        private Snapshot(List<Line> lines) {
            this.lines = lines;
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }
    }

    private static final class Line {
        final String source;
        final long lists, itemsIn, removed, unreadable, emptied, suspectLists;
        final String lastReason, suspect;
        final java.util.Map<String, Long> kinds = new java.util.HashMap<>();

        Line(String source, Counter counter) {
            this.source = source;
            this.lists = counter.lists.get();
            this.itemsIn = counter.itemsIn.get();
            this.removed = counter.removed.get();
            this.unreadable = counter.unreadable.get();
            this.emptied = counter.emptied.get();
            this.lastReason = counter.lastReason;
            this.suspect = counter.suspect;
            this.suspectLists = counter.suspectLists.get();
            for (java.util.Map.Entry<String, AtomicLong> kind : counter.kinds.entrySet()) {
                kinds.put(kind.getKey(), kind.getValue().get());
            }
        }
    }

    /**
     * A list this route was handed, counted before anything decides whether to filter it.
     *
     * <p>Counting it here rather than after the enablement check is the point: a route that ran
     * with every filter switched off still proves the hook is alive, and that is exactly the
     * question a report with no removals leaves open.
     */
    public static void sawList(String source, int size) {
        Counter counter = counter(source);
        if (counter == null) return;
        counter.lists.incrementAndGet();
        if (size > 0) counter.itemsIn.addAndGet(size);
    }

    /**
     * Elements this route was handed that it could not read as videos.
     *
     * <p>A route that kept everything reads the same whether nothing matched a rule or nothing
     * was ever tested, and issue #2's export was exactly that: 64 items, 0 removed, two ads
     * watched. This is the half that separates them.
     */
    public static void unreadable(String source, int count) {
        if (count <= 0) return;
        Counter counter = counter(source);
        if (counter == null) return;
        counter.unreadable.addAndGet(count);
    }

    /**
     * A list this route handed back with nothing left in it.
     *
     * <p>A batch filtered down to zero is not the same as a batch nothing matched, and from the
     * outside it does not look like filtering at all: the pager has nothing to advance to, and
     * whether TikTok asks for more is TikTok's decision. Upstream reported the feed freezing on
     * the swipe after a livestream was hidden, and an export that counts only removals cannot
     * tell that story from a healthy one. This is the line that can.
     */
    public static void emptied(String source) {
        Counter counter = counter(source);
        if (counter == null) return;
        counter.emptied.incrementAndGet();
    }

    /**
     * One filter has wiped {@code lists} lists in a row on this route.
     *
     * <p>Issue #20's export showed 29 profile pages of 10 reduced to 0 with SeriesFilter as
     * the last reason on every one, and it still took a reader to notice that the last reason
     * never changed. The longest run and its filter are kept, so a run that ended before the
     * report was sent is still in it.
     */
    public static void suspect(String source, String filter, int lists) {
        if (filter == null || lists <= 0) return;
        Counter counter = counter(source);
        if (counter == null) return;
        synchronized (STATE_LOCK) {
            if (lists < counter.suspectLists.get()) return;
            counter.suspect = filter;
            counter.suspectLists.set(lists);
        }
    }

    /**
     * One element of this shape was handed to this route.
     *
     * <p>For a route whose elements come in kinds a filter cannot see yet. Issue #21 asks for a
     * block of TikTok Shop products in search results that no account here is served, and a
     * report from an account that is served it can only say which shape it has if the export
     * counts shapes. The caller names the shape and nothing it says.
     */
    public static void sawKind(String source, String kind) {
        if (kind == null) return;
        Counter counter = counter(source);
        if (counter == null) return;
        count(counter.kinds, kind, 1);
    }

    private static void count(ConcurrentHashMap<String, AtomicLong> kinds, String kind, long add) {
        AtomicLong tally = kinds.get(kind);
        if (tally == null) {
            // A new kind takes the map's monitor: the bound is a check on the size followed by
            // an insert, and two parser threads that both saw eleven kinds each added their own
            // twelfth. A kind already counted never comes here.
            synchronized (kinds) {
                tally = kinds.get(kind);
                if (tally == null) {
                    if (kinds.size() >= MAX_KINDS && !OTHER_KINDS.equals(kind)) {
                        kind = OTHER_KINDS;
                        tally = kinds.get(kind);
                    }
                    if (tally == null) {
                        tally = new AtomicLong();
                        kinds.put(kind, tally);
                    }
                }
            }
        }
        tally.addAndGet(add);
    }

    /**
     * The posts the routes counted through {@link #removedItems} have taken out since the process
     * started. A diagnostic clear leaves it alone, since it's the reader's running count rather
     * than a report line.
     */
    private static final AtomicLong SESSION_REMOVED = new AtomicLong();

    /**
     * What a route that takes whole posts out of what TikTok shows removed: videos, ads, search
     * results. These alone make the running count. A tag or banner inside a post isn't one, and
     * neither is a video TikTok offers again from its cache after a list already lost it.
     */
    public static void removedItems(String source, int count, String reason) {
        if (count > 0) SESSION_REMOVED.addAndGet(count);
        removed(source, count, reason);
    }

    /** What this route took out of the list it was just handed. */
    public static void removed(String source, int count, String reason) {
        if (count <= 0) return;
        Counter counter = counter(source);
        if (counter == null) return;
        counter.removed.addAndGet(count);
        if (reason != null) counter.lastReason = reason;
    }

    private static Counter counter(String source) {
        if (source == null) return null;
        Counter existing = COUNTERS.get(source);
        if (existing != null) return existing;
        synchronized (STATE_LOCK) {
            existing = COUNTERS.get(source);
            if (existing != null) return existing;
            if (COUNTERS.size() >= MAX_SOURCES) return null;
            Counter created = new Counter();
            COUNTERS.put(source, created);
            SEEN.add(source);
            return created;
        }
    }

    /** One line per route the app has reached, in the order it first reached them. */
    public static List<String> report() {
        synchronized (STATE_LOCK) {
            List<String> lines = new ArrayList<>();
            for (String source : SEEN) {
                Counter counter = COUNTERS.get(source);
                if (counter == null) continue;
                StringBuilder line = new StringBuilder(source)
                        .append(": ").append(counter.lists.get()).append(" lists, ")
                        .append(counter.itemsIn.get()).append(" items, ")
                        .append(counter.removed.get()).append(" removed");
                long unreadable = counter.unreadable.get();
                if (unreadable > 0) line.append(", ").append(unreadable).append(" not videos");
                long emptied = counter.emptied.get();
                if (emptied > 0) line.append(", ").append(emptied).append(" left empty");
                String suspect = counter.suspect;
                if (suspect != null) {
                    line.append(", suspect=").append(suspect)
                            .append(" lists=").append(counter.suspectLists.get());
                }
                String reason = counter.lastReason;
                if (reason != null) line.append(". Last reason: ").append(reason);
                if (!counter.kinds.isEmpty()) line.append(". Kinds: ").append(kindsOf(counter.kinds));
                lines.add(line.toString());
            }
            return lines;
        }
    }

    /** The kinds most often seen first, then by name, so two reports read the same way. */
    private static String kindsOf(ConcurrentHashMap<String, AtomicLong> kinds) {
        List<java.util.Map.Entry<String, AtomicLong>> sorted = new ArrayList<>(kinds.entrySet());
        java.util.Collections.sort(sorted, (a, b) -> {
            int byCount = Long.compare(b.getValue().get(), a.getValue().get());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        StringBuilder text = new StringBuilder();
        for (java.util.Map.Entry<String, AtomicLong> kind : sorted) {
            if (text.length() > 0) text.append(", ");
            text.append(kind.getKey()).append(' ').append(kind.getValue().get());
        }
        return text.toString();
    }

    /** Takes the state a diagnostic clear is about to remove. */
    public static Snapshot snapshot() {
        synchronized (STATE_LOCK) {
            return snapshotLocked();
        }
    }

    /** Atomically takes the state a diagnostic clear removes, then starts from zero. */
    public static Snapshot snapshotAndClear() {
        synchronized (STATE_LOCK) {
            Snapshot snapshot = snapshotLocked();
            COUNTERS.clear();
            SEEN.clear();
            return snapshot;
        }
    }

    private static Snapshot snapshotLocked() {
        List<Line> copy = new ArrayList<>();
        for (String source : SEEN) {
            Counter counter = COUNTERS.get(source);
            if (counter != null) copy.add(new Line(source, counter));
        }
        return new Snapshot(copy);
    }

    /**
     * Adds a cleared snapshot back on top of anything counted since.
     *
     * <p>The feed keeps scrolling while the row still offers Undo, so the counts move between
     * the clear and the tap that puts them back. Adding rather than replacing keeps Undo from
     * being a second destructive action.
     */
    public static void restore(Snapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return;
        synchronized (STATE_LOCK) {
            List<String> countedSince = new ArrayList<>(SEEN);
            for (Line saved : snapshot.lines) {
                Counter counter = counter(saved.source);
                if (counter == null) continue;
                counter.lists.addAndGet(saved.lists);
                counter.itemsIn.addAndGet(saved.itemsIn);
                counter.removed.addAndGet(saved.removed);
                counter.unreadable.addAndGet(saved.unreadable);
                counter.emptied.addAndGet(saved.emptied);
                if (counter.lastReason == null) counter.lastReason = saved.lastReason;
                // The named kinds go back first, most counted first, and the overflow last, so
                // an undo with nothing counted in between gives back the same Kinds line. In
                // hash order, "other" could take a slot and push a named kind into it.
                List<java.util.Map.Entry<String, Long>> savedKinds = new ArrayList<>(saved.kinds.entrySet());
                java.util.Collections.sort(savedKinds, (a, b) -> {
                    boolean otherA = OTHER_KINDS.equals(a.getKey());
                    boolean otherB = OTHER_KINDS.equals(b.getKey());
                    if (otherA != otherB) return otherA ? 1 : -1;
                    int byCount = Long.compare(b.getValue(), a.getValue());
                    return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
                });
                for (java.util.Map.Entry<String, Long> kind : savedKinds) {
                    count(counter.kinds, kind.getKey(), kind.getValue());
                }
                // The longest run wins, whichever side of the clear it was on.
                if (saved.suspect != null && saved.suspectLists >= counter.suspectLists.get()) {
                    counter.suspect = saved.suspect;
                    counter.suspectLists.set(saved.suspectLists);
                }
            }

            // The restored routes were reached first. Keep newer ones after them.
            SEEN.clear();
            for (Line saved : snapshot.lines) {
                if (COUNTERS.containsKey(saved.source) && !SEEN.contains(saved.source)) {
                    SEEN.add(saved.source);
                }
            }
            for (String source : countedSince) {
                if (!SEEN.contains(source)) SEEN.add(source);
            }
        }
    }

    /** Forgets everything, which is what clearing the diagnostic data does. */
    public static void clear() {
        synchronized (STATE_LOCK) {
            COUNTERS.clear();
            SEEN.clear();
        }
    }

    /** What every route has taken out since TikTok started, through any diagnostic clear. */
    public static long sessionRemoved() {
        return SESSION_REMOVED.get();
    }

    /** Starts the running count from zero, for a test that needs a known start. */
    public static void resetSessionForTests() {
        SESSION_REMOVED.set(0);
    }
}

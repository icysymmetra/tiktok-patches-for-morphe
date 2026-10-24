/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.diagnostics;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What each hook family found in this TikTok build, and what it did not.
 *
 * <p>The patches attach to code TikTok renames on every release, and a hook that no longer
 * finds its anchor fails quietly: the switch still reads on and the thing it promises simply
 * does not happen. Nothing told anyone, so the lookups report here and the Diagnostics screen
 * shows a family at a time.
 *
 * <p>Only lookups the caller has no fallback for belong here. Most readers try a getter and
 * then a field, or several shapes of row in turn, and expect most of those to miss. Neither
 * does a condition that varies from row to row: this says whether a build is broken, and a
 * single comment cell that came up without its thumbs down is not that.
 *
 * <p>Reporting runs on the layout callback of a scrolling feed, several times per pass, so an
 * answer already known has to cost a hash lookup and nothing else. No lock is taken on a
 * repeat, and the wording of a miss is composed only on the call that records it.
 */
public final class HookStatus {
    /** Enough detail to describe a broken build; past this a family says it stopped counting. */
    private static final int MAX_ENTRIES_PER_FAMILY = 200;
    /** Serializes first observations with diagnostic snapshot, clear, and restore. */
    private static final Object STATE_LOCK = new Object();

    /** One immutable miss keeps the raw dedupe key and its displayed detail inseparable. */
    private static final class Miss {
        final String key;
        final String detail;

        Miss(String key, String detail) {
            this.key = key;
            this.detail = detail;
        }
    }

    private static final class Family {
        // newKeySet() is documented at API 24. Keep the concurrent-map-backed form for the
        // declared API 23 floor instead of relying on methods some libcore builds expose early.
        /** Raw names, so the hot path compares what the caller already holds. */
        final Set<String> bound = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final Set<String> missed = Collections.newSetFromMap(new ConcurrentHashMap<>());
        /** Raw key and displayed detail together, in the order misses arrived. */
        final List<Miss> order = new CopyOnWriteArrayList<>();
        volatile boolean truncated;
        volatile boolean boundTruncated;
    }

    // Declared as the class rather than Map: putIfAbsent on the Map interface is an API 24
    // default method, and ConcurrentHashMap has carried its own since API 1.
    private static final ConcurrentHashMap<String, Family> FAMILIES = new ConcurrentHashMap<>();
    /**
     * Bumped whenever the row is emptied.
     *
     * <p>A caller that reports something once a session, rather than relying on the dedupe here,
     * has no other way to notice that a diagnostic clear threw its report away: it would stay
     * quiet and the exported report would call a broken build healthy.
     */
    private static volatile long generation;
    /** Families in the order the app first touched them; the map does not keep that. */
    private static final List<String> SEEN = new CopyOnWriteArrayList<>();

    /** A deep copy used by the diagnostic clear row's one-tap undo. */
    public static final class Snapshot {
        private final List<FamilySnapshot> families;

        private Snapshot(List<FamilySnapshot> families) {
            this.families = families;
        }

        public boolean isEmpty() {
            return families.isEmpty();
        }
    }

    private static final class FamilySnapshot {
        final String name;
        final List<String> bound;
        final List<Miss> misses;
        final boolean truncated;
        final boolean boundTruncated;

        FamilySnapshot(String name, Family family) {
            this.name = name;
            this.bound = new ArrayList<>(family.bound);
            this.misses = new ArrayList<>(family.order);
            this.truncated = family.truncated;
            this.boundTruncated = family.boundTruncated;
        }
    }

    private HookStatus() {
    }

    /** A lookup that found what it wanted. A repeat costs one hash lookup and nothing else. */
    public static void bound(String family, String name) {
        Family entry = FAMILIES.get(family);
        if (entry != null && (entry.boundTruncated || entry.bound.contains(name))) return;
        synchronized (STATE_LOCK) {
            entry = family(family);
            if (entry.boundTruncated || entry.bound.contains(name)) return;
            if (entry.bound.size() < MAX_ENTRIES_PER_FAMILY) entry.bound.add(name);
            else entry.boundTruncated = true;
        }
    }

    /**
     * A view this build does not have under the id the extension knows it by.
     *
     * <p>An id that stays missing is reported again on every layout pass, so the check comes
     * first and the wording is built only on the pass that records it. Formatting it as an
     * argument would allocate a string per pass and throw it away.
     */
    public static void missingViewId(String family, String name) {
        Family entry = FAMILIES.get(family);
        // The truncated check comes first. Past the cap a key is never added, so without this
        // every later pass would miss the set and rebuild the wording it is not going to use.
        if (entry != null && (entry.truncated || entry.missed.contains(name))) return;
        record(family, name, "view id '" + name + "'");
    }

    /**
     * Removes a view-id miss after the same required view is found later.
     *
     * <p>Android can run a layout listener while an activity is still inflating. A lookup can
     * therefore miss on the first pass and bind on the next one without any TikTok contract
     * having changed. Keeping that first pass forever makes a working build look broken. Only
     * the exact view-id key is removed; member misses and hooks that threw remain evidence.
     */
    public static void recoveredViewId(String family, String name) {
        Family entry = FAMILIES.get(family);
        if (entry == null || !entry.missed.contains(name)) return;
        synchronized (STATE_LOCK) {
            entry = FAMILIES.get(family);
            if (entry == null || !entry.missed.remove(name)) return;
            for (Miss miss : entry.order) {
                if (miss.key.equals(name)) {
                    entry.order.remove(miss);
                    break;
                }
            }
        }
    }

    /** A member the extension asked for by name and this build does not have. */
    public static void missingMember(String family, String kind, String owner, String name) {
        String key = owner + '#' + name;
        Family entry = FAMILIES.get(family);
        if (entry != null && (entry.truncated || entry.missed.contains(key))) return;
        record(family, key, kind + " " + owner + "#" + name);
    }

    /**
     * A hook that found its anchor, ran, and came back out through a catch.
     *
     * <p>This is a different failure from a missing view or a renamed member, and it used to
     * leave no trace at all: the extension logged an exception and returned TikTok's own value,
     * so the export called the family healthy while the feature silently did nothing. It counts
     * as a miss because a hook that threw did not do its job, and the first one is named on the
     * family's line.
     *
     * <p>Keyed by the hook rather than by the exception, so a throw on every share sheet records
     * once and a second hook in the same family still gets its own line.
     */
    public static void threw(String family, String name, Throwable failure) {
        String key = name + " threw";
        Family entry = FAMILIES.get(family);
        if (entry != null && (entry.truncated || entry.missed.contains(key))) return;
        String cause = failure == null ? "an error it could not name" : failure.getClass().getName();
        record(family, key,
                "a working '" + name + "' hook (it threw " + cause + ")",
                "The '" + name + "' hook for " + family + " threw " + cause
                        + ", so TikTok's own behaviour was left alone");
    }

    private static void record(String family, String key, String detail) {
        record(family, key, detail, null);
    }

    private static void record(String family, String key, String detail, String ownMessage) {
        synchronized (STATE_LOCK) {
            Family entry = family(family);
            if (entry.truncated || entry.missed.contains(key)) return;
            if (entry.missed.size() >= MAX_ENTRIES_PER_FAMILY) {
                entry.truncated = true;
                return;
            }
            entry.missed.add(key);
            entry.order.add(new Miss(key, detail));
        }

        // Never hold STATE_LOCK while Logger enters LogBufferManager. Diagnostic clear takes
        // the buffer lock first and then snapshots this state, so doing both here would deadlock.
        String message = ownMessage == null ? "no " + detail + " for " + family : ownMessage;
        String line = ownMessage == null ? "This TikTok build has " + message : message;
        Logger.printInfo(() -> line);
        LogBufferManager.appendEvent(DiagnosticCategory.PATCH_ERRORS, "HookStatus", "WARN", message);
    }

    private static Family family(String name) {
        Family entry = FAMILIES.get(name);
        if (entry != null) return entry;
        Family created = new Family();
        Family raced = FAMILIES.putIfAbsent(name, created);
        if (raced != null) return raced;
        SEEN.add(name);
        return created;
    }

    /** Every lookup that missed, across every family, first miss first. */
    public static List<String> missing() {
        synchronized (STATE_LOCK) {
            List<String> all = new ArrayList<>();
            for (String name : SEEN) {
                Family entry = FAMILIES.get(name);
                if (entry == null) continue;
                for (Miss miss : entry.order) all.add(name + ": " + miss.detail);
            }
            return all;
        }
    }

    /** What one family looked for and did not find, first miss first. */
    public static List<String> missing(String family) {
        synchronized (STATE_LOCK) {
            Family entry = FAMILIES.get(family);
            List<String> result = new ArrayList<>();
            if (entry != null) {
                for (Miss miss : entry.order) result.add(miss.detail);
            }
            return result;
        }
    }

    /** True once any family has reported a miss, so a caller can say "all bound" cheaply. */
    public static boolean anyMissing() {
        synchronized (STATE_LOCK) {
            for (Family entry : FAMILIES.values()) {
                if (!entry.order.isEmpty()) return true;
            }
            return false;
        }
    }

    /** The families missing something, in the order the app first touched them. */
    public static List<String> familiesMissingSomething() {
        synchronized (STATE_LOCK) {
            List<String> names = new ArrayList<>();
            for (String name : SEEN) {
                Family entry = FAMILIES.get(name);
                if (entry != null && !entry.order.isEmpty()) names.add(name);
            }
            return names;
        }
    }

    /**
     * One line per family it has heard from: how many lookups bound, how many did not, and the
     * first thing that went missing. A family nothing has touched yet says nothing, because a
     * hook that has not run cannot be called broken. A family that stopped counting says so,
     * rather than letting a catastrophically broken build read like a mildly broken one.
     */
    /**
     * One family's line, written as a whole sentence.
     *
     * <p>It used to be five pieces glued together here, and no translation table can hold a
     * fragment: a language that puts the count somewhere else in the sentence had nowhere to
     * go. A bundle with a table sets one of these; a bundle without gets the English below.
     */
    public interface LineWriter {
        String line(String family, int found, int missing, boolean truncated, String firstMiss);
    }

    private static final LineWriter ENGLISH = (family, found, missing, truncated, firstMiss) -> {
        StringBuilder line = new StringBuilder(family)
                .append(": ").append(found).append(" found, ").append(missing).append(" missing");
        if (truncated) line.append(", and more it stopped counting");
        if (firstMiss != null) line.append(". First missing: ").append(firstMiss);
        return line.toString();
    };

    private static volatile LineWriter lineWriter;

    /** Set once by a bundle that can translate the line. */
    public static void setLineWriter(LineWriter writer) {
        lineWriter = writer;
    }

    public static List<String> report() {
        return report(null);
    }

    /**
     * One line per family, with {@code pausedMark} on the end of every line whose family reads
     * a setting, or with nothing added when the mark is null.
     */
    public static List<String> report(@Nullable String pausedMark) {
        synchronized (STATE_LOCK) {
            List<String> lines = new ArrayList<>();
            for (String name : SEEN) {
                Family entry = FAMILIES.get(name);
                if (entry == null) continue;
                LineWriter writer = lineWriter;
                String line = (writer == null ? ENGLISH : writer).line(
                        name,
                        entry.bound.size(),
                        entry.order.size(),
                        entry.truncated || entry.boundTruncated,
                        entry.order.isEmpty() ? null : entry.order.get(0).detail);
                lines.add(pausedMark != null && !RUNS_WHILE_PAUSED.contains(name) ? line + pausedMark : line);
            }
            return lines;
        }
    }

    /** Families whose hooks read no setting, so a pause changes nothing for them. */
    private static final Set<String> RUNS_WHILE_PAUSED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Says that this family's hooks take no setting into account. A paused export marks every
     * other family as taking TikTok's own path, and a family that does not has to say so, or the
     * sticker save button reads as switched off while it is on the screen working.
     */
    public static void runsWhilePaused(String family) {
        if (family != null) RUNS_WHILE_PAUSED.add(family);
    }

    /** Takes the state that a diagnostic clear is about to remove. */
    public static Snapshot snapshot() {
        synchronized (STATE_LOCK) {
            return snapshotLocked();
        }
    }

    /** Atomically takes the state a diagnostic clear removes, then starts a new generation. */
    public static Snapshot snapshotAndClear() {
        synchronized (STATE_LOCK) {
            Snapshot snapshot = snapshotLocked();
            FAMILIES.clear();
            SEEN.clear();
            generation++;
            return snapshot;
        }
    }

    /** Which generation of the row this is: it changes every time the row is emptied. */
    public static long generation() {
        return generation;
    }

    private static Snapshot snapshotLocked() {
        List<FamilySnapshot> copy = new ArrayList<>();
        for (String name : SEEN) {
            Family family = FAMILIES.get(name);
            if (family != null) copy.add(new FamilySnapshot(name, family));
        }
        return new Snapshot(copy);
    }

    /**
     * Puts a cleared snapshot before anything learned since the clear.
     *
     * <p>A hook can run again while the row still offers Undo. Keeping those later observations
     * avoids turning Undo into another destructive action. Misses are merged without going
     * through {@link #missingViewId}, because restoring old evidence must not emit new events.
     */
    public static void restore(Snapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return;

        synchronized (STATE_LOCK) {
            List<String> currentOrder = new ArrayList<>(SEEN);
            for (FamilySnapshot saved : snapshot.families) {
                Family current = family(saved.name);
                for (String name : saved.bound) {
                    if (current.bound.contains(name)) continue;
                    if (current.bound.size() < MAX_ENTRIES_PER_FAMILY) current.bound.add(name);
                    else current.boundTruncated = true;
                }

                List<Miss> laterMisses = new ArrayList<>(current.order);
                current.order.clear();
                current.missed.clear();
                for (Miss miss : saved.misses) {
                    if (!current.missed.add(miss.key)) continue;
                    current.order.add(miss);
                }
                for (Miss miss : laterMisses) {
                    if (current.missed.contains(miss.key)) continue;
                    if (current.missed.size() >= MAX_ENTRIES_PER_FAMILY) {
                        current.truncated = true;
                        break;
                    }
                    current.missed.add(miss.key);
                    current.order.add(miss);
                }
                current.truncated |= saved.truncated;
                current.boundTruncated |= saved.boundTruncated;
            }

            // The restored families were observed first. Keep newer families after them.
            SEEN.clear();
            for (FamilySnapshot saved : snapshot.families) {
                if (!SEEN.contains(saved.name)) SEEN.add(saved.name);
            }
            for (String name : currentOrder) {
                if (!SEEN.contains(name)) SEEN.add(name);
            }
        }
    }

    /** Forgets everything, which is what clearing the diagnostic data does. */
    public static void clear() {
        synchronized (STATE_LOCK) {
            FAMILIES.clear();
            SEEN.clear();
            generation++;
        }
    }
}

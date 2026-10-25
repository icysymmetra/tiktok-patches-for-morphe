/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows BlueDragon4251/tiktok-patches-for-morphe.
 */
package app.morphe.extension.tiktok.seen;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * A local watch history, used to keep videos you have already seen out of the feed. It
 * never leaves the device and holds nothing but an account, a video id and when it was watched.
 *
 * <p>The feed path never touches SQLite: the stored ids load once on a background thread
 * into an in-memory map, and a newly watched id goes into memory first and is written
 * behind it.</p>
 *
 * <p>Each TikTok account on the phone has a record of its own. Memory holds the signed-in
 * account's, and every read and write checks the account first, so a switch replaces memory
 * before the feed is filtered again: one account's watching never hides a video from another,
 * and a second account never sees what the first one watched.</p>
 */
public final class SeenVideoHistory {
    public enum ClearResult { CLEARED, FAILED, SUPERSEDED }

    public interface ClearCallback {
        void onComplete(ClearResult result);
    }

    public enum UndoResult {
        NOT_READY,
        EMPTY,
        RESTORED,
        PARTIAL,
        NONE_RETAINED,
        FAILED,
        /** A newer clear took over while this undo was queued, so it no longer applies. */
        SUPERSEDED
    }

    public interface UndoCallback {
        void onComplete(UndoResult result);
    }

    public interface UndoDetailsCallback {
        /** Counts describe cleared records retained by the committed merge. */
        void onComplete(UndoResult result, int retained, int requested);
    }

    public interface AdoptCallback {
        /** How many older records were added; a negative count means the write failed. */
        void onComplete(int added);
    }

    public enum ImportStatus { IMPORTED, ACCOUNT_CHANGED, SUPERSEDED, FAILED }

    /** The account and history generation that opened the picker, never the account at write time. */
    public static final class ImportTarget {
        private final String account;
        private final int generation;

        private ImportTarget(String account, int generation) {
            this.account = account;
            this.generation = generation;
        }

        public String accountKey() { return account; }
        public boolean isCurrentAccount() { return account.equals(SignedInUser.id()); }
    }

    public static final class ImportResult {
        public final ImportStatus status;
        public final int imported;
        public final int skipped;
        /** A successful import replaces the history, so a previous clear's Undo is retired. */
        public final boolean undoRetired;
        /**
         * Where the batch's skipped videos went, for a report that names each reason. Together
         * with {@link #imported} they make up the batch; the batch's own skipped rows aren't in them.
         */
        public final int alreadyRecorded;
        /** Older than Forget seen videos after allows. */
        public final int expired;
        /** Dated later than this phone's clock. */
        public final int future;
        /** Written, then pruned by the per-account cap because newer videos filled it. */
        public final int capped;
        /** Videos the account already had that the cap pruned to make room for the batch. */
        public final int displaced;
        /**
         * Of {@link #imported}, the videos the account already had, whose date the batch moved
         * later. A report that says what was added counts these as already there.
         */
        public final int refreshed;

        private ImportResult(ImportStatus status, int imported, int skipped) {
            this(status, imported, skipped, false, 0, 0, 0, 0, 0, 0);
        }

        private ImportResult(ImportStatus status, int imported, int skipped, boolean undoRetired,
                             int alreadyRecorded, int expired, int future, int capped, int displaced,
                             int refreshed) {
            this.status = status;
            this.imported = imported;
            this.skipped = skipped;
            this.undoRetired = undoRetired;
            this.alreadyRecorded = alreadyRecorded;
            this.expired = expired;
            this.future = future;
            this.capped = capped;
            this.displaced = displaced;
            this.refreshed = refreshed;
        }
    }

    /** One account's history as the history worker had it at one moment. */
    public static final class Snapshot {
        /** Video id to when it was last seen: retention applied, newest {@link #MAX_RECORDS} at most. */
        public final Map<String, Long> videos;
        public final long takenAt;

        private Snapshot(Map<String, Long> videos, long takenAt) {
            this.videos = java.util.Collections.unmodifiableMap(videos);
            this.takenAt = takenAt;
        }
    }

    /** The account a read or write was captured for is no longer the one signed in. */
    public static final class AccountChanged extends java.io.IOException {
        public AccountChanged() {
            super("The signed-in account changed");
        }
    }

    public interface ImportCallback {
        void onComplete(ImportResult result);
    }

    interface DatabaseFactory {
        Database create(Context context);
    }

    interface RowWriter {
        long insert(SQLiteDatabase database, ContentValues values);
    }

    /** The time a watch is marked with. */
    interface WatchClock {
        long nowMs();
    }

    private static final String DATABASE_NAME = "seen_videos.db";
    private static final int DATABASE_VERSION = 2;
    private static final String TABLE = "seen_videos";
    private static final String COLUMN_ACCOUNT = "account";
    private static final String COLUMN_AID = "aid";
    private static final String COLUMN_LAST_SEEN = "last_seen_ms";

    /**
     * The account of the rows version 1 kept, which recorded no account. Nothing on the phone
     * says whose they were, so they hide nothing until someone adds them to an account.
     */
    static final String UNOWNED = "";
    /** Whatever is watched while nobody is signed in. A uid is digits, so it never collides. */
    static final String SIGNED_OUT = "signed-out";

    private static final long UNKNOWN_DURATION_MARK_MS = 2_000L;
    private static final long MIN_MARK_MS = 1_000L;
    private static final long MAX_MARK_MS = 5_000L;
    private static final int MARK_PERCENT = 10;
    /** How many videos each account's record keeps; the settings row formats this number in. */
    public static final int MAX_RECORDS = 10_000;
    private static final Object HISTORY_LOCK = new Object();
    private static int generation;

    private static final ConcurrentHashMap<String, Long> SEEN = new ConcurrentHashMap<>();
    /** Lines up the oldest rows of {@link #SEEN} for eviction at the cap. Guarded by {@link #HISTORY_LOCK}. */
    private static final SeenEviction EVICTION = new SeenEviction();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Morphe-SeenVideoHistory");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean LOAD_STARTED = new AtomicBoolean();

    /** The account whose record memory holds, or null before the first read. */
    private static volatile String partition;
    /** How many unowned rows the last load counted; -1 until one has. */
    private static volatile int unowned = -1;

    private static volatile Database database;
    static final DatabaseFactory DEFAULT_DATABASE_FACTORY = context -> new Database(context);
    static volatile DatabaseFactory databaseFactory = DEFAULT_DATABASE_FACTORY;
    static final RowWriter DEFAULT_ROW_WRITER =
            (writable, values) -> writable.insertWithOnConflict(
                    TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    static volatile RowWriter rowWriter = DEFAULT_ROW_WRITER;
    static final WatchClock SYSTEM_CLOCK = System::currentTimeMillis;
    /** {@link #SYSTEM_CLOCK}, except in tests that hold a progress callback just after it read the account. */
    static volatile WatchClock watchClock = SYSTEM_CLOCK;
    /**
     * The history as it was before the last clear, read from the database rather than from
     * memory: memory may never have been loaded, and a clear deletes every row either way.
     */
    private static volatile Map<String, Long> undo;
    /** The account the last clear emptied, whose rows an undo puts back. */
    private static volatile String undoAccount;
    /**
     * Set the moment a clear is asked for, so the screen can offer the way back without
     * waiting for the copy to be read.
     */
    private static volatile boolean undoOffered;
    private static volatile boolean clearPending;
    private static volatile boolean undoPending;
    private static volatile String callbackAid;
    /** The account {@link #callbackAid} was playing for, so a mark left by one account isn't another's. */
    private static volatile String callbackAccount;
    private static volatile boolean callbackAidMarked;
    /**
     * The video on screen when the feed activity was built again for a new window width, kept
     * out of the filter until playback moves to another video. TikTok puts that video back after
     * the rebuild, but only if the lists it reads again still hold it; by then it has usually
     * been playing long enough to be marked seen (issue #26).
     */
    private static volatile String keptAid;

    private static void mergeSeen(String aid, long timestamp) {
        for (;;) {
            Long existing = SEEN.get(aid);
            if (existing != null && existing >= timestamp) return;
            if (existing == null) {
                if (SEEN.putIfAbsent(aid, timestamp) == null) {
                    EVICTION.added(timestamp);
                    return;
                }
            } else if (SEEN.replace(aid, existing, timestamp)) {
                EVICTION.added(timestamp);
                return;
            }
        }
    }

    private SeenVideoHistory() {
    }

    /**
     * The signed-in account's record, swapped into memory when the account changed. A load, a
     * clear or an undo started for the account before is left to finish on disk and no longer
     * touches memory, and the way back from a clear stays with the account that cleared.
     */
    private static String account() {
        String id = SignedInUser.id();
        String account = id == null ? SIGNED_OUT : id;
        if (account.equals(partition)) return account;
        synchronized (HISTORY_LOCK) {
            if (!account.equals(partition)) {
                partition = account;
                generation++;
                SEEN.clear();
                EVICTION.drop();
                LOAD_STARTED.set(false);
                callbackAid = null;
                callbackAidMarked = false;
                keptAid = null;
                undo = null;
                undoAccount = null;
                undoOffered = false;
                clearPending = false;
                undoPending = false;
            }
        }
        return account;
    }

    public static void onPlayProgressChange(String aid, long positionMs, long durationMs) {
        String normalizedAid = normalizeAid(aid);
        if (normalizedAid == null) {
            return;
        }
        String account = account();
        long nowMs = watchClock.nowMs();

        // A callback that read the account just before a switch leaves its video marked for the
        // account it read. The next account playing the same video starts over.
        if (!normalizedAid.equals(callbackAid) || !account.equals(callbackAccount)) {
            callbackAid = normalizedAid;
            callbackAccount = account;
            callbackAidMarked = false;
            // Playback moved on, so the video kept through a rebuild is an ordinary seen one.
            if (!normalizedAid.equals(keptAid)) keptAid = null;
        }

        if (!Settings.HIDE_SEEN_VIDEOS.get() || callbackAidMarked) {
            return;
        }
        if (!hasReachedSeenThreshold(positionMs, durationMs)) {
            return;
        }

        callbackAidMarked = true;
        markSeen(account, normalizedAid, nowMs);
    }

    public static boolean shouldHide(String aid) {
        if (!Settings.HIDE_SEEN_VIDEOS.get()) {
            return false;
        }

        String normalizedAid = normalizeAid(aid);
        if (normalizedAid == null) {
            return false;
        }
        String account = account();
        if (normalizedAid.equals(keptAid)) {
            return false;
        }
        ensureLoaded();
        Long lastSeen = SEEN.get(normalizedAid);
        if (lastSeen == null) {
            return false;
        }

        long cutoff = retentionCutoff(System.currentTimeMillis());
        if (cutoff == Long.MIN_VALUE || lastSeen >= cutoff) {
            return true;
        }

        if (SEEN.remove(normalizedAid, lastSeen)) {
            deleteAsync(account, normalizedAid, lastSeen);
        }
        return false;
    }

    /**
     * Keeps the video playing now in the feed through a rebuild of the feed activity. The
     * filter lets it through until playback reaches another video; a refreshed page still
     * drops it, as {@link #shouldHide} always has, once the user has moved on.
     */
    public static void keepThroughRebuild() {
        keptAid = callbackAid;
    }

    /** Forgets the signed-in account's record. Other accounts' records and unowned rows stay. */
    public static void clear() {
        clear(null);
    }

    /** Reports the database result on the main thread; a queued clear is not a completed clear. */
    public static void clear(ClearCallback callback) {
        String account = account();
        synchronized (HISTORY_LOCK) {
            final int clearGeneration = ++generation;
            Map<String, Long> before = new HashMap<>(SEEN);
            Map<String, Long> previousUndo = undo;
            String previousUndoAccount = undoAccount;
            boolean previousOffer = undoOffered && previousUndo != null;
            SEEN.clear();
            callbackAid = null;
            callbackAidMarked = false;
            keptAid = null;
            undo = null;
            undoAccount = account;
            undoOffered = true;
            clearPending = true;
            undoPending = false;
            IO.execute(() -> {
                Map<String, Long> copy = null;
                Throwable failure = null;
                try {
                    // Read the rows before deleting them. Memory is not the source here: a
                    // load may never have run, and the delete takes every row regardless.
                    copy = readAll(account);
                    getDatabase().getWritableDatabase().delete(
                            TABLE, COLUMN_ACCOUNT + " = ?", new String[]{account});
                } catch (Throwable throwable) {
                    failure = throwable;
                }
                ClearResult result = ClearResult.SUPERSEDED;
                synchronized (HISTORY_LOCK) {
                    // A newer clear owns the offer. Do not let an older worker replace its
                    // copy after the user has asked to clear again.
                    if (generation == clearGeneration) {
                        clearPending = false;
                        if (failure == null) {
                            undo = copy;
                            result = ClearResult.CLEARED;
                        } else {
                            // Neither a failed read nor a failed delete erased the durable record.
                            // Keep newer sightings made while the worker was pending.
                            Map<String, Long> retained = copy == null ? before : copy;
                            for (Map.Entry<String, Long> row : retained.entrySet()) {
                                mergeSeen(row.getKey(), row.getValue());
                            }
                            EVICTION.trim(SEEN, MAX_RECORDS);
                            LOAD_STARTED.set(false);
                            undo = previousUndo;
                            undoAccount = previousUndoAccount;
                            undoOffered = previousOffer;
                            result = ClearResult.FAILED;
                        }
                    }
                }
                if (failure != null) {
                    Logger.printException(() -> "Seen video history clear failed", failure);
                }
                notifyClear(callback, result, clearGeneration);
            });
        }
    }

    public static boolean isClearing() {
        account();
        return clearPending;
    }

    private static void notifyClear(ClearCallback callback, ClearResult result, int clearGeneration) {
        if (callback == null) return;
        Utils.runOnMainThread(() -> {
            account();
            boolean current;
            synchronized (HISTORY_LOCK) { current = generation == clearGeneration; }
            callback.onComplete(current ? result : ClearResult.SUPERSEDED);
        });
    }

    /** Every row of one account in the database, whether or not memory has been loaded. */
    private static Map<String, Long> readAll(String account) {
        Map<String, Long> rows = new HashMap<>();
        try (Cursor cursor = getDatabase().getReadableDatabase().query(
                TABLE,
                new String[]{COLUMN_AID, COLUMN_LAST_SEEN},
                COLUMN_ACCOUNT + " = ?", new String[]{account}, null, null, null)) {
            int aidColumn = cursor.getColumnIndexOrThrow(COLUMN_AID);
            int seenColumn = cursor.getColumnIndexOrThrow(COLUMN_LAST_SEEN);
            while (cursor.moveToNext()) {
                String aid = normalizeAid(cursor.getString(aidColumn));
                if (aid != null) {
                    rows.put(aid, cursor.getLong(seenColumn));
                }
            }
        }
        return rows;
    }

    /**
     * Whether the last thing asked of this history was a clear, so the way back is what to
     * offer next. Answered without waiting for the copy to be read off the database.
     */
    public static boolean canUndo() {
        account();
        return undoOffered;
    }

    /** How many videos the last clear removed, or zero when the copy is not ready yet. */
    public static int undoSize() {
        Map<String, Long> copy = undo;
        return copy == null ? 0 : copy.size();
    }

    /**
     * Queues the history to be put back as it was before the last clear. True means that the
     * write was queued; the callback overload reports whether SQLite committed it. The rows
     * are written again rather than the delete being deferred: a clear that a crash could undo
     * on its own would be worse than no undo at all.
     */
    public static boolean undoClear() {
        return undoClear((UndoCallback) null);
    }

    /**
     * Keeps the original callback available for callers that need only the outcome.
     * Restoring a nonempty subset is PARTIAL; a committed merge retaining none is NONE_RETAINED.
     */
    public static boolean undoClear(UndoCallback callback) {
        return undoClear((result, retained, requested) -> {
            if (callback != null) callback.onComplete(result);
        });
    }

    /** Queues a capped merge on the history worker and reports its committed counts on main. */
    public static boolean undoClear(UndoDetailsCallback callback) {
        account();
        UndoResult immediate = null;
        int requestGeneration;
        synchronized (HISTORY_LOCK) {
            Map<String, Long> copy = undo;
            String account = undoAccount;
            requestGeneration = generation;
            if (copy == null || account == null || undoPending) {
                immediate = UndoResult.NOT_READY;
            } else if (copy.isEmpty()) {
                undo = null;
                undoOffered = false;
                immediate = UndoResult.EMPTY;
            } else {
                final int undoGeneration = ++generation;
                undoPending = true;
                IO.execute(() -> {
                    UndoResult result = UndoResult.FAILED;
                    int retained = 0;
                    try {
                        Map<String, Long> before = readAll(account);
                        Map<String, Long> rows = new HashMap<>(before);
                        synchronized (HISTORY_LOCK) {
                            account();
                            if (generation != undoGeneration || undo != copy) {
                                notifyUndo(callback, UndoResult.SUPERSEDED, 0, copy.size(), undoGeneration);
                                return;
                            }
                            // Include sightings queued after Undo without moving the merge onto main.
                            for (Map.Entry<String, Long> row : SEEN.entrySet()) {
                                Long existing = rows.get(row.getKey());
                                if (existing == null || row.getValue() > existing) {
                                    rows.put(row.getKey(), row.getValue());
                                }
                            }
                        }
                        for (Map.Entry<String, Long> row : copy.entrySet()) {
                            Long existing = rows.get(row.getKey());
                            if (existing == null || row.getValue() > existing) {
                                rows.put(row.getKey(), row.getValue());
                            }
                        }
                        long cutoff = retentionCutoff(System.currentTimeMillis());
                        java.util.Iterator<Map.Entry<String, Long>> iterator = rows.entrySet().iterator();
                        while (iterator.hasNext()) {
                            if (iterator.next().getValue() < cutoff) iterator.remove();
                        }
                        trimMemory(rows);
                        SQLiteDatabase writable = getDatabase().getWritableDatabase();
                        writable.beginTransaction();
                        boolean committed = false;
                        try {
                            for (Map.Entry<String, Long> row : rows.entrySet()) {
                                if (row.getValue().equals(before.get(row.getKey()))) continue;
                                ContentValues values = new ContentValues();
                                values.put(COLUMN_ACCOUNT, account);
                                values.put(COLUMN_AID, row.getKey());
                                values.put(COLUMN_LAST_SEEN, row.getValue());
                                if (rowWriter.insert(writable, values) == -1L) {
                                    throw new IllegalStateException("SQLite rejected seen-history Undo");
                                }
                            }
                            // Capture once so a busy feed cannot keep Undo running indefinitely.
                            // These sightings must become durable before they affect its count;
                            // their ordinary queued writes cannot run until this worker returns.
                            Map<String, Long> arrived;
                            synchronized (HISTORY_LOCK) {
                                account();
                                arrived = generation == undoGeneration && undo == copy
                                        ? new HashMap<>(SEEN) : java.util.Collections.emptyMap();
                            }
                            for (Map.Entry<String, Long> row : arrived.entrySet()) {
                                Long existing = rows.get(row.getKey());
                                if (row.getValue() < cutoff
                                        || (existing != null && existing >= row.getValue())) continue;
                                ContentValues values = new ContentValues();
                                values.put(COLUMN_ACCOUNT, account);
                                values.put(COLUMN_AID, row.getKey());
                                values.put(COLUMN_LAST_SEEN, row.getValue());
                                if (rowWriter.insert(writable, values) == -1L) {
                                    throw new IllegalStateException("SQLite rejected concurrent seen-history Undo row");
                                }
                                rows.put(row.getKey(), row.getValue());
                            }
                            trimMemory(rows);
                            if (cutoff != Long.MIN_VALUE) {
                                writable.delete(TABLE, COLUMN_ACCOUNT + " = ? AND " + COLUMN_LAST_SEEN + " < ?",
                                        new String[]{account, String.valueOf(cutoff)});
                            }
                            writable.execSQL("DELETE FROM " + TABLE + " WHERE " + COLUMN_ACCOUNT + " = ? AND "
                                    + COLUMN_AID + " NOT IN (SELECT " + COLUMN_AID + " FROM " + TABLE + " WHERE "
                                    + COLUMN_ACCOUNT + " = ? ORDER BY " + COLUMN_LAST_SEEN + " DESC, "
                                    + COLUMN_AID + " ASC LIMIT " + MAX_RECORDS + ")",
                                    new Object[]{account, account});
                            synchronized (HISTORY_LOCK) {
                                account();
                                if (generation == undoGeneration && undo == copy) {
                                    writable.setTransactionSuccessful();
                                    committed = true;
                                }
                            }
                        } finally {
                            writable.endTransaction();
                        }
                        synchronized (HISTORY_LOCK) {
                            account();
                            if (committed && generation == undoGeneration && undo == copy) {
                                for (Map.Entry<String, Long> row : before.entrySet()) {
                                    if (!rows.containsKey(row.getKey())) SEEN.remove(row.getKey(), row.getValue());
                                }
                                for (Map.Entry<String, Long> row : rows.entrySet()) {
                                    mergeSeen(row.getKey(), row.getValue());
                                }
                                EVICTION.trim(SEEN, MAX_RECORDS);
                                // Later sightings are separate writes, not part of this commit.
                                for (String id : copy.keySet()) if (rows.containsKey(id)) retained++;
                                undo = null;
                                undoOffered = false;
                                LOAD_STARTED.set(true);
                                result = retained == 0 ? UndoResult.NONE_RETAINED
                                        : retained == copy.size() ? UndoResult.RESTORED : UndoResult.PARTIAL;
                            } else {
                                result = UndoResult.SUPERSEDED;
                            }
                        }
                    } catch (Throwable throwable) {
                        Logger.printException(() -> "Seen video history undo failed", throwable);
                    } finally {
                        synchronized (HISTORY_LOCK) {
                            if (generation == undoGeneration) undoPending = false;
                        }
                    }
                    notifyUndo(callback, result, retained, copy.size(), undoGeneration);
                });
            }
        }
        if (immediate != null) notifyUndo(callback, immediate, 0, 0, requestGeneration);
        return immediate == null;
    }

    public static boolean isRestoring() {
        account();
        return undoPending;
    }

    private static void notifyUndo(UndoDetailsCallback callback, UndoResult result,
                                   int retained, int requested, int undoGeneration) {
        if (callback == null) return;
        Utils.runOnMainThread(() -> {
            account();
            boolean current;
            synchronized (HISTORY_LOCK) { current = generation == undoGeneration; }
            callback.onComplete(current ? result : UndoResult.SUPERSEDED,
                    current ? retained : 0, requested);
        });
    }

    public static int size() {
        account();
        ensureLoaded();
        return SEEN.size();
    }

    /** Null while signed out. Capture this before the file picker can change the foreground account. */
    public static ImportTarget captureImportTarget() {
        String current = account();
        synchronized (HISTORY_LOCK) {
            return SIGNED_OUT.equals(current) ? null : new ImportTarget(current, generation);
        }
    }

    /**
     * Reads the captured account's history on the history worker, behind every write queued
     * before it, so the copy is one moment: no half-applied clear, import or undo, and every
     * sighting already marked is in it. Refused with {@link AccountChanged} once another
     * account is signed in. Blocks until the worker gets to it, so it's for a file worker,
     * never the main thread or the history worker itself.
     */
    public static Snapshot snapshot(ImportTarget target) throws java.io.IOException {
        if (target == null) throw new AccountChanged();
        java.util.concurrent.Future<Snapshot> read;
        try {
            read = IO.submit(() -> {
                if (!target.account.equals(account())) throw new AccountChanged();
                long now = System.currentTimeMillis();
                return new Snapshot(readNewest(target.account, retentionCutoff(now)), now);
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            throw new java.io.IOException("Seen video history could not queue the snapshot", failure);
        }
        try {
            return read.get();
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof AccountChanged) throw (AccountChanged) cause;
            throw new java.io.IOException("Seen video history could not be read", cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Stopped waiting for the seen video history");
        }
    }

    /** The account's rows the feed would still hide: inside retention, newest first, capped. */
    private static Map<String, Long> readNewest(String account, long cutoff) {
        Map<String, Long> rows = new HashMap<>();
        String selection = COLUMN_ACCOUNT + " = ?"
                + (cutoff == Long.MIN_VALUE ? "" : " AND " + COLUMN_LAST_SEEN + " >= ?");
        String[] selectionArgs = cutoff == Long.MIN_VALUE
                ? new String[]{account}
                : new String[]{account, String.valueOf(cutoff)};
        try (Cursor cursor = getDatabase().getReadableDatabase().query(
                TABLE,
                new String[]{COLUMN_AID, COLUMN_LAST_SEEN},
                selection, selectionArgs, null, null,
                COLUMN_LAST_SEEN + " DESC, " + COLUMN_AID + " ASC",
                String.valueOf(MAX_RECORDS))) {
            int aidColumn = cursor.getColumnIndexOrThrow(COLUMN_AID);
            int seenColumn = cursor.getColumnIndexOrThrow(COLUMN_LAST_SEEN);
            while (cursor.moveToNext()) {
                String aid = normalizeAid(cursor.getString(aidColumn));
                if (aid != null) rows.put(aid, cursor.getLong(seenColumn));
            }
        }
        return rows;
    }

    /** Commits a bounded batch before publishing anything to the feed's memory cache. */
    public static void importHistory(ImportTarget target, WatchHistoryImport.Records records,
                                     ImportCallback callback) {
        try {
            IO.execute(() -> importOnWorker(target, records, callback));
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            Logger.printException(() -> "Seen video history could not queue the import", failure);
            notifyImport(callback, new ImportResult(ImportStatus.FAILED, 0, 0));
        }
    }

    private static ImportStatus importTargetStatus(ImportTarget target) {
        String current = account();
        synchronized (HISTORY_LOCK) {
            if (target == null || !target.account.equals(current)) return ImportStatus.ACCOUNT_CHANGED;
            return target.generation == generation ? ImportStatus.IMPORTED : ImportStatus.SUPERSEDED;
        }
    }

    private static final class ImportStopped extends Exception {
        final ImportStatus status;
        ImportStopped(ImportStatus status) { this.status = status; }
    }

    private static void requireImportTarget(ImportTarget target) throws ImportStopped {
        ImportStatus status = importTargetStatus(target);
        if (status != ImportStatus.IMPORTED) throw new ImportStopped(status);
    }

    private static void importOnWorker(ImportTarget target, WatchHistoryImport.Records records,
                                       ImportCallback callback) {
        try {
            requireImportTarget(target);
            if (records == null || records.videos.size() > MAX_RECORDS) {
                throw new IllegalArgumentException("Invalid seen-history import batch");
            }
            long now = System.currentTimeMillis();
            long cutoff = retentionCutoff(now);
            Map<String, Long> before;
            Map<String, Long> after;
            int unownedCount;
            Map<String, Long> changed = new HashMap<>();
            int alreadyRecorded = 0;
            int expired = 0;
            int future = 0;
            SQLiteDatabase writable = getDatabase().getWritableDatabase();
            writable.beginTransaction();
            try {
                before = readAll(target.account);
                for (Map.Entry<String, Long> row : records.videos.entrySet()) {
                    long imported = row.getValue();
                    Long existing = before.get(row.getKey());
                    if (imported < cutoff) {
                        expired++;
                        continue;
                    }
                    if (imported > now) {
                        future++;
                        continue;
                    }
                    if (existing != null && existing >= imported) {
                        alreadyRecorded++;
                        continue;
                    }
                    ContentValues values = new ContentValues();
                    values.put(COLUMN_ACCOUNT, target.account);
                    values.put(COLUMN_AID, row.getKey());
                    values.put(COLUMN_LAST_SEEN, imported);
                    if (rowWriter.insert(writable, values) == -1L) {
                        throw new IllegalStateException("SQLite rejected a watch-history import row");
                    }
                    changed.put(row.getKey(), imported);
                }
                // Pruning is part of this transaction. Its failure must roll back the entire import.
                if (cutoff != Long.MIN_VALUE) {
                    writable.delete(TABLE, COLUMN_ACCOUNT + " = ? AND " + COLUMN_LAST_SEEN + " < ?",
                            new String[]{target.account, String.valueOf(cutoff)});
                }
                writable.execSQL("DELETE FROM " + TABLE + " WHERE " + COLUMN_ACCOUNT + " = ? AND "
                        + COLUMN_AID + " NOT IN (SELECT " + COLUMN_AID + " FROM " + TABLE + " WHERE "
                        + COLUMN_ACCOUNT + " = ? ORDER BY " + COLUMN_LAST_SEEN + " DESC, "
                        + COLUMN_AID + " ASC LIMIT " + MAX_RECORDS + ")",
                        new Object[]{target.account, target.account});
                after = readAll(target.account);
                try (Cursor count = writable.rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE "
                        + COLUMN_ACCOUNT + " = ?", new String[]{UNOWNED})) {
                    unownedCount = count.moveToFirst() ? count.getInt(0) : 0;
                }
                requireImportTarget(target);
                writable.setTransactionSuccessful();
            } finally {
                writable.endTransaction();
            }
            int imported = 0;
            int refreshed = 0;
            for (String id : changed.keySet()) {
                if (!after.containsKey(id)) continue;
                imported++;
                if (before.containsKey(id)) refreshed++;
            }
            // Rows inside retention that the account had and the cap took for the batch.
            int displaced = 0;
            for (Map.Entry<String, Long> row : before.entrySet()) {
                if (row.getValue() >= cutoff && !changed.containsKey(row.getKey())
                        && !after.containsKey(row.getKey())) displaced++;
            }
            boolean undoRetired = false;
            synchronized (HISTORY_LOCK) {
                if (importTargetStatus(target) == ImportStatus.IMPORTED) {
                    // A sighting queued during this transaction keeps its newer memory timestamp.
                    // Remove pruned old entries only if memory still holds exactly their old value.
                    for (Map.Entry<String, Long> row : before.entrySet()) {
                        if (!after.containsKey(row.getKey())) SEEN.remove(row.getKey(), row.getValue());
                    }
                    for (Map.Entry<String, Long> row : after.entrySet()) mergeSeen(row.getKey(), row.getValue());
                    EVICTION.trim(SEEN, MAX_RECORDS);
                    unowned = unownedCount;
                    LOAD_STARTED.set(true);
                    // Restoring an older clear on top of a full imported history can prune every
                    // restored ID. Retire that offer only after a committed, mutating import.
                    if (imported > 0 && undoOffered && target.account.equals(undoAccount)) {
                        undo = null;
                        undoAccount = null;
                        undoOffered = false;
                        undoRetired = true;
                    }
                }
            }
            notifyImport(callback, new ImportResult(ImportStatus.IMPORTED, imported,
                    records.skipped + records.videos.size() - imported, undoRetired,
                    alreadyRecorded, expired, future, changed.size() - imported, displaced, refreshed));
        } catch (ImportStopped stopped) {
            notifyImport(callback, new ImportResult(stopped.status, 0, 0));
        } catch (Exception failure) {
            Logger.printException(() -> "Seen video history import failed", failure);
            notifyImport(callback, new ImportResult(ImportStatus.FAILED, 0, 0));
        }
    }

    private static void notifyImport(ImportCallback callback, ImportResult result) {
        if (callback != null) Utils.runOnMainThread(() -> callback.onComplete(result));
    }

    /**
     * How many rows version 1 left without an account, as the last load counted them. They
     * hide nothing until {@link #adoptUnowned} adds them to an account, or they age out.
     */
    public static int unownedCount() {
        account();
        ensureLoaded();
        return Math.max(0, unowned);
    }

    /**
     * Adds every unowned row to the signed-in account's record, keeping the newer sighting of
     * a video both hold. Nothing on the phone says whose those rows were, so this happens only
     * when someone asks. The callback runs on the main thread with how many were added.
     */
    public static void adoptUnowned(AdoptCallback callback) {
        String account = account();
        final int adoptGeneration;
        synchronized (HISTORY_LOCK) {
            adoptGeneration = generation;
        }
        IO.execute(() -> {
            int added = -1;
            try {
                Map<String, Long> rows = readAll(UNOWNED);
                SQLiteDatabase writable = getDatabase().getWritableDatabase();
                writable.beginTransaction();
                try {
                    String[] args = {account};
                    // A video both hold keeps the newer time. Written for the SQLite of API 23,
                    // which has no upsert.
                    writable.execSQL("UPDATE " + TABLE + " SET " + COLUMN_LAST_SEEN + " = (SELECT u."
                            + COLUMN_LAST_SEEN + " FROM " + TABLE + " u WHERE u." + COLUMN_ACCOUNT + " = '' AND u."
                            + COLUMN_AID + " = " + TABLE + "." + COLUMN_AID + ") WHERE " + COLUMN_ACCOUNT
                            + " = ? AND " + COLUMN_LAST_SEEN + " < (SELECT u." + COLUMN_LAST_SEEN + " FROM "
                            + TABLE + " u WHERE u." + COLUMN_ACCOUNT + " = '' AND u." + COLUMN_AID + " = "
                            + TABLE + "." + COLUMN_AID + ")", args);
                    writable.execSQL("INSERT OR IGNORE INTO " + TABLE + " (" + COLUMN_ACCOUNT + ", "
                            + COLUMN_AID + ", " + COLUMN_LAST_SEEN + ") SELECT ?, " + COLUMN_AID + ", "
                            + COLUMN_LAST_SEEN + " FROM " + TABLE + " WHERE " + COLUMN_ACCOUNT + " = ''", args);
                    writable.delete(TABLE, COLUMN_ACCOUNT + " = ?", new String[]{UNOWNED});
                    writable.setTransactionSuccessful();
                } finally {
                    writable.endTransaction();
                }
                added = rows.size();
                synchronized (HISTORY_LOCK) {
                    unowned = 0;
                    // Memory takes them only while it still holds the account that asked.
                    if (generation == adoptGeneration && account.equals(partition)) {
                        for (Map.Entry<String, Long> row : rows.entrySet()) mergeSeen(row.getKey(), row.getValue());
                        EVICTION.trim(SEEN, MAX_RECORDS);
                    }
                }
                pruneDatabase(account, System.currentTimeMillis());
            } catch (Throwable throwable) {
                Logger.printException(() -> "Seen video history could not add the older records", throwable);
            }
            if (callback != null) {
                int result = added;
                Utils.runOnMainThread(() -> callback.onComplete(result));
            }
        });
    }

    /**
     * Pruning runs a delete whose subquery orders the whole table, and it ran after every video
     * watched. The table only has to stay near its cap, so once every so many writes is enough.
     */
    private static final int WRITES_BETWEEN_PRUNES = 200;
    private static final AtomicInteger writesSincePrune = new AtomicInteger();

    private static boolean pruneIsDue() {
        return writesSincePrune.incrementAndGet() % WRITES_BETWEEN_PRUNES == 0;
    }

    private static void markSeen(String account, String aid, long nowMs) {
        synchronized (HISTORY_LOCK) {
            // A progress callback can read the account, then lose the race to a switch. Its
            // watch is still that account's on disk, but memory holds the next account's record.
            if (account.equals(partition)) {
                ensureLoaded();
                SEEN.put(aid, nowMs);
                EVICTION.added(nowMs);
                EVICTION.trim(SEEN, MAX_RECORDS);
            }
            IO.execute(() -> {
                try {
                    // Undo can commit a newer sighting before this older queued write runs.
                    // Conditional INSERT works on the API 23 SQLite without requiring UPSERT.
                    getDatabase().getWritableDatabase().execSQL(
                            "INSERT OR REPLACE INTO " + TABLE + " (" + COLUMN_ACCOUNT + ", "
                                    + COLUMN_AID + ", " + COLUMN_LAST_SEEN + ") SELECT ?, ?, ?"
                                    + " WHERE NOT EXISTS (SELECT 1 FROM " + TABLE + " WHERE "
                                    + COLUMN_ACCOUNT + " = ? AND " + COLUMN_AID + " = ? AND "
                                    + COLUMN_LAST_SEEN + " >= ?)",
                            new Object[]{account, aid, nowMs, account, aid, nowMs});
                    if (pruneIsDue()) {
                        pruneDatabase(account, nowMs);
                    }
                } catch (Throwable throwable) {
                    Logger.printException(() -> "Seen video history write failed", throwable);
                }
            });
        }
    }

    private static void ensureLoaded() {
        synchronized (HISTORY_LOCK) {
            // Every caller asked for the account first, so memory has a partition to load.
            final String account = partition;
            if (account == null || !LOAD_STARTED.compareAndSet(false, true)) {
                return;
            }
            final int loadGeneration = generation;
            IO.execute(() -> {
                long nowMs = System.currentTimeMillis();
                long cutoff = retentionCutoff(nowMs);
                try {
                    SQLiteDatabase readable = getDatabase().getReadableDatabase();
                    String selection = COLUMN_ACCOUNT + " = ?"
                            + (cutoff == Long.MIN_VALUE ? "" : " AND " + COLUMN_LAST_SEEN + " >= ?");
                    String[] selectionArgs = cutoff == Long.MIN_VALUE
                            ? new String[]{account}
                            : new String[]{account, String.valueOf(cutoff)};
                    try (Cursor cursor = readable.query(
                            TABLE,
                            new String[]{COLUMN_AID, COLUMN_LAST_SEEN},
                            selection,
                            selectionArgs,
                            null,
                            null,
                            COLUMN_LAST_SEEN + " DESC, " + COLUMN_AID + " ASC",
                            String.valueOf(MAX_RECORDS)
                    )) {
                        int aidColumn = cursor.getColumnIndexOrThrow(COLUMN_AID);
                        int seenColumn = cursor.getColumnIndexOrThrow(COLUMN_LAST_SEEN);
                        while (cursor.moveToNext()) {
                            String aid = normalizeAid(cursor.getString(aidColumn));
                            if (aid == null) {
                                continue;
                            }
                            long persisted = cursor.getLong(seenColumn);
                            synchronized (HISTORY_LOCK) {
                                if (generation != loadGeneration) break;
                                mergeSeen(aid, persisted);
                            }
                        }
                    }
                    // Rows arrive newest first, each older than the last, so a trim per row
                    // would line up again for every one. Memory runs over the cap only by the
                    // videos watched while this loaded, and one trim at the end takes those off.
                    synchronized (HISTORY_LOCK) {
                        if (generation == loadGeneration) EVICTION.trim(SEEN, MAX_RECORDS);
                    }
                    pruneDatabase(account, nowMs);
                    try (Cursor count = readable.rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE "
                            + COLUMN_ACCOUNT + " = ?", new String[]{UNOWNED})) {
                        unowned = count.moveToFirst() ? count.getInt(0) : 0;
                    }
                } catch (Throwable throwable) {
                    synchronized (HISTORY_LOCK) {
                        // A failed open must not permanently claim that the first load
                        // happened. The next read can retry after the cause is gone. The
                        // generation may have changed while the open was waiting, but no
                        // newer load can start until this gate is released.
                        LOAD_STARTED.set(false);
                    }
                    Logger.printException(() -> "Seen video history load failed", throwable);
                }
            });
        }
    }

    /**
     * The age limit holds every account's rows and the unowned ones; the size cap holds the
     * account being written, since each account keeps up to {@link #MAX_RECORDS} of its own.
     */
    private static void pruneDatabase(String account, long nowMs) {
        long cutoff = retentionCutoff(nowMs);
        try {
            if (cutoff != Long.MIN_VALUE) {
                getDatabase().getWritableDatabase().delete(
                        TABLE,
                        COLUMN_LAST_SEEN + " < ?",
                        new String[]{String.valueOf(cutoff)}
                );
            }
            getDatabase().getWritableDatabase().execSQL(
                    "DELETE FROM " + TABLE + " WHERE " + COLUMN_ACCOUNT + " = ? AND " + COLUMN_AID
                            + " NOT IN (SELECT " + COLUMN_AID + " FROM " + TABLE + " WHERE "
                            + COLUMN_ACCOUNT + " = ? ORDER BY " + COLUMN_LAST_SEEN + " DESC, " + COLUMN_AID + " ASC LIMIT "
                            + MAX_RECORDS + ")", new Object[]{account, account});
        } catch (Throwable throwable) {
            Logger.printException(() -> "Seen video history prune failed", throwable);
        }

        if (!account.equals(partition)) return;
        for (Map.Entry<String, Long> entry : SEEN.entrySet()) {
            Long timestamp = entry.getValue();
            if (timestamp != null && timestamp < cutoff) {
                SEEN.remove(entry.getKey(), timestamp);
            }
        }
    }

    private static void trimMemory(Map<String, Long> rows) {
        int excess = rows.size() - MAX_RECORDS;
        if (excess <= 0) return;
        // One ordinary sighting needs one scan. Bulk merges sort once, not once per eviction.
        // Memory's own record goes through EVICTION instead; this trims a copy being built.
        java.util.Comparator<Map.Entry<String, Long>> oldestFirst = SeenEviction.OLDEST_FIRST;
        if (excess == 1) {
            Map.Entry<String, Long> oldest = null;
            for (Map.Entry<String, Long> row : rows.entrySet()) {
                if (oldest == null || oldestFirst.compare(row, oldest) < 0) oldest = row;
            }
            if (oldest != null) rows.remove(oldest.getKey());
            return;
        }
        java.util.List<Map.Entry<String, Long>> ordered = new java.util.ArrayList<>(rows.entrySet());
        java.util.Collections.sort(ordered, oldestFirst);
        for (int index = 0; index < excess; index++) {
            Map.Entry<String, Long> row = ordered.get(index);
            rows.remove(row.getKey());
        }
    }

    private static void deleteAsync(String account, String aid, long expiredTimestamp) {
        IO.execute(() -> {
            try {
                getDatabase().getWritableDatabase().delete(
                        TABLE,
                        COLUMN_ACCOUNT + " = ? AND " + COLUMN_AID + " = ? AND " + COLUMN_LAST_SEEN + " <= ?",
                        new String[]{account, aid, String.valueOf(expiredTimestamp)}
                );
            } catch (Throwable throwable) {
                Logger.printException(() -> "Seen video history delete failed", throwable);
            }
        });
    }

    private static long retentionCutoff(long nowMs) {
        int days = retentionDays();
        if (days <= 0) {
            return Long.MIN_VALUE;
        }
        return nowMs - days * 24L * 60L * 60L * 1_000L;
    }

    /** Zero keeps everything; anything older than this many days is dropped. */
    private static int retentionDays() {
        // 0 to 3650, the range the dialog offers. A restored backup can hold anything.
        return Math.max(0, Math.min(3650, Settings.SEEN_VIDEO_RETENTION_DAYS.get()));
    }

    /**
     * With no percent chosen, a tenth of the video held to one to five seconds. With one, that
     * share of the video, never less than a second and never later than a second before the
     * end, where the last progress report may not land. A video of unknown length counts after
     * two seconds either way, since a share of it can't be worked out.
     */
    static boolean hasReachedSeenThreshold(long positionMs, long durationMs) {
        long safePosition = Math.max(0L, positionMs);
        if (durationMs <= 0L) {
            return safePosition >= UNKNOWN_DURATION_MARK_MS;
        }

        // A clip shorter than the second itself never reaches it: its position stops at its
        // length and loops, so the floor is half the clip for those.
        long floor = durationMs < MIN_MARK_MS ? durationMs / 2L : MIN_MARK_MS;
        int chosen = markPercent();
        if (chosen == 0) {
            long percentThreshold = durationMs * MARK_PERCENT / 100L;
            return safePosition >= Math.max(floor, Math.min(MAX_MARK_MS, percentThreshold));
        }
        long share = durationMs * chosen / 100L;
        return safePosition >= Math.max(floor, Math.min(share, durationMs - MIN_MARK_MS));
    }

    /** 0 to 90, the range the dialog offers. A restored backup can hold anything. */
    private static int markPercent() {
        return Math.max(0, Math.min(90, Settings.SEEN_VIDEO_MARK_PERCENT.get()));
    }

    private static String normalizeAid(String aid) {
        if (aid == null) {
            return null;
        }
        String normalized = aid.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static Database getDatabase() {
        Database result = database;
        if (result != null) {
            return result;
        }

        synchronized (SeenVideoHistory.class) {
            result = database;
            if (result == null) {
                Context context = app.morphe.extension.shared.Utils.getContext();
                if (context == null) {
                    throw new IllegalStateException("Application context is not available");
                }
                result = databaseFactory.create(context.getApplicationContext());
                if (result == null) {
                    throw new IllegalStateException("Database factory returned null");
                }
                database = result;
            }
            return result;
        }
    }

    static class Database extends SQLiteOpenHelper {
        Database(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // The account defaults to unowned, so a row an older bundle writes after a downgrade
            // lands where version 1's rows went rather than failing the insert.
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                            COLUMN_ACCOUNT + " TEXT NOT NULL DEFAULT '', " +
                            COLUMN_AID + " TEXT NOT NULL, " +
                            COLUMN_LAST_SEEN + " INTEGER NOT NULL, " +
                            "PRIMARY KEY (" + COLUMN_ACCOUNT + ", " + COLUMN_AID + ")" +
                            ")"
            );
            db.execSQL(
                    "CREATE INDEX IF NOT EXISTS seen_videos_account_last_seen " +
                            "ON " + TABLE + " (" + COLUMN_ACCOUNT + ", " + COLUMN_LAST_SEEN + ")"
            );
        }

        /**
         * Version 1 kept a video id and a time, keyed on the id. Its rows move into the version 2
         * table as unowned: dropping them would throw away the record the whole feature exists
         * to keep, and giving them to whoever is signed in next could hand one person's
         * watching to another. A table that already has the account column is left alone, which
         * is the case after an older bundle ran over a version 2 database and set the version
         * back to 1: moving it again would strip every row of its account.
         */
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (hasTable(db) && !hasAccountColumn(db)) {
                db.execSQL("DROP INDEX IF EXISTS seen_videos_last_seen");
                db.execSQL("ALTER TABLE " + TABLE + " RENAME TO " + TABLE + "_v1");
                onCreate(db);
                db.execSQL("INSERT OR REPLACE INTO " + TABLE + " (" + COLUMN_ACCOUNT + ", " + COLUMN_AID
                        + ", " + COLUMN_LAST_SEEN + ") SELECT '', " + COLUMN_AID + ", " + COLUMN_LAST_SEEN
                        + " FROM " + TABLE + "_v1");
                db.execSQL("DROP TABLE " + TABLE + "_v1");
            }
            onCreate(db);
        }

        /**
         * Going back to an older bundle is not a reason to lose the history either. The
         * default here throws, which would take the app down on the first read.
         */
        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            onCreate(db);
        }

        private static boolean hasTable(SQLiteDatabase db) {
            try (Cursor cursor = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", new String[]{TABLE})) {
                return cursor.moveToFirst();
            }
        }

        private static boolean hasAccountColumn(SQLiteDatabase db) {
            try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + TABLE + ")", null)) {
                int name = cursor.getColumnIndexOrThrow("name");
                while (cursor.moveToNext()) {
                    if (COLUMN_ACCOUNT.equals(cursor.getString(name))) return true;
                }
            }
            return false;
        }
    }
}

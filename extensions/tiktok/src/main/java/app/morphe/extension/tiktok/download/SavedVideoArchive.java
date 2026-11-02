/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Bundle;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.SystemClock;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A bounded local record of successful video saves, checked off the UI thread. A photo post saved
 * here is recorded too, under its aweme id like a video: as the video Save photo posts as a video
 * makes, or for a post saved as photos as the first photo that landed, which is what Open shows.
 *
 * <p>The reader can forget it from settings. Forgetting takes every row out at once, keeps them in
 * memory for the settings banner's Undo and nowhere else, and moves the record to a new
 * generation: a save that was already running when the reader forgot records nothing, so the
 * record they emptied doesn't fill back up behind them. The saved files are left alone.
 *
 * <p>The profile grid's mark ({@link SavedVideoMark}) asks about every cell it binds, on the main
 * thread, so it never reaches the database: it asks {@link #isSaved}, which answers from the ids
 * the record holds, read once off the main thread and changed with the rows from then on.
 */
public final class SavedVideoArchive {
    static final String DATABASE_NAME = "hushfeed-saved-videos.db";
    public static final int LIMIT = 10_000;
    /** How long a read of the ids that failed, or couldn't start, waits before the next try. */
    static final long IDS_RETRY_MS = 60_000L;

    /** Serializes writes, so a forget and a save finishing at the same moment can't interleave. */
    private static final Object LOCK = new Object();
    /**
     * The ids the record holds, or null until they're read. Read without the lock, from the main
     * thread among others. Changed only under {@link #LOCK}, in step with the rows: a save that
     * lands adds its id and takes out any the limit pushed off, a forget empties it and an Undo
     * reads it again. A process that never shows the mark never reads it.
     */
    private static volatile Set<String> savedIds;
    /** Set while a read of the ids is queued or running, so a grid full of cells asks once. */
    private static final AtomicBoolean READING_IDS = new AtomicBoolean();
    /** {@link SystemClock#elapsedRealtime()} before which a failed read isn't tried again. */
    private static volatile long idsRetryAt = Long.MIN_VALUE;
    /** Moved on by every forget. A save records only into the generation it started in. */
    private static final AtomicLong GENERATION = new AtomicLong();
    /**
     * What the latest forget took, for its Undo. Set under {@link #LOCK} and never written out.
     * Read and let go of without the lock: the settings row asks about it and its banner expires
     * it on the main thread, which must not wait on a restore or a save's database write.
     */
    private static final AtomicReference<Snapshot> UNDO = new AtomicReference<>();

    public enum ForgetResult { FORGOTTEN, NOTHING_SAVED, FAILED }

    public enum UndoResult { RESTORED, EXPIRED, FAILED }

    private static final class Row {
        final String aid, name, uri, path;
        final long savedAt;

        Row(String aid, String name, String uri, String path, long savedAt) {
            this.aid = aid;
            this.name = name;
            this.uri = uri;
            this.path = path;
            this.savedAt = savedAt;
        }
    }

    private static final class Snapshot {
        final long generation;
        final List<Row> rows;

        Snapshot(long generation, List<Row> rows) {
            this.generation = generation;
            this.rows = rows;
        }
    }

    /** What {@link #check} found. */
    enum Check { NEW, OFFERED, FAILED }

    private SavedVideoArchive() {}

    /**
     * For a photo post's save, off the main thread: offers Open or Save again when the post is
     * recorded, as a video's save does. {@code OFFERED} means the offer owns the save and calls
     * {@code release} when it's done with it; {@code FAILED} has said so and nothing is saved.
     */
    static Check check(Context context, String id, Runnable saveAgain, Runnable release) {
        try {
            MediaFileWriter.Saved previous = find(context, id);
            if (previous == null) return Check.NEW;
            offer(previous, saveAgain, release);
            return Check.OFFERED;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not check for an already-saved post", failure);
            Utils.showToastLong(L10n.t("The already-saved check failed. Try again."));
            return Check.FAILED;
        }
    }

    /** {@link #remember} for a save that must not fail because the record did. */
    static void rememberQuietly(Context context, String id, MediaFileWriter.Saved saved, long startedIn) {
        try {
            remember(context, id, saved, startedIn);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not remember the saved post", failure);
        }
    }

    /** The generation a save starts in, read as the save is accepted. */
    static long generation() {
        return GENERATION.get();
    }

    /**
     * Records a finished save, unless the record was forgotten after the save began.
     *
     * @param startedIn {@link #generation()} as the save was accepted
     */
    static void remember(Context context, String id, MediaFileWriter.Saved saved, long startedIn) {
        synchronized (LOCK) {
            if (GENERATION.get() != startedIn) return;
            Set<String> ids = savedIds;
            List<String> pushedOff = writeRow(context, id, saved, ids != null);
            if (ids != null) {
                ids.add(id);
                ids.removeAll(pushedOff);
            }
        }
    }

    /**
     * Writes the row and keeps the record within {@link #LIMIT}. Throws when the write fails, and
     * then nothing changed.
     *
     * @return the ids the limit pushed off, when {@code listPushedOff}; otherwise empty
     */
    private static List<String> writeRow(Context context, String id, MediaFileWriter.Saved saved,
            boolean listPushedOff) {
        try (Database helper = new Database(context)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues row = new ContentValues();
            row.put("aid", id);
            row.put("name", saved.name);
            row.put("uri", saved.uri == null ? "" : saved.uri.toString());
            row.put("path", saved.file == null ? "" : saved.file.getAbsolutePath());
            row.put("saved_at", System.currentTimeMillis());
            db.beginTransaction();
            try {
                if (db.insertWithOnConflict("saved_videos", null, row, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
                    throw new android.database.sqlite.SQLiteException("Could not remember the saved video");
                }
                List<String> pushedOff = listPushedOff ? pastLimit(db) : Collections.<String>emptyList();
                db.execSQL("DELETE FROM saved_videos WHERE aid IN (SELECT aid FROM saved_videos "
                        + "ORDER BY saved_at DESC, aid DESC LIMIT -1 OFFSET ?)", new Object[]{LIMIT});
                db.setTransactionSuccessful();
                return pushedOff;
            } finally {
                db.endTransaction();
            }
        }
    }

    /** The rows past {@link #LIMIT}, the ones the trim after a write takes out. */
    private static List<String> pastLimit(SQLiteDatabase db) {
        List<String> ids = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT aid FROM saved_videos "
                + "ORDER BY saved_at DESC, aid DESC LIMIT -1 OFFSET " + LIMIT, null)) {
            while (cursor.moveToNext()) ids.add(cursor.getString(0));
        }
        return ids;
    }

    /**
     * Whether the record holds {@code id}, for the grid's mark. Never touches the database, so it
     * is safe on the main thread for every cell: until the ids have been read this answers false
     * and has them read off the main thread, and the cells bound after that get their answer.
     */
    static boolean isSaved(String id) {
        if (id == null || id.isEmpty()) return false;
        Set<String> ids = savedIds;
        if (ids == null) {
            readIdsLater();
            return false;
        }
        return ids.contains(id);
    }

    /**
     * Has the ids read off the main thread, unless they are read already, a read is under way, or
     * the last one failed less than {@link #IDS_RETRY_MS} ago.
     */
    static void readIdsLater() {
        if (savedIds != null || SystemClock.elapsedRealtime() < idsRetryAt) return;
        Context context = Utils.getContext();
        if (context == null || !READING_IDS.compareAndSet(false, true)) return;
        Context application = context.getApplicationContext();
        Context app = application == null ? context : application;
        boolean started = Utils.runOnBackgroundThread(() -> {
            try {
                readIds(app);
            } finally {
                READING_IDS.set(false);
            }
        });
        if (!started) {
            idsRetryAt = SystemClock.elapsedRealtime() + IDS_RETRY_MS;
            READING_IDS.set(false);
        }
    }

    /** Reads the ids under the lock, so no save, forget or Undo lands between the read and the swap. */
    private static void readIds(Context context) {
        synchronized (LOCK) {
            if (savedIds != null) return;
            try {
                savedIds = queryIds(context);
            } catch (RuntimeException failure) {
                idsRetryAt = SystemClock.elapsedRealtime() + IDS_RETRY_MS;
                Logger.printException(() -> "Could not read which videos are saved", failure);
            }
        }
    }

    /** Every id in the record, in a set that is safe to read while it is changed under the lock. */
    private static Set<String> queryIds(Context context) {
        Set<String> ids = emptyIds();
        try (Database helper = new Database(context);
             Cursor cursor = helper.getReadableDatabase().query("saved_videos", new String[]{"aid"},
                     null, null, null, null, null)) {
            while (cursor.moveToNext()) ids.add(cursor.getString(0));
        }
        return ids;
    }

    private static Set<String> emptyIds() {
        return Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    }

    /**
     * Takes every row out of the record and keeps them in memory for {@link #undo}. Off the main
     * thread. A failure changes nothing, the Undo of an earlier forget included.
     *
     * @return the result, and through {@code forgotten} the generation an Undo must name
     */
    public static ForgetResult forget(Context context, long[] forgotten) {
        synchronized (LOCK) {
            List<Row> rows = new ArrayList<>();
            try (Database helper = new Database(context)) {
                SQLiteDatabase db = helper.getWritableDatabase();
                db.beginTransaction();
                try {
                    try (var cursor = db.query("saved_videos",
                            new String[]{"aid", "name", "uri", "path", "saved_at"},
                            null, null, null, null, "saved_at DESC", String.valueOf(LIMIT))) {
                        while (cursor.moveToNext()) {
                            rows.add(new Row(cursor.getString(0), cursor.getString(1),
                                    cursor.getString(2), cursor.getString(3), cursor.getLong(4)));
                        }
                    }
                    db.delete("saved_videos", null, null);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (RuntimeException failure) {
                Logger.printException(() -> "Could not forget the saved videos", failure);
                return ForgetResult.FAILED;
            }
            long generation = GENERATION.incrementAndGet();
            // A fresh set rather than a clear, so a cell never reads one half emptied.
            if (savedIds != null) savedIds = emptyIds();
            if (forgotten != null && forgotten.length > 0) forgotten[0] = generation;
            // A newer forget replaces an older Undo: putting that one back now would restore rows
            // the reader has since chosen to forget again.
            UNDO.set(rows.isEmpty() ? null : new Snapshot(generation, Collections.unmodifiableList(rows)));
            return rows.isEmpty() ? ForgetResult.NOTHING_SAVED : ForgetResult.FORGOTTEN;
        }
    }

    /**
     * Puts back what the forget of {@code generation} took, around anything saved since: a newer
     * save of the same video keeps its row, and the record stays within {@link #LIMIT} with the
     * oldest rows the first to go. Off the main thread.
     */
    public static UndoResult undo(Context context, long generation) {
        synchronized (LOCK) {
            Snapshot held = UNDO.get();
            if (held == null || held.generation != generation) return UndoResult.EXPIRED;
            try (Database helper = new Database(context)) {
                SQLiteDatabase db = helper.getWritableDatabase();
                db.beginTransaction();
                try {
                    for (Row row : held.rows) {
                        ContentValues values = new ContentValues();
                        values.put("aid", row.aid);
                        values.put("name", row.name);
                        values.put("uri", row.uri);
                        values.put("path", row.path);
                        values.put("saved_at", row.savedAt);
                        db.insertWithOnConflict("saved_videos", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                    }
                    db.execSQL("DELETE FROM saved_videos WHERE aid IN (SELECT aid FROM saved_videos "
                            + "ORDER BY saved_at DESC, aid DESC LIMIT -1 OFFSET ?)", new Object[]{LIMIT});
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (RuntimeException failure) {
                // Kept, so the reader can try again until the banner's time is up.
                Logger.printException(() -> "Could not put the saved videos back", failure);
                return UndoResult.FAILED;
            }
            // Unless the banner let go of it meanwhile, or a newer forget replaced it.
            UNDO.compareAndSet(held, null);
            // What came back went around the saves made since and the limit, so the ids are read
            // again rather than worked out. Should that fail, the next cell that asks reads them.
            if (savedIds != null) {
                try {
                    savedIds = queryIds(context);
                } catch (RuntimeException failure) {
                    savedIds = null;
                    Logger.printException(() -> "Could not read which videos are saved", failure);
                }
            }
            return UndoResult.RESTORED;
        }
    }

    /** Whether the forget of {@code generation} can still be undone. */
    public static boolean canUndo(long generation) {
        Snapshot held = UNDO.get();
        return held != null && held.generation == generation;
    }

    /** Lets go of the rows the forget of {@code generation} took, once its Undo is gone. */
    public static void discardUndo(long generation) {
        Snapshot held = UNDO.get();
        if (held != null && held.generation == generation) UNDO.compareAndSet(held, null);
    }

    static MediaFileWriter.Saved find(Context context, String id) {
        MediaFileWriter.Saved saved;
        try (Database helper = new Database(context);
             var cursor = helper.getReadableDatabase().query("saved_videos", new String[]{"name", "uri", "path"},
                     "aid=?", new String[]{id}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            String uri = cursor.getString(1);
            String path = cursor.getString(2);
            saved = new MediaFileWriter.Saved(cursor.getString(0), uri.isEmpty() ? null : Uri.parse(uri),
                    path.isEmpty() ? null : new File(path));
        }
        if (saved.uri == null) return saved.file != null && saved.file.isFile() && saved.file.length() > 0 ? saved : null;
        try (var file = context.getContentResolver().openFileDescriptor(saved.uri, "r")) {
            return file != null && file.getStatSize() != 0 ? saved : null;
        } catch (IOException | SecurityException missing) {
            // A deleted file or revoked access must not stop a new save. Keep the row because
            // permissions can return, and it is still bounded by the same retention limit.
            return null;
        }
    }

    static void offer(MediaFileWriter.Saved saved, Runnable saveAgain, Runnable release) {
        Utils.runOnMainThread(() -> {
            Activity activity = Utils.getVisibleActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                release.run();
                Utils.showToastLong(L10n.t("This video is already saved. Open TikTok to save another copy."));
                return;
            }
            AtomicBoolean saving = new AtomicBoolean();
            try {
                SettingsUi.syncDarkMode(activity);
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(L10n.t("This video is already saved"))
                        .setMessage(saved.name)
                        .setNegativeButton(L10n.t("Open"), (ignored, which) -> SaveNotice.open(saved))
                        .setPositiveButton(L10n.t("Save again"), (ignored, which) -> {
                            saving.set(true);
                            saveAgain.run();
                        })
                        .setNeutralButton(L10n.t("Cancel"), null).create();
                Application application = activity.getApplication();
                Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityDestroyed(Activity destroyed) {
                        if (destroyed == activity) dialog.dismiss();
                    }
                    @Override public void onActivityCreated(Activity item, Bundle state) { }
                    @Override public void onActivityStarted(Activity item) { }
                    @Override public void onActivityResumed(Activity item) { }
                    @Override public void onActivityPaused(Activity item) { }
                    @Override public void onActivityStopped(Activity item) { }
                    @Override public void onActivitySaveInstanceState(Activity item, Bundle state) { }
                };
                dialog.setOnDismissListener(ignored -> {
                    application.unregisterActivityLifecycleCallbacks(lifecycle);
                    if (!saving.get()) release.run();
                });
                dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
                application.registerActivityLifecycleCallbacks(lifecycle);
                try {
                    dialog.show();
                } catch (RuntimeException failure) {
                    application.unregisterActivityLifecycleCallbacks(lifecycle);
                    throw failure;
                }
            } catch (RuntimeException failure) {
                release.run();
                Logger.printException(() -> "Could not offer the previously saved video", failure);
                Utils.showToastLong(L10n.t("The saved-video choice couldn't open. Try again."));
            }
        });
    }

    /** Forgets the in-memory Undo, the ids and the generation, as a new process would. Tests only. */
    static void resetForTests() {
        synchronized (LOCK) {
            UNDO.set(null);
            GENERATION.set(0);
            savedIds = null;
            READING_IDS.set(false);
            idsRetryAt = Long.MIN_VALUE;
        }
    }

    /** Whether the ids are in memory yet. Tests only. */
    static boolean idsReadForTests() {
        return savedIds != null;
    }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) { super(context.getApplicationContext(), DATABASE_NAME, null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE saved_videos (aid TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, "
                    + "uri TEXT NOT NULL, path TEXT NOT NULL, saved_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX saved_videos_recency ON saved_videos(saved_at)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported saved-video archive upgrade");
        }
    }
}

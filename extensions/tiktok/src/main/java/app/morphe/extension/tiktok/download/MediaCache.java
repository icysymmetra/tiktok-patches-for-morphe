/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Base64;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.AtomicFile;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.RejectedExecutionException;

/** Owns temporary media files and the pending publications created by this extension. */
public final class MediaCache {
    static final String DIRECTORY_NAME = "hushfeed-media";
    static final long STALE_AFTER_MS = 24L * 60 * 60 * 1000;
    private static final String PENDING_FILE_NAME = "pending-uris.tsv";
    private static final String PENDING_LOCK_FILE_NAME = "pending-uris.lock";
    private static final String PENDING_INTENT_PREFIX = "intent:";
    /** What a row is called between the insert and the publish. */
    static final String TEMPORARY_NAME_PREFIX = "hushfeed-pending-";
    private static final AtomicInteger TEMPORARY_NAMES = new AtomicInteger();
    private static final Object LOCK = new Object();
    private static final AtomicBoolean RECONCILIATION_STARTED = new AtomicBoolean();
    private static final Set<String> ACTIVE_FILES = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private MediaCache() {}

    static File createTempFile(Context context, String prefix, String suffix) throws IOException {
        File file = File.createTempFile(prefix, suffix, directory(context));
        ACTIVE_FILES.add(file.getAbsolutePath());
        return file;
    }

    /** The folder the temporary files live in, for a start's look at what an earlier TikTok left. */
    static File cacheDirectory(Context context) throws IOException {
        return directory(context);
    }

    /** True while a job in this process still owns {@code file}. */
    static boolean isActive(File file) {
        return file != null && ACTIVE_FILES.contains(file.getAbsolutePath());
    }

    /** Final cleanup: the job no longer owns this file, even if its deletion must be retried. */
    static boolean delete(File file) {
        try {
            return deletePartial(file);
        } finally {
            if (file != null) ACTIVE_FILES.remove(file.getAbsolutePath());
        }
    }

    /** Removes a partial transfer while its caller still owns the file for a retry or cleanup. */
    static boolean deletePartial(File file) {
        return file == null || !file.exists() || file.delete();
    }

    static void markPending(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("MediaStore returned no URI");
        synchronized (LOCK) {
            File directory = journalDirectory(context);
            try (RandomAccessFile access = openJournalLock(directory);
                 FileChannel channel = access.getChannel();
                 FileLock ignored = channel.lock()) {
                Map<String, Long> records = readPendingAndMigrate(context, directory);
                records.put(uri.toString(), System.currentTimeMillis());
                writePending(directory, records);
            }
        }
    }

    /**
     * Records the insert intent before asking MediaStore for a row, closing the crash window.
     *
     * <p>The folder goes in the token as well as the name. Without it a recovery for
     * `video.mp4` in Download reaches a pending `video.mp4` in Movies, and the two are
     * different files that happen to share a name.
     */
    static String beginPending(Context context, Uri collection, String displayName, String relativePath)
            throws IOException {
        if (collection == null || displayName == null || displayName.isEmpty()) {
            throw new IOException("MediaStore publication details are missing");
        }
        String token = PENDING_INTENT_PREFIX + Long.toHexString(System.nanoTime()) + ":"
                + encode(collection.toString()) + ":" + encode(displayName)
                + ":" + encode(relativePath == null ? "" : relativePath);
        synchronized (LOCK) {
            File directory = journalDirectory(context);
            try (RandomAccessFile access = openJournalLock(directory);
                 FileChannel channel = access.getChannel();
                 FileLock ignored = channel.lock()) {
                Map<String, Long> records = readPendingAndMigrate(context, directory);
                records.put(token, System.currentTimeMillis());
                writePending(directory, records);
            }
        }
        return token;
    }

    /** Inserts and journals a pending row as one recoverable operation. */
    static Uri insertPending(
            Context context,
            ContentResolver resolver,
            Uri collection,
            ContentValues values
    ) throws IOException {
        String displayName = values == null
                ? null : values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
        if (displayName == null || displayName.isEmpty()) {
            throw new IOException("MediaStore publication details are missing");
        }
        String relativePath = values == null
                ? null : values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
        // The row goes in under a name nothing can collide with, and takes the reader's name at
        // publish. MediaStore renames an insert that collides, video.mp4 becoming video (1).mp4,
        // and recovery has only the name the insert asked for to go on, so a crash after a
        // renamed insert left a row nothing would ever find. A rename at publish is harmless
        // because the journal is holding the row's URI by then.
        String temporaryName = temporaryName(displayName);
        String token = beginPending(context, collection, temporaryName, relativePath);
        Uri uri = null;
        try {
            ContentValues pending = new ContentValues(values);
            pending.put(MediaStore.MediaColumns.DISPLAY_NAME, temporaryName);
            uri = resolver.insert(collection, pending);
            if (uri == null) throw new IOException("MediaStore returned no URI");
            markPending(context, token, uri);
            return uri;
        } catch (IOException | RuntimeException error) {
            boolean deleted = uri == null;
            if (uri != null) {
                try {
                    deleted = resolver.delete(uri, null, null) > 0;
                } catch (RuntimeException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            if (deleted) {
                try {
                    clearPendingIntent(context, token);
                } catch (IOException journalError) {
                    error.addSuppressed(journalError);
                }
            }
            throw error;
        }
    }

    private static void markPending(Context context, String token, Uri uri) throws IOException {
        if (uri == null) throw new IOException("MediaStore returned no URI");
        synchronized (LOCK) {
            File directory = journalDirectory(context);
            try (RandomAccessFile access = openJournalLock(directory);
                 FileChannel channel = access.getChannel();
                 FileLock ignored = channel.lock()) {
                Map<String, Long> records = readPendingAndMigrate(context, directory);
                records.remove(token);
                records.put(uri.toString(), System.currentTimeMillis());
                writePending(directory, records);
            }
        }
    }

    static void clearPending(Context context, Uri uri) throws IOException {
        if (uri == null) return;
        clearPendingKey(context, uri.toString());
    }

    private static void clearPendingIntent(Context context, String token) throws IOException {
        clearPendingKey(context, token);
    }

    private static void clearPendingKey(Context context, String key) throws IOException {
        if (key == null) return;
        synchronized (LOCK) {
            File directory = journalDirectory(context);
            try (RandomAccessFile access = openJournalLock(directory);
                 FileChannel channel = access.getChannel();
                 FileLock ignored = channel.lock()) {
                Map<String, Long> records = readPendingAndMigrate(context, directory);
                if (records.remove(key) != null) writePending(directory, records);
            }
        }
    }

    /**
     * The start's sweep, then what the last TikTok left of its saves. Runs from the host
     * application's attachBaseContext, where getApplicationContext() is still null (the
     * application object is only handed to the package after attach), so the context it was
     * given stands in; with null here every sweep returned at once and nothing was ever cleaned.
     * Only the main process starts a sweep. All journal readers and writers also take the
     * durable file lock, so another process cannot overwrite a publication's recovery record.
     *
     * <p>It also gets the profile grid's saved-video marks ready. That waits for the main thread,
     * which reaches it once the application has started: the settings aren't read this early.
     */
    public static void reconcileAsync(Context context) {
        if (context == null || !Utils.isMainProcess()
                || !RECONCILIATION_STARTED.compareAndSet(false, true)) return;
        Utils.runOnMainThread(SavedVideoMark::warm);
        Context app = applicationOr(context);
        try {
            Utils.submitOnBackgroundThread(() -> {
                try {
                    reconcile(app);
                    UnfinishedSaves.atStart(app);
                    LiveSpoolRecovery.atStart(app);
                } finally {
                    RECONCILIATION_STARTED.set(false);
                }
                return null;
            });
        } catch (RejectedExecutionException error) {
            RECONCILIATION_STARTED.set(false);
            Logger.printException(() -> "Could not schedule media cache reconciliation", error);
        }
    }

    private static Context applicationOr(Context context) {
        Context application = context.getApplicationContext();
        return application == null ? context : application;
    }

    static void reconcile(Context context) {
        if (context == null) return;
        try {
            Context app = applicationOr(context);
            synchronized (LOCK) {
                File directory = directory(app);
                long cutoff = System.currentTimeMillis() - STALE_AFTER_MS;
                File pendingBase = new File(directory, PENDING_FILE_NAME);
                File pendingBackup = new File(directory, PENDING_FILE_NAME + ".bak");
                File pendingNew = new File(directory, PENDING_FILE_NAME + ".new");
                File[] files = directory.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!file.isFile() || file.equals(pendingBase) || file.equals(pendingBackup)
                                || file.equals(pendingNew)
                                || ACTIVE_FILES.contains(file.getAbsolutePath())) continue;
                        if (file.lastModified() < cutoff && !file.delete()) {
                            Logger.printInfo(() -> "Could not remove stale media file " + file.getName());
                        }
                    }
                }

                File journal = journalDirectory(app);
                try (RandomAccessFile access = openJournalLock(journal);
                     FileChannel channel = access.getChannel();
                     FileLock ignored = channel.lock()) {
                    Map<String, Long> records = readPendingAndMigrate(app, journal);
                    boolean changed = false;
                    Iterator<Map.Entry<String, Long>> iterator = records.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<String, Long> entry = iterator.next();
                        if (entry.getValue() >= cutoff) continue;
                        if (reconcilePendingEntry(app.getContentResolver(), entry.getKey())) {
                            iterator.remove();
                            changed = true;
                        }
                    }
                    if (changed) writePending(journal, records);
                }
            }
        } catch (IOException | RuntimeException error) {
            Logger.printException(() -> "Media cache reconciliation failed", error);
        }
    }

    private static File directory(Context context) throws IOException {
        if (context == null || context.getCacheDir() == null) {
            throw new IOException("Application cache is unavailable");
        }
        File directory = new File(context.getCacheDir(), DIRECTORY_NAME);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create media cache");
        }
        return directory;
    }

    /** Recovery metadata is neither evictable cache nor portable across a restored install. */
    private static File journalDirectory(Context context) throws IOException {
        if (context == null || context.getNoBackupFilesDir() == null) {
            throw new IOException("Application publication storage is unavailable");
        }
        File directory = new File(context.getNoBackupFilesDir(), DIRECTORY_NAME);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create publication journal storage");
        }
        return directory;
    }

    /** Never delete the lock file: every process must keep locking the same inode. */
    private static RandomAccessFile openJournalLock(File directory) throws IOException {
        return new RandomAccessFile(new File(directory, PENDING_LOCK_FILE_NAME), "rw");
    }

    /** Called under both the thread lock and the durable process lock. */
    private static Map<String, Long> readPendingAndMigrate(Context context, File directory) throws IOException {
        Map<String, Long> records = readPending(directory);
        File cache = context.getCacheDir();
        if (cache == null) return records;
        File legacy = new File(cache, DIRECTORY_NAME);
        if (legacy.equals(directory) || !hasPendingJournal(legacy)) return records;
        Map<String, Long> previous = readPending(legacy);
        for (Map.Entry<String, Long> entry : previous.entrySet()) {
            Long current = records.get(entry.getKey());
            // A freshly journaled publication must not inherit a legacy record's old age.
            if (current == null || entry.getValue() > current) records.put(entry.getKey(), entry.getValue());
        }
        // Commit all readable ownership before retiring the old journal. Failure keeps it.
        writePending(directory, records);
        for (String suffix : new String[]{".new", ".bak", ""}) {
            File old = new File(legacy, PENDING_FILE_NAME + suffix);
            if (old.exists() && !old.delete()) throw new IOException("Could not retire legacy publication journal");
        }
        return records;
    }

    private static boolean hasPendingJournal(File directory) {
        return new File(directory, PENDING_FILE_NAME).exists()
                || new File(directory, PENDING_FILE_NAME + ".bak").exists()
                || new File(directory, PENDING_FILE_NAME + ".new").exists();
    }

    private static Map<String, Long> readPending(File directory) throws IOException {
        Map<String, Long> records = new LinkedHashMap<>();
        File base = new File(directory, PENDING_FILE_NAME);
        File backup = new File(directory, PENDING_FILE_NAME + ".bak");
        if (!hasPendingJournal(directory)) return records;
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            File file = new File(directory, PENDING_FILE_NAME + suffix);
            if (file.exists() && !file.isFile()) throw new IOException("Unreadable media publication journal");
        }
        if (!base.exists() && !backup.exists()) throw new IOException("Uncommitted media publication journal");
        try {
            // A backup is the committed record until a write retires it. AtomicFile.openRead
            // replaces base/deletes .new before decoding; an unreadable source must stay intact.
            File committed = backup.exists() ? backup : base;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(committed), StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0 || tab == line.length() - 1) throw new IOException("Malformed media publication record");
                    try {
                        long timestamp = Long.parseLong(line.substring(0, tab));
                        String uri = line.substring(tab + 1);
                        if (timestamp <= 0) throw new IOException("Invalid media publication timestamp");
                        records.put(uri, timestamp);
                    } catch (NumberFormatException error) {
                        throw new IOException("Unreadable media publication timestamp", error);
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            IOException failure = error instanceof IOException
                    ? (IOException) error : new IOException("Could not read media publication journal", error);
            throw failure;
        }
        return records;
    }

    private static void writePending(File directory, Map<String, Long> records) throws IOException {
        File base = new File(directory, PENDING_FILE_NAME);
        if (records.isEmpty()) {
            if (base.exists() && !base.delete()) throw new IOException("Could not clear media publication journal");
            File backup = new File(directory, PENDING_FILE_NAME + ".bak");
            if (backup.exists() && !backup.delete()) throw new IOException("Could not clear media journal backup");
            return;
        }
        AtomicFile atomic = new AtomicFile(base);
        FileOutputStream output = atomic.startWrite();
        try {
            Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            for (Map.Entry<String, Long> entry : records.entrySet()) {
                writer.write(Long.toString(entry.getValue()));
                writer.write('\t');
                writer.write(entry.getKey());
                writer.write('\n');
            }
            writer.flush();
            // AtomicFile logs some sync/rename failures instead of throwing. A successful
            // return alone must not authorize an insert or retire legacy ownership.
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            atomic.failWrite(output);
            throw error;
        }
        // A finished write is no longer eligible for rollback. Older AtomicFile versions
        // delete the committed base if failWrite is called after its backup is retired.
        if (!base.isFile() || new File(directory, PENDING_FILE_NAME + ".new").exists()
                || new File(directory, PENDING_FILE_NAME + ".bak").exists()) {
            throw new IOException("Media publication journal commit is incomplete");
        }
        if (!readPending(directory).equals(records)) {
            throw new IOException("Media publication journal commit did not retain its records");
        }
    }

    private static boolean reconcilePendingEntry(ContentResolver resolver, String value) {
        if (value != null && value.startsWith(PENDING_INTENT_PREFIX)) {
            return reconcilePendingIntent(resolver, value);
        }
        return reconcilePendingUri(resolver, value);
    }

    private static boolean reconcilePendingUri(ContentResolver resolver, String value) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        Uri uri;
        try {
            uri = Uri.parse(value);
            if (uri == null) return false;
            try (Cursor cursor = queryIncludingPending(resolver, uri,
                    new String[]{MediaStore.MediaColumns.IS_PENDING}, null, null)) {
                if (cursor == null) return false;
                if (!cursor.moveToFirst()) return true;
                int column = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING);
                if (column < 0) return false;
                if (cursor.getInt(column) == 0) return true;
            }
            return resolver.delete(uri, null, null) > 0;
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not reconcile pending media URI", error);
            return false;
        }
    }

    /**
     * Removes whatever an interrupted insert left behind under a journaled name.
     *
     * <p>Every row carrying the name is looked at. A published row is not evidence that our own
     * insert landed: nothing is published until the download has been written, so a published row
     * under this name belongs to an earlier save. Returning on the first one, which is what this
     * did, left the real orphan in the gallery forever whenever a published row happened to be
     * read first, and the query orders rows however the provider feels like.
     *
     * <p>A pending row we can see is our own. From API 29 a pending row is visible only to the
     * app that owns it, so there is nobody else's to delete.
     *
     * @return true when nothing is left to clean: no row matched, or every pending row that did
     *         was deleted. False keeps the journal entry for the next run.
     */
    private static boolean reconcilePendingIntent(ContentResolver resolver, String value) {
        PendingIntent intent = decodeIntent(value);
        if (intent == null) return false;
        try (Cursor cursor = queryIncludingPending(resolver, intent.collection,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH},
                MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{intent.displayName})) {
            if (cursor == null) return false;
            int idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
            int nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int pendingColumn = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING);
            int pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            if (idColumn < 0 || nameColumn < 0 || pendingColumn < 0) return false;
            boolean allDeleted = true;
            while (cursor.moveToNext()) {
                if (!intent.displayName.equals(cursor.getString(nameColumn))) continue;
                if (cursor.getInt(pendingColumn) == 0) continue;
                // A token written before the folder was recorded cannot scope itself, so it
                // takes every folder. One written since takes only the folder it asked for.
                if (intent.relativePath != null && pathColumn >= 0
                        && !samePath(intent.relativePath, cursor.getString(pathColumn))) {
                    continue;
                }
                Uri uri = ContentUris.withAppendedId(intent.collection, cursor.getLong(idColumn));
                if (resolver.delete(uri, null, null) <= 0) allDeleted = false;
            }
            return allDeleted;
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not reconcile pending media insert", error);
            return false;
        }
    }

    /**
     * A query that sees this app's own pending rows, which are the ones the sweep is for.
     * MediaStore hides pending rows from a query, the owner's included, unless it asks: on API 29
     * with MediaStore.setIncludePending on the URI, from API 30 with QUERY_ARG_MATCH_PENDING set to
     * MATCH_INCLUDE (which still shows no other app's pending rows). Without asking, every orphan
     * read as gone, lost its journal entry and stayed in MediaStore for good.
     */
    @SuppressWarnings("deprecation")
    static Cursor queryIncludingPending(ContentResolver resolver, Uri uri, String[] projection,
            String selection, String[] selectionArgs) {
        if (Build.VERSION.SDK_INT >= 30) {
            Bundle arguments = new Bundle();
            arguments.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            if (selection != null) {
                arguments.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
                arguments.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
            }
            return resolver.query(uri, projection, arguments, null);
        }
        Uri asking = Build.VERSION.SDK_INT == 29 ? MediaStore.setIncludePending(uri) : uri;
        return resolver.query(asking, projection, selection, selectionArgs, null);
    }

    /** MediaStore stores a folder with a trailing separator and the callers do not write one. */
    private static boolean samePath(String wanted, String stored) {
        return trimSeparators(wanted).equals(trimSeparators(stored));
    }

    private static String trimSeparators(String path) {
        if (path == null) return "";
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') start++;
        while (end > start && path.charAt(end - 1) == '/') end--;
        return path.substring(start, end);
    }

    /**
     * A name for the insert that no existing file can already hold.
     *
     * <p>The extension is kept: MediaStore checks it against the MIME type and will append one
     * of its own if it disagrees, which would be another rename of the kind this exists to
     * avoid.
     */
    private static String temporaryName(String displayName) {
        int dot = displayName.lastIndexOf('.');
        String extension = dot > 0 && dot < displayName.length() - 1 ? displayName.substring(dot) : "";
        return TEMPORARY_NAME_PREFIX + Long.toHexString(System.nanoTime())
                + "-" + Integer.toHexString(TEMPORARY_NAMES.incrementAndGet()) + extension;
    }

    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP);
    }

    /**
     * Reads a token back, in either shape.
     *
     * <p>A token written before the folder was recorded has three fields and a null folder,
     * which recovery reads as "every folder". One written since has four.
     */
    private static PendingIntent decodeIntent(String value) {
        try {
            String encoded = value.substring(PENDING_INTENT_PREFIX.length());
            int firstSeparator = encoded.indexOf(':');
            int secondSeparator = firstSeparator < 0 ? -1 : encoded.indexOf(':', firstSeparator + 1);
            if (firstSeparator <= 0 || secondSeparator == firstSeparator + 1
                    || secondSeparator == encoded.length() - 1) return null;
            int thirdSeparator = encoded.indexOf(':', secondSeparator + 1);
            int nameEnd = thirdSeparator < 0 ? encoded.length() : thirdSeparator;
            if (nameEnd == secondSeparator + 1) return null;
            String collection = new String(Base64.decode(
                    encoded.substring(firstSeparator + 1, secondSeparator), Base64.URL_SAFE),
                    StandardCharsets.UTF_8);
            String displayName = new String(Base64.decode(
                    encoded.substring(secondSeparator + 1, nameEnd), Base64.URL_SAFE),
                    StandardCharsets.UTF_8);
            String relativePath = null;
            if (thirdSeparator >= 0) {
                String decoded = new String(Base64.decode(
                        encoded.substring(thirdSeparator + 1), Base64.URL_SAFE),
                        StandardCharsets.UTF_8);
                relativePath = decoded.isEmpty() ? null : decoded;
            }
            return new PendingIntent(Uri.parse(collection), displayName, relativePath);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static final class PendingIntent {
        final Uri collection;
        final String displayName;
        /** Null for a token from before the folder was recorded, and for a save with no folder. */
        final String relativePath;

        PendingIntent(Uri collection, String displayName, String relativePath) {
            this.collection = collection;
            this.displayName = displayName;
            this.relativePath = relativePath;
        }
    }
}

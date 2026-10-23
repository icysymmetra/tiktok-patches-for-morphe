/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.AtomicFile;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * What each accepted save was doing, kept on disk so the start after a closed TikTok can say what
 * it left unfinished.
 *
 * <p>A record goes in as a save is accepted, gains a slot as each of its files reaches the gallery
 * and is closed when the job is over, whichever way it ended: the work reports its own result, so
 * a closed record has nothing left to say. Only a record still open at the next start, written by
 * a process that is gone, is read back. A slot confirmed published is done, whatever happened to
 * the file since. A slot that had its row or file but no confirmation is looked up, which is how a
 * publish that landed a moment before the process died still counts as done. A file the record
 * never reached did not finish.
 *
 * <p>It holds what that takes and nothing more: the kind of save, a random id, how many files it
 * expected, two timestamps, and for a file still in flight its MediaStore row, or on Android 9 and
 * older its path and expected size. A confirmed file keeps none of those. There is no address and
 * no token in it. The Android 9 path is the name of the reader's own file, which already sits in
 * shared storage under it, and a filename template can put the creator and the video id there;
 * it is kept only while that file is written and goes the moment it is confirmed. The records
 * file is private to TikTok and nothing exports, backs up or logs it: the log lines name kinds
 * and counts only, since they go into exported reports.
 *
 * <p>Bounded twice: records from earlier processes go after {@link #MAX_AGE_MS}, and past
 * {@link #MAX_RECORDS} the oldest of them go first. This process's own records are never dropped.
 */
final class SaveRecords {
    static final String FILE_NAME = "hushfeed-save-records.json";
    static final int MAX_RECORDS = 24;
    static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    /** Slots one record keeps. A photo post holds at most 35; past this a record reads as unsure. */
    static final int MAX_SLOTS = 64;
    /** The most files one record may claim, so a damaged file can't ask for a notice of millions. */
    private static final int MAX_FILES = 1000;
    private static final int VERSION = 1;

    /** The labels savers give the queue. Anything else is kept as {@link #OTHER}, never as given. */
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList(
            "video", "original photos", "story", "sound", "original-sound", "profile picture",
            "sticker", "comment live photo", "photo video", "video frame", BatchSave.LABEL));
    static final String OTHER = "other";

    private static final Object LOCK = new Object();
    /**
     * This process, as its records name it. Random rather than the pid, which Android reuses.
     * Not final only so a test can play the next start.
     */
    private static volatile String process = UUID.randomUUID().toString();
    /** This process's open records, oldest first. Guarded by {@link #LOCK}. */
    private static final LinkedHashMap<String, Record> OPEN = new LinkedHashMap<>();
    /** Records earlier processes left open, read once per process. Null until read; LOCK. */
    private static List<Record> earlier;
    /** Set while a write is queued, so a burst of accepts and closes costs one write. LOCK. */
    private static boolean writeQueued;
    /** Held across the file write and its fsync, and never while {@link #LOCK} is. */
    private static final Object WRITE_LOCK = new Object();
    /** The number of the last snapshot taken. LOCK. */
    private static long snapshots;
    /** The number of the snapshot on disk. WRITE_LOCK. */
    private static long written;
    /** The record of the job this thread is running, for the publish that happens inside it. */
    private static final ThreadLocal<Record> CURRENT = new ThreadLocal<>();
    private static final Random IDS = new Random();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Metra-SaveRecords");
        thread.setDaemon(true);
        return thread;
    });

    private SaveRecords() {
    }

    /** One accepted save. Its fields change under {@link #LOCK} only. */
    static final class Record {
        final String id;
        final String process;
        final String kind;
        final int files;
        final long created;
        long updated;
        /** A write for this record failed, so what is on disk may be behind what happened. */
        boolean unsure;
        final List<Slot> slots = new ArrayList<>();

        Record(String id, String process, String kind, int files, long created, long updated) {
            this.id = id;
            this.process = process;
            this.kind = kind;
            this.files = files;
            this.created = created;
            this.updated = updated;
        }
    }

    /** One file of a save, from the moment it has a row or a path until it is confirmed. */
    static final class Slot {
        final Record record;
        /** The MediaStore row, Android 10 and newer. Null once published. */
        String uri;
        /** The file itself, Android 9 and older: its own name, creator and video id included. Null once published. */
        String file;
        /** What a complete file measures, or -1 when that isn't known in advance. */
        long size;
        boolean published;

        Slot(Record record, String uri, String file, long size) {
            this.record = record;
            this.uri = uri;
            this.file = file;
            this.size = size;
        }
    }

    /** What reconciliation found for one record an earlier process left open. */
    static final class Unfinished {
        final String id;
        final String kind;
        final int files;
        final int done;
        final int unfinished;
        final int uncertain;

        Unfinished(String id, String kind, int files, int done, int unfinished, int uncertain) {
            this.id = id;
            this.kind = kind;
            this.files = files;
            this.done = done;
            this.unfinished = unfinished;
            this.uncertain = uncertain;
        }

        boolean finished() {
            return unfinished == 0 && uncertain == 0;
        }
    }

    /** The saves to name, in the order they were accepted, and the records the notice consumes. */
    static final class Report {
        final List<Unfinished> saves;

        Report(List<Unfinished> saves) {
            this.saves = saves;
        }

        List<String> ids() {
            List<String> ids = new ArrayList<>();
            for (Unfinished save : saves) ids.add(save.id);
            return ids;
        }
    }

    // ---------------------------------------------------------------------------------------
    // This process: accept, publish, close.
    // ---------------------------------------------------------------------------------------

    /**
     * A record for a save the queue is about to accept, held in memory until {@link #accepted}.
     * Null when nothing records it: no context yet, or a process other than TikTok's main one,
     * which never saves and must not write the file the main one owns.
     */
    static Record open(String label, int files) {
        try {
            Context context = Utils.getContext();
            if (context == null || !Utils.isMainProcess()) return null;
            String kind = label != null && KINDS.contains(label) ? label : OTHER;
            long now = System.currentTimeMillis();
            Record record = new Record(newId(), process, kind,
                    Math.max(1, Math.min(MAX_FILES, files)), now, now);
            synchronized (LOCK) {
                OPEN.put(record.id, record);
            }
            return record;
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not open a save record (" + failure.getClass().getSimpleName() + ")");
            return null;
        }
    }

    /** The queue took the save: it goes on disk, off the calling thread. */
    static void accepted(Record record) {
        if (record == null) return;
        persistLater(null);
    }

    /**
     * The job is over, however it ended: saved, failed, cancelled while it waited or refused.
     * Safe either way, since the record says nothing about how it went.
     */
    static void close(Record record) {
        if (record == null) return;
        boolean removed;
        synchronized (LOCK) {
            removed = OPEN.remove(record.id) != null;
        }
        if (removed) persistLater(null);
    }

    /** Makes {@code record} the one a publish on this thread belongs to. Returns what it replaced. */
    static Record enter(Record record) {
        Record outer = CURRENT.get();
        CURRENT.set(record);
        return outer;
    }

    static void exit(Record outer) {
        if (outer == null) CURRENT.remove();
        else CURRENT.set(outer);
    }

    /**
     * A MediaStore row now exists for a file of the running save, still pending. Written before
     * a byte is copied into it, so a publish can't finish without its row being on disk first.
     */
    static Slot located(Uri uri) {
        return uri == null ? null : addSlot(uri.toString(), null, -1L);
    }

    /** A file claimed on Android 9 and older, empty until the copy fills it to {@code size}. */
    static Slot located(File file, long size) {
        return file == null ? null : addSlot(null, file.getAbsolutePath(), size > 0 ? size : -1L);
    }

    private static Slot addSlot(String uri, String file, long size) {
        Record record = CURRENT.get();
        if (record == null) return null;
        Slot slot;
        synchronized (LOCK) {
            // A record already closed, or one from before a test's fresh start, takes nothing.
            if (OPEN.get(record.id) != record) return null;
            record.updated = System.currentTimeMillis();
            if (record.slots.size() >= MAX_SLOTS) {
                record.unsure = true;
                slot = null;
            } else {
                slot = new Slot(record, uri, file, size);
                record.slots.add(slot);
            }
        }
        persistNow(null);
        return slot;
    }

    /** The file is in the gallery. Its row and path are forgotten: done needs neither. */
    static void published(Slot slot) {
        if (slot == null) return;
        synchronized (LOCK) {
            slot.published = true;
            slot.uri = null;
            slot.file = null;
            slot.size = -1L;
            slot.record.updated = System.currentTimeMillis();
            if (OPEN.get(slot.record.id) != slot.record) return;
        }
        persistNow(null);
    }

    /**
     * The publish failed and cleaned up after itself; the save says so in its own words. The
     * slot goes, so a later file of the same save, or a second mirror, isn't counted twice.
     */
    static void abandoned(Slot slot) {
        if (slot == null) return;
        synchronized (LOCK) {
            slot.record.slots.remove(slot);
            slot.record.updated = System.currentTimeMillis();
            if (OPEN.get(slot.record.id) != slot.record) return;
        }
        persistNow(null);
    }

    /**
     * One of the running save's files needed no writing: a post of a run that was saved here
     * before. It takes a slot that is done at once, so a run that closes with TikTok doesn't name
     * a file that was in the gallery all along as one that didn't finish.
     */
    static void countedWithoutFile() {
        Record record = CURRENT.get();
        if (record == null) return;
        synchronized (LOCK) {
            if (OPEN.get(record.id) != record) return;
            record.updated = System.currentTimeMillis();
            if (record.slots.size() >= MAX_SLOTS) {
                record.unsure = true;
            } else {
                Slot slot = new Slot(record, null, null, -1L);
                slot.published = true;
                record.slots.add(slot);
            }
        }
        persistNow(null);
    }

    // ---------------------------------------------------------------------------------------
    // The next start: reconcile, then consume what the notice named.
    // ---------------------------------------------------------------------------------------

    /**
     * Reads back what earlier processes left open and works out, for each, what finished. A
     * record where everything finished is consumed at once, without a word. The rest come back
     * to be named, and stay on disk until {@link #consume} is told they were. This process's own
     * records are never read here: they are not in the list this works from.
     */
    static Report reconcile(Context context) {
        if (context == null) return new Report(Collections.<Unfinished>emptyList());
        List<Record> left;
        synchronized (LOCK) {
            left = new ArrayList<>(earlier(context));
        }
        List<Unfinished> saves = new ArrayList<>();
        List<String> quiet = new ArrayList<>();
        for (Record record : left) {
            Unfinished result = evaluate(context, record);
            if (result.finished()) quiet.add(record.id);
            else saves.add(result);
        }
        if (!quiet.isEmpty()) consume(context, quiet);
        int open = left.size();
        int named = saves.size();
        if (open > 0) {
            Logger.printInfo(() -> "Save records from an earlier start: " + open + " left open, "
                    + named + " with files that didn't finish or can't be confirmed");
        }
        return new Report(saves);
    }

    /** Takes the named records off disk. Only earlier processes' records can be named here. */
    static void consume(Context context, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        boolean changed = false;
        synchronized (LOCK) {
            Iterator<Record> iterator = earlier(context).iterator();
            while (iterator.hasNext()) {
                if (ids.contains(iterator.next().id)) {
                    iterator.remove();
                    changed = true;
                }
            }
        }
        if (changed) persistLater(context);
    }

    private static Unfinished evaluate(Context context, Record record) {
        List<Slot> slots;
        boolean unsure;
        synchronized (LOCK) {
            slots = new ArrayList<>(record.slots);
            unsure = record.unsure;
        }
        int files = Math.max(record.files, slots.size());
        int done = 0;
        int unfinished = 0;
        int uncertain = 0;
        for (Slot slot : slots) {
            switch (outcome(context, slot)) {
                case DONE:
                    done++;
                    break;
                case MISSING:
                    unfinished++;
                    break;
                default:
                    uncertain++;
                    break;
            }
        }
        // Files the record never reached. When one of its writes failed, one of them may have
        // landed all the same, and calling it unfinished could name a file that is in the gallery.
        int unrecorded = Math.max(0, files - slots.size());
        if (unsure) uncertain += unrecorded;
        else unfinished += unrecorded;
        return new Unfinished(record.id, record.kind, files, done, unfinished, uncertain);
    }

    enum Outcome { DONE, MISSING, UNCERTAIN }

    static Outcome outcome(Context context, Slot slot) {
        if (slot.published) return Outcome.DONE;
        try {
            if (slot.uri != null) return rowOutcome(context, Uri.parse(slot.uri));
            if (slot.file != null) return fileOutcome(context, new File(slot.file), slot.size);
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not look up a save's file (" + failure.getClass().getSimpleName() + ")");
        }
        return Outcome.UNCERTAIN;
    }

    /**
     * A row published is done: the process died between MediaStore taking the file and the record
     * hearing of it. A row still pending, or gone, did not finish; MediaCache takes a pending row
     * away once it is a day old, so it is left for that.
     */
    private static Outcome rowOutcome(Context context, Uri uri) {
        if (Build.VERSION.SDK_INT < 29) return Outcome.UNCERTAIN;
        try (Cursor cursor = MediaCache.queryIncludingPending(context.getContentResolver(), uri,
                new String[]{MediaStore.MediaColumns.IS_PENDING}, null, null)) {
            if (cursor == null) return Outcome.UNCERTAIN;
            if (!cursor.moveToFirst()) return Outcome.MISSING;
            int column = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING);
            if (column < 0) return Outcome.UNCERTAIN;
            return cursor.getInt(column) == 0 ? Outcome.DONE : Outcome.MISSING;
        }
    }

    /**
     * Android 9 and older write the file itself. At its full size it is done, and it is handed
     * to the media scanner again, since the process may have died before the first scan. Short,
     * or gone, it did not finish. With no size to compare, a file that has anything in it can't
     * be told from a complete one.
     */
    private static Outcome fileOutcome(Context context, File file, long size) {
        if (!file.isFile()) return Outcome.MISSING;
        long length = file.length();
        if (size > 0) {
            if (length != size) return Outcome.MISSING;
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
            return Outcome.DONE;
        }
        return length > 0 ? Outcome.UNCERTAIN : Outcome.MISSING;
    }

    // ---------------------------------------------------------------------------------------
    // The file.
    // ---------------------------------------------------------------------------------------

    private static void persistLater(Context context) {
        synchronized (LOCK) {
            if (writeQueued) return;
            writeQueued = true;
        }
        try {
            WRITER.execute(() -> {
                // Cleared before the write, so a change made while it runs queues another.
                synchronized (LOCK) {
                    writeQueued = false;
                }
                persistNow(context);
            });
        } catch (RuntimeException refused) {
            synchronized (LOCK) {
                writeQueued = false;
            }
            persistNow(context);
        }
    }

    /**
     * Writes every record this process knows of: the earlier ones not yet consumed, then its own.
     * A failure marks this process's records unsure, which the next write that succeeds carries.
     *
     * <p>Two steps under two locks. The snapshot is taken under {@link #LOCK}, which a Save or
     * Cancel tap takes on the main thread, and it's quick. The write and its fsync happen under
     * {@link #WRITE_LOCK} only, so a slow disk holds up the next write and never a tap. Each
     * snapshot is numbered as it's taken, and one older than what is already on disk is dropped:
     * two writers can reach the disk in either order, and the older state must not land last.
     */
    private static void persistNow(Context given) {
        Context context = given != null ? given : Utils.getContext();
        if (context == null) return;
        byte[] bytes;
        long snapshot;
        synchronized (LOCK) {
            try {
                List<Record> previous = earlier(context);
                prune(previous, System.currentTimeMillis(), OPEN.size());
                JSONArray records = new JSONArray();
                for (Record record : previous) records.put(json(record));
                for (Record record : OPEN.values()) records.put(json(record));
                if (records.length() == 0) {
                    bytes = null;
                } else {
                    JSONObject root = new JSONObject();
                    root.put("v", VERSION);
                    root.put("records", records);
                    bytes = root.toString().getBytes(StandardCharsets.UTF_8);
                }
            } catch (JSONException | RuntimeException failure) {
                markUnsure(failure);
                return;
            }
            snapshot = ++snapshots;
        }
        try {
            AtomicFile file = file(context);
            synchronized (WRITE_LOCK) {
                if (snapshot <= written) return;
                if (bytes == null) {
                    if (exists(file)) file.delete();
                } else {
                    write(file, bytes);
                }
                written = snapshot;
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (LOCK) {
                markUnsure(failure);
            }
        }
    }

    /** LOCK held. */
    private static void markUnsure(Exception failure) {
        for (Record record : OPEN.values()) record.unsure = true;
        // The class alone: a parse or write message can carry the file's own text.
        Logger.printInfo(() -> "Could not write the save records (" + failure.getClass().getSimpleName() + ")");
    }

    /** Drops earlier records past the age cap, then the oldest past the count cap. LOCK held. */
    private static void prune(List<Record> previous, long now, int current) {
        Iterator<Record> iterator = previous.iterator();
        while (iterator.hasNext()) {
            Record record = iterator.next();
            if (record.updated < now - MAX_AGE_MS || record.updated > now + MAX_AGE_MS) iterator.remove();
        }
        while (!previous.isEmpty() && previous.size() + current > MAX_RECORDS) {
            Record oldest = previous.get(0);
            for (Record record : previous) if (record.updated < oldest.updated) oldest = record;
            previous.remove(oldest);
        }
    }

    /** The earlier processes' records, read from disk the first time. LOCK held. */
    private static List<Record> earlier(Context context) {
        if (earlier != null) return earlier;
        List<Record> read = new ArrayList<>();
        try {
            AtomicFile file = file(context);
            if (exists(file)) {
                JSONArray records = new JSONObject(new String(readAll(file), StandardCharsets.UTF_8))
                        .optJSONArray("records");
                for (int index = 0; records != null && index < records.length(); index++) {
                    Record record = record(records.optJSONObject(index));
                    // This process can't have written anything before this first read.
                    if (record != null && !process.equals(record.process)) read.add(record);
                }
            }
        } catch (IOException | JSONException | RuntimeException failure) {
            // Unreadable is dropped rather than kept: the next write replaces it, and a record
            // that can't be read can't be named anyway.
            Logger.printInfo(() -> "Could not read the save records (" + failure.getClass().getSimpleName() + ")");
        }
        prune(read, System.currentTimeMillis(), 0);
        earlier = read;
        return earlier;
    }

    private static JSONObject json(Record record) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", record.id);
        json.put("p", record.process);
        json.put("k", record.kind);
        json.put("n", record.files);
        json.put("t0", record.created);
        json.put("t1", record.updated);
        if (record.unsure) json.put("u", true);
        JSONArray slots = new JSONArray();
        for (Slot slot : record.slots) {
            JSONObject entry = new JSONObject();
            if (slot.published) {
                entry.put("d", 1);
            } else if (slot.uri != null) {
                entry.put("u", slot.uri);
            } else if (slot.file != null) {
                entry.put("f", slot.file);
                if (slot.size > 0) entry.put("z", slot.size);
            }
            slots.put(entry);
        }
        json.put("s", slots);
        return json;
    }

    private static Record record(JSONObject json) {
        if (json == null) return null;
        String id = json.optString("id", "");
        String owner = json.optString("p", "");
        String kind = json.optString("k", OTHER);
        int files = json.optInt("n", 0);
        long created = json.optLong("t0", 0L);
        long updated = json.optLong("t1", 0L);
        if (id.isEmpty() || owner.isEmpty() || files < 1 || files > MAX_FILES || updated <= 0) return null;
        Record record = new Record(id, owner, KINDS.contains(kind) ? kind : OTHER, files, created, updated);
        record.unsure = json.optBoolean("u", false);
        JSONArray slots = json.optJSONArray("s");
        for (int index = 0; slots != null && index < slots.length() && index < MAX_SLOTS; index++) {
            JSONObject entry = slots.optJSONObject(index);
            if (entry == null) continue;
            Slot slot = new Slot(record, emptyToNull(entry.optString("u", "")),
                    emptyToNull(entry.optString("f", "")), entry.optLong("z", -1L));
            slot.published = entry.optInt("d", 0) == 1;
            record.slots.add(slot);
        }
        return record;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    static AtomicFile file(Context context) throws IOException {
        File directory = context.getFilesDir();
        if (directory == null) throw new IOException("Application files are unavailable");
        return new AtomicFile(new File(directory, FILE_NAME));
    }

    private static boolean exists(AtomicFile file) {
        File base = file.getBaseFile();
        return base.isFile() || new File(base.getPath() + ".bak").isFile();
    }

    private static byte[] readAll(AtomicFile file) throws IOException {
        try (InputStream input = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    private static void write(AtomicFile file, byte[] bytes) throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | RuntimeException failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        }
    }

    private static String newId() {
        synchronized (IDS) {
            return Long.toHexString(IDS.nextLong());
        }
    }

    // ---------------------------------------------------------------------------------------
    // For the tests.
    // ---------------------------------------------------------------------------------------

    /** Waits for every queued write. */
    static void flushForTests() throws Exception {
        WRITER.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    /**
     * The next start, as a new process meets it: nothing of this one's memory, only the file.
     * Call {@link #flushForTests} first and let this process's jobs end, as a dead one's would.
     */
    static void freshStartForTests() {
        synchronized (LOCK) {
            OPEN.clear();
            earlier = null;
            writeQueued = false;
            process = UUID.randomUUID().toString();
        }
        UnfinishedSaves.resetForTests();
    }

    /** This process's open records, oldest first, for asserting on what a job left. */
    static List<Record> openForTests() {
        synchronized (LOCK) {
            return new ArrayList<>(OPEN.values());
        }
    }
}

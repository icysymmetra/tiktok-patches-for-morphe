/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.atDeath;
import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.await;
import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.drain;
import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.records;
import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.startAgain;
import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.MediaStore;

import app.morphe.extension.shared.Utils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A save cut off by TikTok closing, on Android 10 and newer, where every file is a MediaStore
 * row. Each case plays a death at one moment around publication, hands the next start what was
 * on disk then, and checks what that start finds: nothing to say when every file landed, and
 * otherwise exactly the files that didn't, never one that did.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29, qualifiers = "en")
public class SaveRecordsTest {
    private static final String FOLDER = "Pictures/Hushfeed";
    private static final Uri COLLECTION = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

    private Context context;
    private final List<CountDownLatch> holds = new ArrayList<>();

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        RecordingMediaProvider.reset();
        Robolectric.setupContentProvider(RecordingMediaProvider.class, "media");
        SaveRecordsFixtures.reset(context);
    }

    @After public void tearDown() throws Exception {
        for (CountDownLatch hold : holds) hold.countDown();
        RecordingMediaProvider.reset();
        SaveRecordsFixtures.reset(context);
    }

    private CountDownLatch hold() {
        CountDownLatch hold = new CountDownLatch(1);
        holds.add(hold);
        return hold;
    }

    /** Publishes one small picture through the real writer, as a saver would. */
    private MediaFileWriter.Saved publish(String name) {
        try {
            File source = new File(context.getCacheDir(), "source-" + name);
            Files.write(source.toPath(), ("picture " + name).getBytes(StandardCharsets.UTF_8));
            return MediaFileWriter.publishForResult(context, source, name, "image/jpeg", FOLDER, false);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Runs {@code work} as the save it would be, and stops it at the moment the latch opens. */
    private CountDownLatch submitAndStop(String kind, int files, Runnable work, CountDownLatch hold) {
        CountDownLatch reached = new CountDownLatch(1);
        assertNotNull(MediaJobScheduler.submit(kind, null, files, () -> {
            work.run();
            reached.countDown();
            await(hold);
        }, null));
        return reached;
    }

    private static Uri row(long id) {
        return ContentUris.withAppendedId(COLLECTION, id);
    }

    // -----------------------------------------------------------------------------------------
    // Death around publication.
    // -----------------------------------------------------------------------------------------

    /** Before any file: saves running and a save still waiting in line are all named, all missing. */
    @Test public void savesThatNeverPublishedAnythingDidNotFinish() throws Exception {
        CountDownLatch hold = hold();
        CountDownLatch started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        for (int index = 0; index < MediaJobScheduler.MAX_RUNNING_JOBS; index++) {
            assertNotNull(MediaJobScheduler.submit("video", null, 1, () -> {
                started.countDown();
                await(hold);
            }, null));
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
        MediaJobScheduler.Job waiting = MediaJobScheduler.submit("original photos", null, 5, () -> { }, null);
        assertTrue("the fourth save should be waiting", waiting.waiting());

        byte[] died = atDeath(context);
        hold.countDown();
        startAgain(context, died);
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertEquals(4, report.saves.size());
        for (int index = 0; index < 3; index++) {
            assertSave(report.saves.get(index), "video", 1, 0, 1, 0);
        }
        assertSave(report.saves.get(3), "original photos", 5, 0, 5, 0);
    }

    /** Between two files: the two that landed stay done and in the gallery; the rest are named. */
    @Test public void deathBetweenFilesNamesOnlyTheFilesThatDidNotLand() throws Exception {
        CountDownLatch hold = hold();
        CountDownLatch reached = submitAndStop("original photos", 4, () -> {
            publish("photo_1.jpg");
            publish("photo_2.jpg");
        }, hold);
        assertTrue(reached.await(5, TimeUnit.SECONDS));

        byte[] died = atDeath(context);
        hold.countDown();
        startAgain(context, died);
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertEquals(1, report.saves.size());
        assertSave(report.saves.get(0), "original photos", 4, 2, 2, 0);
        assertEquals("a landed photo was touched", List.of(100L, 101L), RecordingMediaProvider.ids());
        assertEquals(0, RecordingMediaProvider.pendingOf(100L));
        assertEquals(0, RecordingMediaProvider.pendingOf(101L));
    }

    /** After the last file, before the record closed: nothing is missing, so nothing is said. */
    @Test public void deathAfterTheLastPublishBeforeTheRecordClosedSaysNothing() throws Exception {
        CountDownLatch hold = hold();
        CountDownLatch reached = submitAndStop("original photos", 2, () -> {
            publish("photo_1.jpg");
            publish("photo_2.jpg");
        }, hold);
        assertTrue(reached.await(5, TimeUnit.SECONDS));

        byte[] died = atDeath(context);
        assertEquals("the record was closed before the death", 1, new JSONObject(text(died))
                .getJSONArray("records").length());
        hold.countDown();
        startAgain(context, died);
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertTrue("a finished save was named", report.saves.isEmpty());
        assertEquals("a finished save's record was not consumed", 0, records(context).length());
    }

    /**
     * The race: MediaStore has published the row, and the process dies before the record hears
     * of it. The record says only that the row existed, and the row says it landed.
     */
    @Test public void aRowPublishedJustBeforeDeathIsDone() throws Exception {
        byte[][] died = {null};
        RecordingMediaProvider.onPublish = () -> {
            try {
                died[0] = atDeath(context);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
        CountDownLatch finished = new CountDownLatch(1);
        assertNotNull(MediaJobScheduler.submit("video", null, 1, () -> publish("clip.jpg"), finished::countDown));
        await(finished);
        assertNotNull("the provider never saw a publish", died[0]);

        // The copy the dead process left really held the row unconfirmed, so this is the race.
        JSONObject slot = new JSONObject(text(died[0])).getJSONArray("records").getJSONObject(0)
                .getJSONArray("s").getJSONObject(0);
        assertEquals(row(100L).toString(), slot.getString("u"));
        assertFalse(slot.has("d"));

        startAgain(context, died[0]);
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertTrue("a file in the gallery was named as unfinished", report.saves.isEmpty());
    }

    /**
     * Rows the record reached but never confirmed: one still pending (the copy was under way),
     * one gone, one published. Only the published one is done. The pending row is left where it
     * is: taking it away is MediaCache's, once it is a day old.
     */
    @Test public void aRowStillPendingOrGoneDidNotFinish() throws Exception {
        RecordingMediaProvider.add(7L, "pending.jpg", 1, FOLDER);
        RecordingMediaProvider.add(9L, "landed.jpg", 0, FOLDER);
        SaveRecords.Record record = SaveRecords.open("original photos", 3);
        SaveRecords.accepted(record);
        SaveRecords.Record outer = SaveRecords.enter(record);
        try {
            assertNotNull(SaveRecords.located(row(7L)));
            assertNotNull(SaveRecords.located(row(8L)));
            assertNotNull(SaveRecords.located(row(9L)));
        } finally {
            SaveRecords.exit(outer);
        }

        startAgain(context, atDeath(context));
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertEquals(1, report.saves.size());
        assertSave(report.saves.get(0), "original photos", 3, 1, 2, 0);
        assertEquals(List.of(7L, 9L), RecordingMediaProvider.ids());
        assertEquals("the pending row was touched", 1, RecordingMediaProvider.pendingOf(7L));
    }

    /** A file confirmed and deleted since is still a file that was saved, not one that failed. */
    @Test public void aConfirmedFileDeletedSinceIsStillDone() throws Exception {
        CountDownLatch hold = hold();
        CountDownLatch reached = submitAndStop("video", 1, () -> publish("clip.jpg"), hold);
        assertTrue(reached.await(5, TimeUnit.SECONDS));
        byte[] died = atDeath(context);
        hold.countDown();
        startAgain(context, died);
        context.getContentResolver().delete(row(100L), null, null);

        assertTrue(SaveRecords.reconcile(context).saves.isEmpty());
    }

    /** A row MediaStore won't answer for can't be called done or missing. */
    @Test public void aRowThatCannotBeReadIsUncertain() throws Exception {
        RecordingMediaProvider.add(7L, "clip.jpg", 0, FOLDER);
        RecordingMediaProvider.unreadable = 7L;
        SaveRecords.Record record = SaveRecords.open("story", 1);
        SaveRecords.accepted(record);
        SaveRecords.Record outer = SaveRecords.enter(record);
        try {
            SaveRecords.located(row(7L));
        } finally {
            SaveRecords.exit(outer);
        }

        startAgain(context, atDeath(context));
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertEquals(1, report.saves.size());
        assertSave(report.saves.get(0), "story", 1, 0, 0, 1);
    }

    /**
     * A record whose own write failed may be behind: a file it never reached could have landed
     * all the same. Those are named as uncertain rather than as not finished.
     */
    @Test public void aRecordThatMissedAWriteNamesItsUnreachedFilesAsUncertain() throws Exception {
        long now = System.currentTimeMillis();
        String file = "{\"v\":1,\"records\":[{\"id\":\"1\",\"p\":\"gone\",\"k\":\"original photos\",\"n\":3,"
                + "\"t0\":" + now + ",\"t1\":" + now + ",\"u\":true,\"s\":[{\"d\":1}]}]}";
        startAgain(context, file.getBytes(StandardCharsets.UTF_8));

        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertEquals(1, report.saves.size());
        assertSave(report.saves.get(0), "original photos", 3, 1, 0, 2);
    }

    // -----------------------------------------------------------------------------------------
    // What the next start must leave alone.
    // -----------------------------------------------------------------------------------------

    /**
     * A save this process is running is never read as an earlier one's, and consuming what the
     * notice named leaves it on disk. Once this process dies with it open, the start after names it.
     */
    @Test public void thisProcessesSavesAreNeverTouched() throws Exception {
        SaveRecords.accepted(SaveRecords.open("video", 1));
        startAgain(context, atDeath(context));

        CountDownLatch hold = hold();
        CountDownLatch started = new CountDownLatch(1);
        assertNotNull(MediaJobScheduler.submit("sound", null, 1, () -> {
            started.countDown();
            await(hold);
        }, null));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        String current = SaveRecords.openForTests().get(0).id;

        SaveRecords.Report report = SaveRecords.reconcile(context);
        assertEquals(1, report.saves.size());
        assertEquals("video", report.saves.get(0).kind);
        SaveRecords.consume(context, report.ids());
        SaveRecords.consume(context, List.of(current));

        JSONArray left = records(context);
        assertEquals("consuming took this process's own save", 1, left.length());
        assertEquals(current, left.getJSONObject(0).getString("id"));
        assertEquals("sound", left.getJSONObject(0).getString("k"));

        byte[] died = atDeath(context);
        hold.countDown();
        startAgain(context, died);
        SaveRecords.Report next = SaveRecords.reconcile(context);
        assertEquals(1, next.saves.size());
        assertSave(next.saves.get(0), "sound", 1, 0, 1, 0);
    }

    /** Old records go after a week, and past the cap the oldest go first; this process's stay. */
    @Test public void theStoreIsBoundedByAgeAndCount() throws Exception {
        long now = System.currentTimeMillis();
        StringBuilder file = new StringBuilder("{\"v\":1,\"records\":[");
        for (int index = 0; index < 30; index++) {
            // Five past the age cap first, then twenty-five in order, oldest first.
            long updated = index < 5 ? now - SaveRecords.MAX_AGE_MS - 60_000L : now - (30 - index) * 1000L;
            if (index > 0) file.append(',');
            file.append("{\"id\":\"r").append(index).append("\",\"p\":\"gone\",\"k\":\"video\",\"n\":1,")
                    .append("\"t0\":").append(updated).append(",\"t1\":").append(updated).append(",\"s\":[]}");
        }
        file.append("]}");
        startAgain(context, file.toString().getBytes(StandardCharsets.UTF_8));

        SaveRecords.Report report = SaveRecords.reconcile(context);
        assertEquals(SaveRecords.MAX_RECORDS, report.saves.size());
        assertEquals("the oldest recent record should have gone first", "r6", report.saves.get(0).id);
        for (SaveRecords.Unfinished save : report.saves) {
            assertFalse("a record past the age cap was kept: " + save.id,
                    save.id.equals("r0") || save.id.equals("r4"));
        }

        CountDownLatch hold = hold();
        CountDownLatch started = new CountDownLatch(1);
        assertNotNull(MediaJobScheduler.submit("sticker", null, 1, () -> {
            started.countDown();
            await(hold);
        }, null));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        JSONArray kept = records(context);
        assertEquals(SaveRecords.MAX_RECORDS, kept.length());
        assertEquals("this process's save was dropped", "sticker",
                kept.getJSONObject(kept.length() - 1).getString("k"));
    }

    // -----------------------------------------------------------------------------------------
    // The write itself.
    // -----------------------------------------------------------------------------------------

    /**
     * A write stuck on a slow disk holds up no Save or Cancel tap, which open and close records on
     * the main thread: the file is written outside the lock they take. And a snapshot taken before
     * another can't land after it, so the stuck write, once let go, doesn't put back a state the
     * newer one already replaced.
     */
    @Test public void aSlowWriteHoldsUpNoTapAndAnOlderSnapshotNeverLandsLast() throws Exception {
        SaveRecords.accepted(SaveRecords.open("video", 1));
        startAgain(context, atDeath(context));
        SaveRecords.Report report = SaveRecords.reconcile(context);
        assertEquals(1, report.saves.size());

        CountDownLatch reachedDisk = new CountDownLatch(1);
        CountDownLatch disk = hold();
        Context slow = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() {
                reachedDisk.countDown();
                await(disk);
                return super.getFilesDir();
            }
        };
        // The older snapshot: the notice's record consumed, written by a disk that won't answer.
        SaveRecords.consume(slow, report.ids());
        assertTrue("the write never reached the disk", reachedDisk.await(5, TimeUnit.SECONDS));

        SaveRecords.Record[] tapped = {null};
        Thread tap = new Thread(() -> tapped[0] = SaveRecords.open("sound", 1));
        tap.start();
        tap.join(2000);
        assertFalse("a Save tap waited on a write stuck on the disk", tap.isAlive());

        // The newer snapshot: a file of a running save found its row, written straight through.
        SaveRecords.Record story = SaveRecords.open("story", 1);
        Thread publisher = new Thread(() -> {
            SaveRecords.Record outer = SaveRecords.enter(story);
            try {
                SaveRecords.located(row(42L));
            } finally {
                SaveRecords.exit(outer);
            }
        });
        publisher.start();
        publisher.join(5000);
        assertFalse("a publish waited on a write stuck on the disk", publisher.isAlive());

        disk.countDown();
        JSONArray kept = records(context);
        assertEquals("the older snapshot landed over the newer one: " + kept, 2, kept.length());
        JSONObject last = kept.getJSONObject(1);
        assertEquals("story", last.getString("k"));
        assertEquals(row(42L).toString(), last.getJSONArray("s").getJSONObject(0).getString("u"));
        SaveRecords.close(tapped[0]);
        SaveRecords.close(story);
    }

    // -----------------------------------------------------------------------------------------
    // What is kept, and what is said in the log.
    // -----------------------------------------------------------------------------------------

    /**
     * The record holds no address, token, video id or name: the queue key is never written, and a
     * confirmed file keeps nothing of where it went. A file in flight keeps only its row.
     */
    @Test public void nothingIdentifyingIsKeptOrLogged() throws Exception {
        String videoId = "7312345678901234567";
        String key = "sticker https://p16-sign.tiktokcdn.com/obj/abc?x-signature=SECRETTOKEN";
        CountDownLatch hold = hold();
        CountDownLatch reached = new CountDownLatch(1);
        assertNotNull(MediaJobScheduler.submit("sticker", key, 2, () -> {
            publish("creatorname_" + videoId + ".jpg");
            reached.countDown();
            await(hold);
        }, null));
        assertTrue(reached.await(5, TimeUnit.SECONDS));

        String kept = text(atDeath(context));
        for (String secret : new String[]{"http", "tiktokcdn", "SECRETTOKEN", videoId, "creatorname", "Pictures", "Metra"}) {
            assertFalse("the records kept " + secret + ": " + kept, kept.contains(secret));
        }

        // And a file still in flight, with its row, read back by the next start.
        SaveRecords.Record record = SaveRecords.open("story", 1);
        SaveRecords.accepted(record);
        SaveRecords.Record outer = SaveRecords.enter(record);
        try {
            SaveRecords.located(row(555L));
        } finally {
            SaveRecords.exit(outer);
        }
        byte[] died = atDeath(context);
        hold.countDown();
        startAgain(context, died);
        ShadowLog.clear();
        SaveRecords.Report report = SaveRecords.reconcile(context);
        UnfinishedSaves.message(context, report);

        assertEquals(2, report.saves.size());
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            String line = item.msg + (item.throwable == null ? "" : " " + item.throwable);
            for (String secret : new String[]{"content:", "images/media", videoId, "creatorname", "Pictures", "http"}) {
                assertFalse("the log carried " + secret + ": " + line, line.contains(secret));
            }
        }
    }

    private static void assertSave(SaveRecords.Unfinished save, String kind, int files, int done,
            int unfinished, int uncertain) {
        assertEquals("kind", kind, save.kind);
        assertEquals(kind + " files", files, save.files);
        assertEquals(kind + " done", done, save.done);
        assertEquals(kind + " unfinished", unfinished, save.unfinished);
        assertEquals(kind + " uncertain", uncertain, save.uncertain);
    }

    /**
     * Enough MediaStore for a publish and a lookup: rows by id, the unique rename MediaStore does,
     * a file to copy into, and a hook at the moment a row stops being pending.
     */
    public static final class RecordingMediaProvider extends ContentProvider {
        private static final List<Object[]> ROWS = new ArrayList<>();
        private static long nextId;
        /** Runs inside the update that publishes a row, after the row says so. */
        static volatile Runnable onPublish;
        /** A row id whose lookup answers with no cursor, or -1. */
        static volatile long unreadable;

        static synchronized void reset() {
            ROWS.clear();
            nextId = 100L;
            onPublish = null;
            unreadable = -1L;
        }

        static synchronized void add(long id, String name, int pending, String path) {
            ROWS.add(new Object[]{id, name, pending, path});
        }

        static synchronized List<Long> ids() {
            List<Long> ids = new ArrayList<>();
            for (Object[] row : ROWS) ids.add((Long) row[0]);
            return ids;
        }

        static synchronized int pendingOf(long id) {
            for (Object[] row : ROWS) if ((Long) row[0] == id) return (Integer) row[2];
            return -1;
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            Long only = null;
            try {
                only = ContentUris.parseId(uri);
            } catch (NumberFormatException | UnsupportedOperationException collection) {
                // A collection: every row.
            }
            if (only != null && only == unreadable) return null;
            String wanted = args == null || args.length == 0 ? null : args[0];
            MatrixCursor cursor = new MatrixCursor(projection);
            synchronized (RecordingMediaProvider.class) {
                for (Object[] row : ROWS) {
                    if (only != null && !only.equals(row[0])) continue;
                    if (wanted != null && !wanted.equals(row[1])) continue;
                    Object[] values = new Object[projection.length];
                    for (int column = 0; column < projection.length; column++) {
                        switch (projection[column]) {
                            case MediaStore.MediaColumns._ID: values[column] = row[0]; break;
                            case MediaStore.MediaColumns.DISPLAY_NAME: values[column] = row[1]; break;
                            case MediaStore.MediaColumns.IS_PENDING: values[column] = row[2]; break;
                            case MediaStore.MediaColumns.RELATIVE_PATH: values[column] = row[3]; break;
                            default: values[column] = null; break;
                        }
                    }
                    cursor.addRow(values);
                }
            }
            return cursor;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            String name = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
            long id;
            synchronized (RecordingMediaProvider.class) {
                id = nextId++;
                ROWS.add(new Object[]{id, unique(name, -1L), 1,
                        values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)});
            }
            return ContentUris.withAppendedId(uri, id);
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            long id = ContentUris.parseId(uri);
            boolean published = false;
            synchronized (RecordingMediaProvider.class) {
                Object[] found = null;
                for (Object[] row : ROWS) if ((Long) row[0] == id) found = row;
                if (found == null) return 0;
                String name = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
                if (name != null) found[1] = unique(name, id);
                Integer pending = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING);
                if (pending != null) {
                    published = pending == 0 && (Integer) found[2] != 0;
                    found[2] = pending;
                }
            }
            Runnable hook = onPublish;
            if (published && hook != null) hook.run();
            return 1;
        }

        @Override public int delete(Uri uri, String selection, String[] args) {
            long id = ContentUris.parseId(uri);
            synchronized (RecordingMediaProvider.class) {
                for (int at = 0; at < ROWS.size(); at++) {
                    if ((Long) ROWS.get(at)[0] == id) {
                        ROWS.remove(at);
                        return 1;
                    }
                }
            }
            return 0;
        }

        private static String unique(String name, long self) {
            String candidate = name;
            for (int attempt = 1; taken(candidate, self); attempt++) {
                int dot = name.lastIndexOf('.');
                candidate = dot > 0 ? name.substring(0, dot) + " (" + attempt + ")" + name.substring(dot)
                        : name + " (" + attempt + ")";
            }
            return candidate;
        }

        private static boolean taken(String name, long self) {
            for (Object[] row : ROWS) if (name.equals(row[1]) && (Long) row[0] != self) return true;
            return false;
        }

        @Override public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) {
            try {
                File file = new File(RuntimeEnvironment.getApplication().getCacheDir(),
                        "recorded-media-" + ContentUris.parseId(uri));
                return android.os.ParcelFileDescriptor.open(file,
                        android.os.ParcelFileDescriptor.MODE_READ_WRITE
                                | android.os.ParcelFileDescriptor.MODE_CREATE);
            } catch (java.io.FileNotFoundException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override public String getType(Uri uri) {
            return null;
        }
    }
}

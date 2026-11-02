/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.atDeath;
import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.await;
import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.startAgain;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Environment;

import app.morphe.extension.shared.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowMediaScannerConnection;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The same deaths on Android 6 to 9, where a save writes the file itself and the gallery only
 * hears of it from the media scanner. A record there holds the file's path and the size a
 * complete copy has, so a file cut off mid-copy is told from one that landed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {23, 28}, qualifiers = "en")
public class SaveRecordsLegacyFileTest {
    private static final String FOLDER = "Pictures/Hushfeed";
    private static final byte[] PICTURE = "a whole picture".getBytes(StandardCharsets.UTF_8);

    private Context context;
    private CountDownLatch hold;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        SaveRecordsFixtures.reset(context);
        ShadowMediaScannerConnection.reset();
        hold = new CountDownLatch(1);
    }

    @After public void tearDown() throws Exception {
        hold.countDown();
        SaveRecordsFixtures.reset(context);
    }

    private File target(String name) {
        File directory = new File(Environment.getExternalStorageDirectory(), FOLDER);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        return new File(directory, name);
    }

    /** A record for one file claimed on disk and never confirmed, the way a copy under way leaves it. */
    private void claimed(String kind, File file, long size) {
        SaveRecords.Record record = SaveRecords.open(kind, 1);
        SaveRecords.accepted(record);
        SaveRecords.Record outer = SaveRecords.enter(record);
        try {
            assertNotNull(SaveRecords.located(file, size));
        } finally {
            SaveRecords.exit(outer);
        }
    }

    /** Through the real writer: one photo of three lands, then the process dies. */
    @Test public void deathBetweenFilesLeavesTheLandedFileDone() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        File[] landed = {null};
        assertNotNull(MediaJobScheduler.submit("original photos", null, 3, () -> {
            try {
                File source = new File(context.getCacheDir(), "source.jpg");
                Files.write(source.toPath(), PICTURE);
                landed[0] = MediaFileWriter.publishForResult(context, source, "photo_1.jpg",
                        "image/jpeg", FOLDER, false).file;
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            reached.countDown();
            await(hold);
        }, null));
        assertTrue(reached.await(5, TimeUnit.SECONDS));

        byte[] died = atDeath(context);
        hold.countDown();
        startAgain(context, died);
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertEquals(1, report.saves.size());
        SaveRecords.Unfinished save = report.saves.get(0);
        assertEquals(3, save.files);
        assertEquals(1, save.done);
        assertEquals(2, save.unfinished);
        assertEquals(0, save.uncertain);
        assertTrue("the landed photo went", landed[0].isFile());
    }

    /**
     * Copied in full but never confirmed: the process died between the copy and the record, or
     * before the scan. It's done, and it goes to the scanner again so the gallery shows it.
     */
    @Test public void aFileAtItsFullSizeIsDoneAndScannedAgain() throws Exception {
        File file = target("clip.jpg");
        Files.write(file.toPath(), PICTURE);
        claimed("video", file, PICTURE.length);

        startAgain(context, atDeath(context));
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertTrue("a complete file was named", report.saves.isEmpty());
        assertTrue("the file was not scanned again",
                ShadowMediaScannerConnection.getSavedPaths().contains(file.getAbsolutePath()));
    }

    /** Cut off mid-copy: short of its size, it didn't finish, and it isn't deleted from here. */
    @Test public void aShortFileDidNotFinish() throws Exception {
        File file = target("clip.jpg");
        Files.write(file.toPath(), "a whole".getBytes(StandardCharsets.UTF_8));
        claimed("video", file, PICTURE.length);

        startAgain(context, atDeath(context));
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertEquals(1, report.saves.size());
        assertEquals(1, report.saves.get(0).unfinished);
        assertTrue("the short file was deleted", file.isFile());
        assertFalse(ShadowMediaScannerConnection.getSavedPaths().contains(file.getAbsolutePath()));
    }

    @Test public void aMissingFileDidNotFinish() throws Exception {
        claimed("sound", target("sound.m4a"), PICTURE.length);

        startAgain(context, atDeath(context));
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertEquals(1, report.saves.size());
        assertEquals("sound", report.saves.get(0).kind);
        assertEquals(1, report.saves.get(0).unfinished);
    }

    /** A sticker's size isn't known in advance: a file with something in it can't be confirmed. */
    @Test public void aFileWithNoKnownSizeIsUncertainUnlessEmpty() throws Exception {
        File written = target("sticker.png");
        Files.write(written.toPath(), PICTURE);
        claimed("sticker", written, -1L);
        File empty = target("sticker_2.png");
        assertTrue(empty.createNewFile());
        claimed("sticker", empty, -1L);

        startAgain(context, atDeath(context));
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertEquals(2, report.saves.size());
        assertEquals(1, report.saves.get(0).uncertain);
        assertEquals(0, report.saves.get(0).unfinished);
        assertEquals(1, report.saves.get(1).unfinished);
    }

    /**
     * What the record may hold here, and for how long: a file being written is noted by its own
     * path, which the default template fills with the creator and the video id, and nothing of it
     * is left once the file is confirmed. No log line carries it, and nothing but SaveRecords
     * names the records file, so no export, backup or report can pick it up.
     */
    @Test public void aFileBeingWrittenIsKeptByItsOwnPathOnlyUntilConfirmed() throws Exception {
        String videoId = "7312345678901234567";
        String name = "creatorname_" + videoId + ".jpg";
        File file = target(name);
        Files.write(file.toPath(), PICTURE);
        ShadowLog.clear();
        SaveRecords.Record record = SaveRecords.open("original photos", 2);
        SaveRecords.accepted(record);
        SaveRecords.Record outer = SaveRecords.enter(record);
        try {
            SaveRecords.Slot slot = SaveRecords.located(file, PICTURE.length);
            String inFlight = SaveRecordsFixtures.text(atDeath(context));
            assertTrue("a file being written wasn't noted by its path: " + inFlight, inFlight.contains(name));

            SaveRecords.published(slot);
            String confirmed = SaveRecordsFixtures.text(atDeath(context));
            assertFalse("a confirmed file kept its path: " + confirmed, confirmed.contains(videoId));
            assertFalse(confirmed.contains("creatorname"));

            SaveRecords.located(target("creatorname_" + videoId + "_2.jpg"), PICTURE.length);
        } finally {
            SaveRecords.exit(outer);
        }
        startAgain(context, atDeath(context));
        SaveRecords.Report report = SaveRecords.reconcile(context);
        UnfinishedSaves.message(context, report);
        assertEquals(1, report.saves.size());
        assertEquals(1, report.saves.get(0).done);
        assertEquals(1, report.saves.get(0).unfinished);

        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            String line = item.msg + (item.throwable == null ? "" : " " + item.throwable);
            for (String secret : new String[]{videoId, "creatorname", "Pictures", SaveRecords.FILE_NAME}) {
                assertFalse("the log carried " + secret + ": " + line, line.contains(secret));
            }
        }

        File root = new File("src/main/java");
        if (!root.isDirectory()) root = new File("extensions/tiktok/src/main/java");
        assertTrue(root.isDirectory());
        List<String> readers = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> sources = Files.walk(root.toPath())) {
            for (java.nio.file.Path source : (Iterable<java.nio.file.Path>) sources::iterator) {
                if (!source.toString().endsWith(".java")
                        || source.getFileName().toString().equals("SaveRecords.java")) continue;
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                if (text.contains(SaveRecords.FILE_NAME) || text.contains("SaveRecords.FILE_NAME")
                        || text.contains("SaveRecords.file(")) readers.add(source.getFileName().toString());
            }
        }
        assertEquals("something else can reach the records file", List.of(), readers);
    }

    /** The README says what the record keeps, the Android 9 path included, and claims nothing more. */
    @Test public void theReadmeSaysWhatTheRecordKeeps() throws Exception {
        File readme = new File("../../README.md");
        if (!readme.isFile()) readme = new File("README.md");
        assertTrue(readme.isFile());
        String text = new String(Files.readAllBytes(readme.toPath()), StandardCharsets.UTF_8);
        int at = text.indexOf("If TikTok closes while saves are still going");
        assertTrue("the README lost its paragraph on unfinished saves", at >= 0);
        String paragraph = text.substring(at, text.indexOf('\n', at));
        assertFalse("the README says no video IDs are kept: " + paragraph, paragraph.contains("no video IDs"));
        assertTrue("the README doesn't say a file being written is noted by its path: " + paragraph,
                paragraph.contains("Android 9 and older") && paragraph.contains("path"));
        assertTrue(paragraph.contains("no links or tokens"));
    }

    /** A file confirmed and deleted since is a file that was saved. */
    @Test public void aConfirmedFileDeletedSinceIsStillDone() throws Exception {
        File file = target("clip.jpg");
        Files.write(file.toPath(), PICTURE);
        SaveRecords.Record record = SaveRecords.open("video", 1);
        SaveRecords.accepted(record);
        SaveRecords.Record outer = SaveRecords.enter(record);
        try {
            SaveRecords.published(SaveRecords.located(file, PICTURE.length));
        } finally {
            SaveRecords.exit(outer);
        }
        String kept = SaveRecordsFixtures.text(atDeath(context));
        assertFalse("a confirmed file kept its path: " + kept, kept.contains("clip"));

        startAgain(context, atDeath(context));
        assertTrue(file.delete());
        assertTrue(SaveRecords.reconcile(context).saves.isEmpty());
    }
}

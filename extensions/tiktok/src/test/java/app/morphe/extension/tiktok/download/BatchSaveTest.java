/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * The run that saves the posts picked on a grid (#27): one count over every post, a post that
 * fails skipped and the next one still saved, Cancel and a refusal from the disk ending it with
 * the reason, already-saved videos counted and named, and a run cut off by TikTok closing named
 * at the next start without counting an already-saved video as lost.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "en")
public class BatchSaveTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private final List<CountDownLatch> holds = new ArrayList<>();

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        SaveNotice.windowRootsForTests = List.of();
        SaveRecordsFixtures.reset(context);
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        for (CountDownLatch hold : holds) hold.countDown();
        BatchSave.saverForTests = null;
        SaveRecordsFixtures.reset(context);
        SaveNotice.windowRootsForTests = null;
        Settings.DOWNLOAD_VIDEO_QUALITY.resetToDefault();
        Settings.DOWNLOAD_WITHOUT_SOUND.resetToDefault();
        Settings.REMOVE_DOWNLOAD_WATERMARK.resetToDefault();
        Settings.DOWNLOAD_PHOTOS_AS_VIDEO.resetToDefault();
        Settings.CHECK_SAVED_VIDEOS.resetToDefault();
        SettingsStatus.advancedDownloadsEnabled = false;
        SlideshowVideo.makerForTests = null;
        SavedVideoArchiveAccess.reset(context);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The banner with no file to open falls back to a toast, which is what the tests read. */
    private static String said() {
        idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(SaveNotice.SHEET_SETTLE_MS + 1, TimeUnit.MILLISECONDS);
        return ShadowToast.getTextOfLatestToast();
    }

    private static List<Object> posts(String... ids) {
        List<Object> posts = new ArrayList<>();
        for (String id : ids) posts.add(id);
        return posts;
    }

    @Test public void everyPostSavedSaysHowMany() {
        SaveProgress progress = SaveProgress.queuedFiles(3);
        List<Object> seen = new ArrayList<>();
        BatchSave.run(context, posts("a", "b", "c"), (app, post, last) -> {
            seen.add(post);
            return BatchSave.Result.SAVED;
        }, progress);
        assertEquals("the posts weren't saved in the order picked", posts("a", "b", "c"), seen);
        assertEquals("Saved 3 videos", said());
    }

    @Test public void aFailedPostIsSkippedAndAnAlreadySavedOneIsNamed() {
        SaveProgress progress = SaveProgress.queuedFiles(4);
        List<Object> seen = new ArrayList<>();
        BatchSave.run(context, posts("a", "b", "c", "d"), (app, post, last) -> {
            seen.add(post);
            if ("b".equals(post)) throw new IOException("gone");
            return "c".equals(post) ? BatchSave.Result.ALREADY_SAVED : BatchSave.Result.SAVED;
        }, progress);
        assertEquals("a failure ended the run", 4, seen.size());
        assertEquals("Saved 3 of 4, 1 skipped\nOne was already saved", said());
    }

    @Test public void cancelLetsThePostUnderWayFinishAndLeavesTheRest() {
        SaveProgress progress = SaveProgress.queuedFiles(4);
        List<Object> seen = new ArrayList<>();
        BatchSave.run(context, posts("a", "b", "c", "d"), (app, post, last) -> {
            seen.add(post);
            if ("b".equals(post)) progress.cancel();
            return BatchSave.Result.SAVED;
        }, progress);
        assertEquals(posts("a", "b"), seen);
        assertEquals("Saved 2 of 4, the rest cancelled", said());
    }

    @Test public void aFullDiskEndsTheRunWithItsReason() {
        SaveProgress progress = SaveProgress.queuedFiles(3);
        List<Object> seen = new ArrayList<>();
        BatchSave.run(context, posts("a", "b", "c"), (app, post, last) -> {
            seen.add(post);
            if ("b".equals(post)) throw new MediaBudget.StopException("full", MediaBudget.StopException.Reason.SPACE);
            return BatchSave.Result.SAVED;
        }, progress);
        assertEquals("the run went on past a full disk", posts("a", "b"), seen);
        assertEquals("Saved 1 of 3, the rest need more free space", said());
    }

    @Test public void nothingSavedSaysSo() {
        SaveProgress progress = SaveProgress.queuedFiles(2);
        BatchSave.run(context, posts("a", "b"), (app, post, last) -> {
            throw new IOException("gone");
        }, progress);
        assertEquals("None of the picked videos could be saved. Try again.", said());
    }

    @Test public void everyPostAlreadySavedStillSaysSo() {
        SaveProgress progress = SaveProgress.queuedFiles(2);
        BatchSave.run(context, posts("a", "b"), (app, post, last) -> BatchSave.Result.ALREADY_SAVED, progress);
        assertEquals("2 were already saved", said());
    }

    @Test public void aRunOfNewAndAlreadySavedVideosCountsEachOnce() {
        SaveProgress progress = SaveProgress.queuedFiles(3);
        BatchSave.run(context, posts("a", "b", "c"), (app, post, last) ->
                "a".equals(post) ? BatchSave.Result.ALREADY_SAVED : BatchSave.Result.SAVED, progress);
        assertEquals("Saved 2 videos\nOne was already saved", said());
        assertEquals("Saved one video", BatchSave.message(context, new SaveProgress.Outcome(1, 1, 0, 0), 0));
    }

    @Test public void theRunIsOneJobInTheMediaQueue() throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        BatchSave.saverForTests = (app, post, last) -> {
            assertFalse("a post was saved on the main thread", Utils.isCurrentlyOnMainThread());
            done.countDown();
            return BatchSave.Result.SAVED;
        };
        assertTrue(BatchSave.start(context, posts("a", "b")));
        assertTrue("the run never saved its posts", done.await(10, TimeUnit.SECONDS));
        SaveRecordsFixtures.drain();
        assertEquals("Saved 2 videos", said());
        assertFalse("nothing to start", BatchSave.start(context, new ArrayList<>()));
    }

    @Test public void aRunCutOffByTikTokClosingIsNamedWithoutItsAlreadySavedVideos() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        holds.add(hold);
        CountDownLatch reached = new CountDownLatch(1);
        assertNotNull(MediaJobScheduler.submit(BatchSave.LABEL, null, 3, () -> {
            SaveRecords.countedWithoutFile();
            reached.countDown();
            SaveRecordsFixtures.await(hold);
        }, null));
        assertTrue(reached.await(5, TimeUnit.SECONDS));

        byte[] died = SaveRecordsFixtures.atDeath(context);
        hold.countDown();
        SaveRecordsFixtures.startAgain(context, died);
        SaveRecords.Report report = SaveRecords.reconcile(context);

        assertEquals(1, report.saves.size());
        SaveRecords.Unfinished save = report.saves.get(0);
        assertEquals(BatchSave.LABEL, save.kind);
        assertEquals(3, save.files);
        assertEquals("the already-saved video was named as lost", 1, save.done);
        assertEquals(2, save.unfinished);
        assertEquals("Picked videos", UnfinishedSaves.kindLabel(context, save.kind));
    }

    @Test public void eachVideoComesFromWhereDownloadWouldTakeIt() {
        Settings.REMOVE_DOWNLOAD_WATERMARK.save(true);
        AdvancedDownloadsTest.VideoData video = new AdvancedDownloadsTest.VideoData(List.of(
                new AdvancedDownloadsTest.Gear("normal_720_0", 200, "https://example.com/720")));
        video.downloadNoWatermarkAddr = new AdvancedDownloadsTest.Address("https://example.com/clean", 10);
        video.downloadAddr = new AdvancedDownloadsTest.Address("https://example.com/stamped", 10);

        BatchSave.Source automatic = BatchSave.source(video, "auto", false);
        assertEquals("Automatic takes TikTok's clean file", List.of("https://example.com/clean"), automatic.video);
        assertTrue(automatic.audio.isEmpty());
        assertFalse(automatic.stripSound);
        assertTrue("muted, the sound comes out", BatchSave.source(video, "auto", true).stripSound);

        BatchSave.Source picked = BatchSave.source(video, "720", false);
        assertEquals("a chosen quality takes its rendition", List.of("https://example.com/720"), picked.video);
        assertFalse(picked.stripSound);
        assertTrue("a rendition with its sound in it, muted", BatchSave.source(video, "720", true).stripSound);
    }

    @Test public void aPictureOnlyRenditionComesWithItsSoundOrNotAtAll() {
        AdvancedDownloadsTest.Gear gear = new AdvancedDownloadsTest.Gear("normal_1080_0", 400, "https://example.com/video");
        gear.videoExtra = "{\"audio_file_id\":\"matching\"}";
        AdvancedDownloadsTest.VideoData video = new AdvancedDownloadsTest.VideoData(List.of(gear));
        video.dash = true;
        video.bitRateAudio = List.of(new AdvancedDownloadsTest.Audio("matching", 100));

        BatchSave.Source source = BatchSave.source(video, "highest", false);
        assertEquals(List.of("https://example.com/video"), source.video);
        assertEquals("https://example.com/matching", source.audio.get(0));
        BatchSave.Source muted = BatchSave.source(video, "highest", true);
        assertTrue("muted, no sound is fetched", muted.audio.isEmpty());
        assertFalse("and there is none to strip", muted.stripSound);

        gear.videoExtra = "{\"audio_file_id\":\"missing\"}";
        assertTrue("a silent video was offered as a complete one", BatchSave.source(video, "highest", false).video.isEmpty());
    }

    /** #197: with Save photo posts as a video on, a picked photo post comes out as that video. */
    @Test public void aPickedPhotoPostIsSavedAsAVideoWhenThatSwitchIsOn() throws Exception {
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(true);
        Settings.CHECK_SAVED_VIDEOS.save(true);
        List<String> made = new ArrayList<>();
        SlideshowVideo.makerForTests = name -> {
            made.add(name);
            java.io.File file = new java.io.File(context.getCacheDir(), "batch-photo-video.mp4");
            java.nio.file.Files.write(file.toPath(), new byte[]{1, 2, 3, 4});
            return new MediaFileWriter.Saved(name, null, file);
        };
        Object post = new AdvancedDownloadsTest.PhotoPost("batch-photo-post", List.of(
                new AdvancedDownloadsTest.Photo("https://example.com/batch/0"),
                new AdvancedDownloadsTest.Photo("https://example.com/batch/1")));
        MediaFileWriter.Saved[] last = {null};

        assertEquals(BatchSave.Result.SAVED, BatchSave.saveOne(context, post, last));
        assertEquals("the photo post wasn't made into a video", 1, made.size());
        assertNotNull(last[0]);
        assertTrue("the video isn't in the record of saved posts",
                SavedVideoArchiveAccess.isRecorded(context, "batch-photo-post"));
        assertEquals("a second pick of the same post made another video", BatchSave.Result.ALREADY_SAVED,
                BatchSave.saveOne(context, post, last));
        assertEquals(1, made.size());
    }
}

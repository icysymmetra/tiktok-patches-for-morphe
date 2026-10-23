/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * The clip of a live photo in a comment: read off the comment's model the way TikTok's own
 * classes hold it (a getter for the image list and the live-photo model, a public field for
 * the clip's addresses), a still leaving nothing to fetch, and the hook staying silent while
 * the Downloads patch is off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentLivePhotoSaverTest {
    /** The clip's addresses, a public field on TikTok's model with no getter. */
    public static final class LivePhotoInfo {
        public final List<String> urlList;
        public LivePhotoInfo(List<String> urlList) { this.urlList = urlList; }
    }

    public static final class Image {
        private final LivePhotoInfo live;
        public Image(LivePhotoInfo live) { this.live = live; }
        public LivePhotoInfo getLivePhotoInfoModel() { return live; }
    }

    public static final class Comment {
        private final List<Image> images;
        private final String cid;
        public Comment(List<Image> images) { this(images, "7000000000000000001"); }
        public Comment(List<Image> images, String cid) {
            this.images = images;
            this.cid = cid;
        }
        public List<Image> getImageList() { return images; }
        public String getCid() { return cid; }
    }

    /** A comment whose model moved: neither the getter nor the field of the image list. */
    public static final class RenamedComment {
        public List<Image> getPhotos() { return null; }
        public String getCid() { return "7000000000000000009"; }
    }

    private static Comment live(String cid, String url) {
        return new Comment(Arrays.asList(new Image(new LivePhotoInfo(Arrays.asList(url)))), cid);
    }

    private boolean downloadsWereEnabled;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        downloadsWereEnabled = SettingsStatus.advancedDownloadsEnabled;
    }

    @After public void tearDown() {
        SettingsStatus.advancedDownloadsEnabled = downloadsWereEnabled;
        HookStatus.clear();
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test public void theClipsAddressesComeOffTheTappedPhoto() {
        Comment comment = new Comment(Arrays.asList(
                new Image(null),
                new Image(new LivePhotoInfo(Arrays.asList("https://v.example/clip-a.mp4", "", "https://v.example/clip-b.mp4")))));
        assertEquals(Arrays.asList("https://v.example/clip-a.mp4", "https://v.example/clip-b.mp4"),
                CommentLivePhotoSaver.clipUrls(comment, 1));
    }

    @Test public void aStillAnIndexOffTheEndAndAnUnknownModelLeaveNothingToFetch() {
        Comment comment = new Comment(Arrays.asList(new Image(null), new Image(new LivePhotoInfo(null))));
        assertTrue(CommentLivePhotoSaver.clipUrls(comment, 0).isEmpty());
        assertTrue(CommentLivePhotoSaver.clipUrls(comment, 1).isEmpty());
        assertTrue(CommentLivePhotoSaver.clipUrls(comment, 2).isEmpty());
        assertTrue(CommentLivePhotoSaver.clipUrls(comment, -1).isEmpty());
        assertTrue(CommentLivePhotoSaver.clipUrls(new Object(), 0).isEmpty());
        assertTrue(CommentLivePhotoSaver.clipUrls(null, 0).isEmpty());
    }

    @Test public void aStillIsNotedAndLeftToTikTok() {
        SettingsStatus.advancedDownloadsEnabled = true;
        CommentLivePhotoSaver.saveClip(new Comment(Arrays.asList(new Image(null))), 0);
        assertTrue(report(), report().contains("comment live photo: 1 found, 0 missing"));
    }

    @Test public void aRenamedImageListIsNamedInTheExport() {
        SettingsStatus.advancedDownloadsEnabled = true;
        CommentLivePhotoSaver.saveClip(new RenamedComment(), 0);
        assertTrue(report(), report().contains("comment live photo: 1 found, 1 missing"));
        assertTrue(report(), report().contains("getImageList or imageList"));
    }

    @Test public void withoutStorageOnAndroid9TheClipIsAskedForBeforeAnyFetch() {
        SettingsStatus.advancedDownloadsEnabled = true;
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        ShadowToast.reset();
        CommentLivePhotoSaver.saveClip(live("7000000000000000002", "https://v.example/clip.mp4"), 0);
        // Marker names never reach the export; the count is what it renders.
        assertTrue(report(), report().contains("comment live photo: 1 found, 0 missing"));
        assertEquals(L10n.t("Allow storage for TikTok in Android settings to save the live photo's clip"),
                ShadowToast.getTextOfLatestToast());
        assertEquals("a fetch was submitted without the permission", 0,
                MediaJobScheduler.queuedJobs() + MediaJobScheduler.runningJobs());
        assertFalse(CommentLivePhotoSaver.activeForTests("7000000000000000002", 0));
    }

    /**
     * The clip path end to end, offline: the workers are held so the clip queues, a second tap
     * on the same photo waits instead of fetching twice, and once released the fetch of a
     * loopback address, which the transport refuses before it connects, ends in the failure
     * toast with the photo released again.
     */
    @Test public void aLivePhotoFetchesItsClipOnceAndSaysSoWhenTheFetchFails() throws Exception {
        SettingsStatus.advancedDownloadsEnabled = true;
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertEquals(0, MediaJobScheduler.queuedJobs() + MediaJobScheduler.runningJobs());
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        for (int index = 0; index < MediaJobScheduler.MAX_RUNNING_JOBS; index++) {
            assertTrue(MediaJobScheduler.submit("clip test hold", () -> {
                started.countDown();
                try {
                    hold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        try {
            assertTrue("the media workers never started", started.await(5, TimeUnit.SECONDS));
            String cid = "7000000000000000003";
            Comment comment = live(cid, "https://127.0.0.1/clip.mp4");
            ShadowToast.reset();
            CommentLivePhotoSaver.saveClip(comment, 0);
            assertTrue(report(), report().contains("comment live photo: 1 found, 0 missing"));
            assertEquals("the clip was not queued", 1, MediaJobScheduler.queuedJobs());
            assertTrue(CommentLivePhotoSaver.activeForTests(cid, 0));
            // Taken, and said so at once, with the wait it faces behind the held workers.
            assertEquals(L10n.t("Saving the live photo's clip") + "\n" + L10n.t("Starts after one other save"),
                    ShadowToast.getTextOfLatestToast());
            CommentLivePhotoSaver.saveClip(comment, 0);
            // The second tap is told the first one is still in line, not that it is saving.
            assertEquals(L10n.t("The last one is still waiting to start"), ShadowToast.getTextOfLatestToast());
            assertEquals("the second tap fetched again", 1, MediaJobScheduler.queuedJobs());
        } finally {
            hold.countDown();
        }
        for (int wait = 0; wait < 500 && (MediaJobScheduler.queuedJobs() != 0
                || MediaJobScheduler.runningJobs() != 0
                || CommentLivePhotoSaver.activeForTests("7000000000000000003", 0)); wait++) {
            Thread.sleep(20);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse("the photo was not released after the fetch", CommentLivePhotoSaver.activeForTests("7000000000000000003", 0));
        assertEquals(L10n.t("The live photo's clip couldn't be saved. Try again."), ShadowToast.getTextOfLatestToast());
        assertEquals("a test media worker was left running", 0, MediaJobScheduler.runningJobs());
    }

    @Test public void withTheDownloadsPatchOffNothingIsReadOrNoted() {
        SettingsStatus.advancedDownloadsEnabled = false;
        CommentLivePhotoSaver.saveClip(new Comment(Arrays.asList(
                new Image(new LivePhotoInfo(Arrays.asList("https://v.example/clip.mp4"))))), 0);
        assertTrue(report(), !report().contains("comment live photo"));
    }
}

package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Looper;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/**
 * A photo post saved as one video. Robolectric has no codecs, so the drawing itself is a device
 * check: this covers the sizes, timings and trims it's planned from, the photo decode, and which
 * saves the switch takes over and which it leaves alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SlideshowVideoTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<CountDownLatch> holds = new ArrayList<>();
    private final Set<String> ownIds = new java.util.HashSet<>();
    private boolean previousAsVideo, previousOriginals, previousPaused, previousAdvanced;
    private int previousSeconds;

    @Before public void setUp() throws Exception {
        drainPool();
        previousAsVideo = Settings.DOWNLOAD_PHOTOS_AS_VIDEO.savedValue();
        previousSeconds = Settings.PHOTO_VIDEO_SECONDS.savedValue();
        previousOriginals = Settings.DOWNLOAD_ORIGINAL_PHOTOS.savedValue();
        previousPaused = Setting.isPaused();
        previousAdvanced = SettingsStatus.advancedDownloadsEnabled;
        setPaused(false);
        Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(true);
        Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(false);
        SettingsStatus.advancedDownloadsEnabled = true;
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        try {
            for (CountDownLatch hold : holds) hold.countDown();
            drainPool();
            idle();
        } finally {
            videoIds().removeAll(ownIds);
            photoIds().removeAll(ownIds);
            Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(previousAsVideo);
            Settings.PHOTO_VIDEO_SECONDS.save(previousSeconds);
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(previousOriginals);
            SettingsStatus.advancedDownloadsEnabled = previousAdvanced;
            setPaused(previousPaused);
        }
    }

    // Planning.

    @Test public void framesKeepThePhotosShapeInSixteenPixelSteps() {
        assertSize(1072, 1440, SlideshowVideo.frameSize(1080, 1440, 1920, 1080));
        assertSize(1072, 1920, SlideshowVideo.frameSize(1080, 1920, 1920, 1080));
        assertSize(1920, 1072, SlideshowVideo.frameSize(1920, 1080, 1920, 1080));
        assertSize(1072, 1072, SlideshowVideo.frameSize(1000, 1000, 1920, 1080));
        assertSize(1344, 1072, SlideshowVideo.frameSize(1000, 800, 1920, 1080));
        // Past 16:9 either way the frame stops narrowing and the photo gets bars.
        assertSize(1920, 1072, SlideshowVideo.frameSize(4000, 500, 1920, 1080));
        assertSize(1072, 1920, SlideshowVideo.frameSize(500, 4000, 1920, 1080));
        assertSize(1072, 1920, SlideshowVideo.frameSize(0, 0, 1920, 1080));
        assertSize(16, 16, SlideshowVideo.frameSize(1, 1, 10, 10));

        List<int[]> sizes = SlideshowVideo.frameSizes(1080, 1440);
        assertEquals(3, sizes.size());
        assertSize(1072, 1440, sizes.get(0));
        assertSize(720, 960, sizes.get(1));
        assertSize(528, 720, sizes.get(2));
        for (int[] size : SlideshowVideo.frameSizes(3024, 4032)) {
            assertEquals(0, size[0] % 16);
            assertEquals(0, size[1] % 16);
        }
    }

    @Test public void photosSitCenteredWithBarsAndDecodeAtAPowerOfTwo() {
        assertEquals(new Rect(0, 5, 1072, 1434), SlideshowVideo.fit(1080, 1440, 1072, 1440));
        assertEquals(new Rect(0, 416, 1920, 656), SlideshowVideo.fit(4000, 500, 1920, 1072));
        assertEquals(new Rect(436, 0, 636, 1072), SlideshowVideo.fit(200, 1072, 1072, 1072));
        assertEquals(new Rect(0, 0, 100, 200), SlideshowVideo.fit(0, 0, 100, 200));

        assertEquals(2, SlideshowVideo.sampleSize(4000, 3000, 1072, 804));
        assertEquals(4, SlideshowVideo.sampleSize(1000, 800, 160, 128));
        assertEquals(1, SlideshowVideo.sampleSize(1000, 800, 1000, 800));
        assertEquals("Never past 64", 64, SlideshowVideo.sampleSize(100_000, 100_000, 1, 1));
        assertEquals(1, SlideshowVideo.sampleSize(10, 10, 0, 0));
    }

    @Test public void timingFollowsTheSettingAndTheSoundIsCutInWholeSamples() {
        assertEquals(1, SlideshowVideo.seconds(0));
        assertEquals(3, SlideshowVideo.seconds(3));
        assertEquals(10, SlideshowVideo.seconds(60));
        assertEquals(3, (int) Settings.PHOTO_VIDEO_SECONDS.defaultValue);
        assertFalse(Settings.DOWNLOAD_PHOTOS_AS_VIDEO.defaultValue);

        assertEquals(45, SlideshowVideo.framesPerPhoto(3));
        assertEquals(0L, SlideshowVideo.presentationTimeNs(0, 15));
        assertEquals(66_666_666L, SlideshowVideo.presentationTimeNs(1, 15));
        assertEquals(1_000_000_000L, SlideshowVideo.presentationTimeNs(15, 15));
        assertEquals(12_000_000L, SlideshowVideo.durationUs(4, 3));
        assertEquals(350_000_000L, SlideshowVideo.durationUs(35, 10));
        assertEquals(529_200L, SlideshowVideo.trimFrames(12_000_000L, 44_100));

        assertEquals("The cut is the limit", 400, SlideshowVideo.pcmBytes(4096, 8192, 100, 4));
        assertEquals("The encoder buffer is the limit", 2048, SlideshowVideo.pcmBytes(4096, 2048, 10_000, 4));
        assertEquals("A half sample stays behind", 4096, SlideshowVideo.pcmBytes(4097, 8192, 10_000, 4));
        assertEquals(0, SlideshowVideo.pcmBytes(3, 8192, 10_000, 4));
        assertEquals(0, SlideshowVideo.pcmBytes(4096, 8192, 0, 4));
        assertEquals(0, SlideshowVideo.pcmBytes(4096, 8192, 100, 0));

        assertEquals(9_026_122L, SlideshowVideo.loopLengthUs(9_000_000L, 26_122L));
        assertEquals(SlideshowVideo.MIN_LOOP_US, SlideshowVideo.loopLengthUs(0, 0));
        assertEquals(SlideshowVideo.MIN_LOOP_US, SlideshowVideo.loopLengthUs(-5, -5));

        assertEquals(4_116_480, SlideshowVideo.bitRate(1072, 1920));
        assertEquals(1_000_000, SlideshowVideo.bitRate(16, 16));
        assertEquals(6_000_000, SlideshowVideo.bitRate(4000, 4000));
        assertEquals(96_000, SlideshowVideo.audioBitRate(1));
        assertEquals(128_000, SlideshowVideo.audioBitRate(2));
    }

    @Test public void aLargePhotoDecodesSmallAndAnUnreadableOneIsNull() throws Exception {
        File png = File.createTempFile("slideshow-", ".png", RuntimeEnvironment.getApplication().getCacheDir());
        File junk = File.createTempFile("slideshow-", ".tmp", RuntimeEnvironment.getApplication().getCacheDir());
        try {
            Bitmap source = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888);
            source.eraseColor(0xff336699);
            try (OutputStream out = new FileOutputStream(png)) {
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            source.recycle();

            Bitmap small = SlideshowVideo.decode(png, new int[]{160, 160});
            assertNotNull(small);
            assertEquals(250, small.getWidth());
            assertEquals(200, small.getHeight());
            Bitmap first = SlideshowVideo.decode(png, null);
            assertNotNull(first);
            assertEquals("The first photo sizes the frame and already fits it", 1000, first.getWidth());

            Files.write(junk.toPath(), "not a photo".getBytes(StandardCharsets.UTF_8));
            assertNull(SlideshowVideo.decode(junk, null));
        } finally {
            assertTrue(png.delete());
            assertTrue(junk.delete());
        }
    }

    // Gating.

    @Test public void switchOffPauseOrNoPatchLeaveEverySaveAsItWas() {
        var one = post("slideshow-off-one", List.of(photo("https://example.com/one")));
        var two = post("slideshow-off-two", List.of(photo("https://example.com/one"), photo("https://example.com/two")));
        Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(false);
        assertFalse(OriginalPhotos.startImageAsVideo(one));
        assertFalse(OriginalPhotos.startPhotos(two, Set.of(0, 1), true));

        Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(true);
        setPaused(true);
        assertFalse(OriginalPhotos.startImageAsVideo(one));
        assertFalse(OriginalPhotos.startPhotos(two, Set.of(0, 1), true));
        setPaused(false);

        SettingsStatus.advancedDownloadsEnabled = false;
        assertFalse(OriginalPhotos.startImageAsVideo(one));
        assertFalse(SlideshowVideo.offer(two, Set.of(0, 1)));

        idle();
        for (var post : List.of(one, two)) {
            assertNull(MediaJobScheduler.job("photo video " + post.getAid()));
            assertNull(MediaJobScheduler.job("photos " + post.getAid()));
        }
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void videoPostsAndLivePhotosStayWithTikTok() {
        var video = new AdvancedDownloadsTest.VideoData(List.of(
                new AdvancedDownloadsTest.Gear("normal_720_0", 700, "https://example.com/video")));
        var ordinary = new PhotoConversionTest.VideoPost("slideshow-ordinary", video);
        ownIds.add(ordinary.getAid());
        assertFalse(OriginalPhotos.startImageAsVideo(ordinary));
        assertFalse(SlideshowVideo.offer(ordinary, null));

        var live = post("slideshow-live", List.of(photo("https://example.com/still"),
                photo("https://example.com/live").live()));
        assertFalse(OriginalPhotos.startImageAsVideo(live));
        assertFalse(OriginalPhotos.startPhotos(live, Set.of(1), true));
        idle();
        assertNull(MediaJobScheduler.job("photo video " + ordinary.getAid()));
        assertNull(MediaJobScheduler.job("photo video " + live.getAid()));
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void downloadVideoOnAOnePhotoPostQueuesThePhotoVideo() throws Exception {
        holdWorkers();
        var post = post("slideshow-one", List.of(photo("https://example.com/one")));
        assertTrue(OriginalPhotos.startImageAsVideo(post));
        MediaJobScheduler.Job job = MediaJobScheduler.job("photo video " + post.getAid());
        assertNotNull(job);
        assertTrue(job.waiting());
        assertEquals("photo video", job.record.kind);
        assertEquals(1, job.record.files);
        assertNull("No photo save runs beside it", MediaJobScheduler.job("photos " + post.getAid()));
        assertTrue(videoIds().contains(post.getAid()));

        assertTrue("A second press while it waits takes the save and says it's busy",
                OriginalPhotos.startImageAsVideo(post));
        idle();
        assertEquals(MediaJobScheduler.busyMessage("photo video " + post.getAid()), ShadowToast.getTextOfLatestToast());

        assertTrue(job.cancel());
        assertFalse(videoIds().contains(post.getAid()));
        assertNull(MediaJobScheduler.job("photo video " + post.getAid()));
    }

    @Test public void aOnePhotoPostSkipsTheQuestionAndNoScreenLeavesThePickerAlone() {
        Activity previous = Utils.getActivity();
        try {
            var one = post("slideshow-picker-one", List.of(photo("https://example.com/one")));
            assertFalse(SlideshowVideo.offer(one, Set.of(0)));
            Utils.setActivity(null);
            var two = post("slideshow-picker-no-screen", List.of(photo("https://example.com/one"),
                    photo("https://example.com/two")));
            if (Utils.getVisibleActivity() == null) assertFalse(SlideshowVideo.offer(two, Set.of(0, 1)));
            idle();
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        } finally {
            Utils.setActivity(previous);
        }
    }

    @Test public void thePickerAsksAndEachAnswerQueuesItsOwnSave() throws Exception {
        holdWorkers();
        Activity previous = Utils.getActivity();
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            var images = List.of(photo("https://example.com/one"), photo("https://example.com/two"),
                    photo("https://example.com/three"));

            var asVideo = post("slideshow-ask-video", images);
            assertTrue(OriginalPhotos.startPhotos(asVideo, Set.of(2, 0), true));
            idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            assertEquals("Save photos or a video?", Shadows.shadowOf(dialog).getTitle().toString());
            assertEquals("Save as video", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("Save photos", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            assertNull("Nothing is queued before an answer", MediaJobScheduler.job("photo video " + asVideo.getAid()));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            MediaJobScheduler.Job video = MediaJobScheduler.job("photo video " + asVideo.getAid());
            assertNotNull(video);
            assertEquals(1, video.record.files);
            assertNull(MediaJobScheduler.job("photos " + asVideo.getAid()));
            assertTrue(video.cancel());

            var asPhotos = post("slideshow-ask-photos", images);
            assertTrue(OriginalPhotos.startPhotos(asPhotos, Set.of(2, 0), true));
            idle();
            dialog = ShadowAlertDialog.getLatestAlertDialog();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            MediaJobScheduler.Job photos = MediaJobScheduler.job("photos " + asPhotos.getAid());
            assertNotNull("Save photos works with Download original photos off", photos);
            assertEquals("The picked photos, not the whole post", 2, photos.record.files);
            assertNull(MediaJobScheduler.job("photo video " + asPhotos.getAid()));
            assertTrue(photos.cancel());

            var cancelled = post("slideshow-ask-cancel", images);
            assertTrue(OriginalPhotos.startPhotos(cancelled, Set.of(1), true));
            idle();
            dialog = ShadowAlertDialog.getLatestAlertDialog();
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            idle();
            assertNull(MediaJobScheduler.job("photos " + cancelled.getAid()));
            assertNull(MediaJobScheduler.job("photo video " + cancelled.getAid()));
        } finally {
            Utils.setActivity(previous);
        }
    }

    @Test public void aFailedPhotoEndsWithTheErrorAndNoFilesLeftBehind() throws Exception {
        // The transport refuses this private address before any connection opens.
        var post = post("slideshow-failure", List.of(photo("https://127.0.0.1/one"),
                photo("https://127.0.0.1/two")));
        assertTrue(OriginalPhotos.startImageAsVideo(post));
        drainPool();
        idle();
        assertEquals("The video couldn't be made from these photos. Try again.", ShadowToast.getTextOfLatestToast());
        assertFalse(videoIds().contains(post.getAid()));
        assertNull(MediaJobScheduler.job("photo video " + post.getAid()));
        assertEquals("No temporary file stays in the cache", List.of(),
                leftovers(RuntimeEnvironment.getApplication().getCacheDir()));
    }

    @Test public void aPostWithoutUsablePhotosSaysSoAndQueuesNothing() {
        var post = post("slideshow-missing", List.of(photo(null)));
        assertTrue(OriginalPhotos.startImageAsVideo(post));
        idle();
        assertEquals("The original photos aren't available. No video was saved.", ShadowToast.getTextOfLatestToast());
        assertNull(MediaJobScheduler.job("photo video " + post.getAid()));
        assertFalse(videoIds().contains(post.getAid()));
    }

    private static List<String> leftovers(File directory) {
        List<String> found = new ArrayList<>();
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) return found;
        for (File file : files) {
            if (file.isDirectory()) found.addAll(leftovers(file));
            else if (file.getName().startsWith("photo-video-")) found.add(file.getName());
        }
        return found;
    }

    private static void assertSize(int width, int height, int[] size) {
        assertEquals("width", width, size[0]);
        assertEquals("height", height, size[1]);
    }

    private AdvancedDownloadsTest.PhotoPost post(String id, List<AdvancedDownloadsTest.Photo> images) {
        ownIds.add(id);
        return new AdvancedDownloadsTest.PhotoPost(id, images);
    }

    private static AdvancedDownloadsTest.Photo photo(String url) {
        return new AdvancedDownloadsTest.Photo(url);
    }

    private static Set<String> videoIds() { return ReflectionHelpers.getStaticField(SlideshowVideo.class, "ACTIVE"); }
    private static Set<String> photoIds() { return ReflectionHelpers.getStaticField(OriginalPhotos.class, "ACTIVE"); }
    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", from(boolean.class, value));
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private void holdWorkers() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        holds.add(hold);
        CountDownLatch started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        for (int i = 0; i < MediaJobScheduler.MAX_RUNNING_JOBS; i++) {
            assertTrue(MediaJobScheduler.submit("slideshow worker hold", () -> {
                started.countDown();
                try { hold.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }));
        }
        assertTrue("The media workers never started", started.await(5, TimeUnit.SECONDS));
    }

    private static void drainPool() throws Exception {
        for (int wait = 0; wait < 500; wait++) {
            if (MediaJobScheduler.idle()) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("The media pool never emptied");
    }
}

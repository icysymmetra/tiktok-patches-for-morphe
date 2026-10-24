package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
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
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

/**
 * The image-to-video entry never reaches the old generic-download or image-picker hooks.
 * Keep its decisions, queued job and failure cleanup separate from the native MP4 callback.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class PhotoConversionTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<CountDownLatch> holds = new ArrayList<>();
    private final Set<String> ownIds = new java.util.HashSet<>();
    private boolean previousOriginals, previousPaused, previousAdvanced;
    private String previousQuality;

    @Before public void setUp() throws Exception {
        drainPool();
        previousOriginals = Settings.DOWNLOAD_ORIGINAL_PHOTOS.savedValue();
        previousQuality = Settings.DOWNLOAD_VIDEO_QUALITY.savedValue();
        previousPaused = Setting.isPaused();
        previousAdvanced = SettingsStatus.advancedDownloadsEnabled;
        setPaused(false);
        Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(true);
        Settings.DOWNLOAD_VIDEO_QUALITY.save("highest");
        SettingsStatus.advancedDownloadsEnabled = false;
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
            photoIds().removeAll(ownIds);
            videoIds().removeAll(ownIds);
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(previousOriginals);
            Settings.DOWNLOAD_VIDEO_QUALITY.save(previousQuality);
            SettingsStatus.advancedDownloadsEnabled = previousAdvanced;
            setPaused(previousPaused);
        }
    }

    @Test public void originalsOffAndPauseKeepTheNativeConversion() {
        var post = post("conversion-off", List.of(photo("https://example.com/original")));
        Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(false);
        assertFalse(OriginalPhotos.startImageAsVideo(post));
        Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(true);
        setPaused(true);
        assertFalse(OriginalPhotos.startImageAsVideo(post));
        assertNull(MediaJobScheduler.job("photos " + post.getAid()));
        assertFalse(photoIds().contains(post.getAid()));
    }

    @Test public void ordinaryVideoAndEmptyPhotoInfoHaveDifferentDispatchPolicies() {
        var video = new AdvancedDownloadsTest.VideoData(List.of(
                new AdvancedDownloadsTest.Gear("normal_720_0", 700, "https://example.com/video")));
        VideoPost ordinary = new VideoPost("conversion-ordinary", video);
        ownIds.add(ordinary.getAid());
        videoIds().add(ordinary.getAid());
        assertTrue("A null photo struct must still reach the custom video save",
                VideoDownloads.start(ordinary, RuntimeEnvironment.getApplication()));
        assertFalse("The conversion hook must not take an ordinary video",
                OriginalPhotos.startImageAsVideo(ordinary));

        var empty = post("conversion-empty-info", List.of());
        empty.video = video;
        videoIds().add(empty.getAid());
        assertFalse("A non-null photo struct must not enter the custom video job",
                VideoDownloads.start(empty, RuntimeEnvironment.getApplication()));
        assertTrue("An empty Photo Mode source must stop conversion with an error",
                OriginalPhotos.startImageAsVideo(empty));
        idle();
        assertEquals("The original photos aren't available. No video was saved.",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void missingAndUnusableSourcesStopConversionWithoutQueuingAVideo() {
        for (List<AdvancedDownloadsTest.Photo> images : java.util.Arrays.<List<AdvancedDownloadsTest.Photo>>asList(
                null, List.of(), List.of(photo(null)), List.of(photo("http://example.com/original")),
                List.of(photo("https://example.com/one"), photo(null)))) {
            var post = post("conversion-missing-" + ownIds.size(), images);
            assertTrue("The requested original save must not fall through to MP4 conversion",
                    OriginalPhotos.startImageAsVideo(post));
            idle();
            assertEquals("The original photos aren't available. No video was saved.",
                    ShadowToast.getTextOfLatestToast());
            assertNull(MediaJobScheduler.job("photos " + post.getAid()));
            assertFalse(photoIds().contains(post.getAid()));
        }
    }

    @Test public void liveVideoChoicesStayNativeButSelectedStillsStillUseTheImageJob() {
        var post = post("conversion-live", List.of(photo("https://example.com/still"),
                photo("https://example.com/live").live()));
        photoIds().add(post.getAid());
        assertFalse("The separate Live Photo conversion choice stays native",
                OriginalPhotos.startImageAsVideo(post));
        assertFalse("A selected Live Photo video stays native",
                OriginalPhotos.startPhotos(post, Set.of(1), true));
        assertTrue("The picker only selected the still, so it is an image save",
                OriginalPhotos.startPhotos(post, Set.of(0), true));
        assertTrue("The native image choice can save the Live Photo's still",
                OriginalPhotos.startPhotos(post, Set.of(1), false));
    }

    @Test public void conversionQueuesTheWholeSuppliedPostAndThePickerKeepsItsSelection() throws Exception {
        holdWorkers();
        var post = post("conversion-all-stills", List.of(photo("https://example.com/one"),
                photo("https://example.com/two"), photo("https://example.com/three")));
        assertTrue(OriginalPhotos.startImageAsVideo(post));
        MediaJobScheduler.Job all = MediaJobScheduler.job("photos " + post.getAid());
        assertNotNull(all);
        assertTrue(all.waiting());
        assertEquals("The conversion signature has no index set", 3, all.record.files);
        assertTrue(all.cancel());
        assertFalse(photoIds().contains(post.getAid()));

        assertTrue(OriginalPhotos.startPhotos(post, Set.of(2, 0), true));
        MediaJobScheduler.Job selected = MediaJobScheduler.job("photos " + post.getAid());
        assertNotNull(selected);
        assertEquals("The image job keeps the picker's indices", 2, selected.record.files);
        assertTrue(selected.cancel());
        assertFalse(photoIds().contains(post.getAid()));
    }

    @Test public void cancellingTheQueuedConversionImageJobClearsItsOwnState() throws Exception {
        holdWorkers();
        var images = List.of(photo("https://example.com/one"),
                photo("https://example.com/two"), photo("https://example.com/three"));
        var selections = List.of(Set.of(0), Set.of(0, 2), Set.of(0, 1, 2));
        List<View> previousRoots = SaveNotice.windowRootsForTests;
        Activity previousActivity = Utils.getActivity();
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            SaveNotice.windowRootsForTests = List.of();
            ViewGroup root = owner.get().findViewById(android.R.id.content);
            for (boolean selected : List.of(false, true)) for (int count = 1; count <= 3; count++) {
                var post = post("conversion-cancel-" + selected + "-" + count,
                        selected ? images : images.subList(0, count));
                assertTrue(selected
                        ? OriginalPhotos.startPhotos(post, selections.get(count - 1), true)
                        : OriginalPhotos.startImageAsVideo(post));
                MediaJobScheduler.Job job = MediaJobScheduler.job("photos " + post.getAid());
                assertNotNull(job);
                assertEquals(count, job.record.files);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(SaveNotice.SHEET_SETTLE_MS + 1, TimeUnit.MILLISECONDS);
                assertNotNull("Photo saves must use file microcopy, including one chosen still",
                        find(root, count == 1 ? "Waiting to save one file" : "Waiting to save " + count + " files"));
                assertNull(find(root, "Waiting to save video"));
                assertNull(find(root, "Saving video"));
                TextView cancel = find(root, "Cancel");
                assertNotNull("Every image count offers Cancel while queued", cancel);
                assertEquals(View.VISIBLE, cancel.getVisibility());
                cancel.performClick();
                idle();
                assertFalse(job.waiting());
                assertNull(MediaJobScheduler.job("photos " + post.getAid()));
                assertFalse(photoIds().contains(post.getAid()));
                assertNull(root.findViewWithTag("hushfeed_save_progress"));
                assertEquals("Save cancelled. Nothing was saved.", ShadowToast.getTextOfLatestToast());
            }
        } finally {
            SaveNotice.windowRootsForTests = previousRoots;
            Utils.setActivity(previousActivity);
        }
    }

    @Test public void failedImageTransferEndsItsOwnJobWithoutAFallbackConversion() throws Exception {
        // The URL passes syntax validation but the transport rejects this numeric private
        // destination before opening any network connection. This exercises the async job.
        var post = post("conversion-transfer-failure", List.of(photo("https://127.0.0.1/original")));
        assertTrue(OriginalPhotos.startImageAsVideo(post));
        drainPool();
        idle();
        assertFalse(photoIds().contains(post.getAid()));
        assertNull(MediaJobScheduler.job("photos " + post.getAid()));
        assertEquals("None of the photos could be saved. Try again.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aFullImageQueueReportsFailureAndStillStopsConversion() throws Exception {
        holdWorkers();
        for (int i = 0; i < MediaJobScheduler.MAX_QUEUED_JOBS; i++) {
            assertTrue(MediaJobScheduler.submit("conversion capacity hold", () -> {}));
        }
        Activity previousActivity = Utils.getActivity();
        List<View> previousRoots = SaveNotice.windowRootsForTests;
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            SaveNotice.windowRootsForTests = List.of();
            ViewGroup root = owner.get().findViewById(android.R.id.content);
            for (int count = 1; count <= 3; count++) {
                var post = post("conversion-full-queue-" + count,
                        java.util.Collections.nCopies(count, photo("https://example.com/original")));
                assertTrue(OriginalPhotos.startImageAsVideo(post));
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(SaveNotice.SHEET_SETTLE_MS + 1, TimeUnit.MILLISECONDS);
                assertFalse(photoIds().contains(post.getAid()));
                assertNull(MediaJobScheduler.job("photos " + post.getAid()));
                assertNull("Refused jobs must not leave a progress row", root.findViewWithTag("hushfeed_save_progress"));
                assertEquals("Too many media saves are already running. Try again in a moment.",
                        ShadowToast.getTextOfLatestToast());
            }
        } finally {
            SaveNotice.windowRootsForTests = previousRoots;
            Utils.setActivity(previousActivity);
        }
    }

    private AdvancedDownloadsTest.PhotoPost post(String id, List<AdvancedDownloadsTest.Photo> images) {
        ownIds.add(id);
        return new AdvancedDownloadsTest.PhotoPost(id, images);
    }

    private static AdvancedDownloadsTest.Photo photo(String url) {
        return new AdvancedDownloadsTest.Photo(url);
    }

    public static final class VideoPost {
        public final AdvancedDownloadsTest.Info photoModeImageInfo = null;
        public final AdvancedDownloadsTest.VideoData video;
        private final String aid;
        VideoPost(String aid, AdvancedDownloadsTest.VideoData video) { this.aid = aid; this.video = video; }
        public String getAid() { return aid; }
    }

    private static Set<String> photoIds() { return ReflectionHelpers.getStaticField(OriginalPhotos.class, "ACTIVE"); }
    private static Set<String> videoIds() { return ReflectionHelpers.getStaticField(VideoDownloads.class, "ACTIVE"); }
    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", from(boolean.class, value));
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private void holdWorkers() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        holds.add(hold);
        CountDownLatch started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        for (int i = 0; i < MediaJobScheduler.MAX_RUNNING_JOBS; i++) {
            assertTrue(MediaJobScheduler.submit("conversion worker hold", () -> {
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

    private static TextView find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}

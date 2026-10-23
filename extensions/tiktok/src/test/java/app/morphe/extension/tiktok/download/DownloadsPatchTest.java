package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Video;
import com.ss.android.ugc.aweme.feed.model.VideoUrlModel;

import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DownloadsPatchTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.DOWNLOAD_VIDEO_PATH.save(DownloadDestination.DEFAULT_PATH);
        Settings.DOWNLOAD_PHOTO_PATH.save(DownloadDestination.DEFAULT_PATH);
        Settings.REMOVE_DOWNLOAD_WATERMARK.save(false);
    }

    @After public void tearDown() {
        Settings.DOWNLOAD_VIDEO_PATH.save(DownloadDestination.DEFAULT_PATH);
        Settings.DOWNLOAD_PHOTO_PATH.save(DownloadDestination.DEFAULT_PATH);
        Settings.REMOVE_DOWNLOAD_WATERMARK.save(false);
    }

    @Test public void missingNoWatermarkAddressFallsBackToTheFirstUsableVideoModel() {
        Video video = new Video();
        Address broken = new Address(Collections.emptyList());
        Address h264 = new Address(List.of("https://example.com/h264"));
        Address play = new Address(List.of("https://example.com/play"));
        video.downloadNoWatermarkAddr = broken;
        video.h264PlayAddr = h264;
        video.playAddr = play;

        DownloadsPatch.patchVideoObject(video);

        assertSame(h264, video.downloadNoWatermarkAddr);
    }

    /** 46.9.3 renamed both play addresses to ...Value. */
    public static final class Video4693 extends Video {
        public VideoUrlModel h264PlayAddrValue;
        public VideoUrlModel playAddrValue;
    }

    @Test public void theRenamedPlayAddressesOf4693AreRead() {
        // Reading the old names threw NoSuchFieldError there and the fallback never ran.
        Video4693 video = new Video4693();
        Address play = new Address(List.of("https://example.com/play"));
        video.downloadNoWatermarkAddr = new Address(Collections.emptyList());
        video.playAddrValue = play;

        DownloadsPatch.patchVideoObject(video);

        assertSame(play, video.downloadNoWatermarkAddr);
    }

    @Test public void blankAndThrowingModelsAreSkippedWithoutReplacingAUsableAddress() {
        Video video = new Video();
        Address clean = new Address(List.of("https://example.com/clean"));
        video.downloadNoWatermarkAddr = clean;
        video.h264PlayAddr = new ThrowingAddress();
        video.playAddr = new Address(List.of("https://example.com/play"));

        DownloadsPatch.patchVideoObject(video);

        assertSame(clean, video.downloadNoWatermarkAddr);
        DownloadsPatch.patchVideoObject(null);
    }

    @Test public void pathsAndWatermarkSettingUseTheValidatedDownloadBoundary() {
        Settings.DOWNLOAD_VIDEO_PATH.save("/storage/emulated/0/movies/clips/");
        Settings.DOWNLOAD_PHOTO_PATH.save("Pictures/photos");
        Settings.REMOVE_DOWNLOAD_WATERMARK.save(true);

        assertEquals("Movies/clips", DownloadsPatch.getVideoDownloadPath());
        assertEquals("Pictures/photos", DownloadsPatch.getPhotoDownloadPath());
        assertTrue(DownloadsPatch.shouldRemoveWatermark());

        Settings.DOWNLOAD_VIDEO_PATH.save("../../private");
        assertFalse(DownloadsPatch.getVideoDownloadPath().contains(".."));
    }

    private static class Address extends VideoUrlModel {
        private final List<String> urls;

        Address(List<String> urls) {
            this.urls = urls;
        }

        @Override public List<String> getUrlList() {
            return urls;
        }
    }

    private static final class ThrowingAddress extends Address {
        ThrowingAddress() {
            super(List.of());
        }

        @Override public List<String> getUrlList() {
            throw new IllegalStateException("native model unavailable");
        }
    }
}

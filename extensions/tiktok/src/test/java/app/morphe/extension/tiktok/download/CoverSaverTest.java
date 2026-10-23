/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Looper;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/** Which cover a save takes, what it's called, and when a Download saves one too. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class CoverSaverTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void reset() {
        Settings.DOWNLOAD_COVER.resetToDefault();
        Settings.SAVE_COVER_BUTTON.resetToDefault();
        SettingsStatus.advancedDownloadsEnabled = false;
        PausedProcess.set(false);
    }

    @Test public void theLargestCoverThatNamesItsSizeIsTaken() {
        Post post = new Post("7350000000000000001");
        post.video.originCoverValue = new UrlModel(720, 1280, "https://p16.example/origin.heic");
        post.video.cover = new UrlModel(1080, 1920, "https://p16.example/cover.jpeg", "https://p19.example/cover.jpeg");
        post.video.lowResCover = new UrlModel(100, 178, "https://p16.example/low.jpeg");
        assertEquals(List.of("https://p16.example/cover.jpeg", "https://p19.example/cover.jpeg"), CoverSaver.coverUrls(post));

        post.video.cover = new UrlModel(720, 720, "https://p16.example/cover.jpeg");
        assertEquals(List.of("https://p16.example/origin.heic"), CoverSaver.coverUrls(post));
    }

    @Test public void aCoverThatNamesNoSizeLosesToOneThatDoesAndOrderBreaksTheRest() {
        Post post = new Post("7350000000000000002");
        post.video.originCoverValue = new UrlModel(0, 0, "https://p16.example/origin.jpeg");
        post.video.cover = new UrlModel(720, 720, "https://p16.example/cover.jpeg");
        assertEquals(List.of("https://p16.example/cover.jpeg"), CoverSaver.coverUrls(post));

        post.video.cover = new UrlModel(0, 0, "https://p16.example/cover.jpeg");
        assertEquals("no sizes anywhere: the original first", List.of("https://p16.example/origin.jpeg"), CoverSaver.coverUrls(post));
        assertEquals(0, CoverSaver.area(new UrlModel(720, 0)));
        assertEquals(0, CoverSaver.area(null));
    }

    @Test public void aCoverWithNoSafeAddressIsSkippedAndAnimatedCoversStayOut() {
        Post post = new Post("7350000000000000003");
        post.video.originCoverValue = new UrlModel(1080, 1920, "http://p16.example/origin.jpeg");
        post.video.cover = new UrlModel(720, 720, "https://p16.example/cover.jpeg");
        assertEquals(List.of("https://p16.example/cover.jpeg"), CoverSaver.coverUrls(post));

        Post animated = new Post("7350000000000000004");
        animated.video.animatedCover = new UrlModel(1080, 1920, "https://p16.example/animated.webp");
        assertTrue(CoverSaver.coverUrls(animated).isEmpty());
    }

    @Test public void aPhotoPostOrNoPostHasNoCoverAndTheLongPressSaysSo() {
        Post photos = new Post("7350000000000000005");
        photos.video.cover = new UrlModel(720, 720, "https://p16.example/cover.jpeg");
        photos.photoModeImageInfo = new Object();
        assertTrue(CoverSaver.coverUrls(photos).isEmpty());
        assertTrue(CoverSaver.coverUrls(null).isEmpty());

        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            ShadowToast.reset();
            CoverSaver.save(controller.get(), photos);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(L10n.t("This video has no cover to save"), ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void theCoverIsNamedAfterItsVideo() {
        Post post = new Post("7350000000000000006");
        String video = DownloadFilenameFormatter.formatSelectedVideoName(post);
        String stem = video.substring(0, video.lastIndexOf('.'));
        assertEquals(stem + "_cover.jpg", DownloadFilenameFormatter.formatCoverName(post, "jpg"));
        assertEquals(stem + "_cover.webp", DownloadFilenameFormatter.formatCoverName(post, "webp"));
    }

    @Test public void aDownloadSavesTheCoverOnlyWithTheSwitchOnAndNeverUnderPause() {
        assertFalse(CoverSaver.besideEnabled());
        Settings.DOWNLOAD_COVER.save(true);
        assertFalse("needs Advanced downloads", CoverSaver.besideEnabled());
        SettingsStatus.advancedDownloadsEnabled = true;
        assertTrue(CoverSaver.besideEnabled());
        PausedProcess.set(true);
        assertFalse(CoverSaver.besideEnabled());
        PausedProcess.set(false);

        // Beside a Download it keeps quiet about a post with no cover; the video's own save speaks.
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            ShadowToast.reset();
            CoverSaver.beside(controller.get(), new Post("7350000000000000007"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(ShadowToast.getTextOfLatestToast());
        }
    }

    /** The share sheet's Save cover takes the cover over, so Download saves only the video (#146). */
    @Test public void withTheShareSheetButtonOnADownloadLeavesTheCoverAlone() {
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_COVER.save(true);
        assertTrue(CoverSaver.besideEnabled());
        assertFalse(CoverSaver.buttonEnabled());
        Settings.SAVE_COVER_BUTTON.save(true);
        assertFalse(CoverSaver.besideEnabled());
        assertTrue(CoverSaver.buttonEnabled());
        Settings.DOWNLOAD_COVER.save(false);
        assertTrue("the button doesn't need the old switch", CoverSaver.buttonEnabled());
        SettingsStatus.advancedDownloadsEnabled = false;
        assertFalse(CoverSaver.buttonEnabled());

        Post post = new Post("7350000000000000008");
        assertFalse(CoverSaver.hasCover(post));
        post.video.cover = new UrlModel(720, 720, "https://p16.example/cover.jpeg");
        assertTrue(CoverSaver.hasCover(post));
    }

    /** With the already-saved check on the video's save makes the cover, so Open adds no second one. */
    @Test public void theAlreadySavedCheckTakesTheCoverWithIt() {
        boolean before = Settings.CHECK_SAVED_VIDEOS.get();
        try {
            Settings.CHECK_SAVED_VIDEOS.save(false);
            assertFalse(VideoDownloads.savesTheCover());
            Settings.CHECK_SAVED_VIDEOS.save(true);
            assertTrue(VideoDownloads.savesTheCover());
        } finally {
            Settings.CHECK_SAVED_VIDEOS.save(before);
        }
    }

    public static final class UrlModel {
        public List<String> urlList;
        public int width, height;
        UrlModel(int width, int height, String... urls) {
            this.width = width;
            this.height = height;
            urlList = Arrays.asList(urls);
        }
        public List<String> getUrlList() { return urlList; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
    }

    public static final class Video {
        public UrlModel originCoverValue, cover, lowResCover, animatedCover;
        public UrlModel getOriginCover() { return originCoverValue; }
        public UrlModel getCover() { return cover; }
        public UrlModel getLowResCover() { return lowResCover; }
        public UrlModel getAnimatedCover() { return animatedCover; }
    }

    public static final class Author {
        public String uniqueId = "dancer";
        public String getUniqueId() { return uniqueId; }
        public String getNickname() { return "Dancer"; }
    }

    public static final class Post {
        public final Video video = new Video();
        public final Author author = new Author();
        public Object photoModeImageInfo;
        public String aid;
        public long createTime = 1700000000L;
        Post(String aid) { this.aid = aid; }
        public Video getVideo() { return video; }
        public Object getPhotoModeImageInfo() { return photoModeImageInfo; }
        public String getAid() { return aid; }
        public Author getAuthor() { return author; }
        public long getCreateTime() { return createTime; }
    }
}

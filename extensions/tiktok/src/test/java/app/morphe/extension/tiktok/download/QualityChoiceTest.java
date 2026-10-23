/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.text.format.Formatter;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.download.AdvancedDownloadsTest.Address;
import app.morphe.extension.tiktok.download.AdvancedDownloadsTest.Gear;
import app.morphe.extension.tiktok.download.AdvancedDownloadsTest.VideoData;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

/** Ask for the quality on each save: what the choice lists, and when it asks at all. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class QualityChoiceTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private boolean previousAdvanced;
    private ActivityController<SaveNoticeTest.HostActivity> controller;
    private Activity activity;

    private static final Gear FULL = new Gear("normal_1080_0", 400, "https://example.com/1080");
    private static final Gear FULL_HEVC = new Gear("adapt_lowest_1080_1", 300, "https://example.com/1080-hevc").codec(1);
    private static final Gear MID = new Gear("normal_720_0", 200, "https://example.com/720");
    /** Reads the same as MID, so it isn't offered twice. */
    private static final Gear MID_TWIN = new Gear("lower_720_0", 200, "https://example.com/720-twin");
    private static final Gear BVC2 = new Gear("adapt_540_2", 150, "https://example.com/540-bvc2").codec(2);
    private static final Gear NO_ADDRESS = new Gear("normal_2160_0", 900, null);
    private static final Gear UNSIZED = new Gear("unnamed", 0, "https://example.com/unsized");

    /** A video post: the id a save is keyed on, and its renditions beside TikTok's own file. */
    public static final class VideoPost {
        public final VideoData video;
        private final String aid;
        VideoPost(String aid, Gear... gears) {
            this.aid = aid;
            video = new VideoData(List.of(gears));
            video.downloadNoWatermarkAddr = new Address("https://example.com/clean", 5000);
        }
        public String getAid() { return aid; }
        public VideoData getVideo() { return video; }
    }

    @Before public void setUp() {
        previousAdvanced = SettingsStatus.advancedDownloadsEnabled;
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_ASK_QUALITY.save(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        host();
        QualityChoice.resetForTests();
        ShadowToast.reset();
    }

    @After public void tearDown() {
        AlertDialog open = ShadowAlertDialog.getLatestAlertDialog();
        if (open != null) open.dismiss();
        idle();
        QualityChoice.resetForTests();
        Settings.DOWNLOAD_ASK_QUALITY.resetToDefault();
        SettingsStatus.advancedDownloadsEnabled = previousAdvanced;
        Utils.setActivity(null);
    }

    private void host() {
        controller = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible();
        activity = controller.get();
        Utils.setActivity(activity);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private String about(String what, long bytes) {
        return what + ", about " + Formatter.formatShortFileSize(activity, bytes);
    }

    private static List<String> labels(List<QualityChoice.Option> options) {
        List<String> labels = new ArrayList<>();
        for (QualityChoice.Option option : options) labels.add(option.label);
        return labels;
    }

    @Test public void theChoiceListsAutomaticThenWhatASaveCanPublish() {
        VideoPost post = new VideoPost("1", MID_TWIN, BVC2, FULL_HEVC, UNSIZED, NO_ADDRESS, MID, FULL);
        List<QualityChoice.Option> options = QualityChoice.options(post.video, activity);
        assertEquals("tallest first, H.264 before HEVC, no ByteVC2, nothing without an address, no twins", List.of(
                about("Automatic (TikTok's file)", 5000),
                about("1080p H.264", 4000),
                about("1080p HEVC", 3000),
                about("720p H.264", 2000),
                "Unknown resolution H.264, size unknown"), labels(options));
        assertSame(VideoDownloads.AUTOMATIC, options.get(0).pick);
        assertSame(FULL, options.get(1).pick);
        assertSame(FULL_HEVC, options.get(2).pick);
        assertSame("the first of two that read the same", MID_TWIN, options.get(3).pick);
        assertSame(UNSIZED, options.get(4).pick);
    }

    @Test public void offTheSaveGoesOnAsBefore() {
        Settings.DOWNLOAD_ASK_QUALITY.save(false);
        assertFalse(QualityChoice.offer(new VideoPost("2", FULL, MID), activity));
        Settings.DOWNLOAD_ASK_QUALITY.save(true);
        SettingsStatus.advancedDownloadsEnabled = false;
        assertFalse("without advanced downloads in the build", QualityChoice.offer(new VideoPost("2", FULL, MID), activity));
        idle();
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void nothingToChooseBetweenAsksNothing() {
        assertFalse("only ByteVC2 and a rendition without an address beside Automatic",
                QualityChoice.offer(new VideoPost("3", BVC2, NO_ADDRESS), activity));
        assertFalse("a post without an id", QualityChoice.offer(new VideoPost(null, FULL, MID), activity));
        AdvancedDownloadsTest.PhotoPost photos = new AdvancedDownloadsTest.PhotoPost("4",
                List.of(new AdvancedDownloadsTest.Photo("https://example.com/photo")));
        photos.video = new VideoData(List.of(FULL, MID));
        assertFalse("a photo post", QualityChoice.offer(photos, activity));
        idle();
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void downloadAsksOnceAndCancelLetsTheNextPressAskAgain() {
        VideoPost post = new VideoPost("5", FULL, MID);
        assertTrue(QualityChoice.offer(post, activity));
        idle();
        AlertDialog choice = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(choice.isShowing());
        ShadowAlertDialog shadow = Shadows.shadowOf(choice);
        assertEquals("Save in which quality?", shadow.getTitle().toString());
        assertEquals(labels(QualityChoice.options(post.video, activity)), toStrings(shadow.getItems()));

        assertTrue("a second press while it's open still takes the save", QualityChoice.offer(post, activity));
        idle();
        assertEquals("The quality choice is still open", ShadowToast.getTextOfLatestToast());
        assertSame("and asks no second time", choice, ShadowAlertDialog.getLatestAlertDialog());

        choice.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertFalse(choice.isShowing());
        assertTrue(QualityChoice.offer(post, activity));
        idle();
        assertNotSame("Cancel let the next press ask again", choice, ShadowAlertDialog.getLatestAlertDialog());
    }

    /** A screen torn down under the choice, as a theme change does, doesn't leave it open for good. */
    @Test public void aScreenTornDownUnderTheChoiceLetsTheNextPressAskAgain() {
        VideoPost post = new VideoPost("7", FULL, MID);
        assertTrue(QualityChoice.offer(post, activity));
        idle();
        AlertDialog choice = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(choice.isShowing());
        controller.pause().stop().destroy();
        host();
        ShadowToast.reset();
        assertTrue(QualityChoice.offer(post, activity));
        idle();
        assertFalse(ShadowToast.showedToast("The quality choice is still open"));
        assertNotSame(choice, ShadowAlertDialog.getLatestAlertDialog());
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    }

    /** Separate picture and sound tracks are offered only with a sound to put beside the picture. */
    @Test public void separateTracksAreOfferedOnlyWithASoundToPairOrWithoutSound() {
        boolean muted = Settings.DOWNLOAD_WITHOUT_SOUND.get();
        try {
            Settings.DOWNLOAD_WITHOUT_SOUND.save(false);
            VideoPost post = new VideoPost("8", FULL, MID);
            post.video.dash = true;
            assertEquals("no sound to pair: Automatic alone, so nothing is asked",
                    1, QualityChoice.options(post.video, activity).size());
            assertFalse(QualityChoice.offer(post, activity));

            Settings.DOWNLOAD_WITHOUT_SOUND.save(true);
            assertEquals("saved without sound, the picture alone is a whole file",
                    3, QualityChoice.options(post.video, activity).size());

            Settings.DOWNLOAD_WITHOUT_SOUND.save(false);
            post.video.bitRateAudio = List.of(new AdvancedDownloadsTest.Audio("audio", 128));
            assertEquals("with a sound to pair", 3, QualityChoice.options(post.video, activity).size());
        } finally {
            Settings.DOWNLOAD_WITHOUT_SOUND.save(muted);
        }
    }

    /** TikTok's save has stood aside for the choice, so a pick that can't be saved says so itself. */
    @Test public void aPickThatCantBeSavedSaysSoAndNeverHandsBackToTikTok() {
        boolean details = Settings.DOWNLOAD_DETAILS.get(), check = Settings.CHECK_SAVED_VIDEOS.get();
        boolean muted = Settings.DOWNLOAD_WITHOUT_SOUND.get();
        try {
            Settings.DOWNLOAD_DETAILS.save(false);
            Settings.CHECK_SAVED_VIDEOS.save(false);
            Settings.DOWNLOAD_WITHOUT_SOUND.save(false);
            // Separate tracks with no sound to put beside the picture.
            VideoPost post = new VideoPost("6", FULL, MID);
            post.video.dash = true;
            assertTrue(VideoDownloads.startPicked(post, activity, MID));
            assertEquals("This video isn't available as a complete file. Try again later.",
                    ShadowToast.getTextOfLatestToast());
            assertFalse(ShadowToast.showedToast(
                    "This quality isn't available as a complete file, so TikTok's own save runs instead"));
            assertFalse("nothing holds the video afterwards", VideoDownloads.saving("6"));
        } finally {
            Settings.DOWNLOAD_DETAILS.save(details);
            Settings.CHECK_SAVED_VIDEOS.save(check);
            Settings.DOWNLOAD_WITHOUT_SOUND.save(muted);
        }
    }

    private static List<String> toStrings(CharSequence[] items) {
        List<String> strings = new ArrayList<>();
        for (CharSequence item : Arrays.asList(items)) strings.add(item.toString());
        return strings;
    }
}

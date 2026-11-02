/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The template preview names what a save would, because it asks the same code: each line is
 * checked against the downloader it describes, run on a post with the preview's own details.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DownloadNamePreviewTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();

    /** Noon UTC on 2023-11-14, the same date in every time zone the tests might run in. */
    private static final long NOW = 1_699_963_200_000L;

    private final DownloadDetailsTest.Post post =
            new DownloadDetailsTest.Post(DownloadNamePreview.CREATOR, DownloadNamePreview.VIDEO_ID);

    @Before public void setUp() {
        post.createTime = NOW / 1000L;
        SettingsStatus.advancedDownloadsEnabled = true;
        SettingsStatus.downloadEnabled = true;
        SettingsStatus.subtitleToolsEnabled = false;
        Settings.DOWNLOAD_VIDEO_PATH.save("DCIM/Clips");
        Settings.DOWNLOAD_PHOTO_PATH.save("Pictures/Posts");
    }

    @After public void tearDown() {
        SettingsStatus.advancedDownloadsEnabled = false;
        SettingsStatus.downloadEnabled = false;
        SettingsStatus.subtitleToolsEnabled = false;
        for (var setting : Arrays.asList(Settings.DOWNLOAD_VIDEO_PATH, Settings.DOWNLOAD_PHOTO_PATH,
                Settings.DOWNLOAD_STICKER_PATH, Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE,
                Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE, Settings.DOWNLOAD_COMMENT_MEDIA_FILENAME_TEMPLATE)) {
            setting.resetToDefault();
        }
        Settings.DOWNLOAD_DETAILS.resetToDefault();
        Settings.DOWNLOAD_AUDIO_TRACK.resetToDefault();
        Settings.DOWNLOAD_SUBTITLES.resetToDefault();
    }

    private static String run(char c, int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    /**
     * A restored backup can hold a pattern of a million dots, and the preview names it on the main
     * thread. Stripping them one substring at a time copied the rest of the text per dot, over a
     * minute of work at this length; one cut takes milliseconds and names the file as before.
     */
    @Test public void aPatternOfStrayDotsIsTrimmedInOnePass() {
        String dots = run('.', 1_000_000);
        long started = System.nanoTime();
        assertEquals("video.mp4", DownloadFilenameFormatter.formatSourceName(
                dots, "creator", "1", NOW, 1, "mp4", false));
        assertEquals("a.mp4", DownloadFilenameFormatter.formatSourceName(
                dots + "a" + dots + " . .", "creator", "1", NOW, 1, "mp4", false));
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        assertTrue("trimming the dots took " + elapsed + " ms", elapsed < 5_000L);
        // Ordinary names come out the same as before.
        assertEquals("name.mp4", DownloadFilenameFormatter.formatSourceName(
                "..name. . ", "creator", "1", NOW, 1, "mp4", false));
        assertEquals("名前 v1.0.mp4", DownloadFilenameFormatter.formatSourceName(
                "名前 v1.0...", "creator", "1", NOW, 1, "mp4", false));
    }

    private static String line(String preview, String label) {
        for (String line : preview.split("\n")) {
            if (line.startsWith(label)) return line.substring(label.length());
        }
        throw new AssertionError("no \"" + label + "\" line in:\n" + preview);
    }

    /** Where TikTok's own downloader puts a staging file named tiktok_name.mp4 for the post. */
    private String tiktokSave(String template) throws Exception {
        Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.save(template);
        File staging = new File(RuntimeEnvironment.getApplication().getCacheDir(),
                DownloadNamePreview.TIKTOK_NAME + ".mp4");
        assertTrue(staging.createNewFile() || staging.isFile());
        try {
            DownloadFilenameFormatter.registerDownloadedMediaName(staging.getPath(), post);
            String folder = DownloadFilenameFormatter.getVideoDestination(staging.getName());
            return folder + "/" + DownloadFilenameFormatter.consumeDestinationName(staging.getName());
        } finally {
            assertTrue(staging.delete());
        }
    }

    @Test public void eachVideoLineMatchesItsDownloader() throws Exception {
        List<String> templates = Arrays.asList(
                "{creator}_{video_id}",
                "{creator}/{date}_{video_id}",
                "{index}_{creator}",
                "日本語 {video_id}",
                "x".repeat(300) + "{video_id}",
                "../{creator}",
                "{original}_{date}");
        for (String template : templates) {
            String preview = DownloadNamePreview.video(template, NOW);
            Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.save(template);
            assertEquals(template,
                    DownloadFilenameFormatter.destinationPath(post, false) + "/"
                            + DownloadFilenameFormatter.formatSelectedVideoName(post),
                    line(preview, "Metra's downloader: "));
            assertEquals(template, tiktokSave(template), line(preview, "TikTok's downloader: "));
        }
    }

    @Test public void anEmptyTemplateKeepsTikToksNameWhileHushfeedUsesItsDefault() throws Exception {
        String preview = DownloadNamePreview.video("", NOW);

        assertEquals("DCIM/Clips/creator_name_2023-11-14_7312345678901234567.mp4",
                line(preview, "Metra's downloader: "));
        assertEquals("DCIM/Clips/TikTok's own name", line(preview, "TikTok's downloader: "));
        assertEquals("TikTok really does keep its own name",
                "DCIM/Clips/tiktok_name.mp4", tiktokSave(""));
    }

    @Test @Config(sdk = 35)
    public void sidecarsFollowTheVideoTheWaySavesDo() {
        SettingsStatus.subtitleToolsEnabled = true;
        Settings.DOWNLOAD_DETAILS.save(true);
        Settings.DOWNLOAD_AUDIO_TRACK.save(true);
        Settings.DOWNLOAD_SUBTITLES.save(true);

        String preview = DownloadNamePreview.video("{creator}/{video_id}", NOW);

        // A TXT beside the video moves the pair to Download on scoped storage.
        assertEquals("Download/Clips/creator_name/7312345678901234567.mp4", line(preview, "Metra's downloader: "));
        assertEquals("Music/Clips/creator_name/7312345678901234567.m4a", line(preview, "Sound: "));
        assertEquals("Download/Clips/creator_name/7312345678901234567.txt", line(preview, "Details: "));
        assertEquals("Download/Clips/creator_name/7312345678901234567.en.srt", line(preview, "Subtitles: "));
        assertEquals("TikTok's own downloader keeps the chosen folder",
                "DCIM/Clips/creator_name/7312345678901234567.mp4", line(preview, "TikTok's downloader: "));
        assertTrue(preview.endsWith("If that name is taken, Android adds a number to the new file."));
    }

    @Test public void subtitlesAloneMoveTheVideoOnlyOnScopedStorage() {
        SettingsStatus.subtitleToolsEnabled = true;
        Settings.DOWNLOAD_SUBTITLES.save(true);

        String preview = DownloadNamePreview.video("{video_id}", NOW);

        assertEquals("DCIM/Clips/7312345678901234567.mp4", line(preview, "Metra's downloader: "));
        assertEquals("DCIM/Clips/7312345678901234567.en.srt", line(preview, "Subtitles: "));
        assertFalse("the switch is off", preview.contains("Sound: "));
        assertTrue(preview.endsWith("If that name is taken, Metra adds a number to the new file."));
    }

    @Test public void photosKeepTheirNumbersAndAMisspelledTokenIsNamed() {
        String preview = DownloadNamePreview.photo("{creator}_{vidoe_id}", NOW);

        assertEquals("Pictures/Posts/creator_name_{vidoe_id}_1.jpg, creator_name_{vidoe_id}_2.jpg",
                line(preview, "Metra's downloader: "));
        assertEquals("Pictures/Posts/creator_name_{vidoe_id}.jpg", line(preview, "TikTok's downloader: "));
        assertEquals("{vidoe_id}", line(preview, "Not a part Metra fills in, so it's kept as typed: "));
        Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save("{creator}_{vidoe_id}");
        assertEquals("creator_name_{vidoe_id}_2.jpg", DownloadFilenameFormatter.formatOriginalPhotoName(post, 2, "jpg"));
    }

    @Test public void aMistypedTokenIsNamedWhateverItsShape() {
        assertEquals(Arrays.asList("{1}", "{video-id}", "{video id}", "{date2}", "{album}"),
                DownloadNamePreview.unknownTokens("{ } {1} {video-id} {video id}{date2} {creator}{album}{album}",
                        DownloadNamePreview.TOKENS));
        assertFalse("braces around spaces aren't a try at a token",
                DownloadNamePreview.video("{creator} { }", NOW).contains("kept as typed"));
        String preview = DownloadNamePreview.video("{creator}_{video-id}", NOW);
        assertEquals("{video-id}", line(preview, "Not a part Metra fills in, so it's kept as typed: "));
        assertEquals("DCIM/Clips/creator_name_{video-id}.mp4", line(preview, "Metra's downloader: "));
    }

    @Test public void tokensOnlyTikToksDownloaderFillsAreNamedForHushfeeds() throws Exception {
        String preview = DownloadNamePreview.video("{original}_{media_id}_{vidoe}", NOW);

        assertEquals("{vidoe}", line(preview, "Not a part Metra fills in, so it's kept as typed: "));
        assertEquals("{original}, {media_id}", line(preview, "Metra's downloader doesn't fill this in, so it's kept as typed: "));
        Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.save("{original}_{media_id}_{vidoe}");
        assertEquals("what Metra's downloader really saves", "{original}_{media_id}_{vidoe}.mp4",
                DownloadFilenameFormatter.formatSelectedVideoName(post));
        assertTrue("and TikTok's fills {original}",
                tiktokSave("{original}_{media_id}_{vidoe}").contains(DownloadNamePreview.TIKTOK_NAME + "_"));

        SettingsStatus.advancedDownloadsEnabled = false;
        assertFalse("without Metra's downloader there's nothing to say about it",
                DownloadNamePreview.video("{original}", NOW).contains("kept as typed"));
        assertFalse("the comment media saver fills both",
                DownloadNamePreview.commentMedia("{original}_{media_id}", NOW).contains("kept as typed"));
    }

    @Test public void onlyTheDownloadersInTheBundleGetALine() {
        SettingsStatus.advancedDownloadsEnabled = false;
        String preview = DownloadNamePreview.video("{video_id}", NOW);

        assertFalse(preview.contains("Metra's downloader: "));
        assertTrue(preview.contains("TikTok's downloader: "));
    }

    @Test public void commentMediaFillsWhatItHasAndSaysUnknownForTheRest() {
        String preview = DownloadNamePreview.commentMedia("{date}_{media_id}_{creator}_{vidoe}", NOW);

        String sticker = DownloadDestination.resolve(Settings.DOWNLOAD_STICKER_PATH.get(), DownloadDestination.Kind.STICKER);
        // A sticker has no creator, and the saver writes "unknown" for one, as the preview does.
        assertEquals(sticker + "/2023-11-14_7f3a9c21_unknown_{vidoe}.png", line(preview, "Stickers: "));
        assertEquals("Pictures/Posts/2023-11-14_7f3a9c21-live_unknown_{vidoe}.mp4", line(preview, "Live photo clips: "));
        assertEquals("{vidoe}", line(preview, "Not a part Metra fills in, so it's kept as typed: "));
    }
}

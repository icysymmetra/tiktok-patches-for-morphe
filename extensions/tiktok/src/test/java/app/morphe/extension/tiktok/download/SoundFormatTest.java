/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.os.Looper;
import android.preference.ListPreference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory;

import java.io.File;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodecList;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.util.DataSource;

/**
 * Which file a saved sound becomes. Opus needs Android 10's Ogg muxer and an Opus encoder, so a
 * phone without either keeps today's .m4a whatever was picked, and the row says why.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {TrackMuxerTest.SampleExtractor.class, TrackMuxerTest.RecordingMuxer.class})
public class SoundFormatTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder files = new TemporaryFolder();

    private static final String APPLIES = "Applies to Save the sound as well and to Save the original sound on a long "
            + "press. Opus makes a smaller file, but the sound is encoded again from TikTok's copy.";
    private static final String NEEDS_ANDROID_10 = "Opus needs Android 10 or later, so sounds save as TikTok sent them.";

    @Before public void prepare() {
        Utils.setActivity(null);
        TrackMuxerTest.SampleExtractor.useDefaultAudio();
        TrackMuxerTest.RecordingMuxer.resetRecording();
    }

    @After public void finish() {
        SettingsStatus.advancedDownloadsEnabled = false;
        Settings.DOWNLOAD_SOUND_FORMAT.resetToDefault();
        Settings.DOWNLOAD_AUDIO_TRACK.resetToDefault();
        Settings.DOWNLOAD_VIDEO_PATH.resetToDefault();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void addOpusEncoder() {
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
                .setName("c2.android.opus.encoder")
                .setIsEncoder(true)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(MediaFormat.createAudioFormat(OpusTranscoder.MIME, 48000, 2))
                        .setIsEncoder(true)
                        .build())
                .build());
    }

    private static ListPreference row() {
        try (var controller = Robolectric.buildActivity(AdvancedDownloadsTest.TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.advancedDownloadsEnabled = true;
            var screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new DownloadsPreferenceCategory(activity, screen);
            return (ListPreference) screen.findPreference(Settings.DOWNLOAD_SOUND_FORMAT.key);
        }
    }

    @Test public void theEndingIsSwappedAndOnlyTheEnding() {
        assertEquals("dancer-7712345.ogg", SoundFormat.withExtension("dancer-7712345.m4a", "ogg"));
        assertEquals("a dot in the folder isn't the file's ending",
                "creator.name/clip.ogg", SoundFormat.withExtension("creator.name/clip", "ogg"));
        assertEquals("v1.2 mix.ogg", SoundFormat.withExtension("v1.2 mix.m4a", "ogg"));
        assertEquals(".hidden.ogg", SoundFormat.withExtension(".hidden", "ogg"));
    }

    @Test public void anAndroid9PhoneKeepsM4aWhateverWasPicked() throws Exception {
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_AUDIO_TRACK.save(true);
        Settings.DOWNLOAD_SOUND_FORMAT.save(SoundFormat.OPUS);
        assertFalse(SoundFormat.opusPossible());
        assertFalse(SoundFormat.opus());
        assertFalse("the row stayed open on Android 9", Settings.DOWNLOAD_SOUND_FORMAT.isAvailable());

        String folder = "DCIM/sound-format-" + System.nanoTime();
        Settings.DOWNLOAD_VIDEO_PATH.save(folder);
        File source = files.newFile("source.mp4");
        Files.write(source.toPath(), new byte[]{70, 71, 72});
        byte[] audio = {91, 92, 93};
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(source.getAbsolutePath()),
                MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2), audio);
        File destination = new File(Environment.getExternalStorageDirectory(), folder);
        try {
            assertTrue(AudioDownloads.write(RuntimeEnvironment.getApplication(), "clip.m4a", source, folder, false));
            assertEquals("the sound was not copied into an MP4",
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, TrackMuxerTest.RecordingMuxer.outputFormat);
            assertArrayEquals(audio, Files.readAllBytes(new File(destination, "clip.m4a").toPath()));
            assertFalse(new File(destination, "clip.ogg").exists());
        } finally {
            File[] outputs = destination.listFiles();
            if (outputs != null) for (File output : outputs) assertTrue(output.delete());
            if (destination.exists()) assertTrue(destination.delete());
        }
    }

    @Test public void theAndroid9RowSaysWhyOpusIsntThere() {
        ListPreference row = row();
        assertEquals(SoundFormat.ORIGINAL, row.getValue());
        assertEquals("As TikTok sent it (M4A or MP3)\n" + NEEDS_ANDROID_10, row.getSummary().toString());
    }

    /**
     * An encode that fails hands the save back to the original format with a word on why, rather
     * than losing the sound. Android 9 is a failure Robolectric can produce without a codec.
     */
    @Test
    public void aFailedEncodeIsSavedAsTikTokSentItAndSaysSo() throws Exception {
        File source = files.newFile("sound.m4a");
        File output = files.newFile("sound.ogg");
        ShadowToast.reset();
        assertFalse(OpusTranscoder.transcodeOrKeep(source, output));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(L10n.t("Opus didn't work on this phone. Saving the sound as TikTok sent it instead."),
                ShadowToast.getTextOfLatestToast());
    }

    @Test @Config(sdk = 35)
    public void androidTenAndLaterNeedAnOpusEncoderToo() {
        Settings.DOWNLOAD_SOUND_FORMAT.save(SoundFormat.OPUS);
        assertFalse("no encoder, no Opus", SoundFormat.opusPossible());
        assertFalse(SoundFormat.opus());
        assertFalse(Settings.DOWNLOAD_SOUND_FORMAT.isAvailable());
    }

    /**
     * Its own test: MediaCodecList reads the codecs once per process and keeps them, so an
     * encoder added after a lookup isn't seen until the shadow's reset between tests.
     */
    @Test @Config(sdk = 35)
    public void anOpusEncoderMakesOpusPossible() {
        addOpusEncoder();
        Settings.DOWNLOAD_SOUND_FORMAT.save(SoundFormat.OPUS);
        assertTrue(SoundFormat.opusPossible());
        assertTrue(Settings.DOWNLOAD_SOUND_FORMAT.isAvailable());
        assertTrue(SoundFormat.opus());
        Settings.DOWNLOAD_SOUND_FORMAT.save(SoundFormat.ORIGINAL);
        assertFalse("M4A was picked", SoundFormat.opus());
    }

    @Test @Config(sdk = 35)
    public void theRowSaysWhatItCoversAndThePreviewNamesTheOgg() {
        addOpusEncoder();
        Settings.DOWNLOAD_SOUND_FORMAT.save(SoundFormat.OPUS);
        ListPreference row = row();
        assertEquals("Opus (.ogg), a smaller file\n" + APPLIES, row.getSummary().toString());

        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_AUDIO_TRACK.save(true);
        Settings.DOWNLOAD_VIDEO_PATH.save("DCIM/Clips");
        String preview = DownloadNamePreview.video("{creator}/{video_id}", 1_699_963_200_000L);
        assertTrue(preview, preview.contains("Sound: Music/Clips/creator_name/7312345678901234567.ogg"));

        Settings.DOWNLOAD_SOUND_FORMAT.save(SoundFormat.ORIGINAL);
        preview = DownloadNamePreview.video("{creator}/{video_id}", 1_699_963_200_000L);
        assertTrue(preview, preview.contains("Sound: Music/Clips/creator_name/7312345678901234567.m4a"));
    }
}

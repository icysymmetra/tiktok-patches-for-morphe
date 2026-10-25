package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.media.MediaFormat;
import android.os.Environment;
import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.util.DataSource;

/** Runs terminal refusals through the same fetch/write boundaries as counted saves. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {TrackMuxerTest.SampleExtractor.class, TrackMuxerTest.RecordingMuxer.class})
public class MediaStopPropagationTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder files = new TemporaryFolder();
    private static final long FLOOR = MediaBudget.MIN_FREE_BYTES + MediaBudget.PUBLISH_OVERHEAD_BYTES;
    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10,
            0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private String previousVideoPath;

    @Before public void prepare() {
        Utils.setActivity(null);
        previousVideoPath = Settings.DOWNLOAD_VIDEO_PATH.get();
        TrackMuxerTest.SampleExtractor.useDefaultAudio();
        TrackMuxerTest.RecordingMuxer.resetRecording();
    }

    @After public void finish() {
        SettingsStatus.advancedDownloadsEnabled = false;
        Settings.DOWNLOAD_AUDIO_TRACK.save(false);
        Settings.DOWNLOAD_VIDEO_PATH.save(previousVideoPath);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void aStreamingDiskRefusalStopsTheBatchBeforeAnotherMirrorOrFileAndCanRecover()
            throws Exception {
        byte[] body = Arrays.copyOf(PNG, 2 * 1024 * 1024);
        File real = files.newFile("photo.tmp");
        AtomicBoolean shrinking = new AtomicBoolean(true);
        AtomicInteger spaceReads = new AtomicInteger();
        File volume = new File(files.getRoot(), "volume") {
            @Override public boolean exists() { return true; }
            @Override public long getUsableSpace() {
                if (!shrinking.get()) return 1024L * 1024 * 1024;
                int read = spaceReads.getAndIncrement();
                if (read == 0) return FLOOR + MediaBudget.UNKNOWN_TRANSFER_RESERVATION_BYTES;
                return FLOOR + MediaBudget.STREAM_SPACE_CHECK_BYTES - (read == 1 ? 0 : 1);
            }
        };
        File target = new File(real.getPath()) {
            @Override public File getParentFile() { return volume; }
        };
        AtomicInteger mirrors = new AtomicInteger(), steps = new AtomicInteger();
        MediaTransport.Client transport = MediaTransportFixtures.publicClient(url -> {
            mirrors.incrementAndGet();
            return response(url, new ByteArrayInputStream(body));
        });
        SaveProgress.Outcome outcome = SaveProgress.begin(2).run(index -> {
            steps.incrementAndGet();
            RemoteMedia.fetch(List.of("https://cdn.example/first", "https://cdn.example/second"),
                    target, RemoteMedia.Kind.IMAGE, transport);
        });
        int attemptedMirrors = mirrors.get(), attemptedFiles = steps.get();
        boolean partialRemained = real.exists();
        shrinking.set(false);
        assertEquals("png", RemoteMedia.fetch(List.of("https://cdn.example/recovered"), target,
                RemoteMedia.Kind.IMAGE, transport));
        assertArrayEquals(body, Files.readAllBytes(real.toPath()));

        assertEquals("disk refusal became a skipped file", SaveProgress.Stop.NO_SPACE, outcome.stop);
        assertEquals(0, outcome.saved);
        assertEquals(0, outcome.skipped);
        assertEquals(1, attemptedFiles);
        assertEquals("a global refusal tried another mirror", 1, attemptedMirrors);
        assertFalse("the refused transfer left partial bytes", partialRemained);
        assertEquals("Saved 0 of 2, the rest need more free space",
                SaveProgress.message(outcome, "all saved"));
    }

    @Test public void aDeadlineDuringTheLastPhotoIsNotReportedAsAnOrdinarySkip() throws Exception {
        File target = files.newFile("last-photo.tmp");
        byte[] body = Arrays.copyOf(PNG, 128 * 1024);
        SaveProgress.Outcome outcome;
        try (RunningDeadline budget = new RunningDeadline()) {
            MediaTransport.Client transport = MediaTransportFixtures.publicClient(url ->
                    response(url, expireOnSecondRead(body, budget)));
            outcome = SaveProgress.begin(2).run(index -> {
                if (index == 1) RemoteMedia.fetch(List.of("https://cdn.example/photo"), target,
                        RemoteMedia.Kind.IMAGE, transport);
            });
        }
        boolean partialRemained = target.exists();
        assertEquals("png", RemoteMedia.fetch(List.of("https://cdn.example/recovered"), target,
                RemoteMedia.Kind.IMAGE, MediaTransportFixtures.publicClient(url ->
                        response(url, new ByteArrayInputStream(body)))));
        assertArrayEquals(body, Files.readAllBytes(target.toPath()));
        assertEquals(SaveProgress.Stop.NO_TIME, outcome.stop);
        assertEquals(1, outcome.saved);
        assertEquals(0, outcome.skipped);
        assertFalse(partialRemained);
        assertEquals("Saved 1 of 2, the rest ran out of time", SaveProgress.message(outcome, "all saved"));
    }

    @Test public void subtitleDeadlineStopsBeforeTheNextTrackAndPreservesTheReason() throws Exception {
        byte[] caption = "1\n00:00:00,000 --> 00:00:01,000\nA caption\n".getBytes(StandardCharsets.UTF_8);
        AtomicInteger steps = new AtomicInteger();
        SaveProgress.Outcome outcome;
        try (RunningDeadline budget = new RunningDeadline()) {
            MediaTransport.Client transport = MediaTransportFixtures.publicClient(url -> response(url,
                    new ByteArrayInputStream(caption) {
                        @Override public synchronized int read(byte[] into, int offset, int length) {
                            int count = super.read(into, offset, length);
                            budget.expire();
                            return count;
                        }
                    }));
            SubtitleDownloads.Track track = new SubtitleDownloads.Track("en", "srt",
                    List.of("https://cdn.example/first", "https://cdn.example/second"), true);
            outcome = SaveProgress.begin(2).run(index -> {
                steps.incrementAndGet();
                SubtitleDownloads.saveOne(RuntimeEnvironment.getApplication(), track, "video.mp4",
                        "Movies/subtitle-stop-test", transport);
            });
        }
        assertEquals("1\n00:00:00,000 --> 00:00:01,000\nA caption\n\n",
                SubtitleDownloads.fetch(List.of("https://cdn.example/recovered"), "srt",
                        MediaTransportFixtures.publicClient(url -> response(url, new ByteArrayInputStream(caption)))));
        assertEquals(SaveProgress.Stop.NO_TIME, outcome.stop);
        assertEquals(0, outcome.skipped);
        assertEquals("a terminal refusal reached the next track", 1, steps.get());
        assertEquals("Saved 0 of 2, the rest ran out of time", SaveProgress.message(outcome, "all saved"));
    }

    /** The story is published before its sound; a sound stopped by the clock must not fail it. */
    @Test public void aStorysSoundStoppedByTheClockIsTheSoundsFailureAlone() throws Exception {
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_AUDIO_TRACK.save(true);
        String folder = "DCIM/story-sound-" + System.nanoTime();
        Settings.DOWNLOAD_VIDEO_PATH.save(folder);
        File source = files.newFile("story.mp4");
        Files.write(source.toPath(), new byte[]{70, 71, 72});
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(source.getAbsolutePath()),
                MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2), new byte[]{91, 92, 93});
        try (RunningDeadline budget = new RunningDeadline()) {
            budget.expire();
            StoryDownloads.saveSound(RuntimeEnvironment.getApplication(), "story.m4a", source, folder);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("The sound couldn't be saved. Try again.",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        assertFalse(new File(new File(Environment.getExternalStorageDirectory(), folder), "story.m4a").exists());
    }

    /**
     * With {creator}/ in the name, the story goes to the creator's folder and its sound has to
     * go there too. It went to the download folder above, so the pair split up.
     */
    @Test public void aStorysSoundGoesIntoTheCreatorFolderBesideTheStory() throws Exception {
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_AUDIO_TRACK.save(true);
        String folder = "DCIM/story-creator-" + System.nanoTime();
        Settings.DOWNLOAD_VIDEO_PATH.save(folder);
        File source = files.newFile("creator-story.mp4");
        Files.write(source.toPath(), new byte[]{70, 71, 72});
        byte[] audio = {94, 95, 96};
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(source.getAbsolutePath()),
                MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2), audio);
        File top = new File(Environment.getExternalStorageDirectory(), folder);
        try {
            StoryDownloads.saveSound(RuntimeEnvironment.getApplication(), "story.m4a", source, folder + "/alice");
            assertArrayEquals(audio, Files.readAllBytes(new File(top, "alice/story.m4a").toPath()));
            assertFalse("the sound split off from its story", new File(top, "story.m4a").exists());
        } finally {
            if (top.isDirectory()) {
                try (var paths = Files.walk(top.toPath())) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    @Test public void countedAudioKeepsTheTerminalReasonAndAValidLaterSaveStillLands() throws Exception {
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_AUDIO_TRACK.save(true);
        String folder = "DCIM/audio-stop-" + System.nanoTime();
        Settings.DOWNLOAD_VIDEO_PATH.save(folder);
        File source = files.newFile("source.mp4");
        Files.write(source.toPath(), new byte[]{70, 71, 72});
        byte[] audio = {91, 92, 93};
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(source.getAbsolutePath()),
                MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2), audio);
        SaveProgress.Outcome outcome;
        try (RunningDeadline budget = new RunningDeadline()) {
            outcome = SaveProgress.begin(2).run(index -> {
                if (index == 0) return; // The already-published video survives its sidecar's refusal.
                budget.expire();
                if (!AudioDownloads.write(RuntimeEnvironment.getApplication(), "refused.m4a", source, folder, false)) {
                    throw new IOException("The sound beside the video was not saved");
                }
            });
        }
        File destination = new File(Environment.getExternalStorageDirectory(), folder);
        try {
            assertTrue(AudioDownloads.write(RuntimeEnvironment.getApplication(), "recovered.m4a", source, folder, false));
            assertArrayEquals(audio, Files.readAllBytes(new File(destination, "recovered.m4a").toPath()));
            assertFalse(new File(destination, "refused.m4a").exists());
            assertEquals(SaveProgress.Stop.NO_TIME, outcome.stop);
            assertEquals(1, outcome.saved);
            assertEquals(0, outcome.skipped);
            assertEquals("Saved 1 of 2, the rest ran out of time", SaveProgress.message(outcome, "all saved"));
        } finally {
            File[] outputs = destination.listFiles();
            if (outputs != null) for (File output : outputs) assertTrue(output.delete());
            if (destination.exists()) assertTrue(destination.delete());
        }
    }

    private static InputStream expireOnSecondRead(byte[] body, RunningDeadline budget) {
        return new ByteArrayInputStream(body) {
            private int reads;
            @Override public synchronized int read(byte[] into, int offset, int length) {
                int count = super.read(into, offset, length);
                if (++reads == 2) budget.expire();
                return count;
            }
        };
    }

    private static HttpURLConnection response(URL url, InputStream body) {
        return new FakeHttpsConnection(url) {
            @Override public int getResponseCode() { return HTTP_OK; }
            @Override public InputStream getInputStream() { return body; }
            @Override public void connect() { }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
        };
    }

    /** Reuses the deadline-reflection seam from the converter tests; no sleeping or global clock. */
    private static final class RunningDeadline implements AutoCloseable {
        private final ThreadLocal<MediaBudget.Deadline> current;
        private final MediaBudget.Deadline previous, active;
        private final Field end;

        @SuppressWarnings("unchecked") RunningDeadline() throws Exception {
            Field slot = MediaBudget.class.getDeclaredField("CURRENT_DEADLINE");
            slot.setAccessible(true);
            current = (ThreadLocal<MediaBudget.Deadline>) slot.get(null);
            previous = current.get();
            Constructor<MediaBudget.Deadline> constructor = MediaBudget.Deadline.class.getDeclaredConstructor(long.class);
            constructor.setAccessible(true);
            active = constructor.newInstance(System.nanoTime() + 120_000_000_000L);
            end = MediaBudget.Deadline.class.getDeclaredField("endNanos");
            end.setAccessible(true);
            current.set(active);
        }

        void expire() {
            try { end.setLong(active, System.nanoTime() - 1); }
            catch (IllegalAccessException error) { throw new AssertionError(error); }
        }

        @Override public void close() {
            if (previous == null) current.remove();
            else current.set(previous);
        }
    }
}

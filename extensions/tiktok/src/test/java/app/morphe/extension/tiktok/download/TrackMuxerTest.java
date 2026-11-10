package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import app.morphe.extension.tiktok.SettingsContextRule;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.ShadowMediaMuxer;
import org.robolectric.shadows.util.DataSource;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {TrackMuxerTest.SampleExtractor.class, TrackMuxerTest.RecordingMuxer.class})
public class TrackMuxerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder files = new TemporaryFolder();

    private static final byte[] SOURCE = {70, 71, 72};
    private static final byte[] VIDEO = {11, 12, 13, 14, 21, 22, 31, 32, 33, 34, 35};
    private static final int[] SAMPLE_SIZES = {4, 2, 5};
    private static final long[] SAMPLE_TIMES = {33_333, 99_999, 155_555};
    private static final int[] SAMPLE_FLAGS = {MediaExtractor.SAMPLE_FLAG_SYNC, 0, MediaExtractor.SAMPLE_FLAG_SYNC};
    private static final byte[] AUDIO = {91, 92, 93};
    private static final int[] AUDIO_SIZES = {3};
    private static final long[] AUDIO_TIMES = {17_000};
    private static final int[] AUDIO_FLAGS = {0};
    /**
     * A sound track the way TikTok's downloads carry it: an edit list puts the encoder's
     * priming frames before zero, so the extractor hands the first samples back at a negative
     * time. The S22's files start at -161 ms.
     */
    private static final byte[] LEAD_IN_AUDIO = {1, 2, 3, 4, 5, 6};
    private static final int[] LEAD_IN_SIZES = {2, 2, 2};
    private static final long[] LEAD_IN_TIMES = {-161_134, -114_694, 23_000};
    private static final int[] LEAD_IN_FLAGS = {MediaExtractor.SAMPLE_FLAG_SYNC, MediaExtractor.SAMPLE_FLAG_SYNC, MediaExtractor.SAMPLE_FLAG_SYNC};

    @Before public void resetRecording() {
        SampleExtractor.last = null;
        SampleExtractor.useDefaultAudio();
        RecordingMuxer.resetRecording();
    }

    @Test public void audioOnlyKeepsEverySampleOfATrackThatStartsBeforeZeroAndMovesItToZero() throws Exception {
        SampleExtractor.useAudio(LEAD_IN_TIMES, LEAD_IN_SIZES, LEAD_IN_FLAGS);
        File source = files.newFile(), output = files.newFile();
        Files.write(source.toPath(), SOURCE);
        DataSource dataSource = DataSource.toDataSource(source.getAbsolutePath());
        ShadowMediaExtractor.addTrack(dataSource,
                MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2), LEAD_IN_AUDIO);
        ShadowMediaExtractor.addTrack(dataSource, MediaFormat.createVideoFormat("video/avc", 1080, 1920), VIDEO);

        TrackMuxer.audioOnly(source, output);

        assertEquals(List.of(0), SampleExtractor.last.selectedTracks);
        assertEquals(3, RecordingMuxer.samples.size());
        long[] times = {0, 46_440, 184_134};
        byte[][] payloads = {{1, 2}, {3, 4}, {5, 6}};
        for (int i = 0; i < times.length; i++) {
            Sample sample = RecordingMuxer.samples.get(i);
            assertEquals(0, sample.track);
            assertEquals(times[i], sample.timeUs);
            assertArrayEquals(payloads[i], sample.payload);
            assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME, sample.flags);
        }
        assertArrayEquals(LEAD_IN_AUDIO, Files.readAllBytes(output.toPath()));
        assertTrue(source.delete());
        assertTrue(output.delete());
    }

    @Test public void combineMovesBothTracksByTheSameLeadInSoTheSoundStaysInStepWithThePicture() throws Exception {
        SampleExtractor.useAudio(LEAD_IN_TIMES, LEAD_IN_SIZES, LEAD_IN_FLAGS);
        File video = files.newFile(), audio = files.newFile(), output = files.newFile();
        Files.write(video.toPath(), SOURCE);
        Files.write(audio.toPath(), SOURCE);
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(video.getAbsolutePath()),
                MediaFormat.createVideoFormat("video/avc", 1080, 1920), VIDEO);
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(audio.getAbsolutePath()),
                MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2), LEAD_IN_AUDIO);

        TrackMuxer.combine(video, audio, output);

        assertEquals(6, RecordingMuxer.samples.size());
        // The picture never started before zero, but it moves by the sound's lead-in all the
        // same: what matters is the gap between the two, which is unchanged.
        long[] pictureTimes = {33_333 + 161_134, 99_999 + 161_134, 155_555 + 161_134};
        long[] soundTimes = {0, 46_440, 184_134};
        for (int i = 0; i < 3; i++) {
            assertEquals(0, RecordingMuxer.samples.get(i).track);
            assertEquals(pictureTimes[i], RecordingMuxer.samples.get(i).timeUs);
            assertEquals(1, RecordingMuxer.samples.get(3 + i).track);
            assertEquals(soundTimes[i], RecordingMuxer.samples.get(3 + i).timeUs);
        }
        assertTrue(video.delete());
        assertTrue(audio.delete());
        assertTrue(output.delete());
    }

    @Test public void videoOnlyPreservesRotationBeforeStartingAndCopiesOnlyVideoSamples() throws Exception {
        for (int rotation : new int[]{90, 180, 270}) assertVideoOnly(rotation);
    }

    @Test public void videoOnlyAcceptsZeroAndAbsentRotationWithoutChangingSamples() throws Exception {
        assertVideoOnly(0);
        assertVideoOnly(null);
    }

    private File sourceWithTracks(Integer rotation) throws IOException {
        resetRecording();
        File source = files.newFile();
        Files.write(source.toPath(), SOURCE);
        DataSource dataSource = DataSource.toDataSource(source.getAbsolutePath());
        // Audio comes first so selecting track zero would copy the wrong media.
        ShadowMediaExtractor.addTrack(dataSource,
                MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2), AUDIO);
        MediaFormat video = MediaFormat.createVideoFormat("video/avc", 1080, 1920);
        if (rotation != null) video.setInteger("rotation-degrees", rotation);
        ShadowMediaExtractor.addTrack(dataSource, video, VIDEO);
        return source;
    }

    private void assertVideoOnly(Integer rotation) throws Exception {
        File source = sourceWithTracks(rotation), output = files.newFile();
        TrackMuxer.videoOnly(source, output);

        assertEquals(rotation == null ? List.of() : List.of(rotation), RecordingMuxer.rotations);
        assertEquals(rotation == null ? 0 : rotation.intValue(), RecordingMuxer.rotationAtStart);
        assertEquals(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, RecordingMuxer.outputFormat);
        assertEquals(List.of(1), SampleExtractor.last.selectedTracks);
        assertEquals(1, RecordingMuxer.formats.size());
        Map<String, Object> video = RecordingMuxer.formats.get(0);
        assertEquals("video/avc", video.get(MediaFormat.KEY_MIME));
        assertEquals(1080, video.get(MediaFormat.KEY_WIDTH));
        assertEquals(1920, video.get(MediaFormat.KEY_HEIGHT));

        assertEquals(3, RecordingMuxer.samples.size());
        byte[][] payloads = {{11, 12, 13, 14}, {21, 22}, {31, 32, 33, 34, 35}};
        long[] times = {33_333, 99_999, 155_555};
        int[] flags = {MediaCodec.BUFFER_FLAG_KEY_FRAME, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME};
        for (int i = 0; i < payloads.length; i++) {
            Sample sample = RecordingMuxer.samples.get(i);
            assertEquals(0, sample.track);
            assertArrayEquals(payloads[i], sample.payload);
            assertEquals(times[i], sample.timeUs);
            assertEquals(flags[i], sample.flags);
        }
        // The stock shadow writes compressed payloads directly, not a playable MP4 container.
        assertArrayEquals(VIDEO, Files.readAllBytes(output.toPath()));
        assertArrayEquals(SOURCE, Files.readAllBytes(source.toPath()));
        List<String> expectedEvents = new ArrayList<>(List.of("setup", "add"));
        if (rotation != null) expectedEvents.add("orientation");
        expectedEvents.addAll(List.of("start", "write", "write", "write", "stop", "release"));
        assertEquals(expectedEvents, RecordingMuxer.events);
        assertTrue(SampleExtractor.last.released);
        assertTrue("the input is still owned by its caller", source.delete());
        assertTrue("the completed output descriptor is closed", output.delete());
    }

    /**
     * Adds sample boundaries and metadata to Robolectric's real track/payload fixture support.
     *
     * <p>The picture track always has the three samples above. The sound track has one by
     * default, and a test that needs a lead-in swaps its samples in with {@link #useAudio}.
     * Which set applies is decided by the selected track's MIME type, so a file holding only
     * a picture and a file holding only a sound both read the way the real extractor would.
     */
    @Implements(MediaExtractor.class)
    public static class SampleExtractor extends ShadowMediaExtractor {
        static SampleExtractor last;
        static long[] audioTimes = AUDIO_TIMES;
        static int[] audioSizes = AUDIO_SIZES;
        static int[] audioFlags = AUDIO_FLAGS;
        final List<Integer> selectedTracks = new ArrayList<>();
        private int sampleIndex;
        private boolean selected;
        private boolean video;
        boolean released;

        static void useDefaultAudio() {
            useAudio(AUDIO_TIMES, AUDIO_SIZES, AUDIO_FLAGS);
        }

        static void useAudio(long[] times, int[] sizes, int[] flags) {
            audioTimes = times;
            audioSizes = sizes;
            audioFlags = flags;
        }

        @Implementation @Override protected void setDataSource(String path) {
            super.setDataSource(path);
            last = this;
        }

        @Implementation @Override protected void selectTrack(int track) {
            super.selectTrack(track);
            selectedTracks.add(track);
            selected = true;
            String mime = getTrackFormat(track).getString(MediaFormat.KEY_MIME);
            video = mime != null && mime.startsWith("video/");
        }

        private long[] times() { return video ? SAMPLE_TIMES : audioTimes; }
        private int[] sizes() { return video ? SAMPLE_SIZES : audioSizes; }
        private int[] flags() { return video ? SAMPLE_FLAGS : audioFlags; }
        private boolean atEnd() { return !selected || sampleIndex >= times().length; }

        @Implementation protected long getSampleTime() {
            return atEnd() ? -1 : times()[sampleIndex];
        }

        @Implementation protected int getSampleFlags() {
            return atEnd() ? 0 : flags()[sampleIndex];
        }

        @Implementation protected long getSampleSize() {
            return atEnd() ? -1 : sizes()[sampleIndex];
        }

        @Implementation @Override protected int readSampleData(ByteBuffer buffer, int offset) {
            // Past the last sample the real extractor hands back -1, and that is the end of
            // the copy loop; a negative stamp is not.
            if (atEnd()) return -1;
            ByteBuffer sample = buffer.duplicate();
            sample.position(offset);
            sample.limit(offset + (int) getSampleSize());
            return super.readSampleData(sample.slice(), 0);
        }

        @Implementation @Override protected boolean advance() {
            boolean available = super.advance();
            sampleIndex++;
            return available && !atEnd();
        }

        @Implementation protected void release() { released = true; }
    }

    private static final class Sample {
        final int track, flags;
        final long timeUs;
        final byte[] payload;

        Sample(int track, int flags, long timeUs, byte[] payload) {
            this.track = track;
            this.flags = flags;
            this.timeUs = timeUs;
            this.payload = payload;
        }
    }

    /** Records native muxer calls while retaining the stock shadow's file writes and close. */
    @Implements(MediaMuxer.class)
    public static class RecordingMuxer extends ShadowMediaMuxer {
        static final List<String> events = new ArrayList<>();
        static final List<Integer> rotations = new ArrayList<>();
        static final List<Map<String, Object>> formats = new ArrayList<>();
        static final List<Sample> samples = new ArrayList<>();
        static int rotationAtStart, outputFormat;

        static void resetRecording() {
            events.clear();
            rotations.clear();
            formats.clear();
            samples.clear();
            rotationAtStart = 0;
            outputFormat = -1;
        }

        @Implementation protected static long nativeSetup(FileDescriptor file, int format) throws IOException {
            events.add("setup");
            outputFormat = format;
            return ShadowMediaMuxer.nativeSetup(file, format);
        }

        @Implementation protected static int nativeAddTrack(long muxer, String[] keys, Object[] values) {
            events.add("add");
            Map<String, Object> format = new HashMap<>();
            for (int i = 0; i < keys.length; i++) format.put(keys[i], values[i]);
            formats.add(format);
            return ShadowMediaMuxer.nativeAddTrack(muxer, keys, values);
        }

        @Implementation protected static void nativeSetOrientationHint(long muxer, int degrees) {
            assertFalse("rotation must be supplied before muxing starts", events.contains("start"));
            events.add("orientation");
            rotations.add(degrees);
        }

        @Implementation protected static void nativeStart(long muxer) {
            events.add("start");
            rotationAtStart = rotations.isEmpty() ? 0 : rotations.get(rotations.size() - 1);
        }

        @Implementation protected static void nativeWriteSampleData(long muxer, int track, ByteBuffer buffer,
                int offset, int size, long timeUs, int flags) {
            assertTrue(events.contains("start"));
            events.add("write");
            byte[] payload = new byte[size];
            ByteBuffer copy = buffer.duplicate();
            copy.position(offset);
            copy.get(payload);
            samples.add(new Sample(track, flags, timeUs, payload));
            ShadowMediaMuxer.nativeWriteSampleData(muxer, track, buffer, offset, size, timeUs, flags);
        }

        @Implementation protected static void nativeStop(long muxer) {
            ShadowMediaMuxer.nativeStop(muxer);
            events.add("stop");
        }

        @Implementation protected static void nativeRelease(long muxer) { events.add("release"); }
    }
}

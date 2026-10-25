package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** #37: rewriting a recorded FLV as an MP4, with the file writer replaced by a recorder of calls. */
public class LiveRemuxTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final class FakeWriter implements LiveRemux.Mp4Writer {
        final List<String> calls = new ArrayList<>();
        final List<Integer> tracks = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        final List<byte[]> samples = new ArrayList<>();
        final List<Boolean> keys = new ArrayList<>();
        boolean finished;
        boolean aborted;
        int video = -1;
        int audio = -1;
        String mime;
        byte[] csd1;

        @Override public int addVideoTrack(String mime, byte[] csd0, byte[] csd1, int width, int height) {
            this.mime = mime;
            this.csd1 = csd1;
            calls.add("video " + width + "x" + height);
            video = 0;
            return 0;
        }

        @Override public int addAudioTrack(byte[] specific, int sampleRate, int channels) {
            calls.add("audio " + sampleRate + "/" + channels);
            audio = 1;
            return 1;
        }

        @Override public void start() {
            calls.add("start");
        }

        @Override public void write(int track, byte[] data, long presentationUs, boolean keyFrame) {
            tracks.add(track);
            samples.add(data);
            times.add(presentationUs);
            keys.add(keyFrame);
        }

        @Override public void finish() {
            finished = true;
            calls.add("finish");
        }

        @Override public void abort() {
            aborted = true;
        }
    }

    private File flv(byte[] bytes) throws IOException {
        File file = folder.newFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    private static byte[] sps() {
        return FlvFixtures.baselineSps(20, 15, 0);
    }

    @Test public void tracksAreAddedBeforeStartAndEverySampleFollowsInOrder() throws IOException {
        FakeWriter writer = new FakeWriter();
        LiveRemux.Summary summary = LiveRemux.fromFlv(flv(FlvFixtures.stream(
                FlvFixtures.avcConfigTag(0, sps(), FlvFixtures.pps()),
                FlvFixtures.aacConfigTag(0),
                FlvFixtures.avcFrameTag(0, true, 0, new byte[]{0x65, 1}),
                FlvFixtures.aacFrameTag(20, (byte) 1),
                FlvFixtures.avcFrameTag(40, false, 0, new byte[]{0x41, 2}),
                FlvFixtures.aacFrameTag(43, (byte) 2))), writer);
        assertEquals(List.of("video 320x240", "audio 44100/2", "start", "finish"), writer.calls);
        assertTrue(writer.finished);
        assertFalse(writer.aborted);
        assertEquals(2, summary.videoSamples);
        assertEquals(2, summary.audioSamples);
        assertEquals(43_000L, summary.durationUs);
        assertEquals(List.of(0, 1, 0, 1), writer.tracks);
        assertTrue(writer.keys.get(0));
        assertFalse(writer.keys.get(2));
    }

    @Test public void videoBeforeTheFirstKeyFrameIsLeftOut() throws IOException {
        FakeWriter writer = new FakeWriter();
        LiveRemux.Summary summary = LiveRemux.fromFlv(flv(FlvFixtures.stream(
                FlvFixtures.avcConfigTag(0, sps(), FlvFixtures.pps()),
                FlvFixtures.avcFrameTag(0, false, 0, new byte[]{0x41, 1}),
                FlvFixtures.avcFrameTag(40, false, 0, new byte[]{0x41, 2}),
                FlvFixtures.avcFrameTag(80, true, 0, new byte[]{0x65, 3}),
                FlvFixtures.avcFrameTag(120, false, 0, new byte[]{0x41, 4}))), writer);
        assertEquals(2, summary.videoSamples);
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x65, 3}, writer.samples.get(0));
        assertTrue(writer.keys.get(0));
    }

    @Test public void aStreamWithNoAudioSettingsStillMakesAVideoOnlyFile() throws IOException {
        FakeWriter writer = new FakeWriter();
        List<byte[]> tags = new ArrayList<>();
        tags.add(FlvFixtures.avcConfigTag(0, sps(), FlvFixtures.pps()));
        for (int i = 0; i < 4; i++) {
            tags.add(FlvFixtures.avcFrameTag(i * 40L, i == 0, 0, new byte[]{(byte) (i == 0 ? 0x65 : 0x41), (byte) i}));
        }
        LiveRemux.Summary summary = LiveRemux.fromFlv(flv(FlvFixtures.stream(tags.toArray(new byte[0][]))), writer);
        assertEquals(List.of("video 320x240", "start", "finish"), writer.calls);
        assertEquals(4, summary.videoSamples);
        assertEquals(0, summary.audioSamples);
    }

    @Test public void audioSettingsThatArriveAfterTheFileOpenedAreNotAddedLate() throws IOException {
        FakeWriter writer = new FakeWriter();
        List<byte[]> tags = new ArrayList<>();
        tags.add(FlvFixtures.avcConfigTag(0, sps(), FlvFixtures.pps()));
        for (int i = 0; i < 200; i++) {
            tags.add(FlvFixtures.avcFrameTag(i * 40L, i == 0, 0, new byte[]{(byte) (i == 0 ? 0x65 : 0x41), 1}));
        }
        tags.add(FlvFixtures.aacConfigTag(8100));
        tags.add(FlvFixtures.aacFrameTag(8120, (byte) 1));
        LiveRemux.Summary summary = LiveRemux.fromFlv(flv(FlvFixtures.stream(tags.toArray(new byte[0][]))), writer);
        assertEquals(List.of("video 320x240", "start", "finish"), writer.calls);
        assertEquals(200, summary.videoSamples);
        assertEquals(0, summary.audioSamples);
    }

    @Test public void aRecordingWithNoVideoIsNothingAndTheFileIsDropped() throws IOException {
        FakeWriter writer = new FakeWriter();
        try {
            LiveRemux.fromFlv(flv(FlvFixtures.stream(
                    FlvFixtures.aacConfigTag(0), FlvFixtures.aacFrameTag(0, (byte) 1))), writer);
            fail("audio alone is not a LIVE video");
        } catch (LiveRemux.NothingRecorded expected) {
            // nothing to save
        }
        assertTrue(writer.aborted);
        assertFalse(writer.finished);
    }

    @Test public void aConfigButNoKeyFrameIsNothing() throws IOException {
        FakeWriter writer = new FakeWriter();
        try {
            LiveRemux.fromFlv(flv(FlvFixtures.stream(
                    FlvFixtures.avcConfigTag(0, sps(), FlvFixtures.pps()),
                    FlvFixtures.avcFrameTag(0, false, 0, new byte[]{0x41, 1}))), writer);
            fail("a picture that can't be started from isn't a recording");
        } catch (LiveRemux.NothingRecorded expected) {
            // nothing to save
        }
        assertTrue(writer.aborted);
    }

    @Test public void anUnsupportedVideoFormatAbortsTheFile() throws IOException {
        FakeWriter writer = new FakeWriter();
        try {
            LiveRemux.fromFlv(flv(FlvFixtures.stream(
                    FlvFixtures.tag(9, 0, new byte[]{(byte) 0x9C, 'h', 'v', 'c', '1', 0, 0, 0}))), writer);
            fail("enhanced FLV isn't read");
        } catch (FlvReader.UnsupportedStream expected) {
            // refused
        }
        assertTrue(writer.aborted);
    }

    @Test public void anHevcRecordingIsWrittenAsAnHevcTrack() throws IOException {
        // #37: TikTok sends HEVC to phones that play it, as FLV codec id 12.
        FakeWriter writer = new FakeWriter();
        LiveRemux.fromFlv(flv(FlvFixtures.stream(
                FlvFixtures.hevcConfigTag(0, FlvFixtures.hevcVps(), FlvFixtures.hevcSps(1088, 1920, 4), FlvFixtures.hevcPps()),
                FlvFixtures.hevcFrameTag(0, true, 0, new byte[]{0x26, 0x01, 1}),
                FlvFixtures.hevcFrameTag(40, false, 0, new byte[]{0x02, 0x01, 2}))), writer);
        assertEquals("video/hevc", writer.mime);
        assertEquals(null, writer.csd1);
        assertEquals("video 1080x1920", writer.calls.get(0));
        assertEquals(2, writer.samples.size());
        assertTrue(writer.finished);
    }

    @Test public void aRecordingCutOffMidFrameKeepsEverythingBeforeThat() throws IOException {
        FakeWriter writer = new FakeWriter();
        byte[] whole = FlvFixtures.stream(
                FlvFixtures.avcConfigTag(0, sps(), FlvFixtures.pps()),
                FlvFixtures.avcFrameTag(0, true, 0, new byte[]{0x65, 1}),
                FlvFixtures.avcFrameTag(40, false, 0, new byte[]{0x41, 2, 3, 4, 5, 6, 7, 8, 9}));
        byte[] cut = java.util.Arrays.copyOf(whole, whole.length - 12);
        LiveRemux.Summary summary = LiveRemux.fromFlv(flv(cut), writer);
        assertEquals(1, summary.videoSamples);
        assertTrue(writer.finished);
    }

    @Test public void timestampsKeepRunningForwardThroughADiscontinuity() {
        LiveRemux.TimeRebaser rebaser = new LiveRemux.TimeRebaser();
        assertEquals(0L, rebaser.map(90_000_000L));
        assertEquals(40_000L, rebaser.map(90_040_000L));
        assertEquals(80_000L, rebaser.map(90_080_000L));
        // The stream restarts its clock near zero: the file carries on from where it was.
        long jumped = rebaser.map(1_000L);
        assertTrue("after the last time, not before it", jumped > 80_000L && jumped < 200_000L);
        long next = rebaser.map(41_000L);
        assertEquals(40_000L, next - jumped);
    }

    @Test public void smallReorderingIsNotMistakenForADiscontinuity() {
        LiveRemux.TimeRebaser rebaser = new LiveRemux.TimeRebaser();
        rebaser.map(1_000_000L);
        rebaser.map(1_200_000L);
        assertEquals(100_000L, rebaser.map(1_100_000L));
    }
}

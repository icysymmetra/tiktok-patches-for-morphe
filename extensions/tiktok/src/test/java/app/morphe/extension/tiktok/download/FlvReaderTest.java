package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** #37: reading a LIVE's FLV stream into the pieces an MP4 needs. */
public class FlvReaderTest {
    private static final class Recorded implements FlvReader.Listener {
        String mime;
        byte[] csd0;
        byte[] csd1;
        int width;
        int height;
        byte[] asc;
        int rate;
        int channels;
        final List<byte[]> video = new ArrayList<>();
        final List<Long> videoUs = new ArrayList<>();
        final List<Boolean> keys = new ArrayList<>();
        final List<byte[]> audio = new ArrayList<>();
        final List<Long> audioUs = new ArrayList<>();

        @Override public void onVideoConfig(String mime, byte[] csd0, byte[] csd1, int width, int height) {
            this.mime = mime;
            this.csd0 = csd0;
            this.csd1 = csd1;
            this.width = width;
            this.height = height;
        }

        @Override public void onAudioConfig(byte[] specific, int sampleRate, int channels) {
            asc = specific;
            rate = sampleRate;
            this.channels = channels;
        }

        @Override public void onVideo(byte[] annexB, long presentationUs, boolean keyFrame) {
            video.add(annexB);
            videoUs.add(presentationUs);
            keys.add(keyFrame);
        }

        @Override public void onAudio(byte[] aac, long presentationUs) {
            audio.add(aac);
            audioUs.add(presentationUs);
        }
    }

    private static Recorded read(byte[] stream) throws IOException {
        Recorded recorded = new Recorded();
        FlvReader.read(new ByteArrayInputStream(stream), recorded);
        return recorded;
    }

    private static byte[] hevcTag() {
        // The extended header bit (0x80) is how newer FLV announces HEVC.
        return FlvFixtures.tag(9, 0, new byte[]{(byte) 0x9C, 'h', 'v', 'c', '1', 0, 0, 0});
    }

    @Test public void theSizeComesFromTheSps() {
        assertArrayEquals(new int[]{320, 240}, FlvReader.SpsSize.of(FlvFixtures.baselineSps(20, 15, 0)));
        assertArrayEquals(new int[]{1280, 720}, FlvReader.SpsSize.of(FlvFixtures.highSps(80, 45)));
    }

    @Test public void croppingTakesTheMacroblockPaddingOff() {
        // 1088 rows with 4 crop units of 2 rows each is 1080.
        assertArrayEquals(new int[]{1920, 1080}, FlvReader.SpsSize.of(FlvFixtures.baselineSps(120, 68, 4)));
    }

    @Test public void anUnreadableSpsGivesNoSize() {
        assertNull(FlvReader.SpsSize.of(new byte[]{0x67, 0x42}));
        assertNull(FlvReader.SpsSize.of(null));
    }

    @Test public void aacSettingsGiveRateAndChannels() {
        assertArrayEquals(new int[]{44100, 2}, FlvReader.AacConfig.parse(new byte[]{0x12, 0x10}));
        assertArrayEquals(new int[]{48000, 1}, FlvReader.AacConfig.parse(new byte[]{0x11, (byte) 0x88}));
        assertNull(FlvReader.AacConfig.parse(new byte[]{0x12}));
    }

    @Test public void videoAndAudioComeOutWithSettingsFirstAndTimesFromTheFirstSample() throws IOException {
        byte[] sps = FlvFixtures.baselineSps(20, 15, 0);
        byte[] idr = {0x65, 1, 2, 3};
        byte[] slice = {0x41, 9, 8};
        Recorded recorded = read(FlvFixtures.stream(
                FlvFixtures.avcConfigTag(5000, sps, FlvFixtures.pps()),
                FlvFixtures.aacConfigTag(5000),
                FlvFixtures.avcFrameTag(5000, true, 0, idr),
                FlvFixtures.aacFrameTag(5010, (byte) 7, (byte) 7),
                FlvFixtures.avcFrameTag(5040, false, 40, slice)));
        assertEquals("video/avc", recorded.mime);
        assertEquals(320, recorded.width);
        assertEquals(240, recorded.height);
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x67}, Arrays.copyOf(recorded.csd0, 5));
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x68, (byte) 0xCE, 0x3C, (byte) 0x80}, recorded.csd1);
        assertEquals(44100, recorded.rate);
        assertEquals(2, recorded.channels);
        assertArrayEquals(new byte[]{0x12, 0x10}, recorded.asc);
        assertEquals(2, recorded.video.size());
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x65, 1, 2, 3}, recorded.video.get(0));
        assertTrue(recorded.keys.get(0));
        assertFalse(recorded.keys.get(1));
        assertEquals(0L, (long) recorded.videoUs.get(0));
        assertEquals("40 ms later plus 40 ms of reordering", 80_000L, (long) recorded.videoUs.get(1));
        assertEquals(1, recorded.audio.size());
        assertArrayEquals(new byte[]{7, 7}, recorded.audio.get(0));
        assertEquals(10_000L, (long) recorded.audioUs.get(0));
    }

    @Test public void severalNalUnitsInOneFrameAreEachGivenAStartCode() throws IOException {
        byte[] sps = FlvFixtures.baselineSps(20, 15, 0);
        Recorded recorded = read(FlvFixtures.stream(
                FlvFixtures.avcConfigTag(0, sps, FlvFixtures.pps()),
                FlvFixtures.avcFrameTag(0, true, 0, new byte[]{0x06, 1}, new byte[]{0x65, 2, 3})));
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x06, 1, 0, 0, 0, 1, 0x65, 2, 3}, recorded.video.get(0));
    }

    @Test public void aTagCutOffByTheEndOfTheInputIsDroppedNotAnError() throws IOException {
        byte[] sps = FlvFixtures.baselineSps(20, 15, 0);
        byte[] whole = FlvFixtures.stream(
                FlvFixtures.avcConfigTag(0, sps, FlvFixtures.pps()),
                FlvFixtures.avcFrameTag(0, true, 0, new byte[]{0x65, 1}),
                FlvFixtures.avcFrameTag(40, false, 0, new byte[]{0x41, 1, 2, 3, 4, 5, 6, 7}));
        Recorded recorded = read(Arrays.copyOf(whole, whole.length - 9));
        assertEquals("only the whole frame", 1, recorded.video.size());
        assertNotNull(recorded.csd0);
    }

    @Test public void anEmptyOrHeaderOnlyStreamGivesNothing() throws IOException {
        assertTrue(read(new byte[0]).video.isEmpty());
        assertTrue(read(FlvFixtures.stream()).video.isEmpty());
    }

    @Test public void aStreamThatIsNotFlvIsRefused() throws IOException {
        try {
            read("<html>blocked</html>".getBytes());
            fail("an error page is not a recording");
        } catch (FlvReader.UnsupportedStream expected) {
            // refused
        }
    }

    @Test public void videoItCantWriteIsRefusedWithAnExplanation() throws IOException {
        try {
            read(FlvFixtures.stream(hevcTag()));
            fail("enhanced FLV isn't read");
        } catch (FlvReader.UnsupportedStream expected) {
            assertTrue(expected.getMessage().contains("can't be saved"));
        }
        try {
            read(FlvFixtures.stream(FlvFixtures.tag(9, 0, new byte[]{0x14, 0, 0, 0, 0})));
            fail("codec 4 is neither H.264 nor HEVC");
        } catch (FlvReader.UnsupportedStream expected) {
            // refused
        }
    }

    @Test public void theEarlyHeadCheckCatchesWhatTheRewriteWouldRefuse() throws IOException {
        try {
            LiveStreamSources.Flv.checkHead(FlvFixtures.stream(hevcTag()), true);
            fail("the first video tag is enough to know");
        } catch (FlvReader.UnsupportedStream expected) {
            // caught early
        }
        // A good start, even cut off mid-frame, passes.
        byte[] good = FlvFixtures.stream(
                FlvFixtures.avcConfigTag(0, FlvFixtures.baselineSps(20, 15, 0), FlvFixtures.pps()),
                FlvFixtures.avcFrameTag(0, true, 0, new byte[]{0x65, 1, 2, 3, 4, 5, 6}));
        LiveStreamSources.Flv.checkHead(Arrays.copyOf(good, good.length - 6), false);
    }

    @Test public void theHevcSizeComesFromItsSpsWithTheCropTakenOff() {
        // 1088 columns with 4 crop units of 2 columns each on the right is 1080.
        assertArrayEquals(new int[]{1080, 1920}, FlvReader.SpsSize.ofHevc(FlvFixtures.hevcSps(1088, 1920, 4)));
        assertArrayEquals(new int[]{720, 1280}, FlvReader.SpsSize.ofHevc(FlvFixtures.hevcSps(720, 1280, 0)));
        assertNull(FlvReader.SpsSize.ofHevc(new byte[]{0x42, 0x01}));
    }

    @Test public void tikTokHevcComesOutAsHevcWithAllItsSettingsInOnePiece() throws IOException {
        // What TikTok's LIVE servers send a phone that plays HEVC: FLV codec id 12 (#37).
        byte[] sps = FlvFixtures.hevcSps(1088, 1920, 4);
        Recorded recorded = read(FlvFixtures.stream(
                FlvFixtures.hevcConfigTag(0, FlvFixtures.hevcVps(), sps, FlvFixtures.hevcPps()),
                FlvFixtures.aacConfigTag(0),
                FlvFixtures.hevcFrameTag(0, true, 0, new byte[]{0x26, 0x01, 9, 9}),
                FlvFixtures.hevcFrameTag(40, false, 0, new byte[]{0x02, 0x01, 7})));
        assertEquals("video/hevc", recorded.mime);
        assertEquals(1080, recorded.width);
        assertEquals(1920, recorded.height);
        assertNull("HEVC's settings all travel in csd-0", recorded.csd1);
        java.io.ByteArrayOutputStream settings = new java.io.ByteArrayOutputStream();
        for (byte[] nal : new byte[][]{FlvFixtures.hevcVps(), sps, FlvFixtures.hevcPps()}) {
            settings.write(new byte[]{0, 0, 0, 1}, 0, 4);
            settings.write(nal, 0, nal.length);
        }
        assertArrayEquals(settings.toByteArray(), recorded.csd0);
        assertEquals(2, recorded.video.size());
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x26, 0x01, 9, 9}, recorded.video.get(0));
        assertTrue(recorded.keys.get(0));
        assertFalse(recorded.keys.get(1));
        assertEquals(44100, recorded.rate);
    }

    @Test public void hevcIsRefusedUpFrontWhereThePhoneCantWriteIt() throws IOException {
        byte[] head = FlvFixtures.stream(
                FlvFixtures.hevcConfigTag(0, FlvFixtures.hevcVps(), FlvFixtures.hevcSps(720, 1280, 0), FlvFixtures.hevcPps()));
        LiveStreamSources.Flv.checkHead(head, true);
        try {
            LiveStreamSources.Flv.checkHead(head, false);
            fail("before Android 7.0 the muxer can't write HEVC");
        } catch (FlvReader.UnsupportedStream expected) {
            // refused before anything is recorded
        }
    }
}

package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** #37: pulling a LIVE's FLV download or HLS pieces into the recorder's spool. */
public class LiveStreamSourcesTest {
    private static final String LIST = "https://pull.example.com/live/index.m3u8";

    /** Serves scripted answers by address; the playlist's answers come one per request, in order. */
    private static final class FakeHttp implements LiveStreamSources.Http {
        final Map<String, ArrayDeque<byte[]>> answers = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<InputStream> opened = new ArrayList<>();

        FakeHttp serve(String url, byte[]... bodies) {
            ArrayDeque<byte[]> queue = answers.get(url);
            if (queue == null) {
                queue = new ArrayDeque<>();
                answers.put(url, queue);
            }
            queue.addAll(Arrays.asList(bodies));
            return this;
        }

        @Override public InputStream open(String url) throws IOException {
            requested.add(url);
            ArrayDeque<byte[]> queue = answers.get(url);
            if (queue == null || queue.isEmpty()) throw new IOException("No answer for " + url);
            // The last answer repeats, like a playlist that has nothing new.
            byte[] body = queue.size() > 1 ? queue.poll() : queue.peek();
            InputStream stream = new ByteArrayInputStream(body);
            opened.add(stream);
            return stream;
        }
    }

    private static final class Spool implements LiveRecorder.Output {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override public void write(byte[] data, int offset, int length) {
            bytes.write(data, offset, length);
        }
    }

    private static final class FakeControl implements LiveRecorder.Control {
        boolean stopped;
        int pauses;
        int stopAfterPauses = Integer.MAX_VALUE;
        final List<Closeable> registered = new ArrayList<>();
        int released;

        @Override public boolean stopRequested() {
            return stopped;
        }

        @Override public void onStop(Closeable closeable) {
            registered.add(closeable);
        }

        @Override public void release(Closeable closeable) {
            released++;
        }

        @Override public boolean pause(long millis) {
            pauses++;
            if (pauses >= stopAfterPauses) stopped = true;
            return !stopped;
        }
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String playlist(long sequence, int count, boolean ended) {
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXT-X-MEDIA-SEQUENCE:").append(sequence).append('\n');
        for (int i = 0; i < count; i++) out.append("#EXTINF:3,\ns").append(sequence + i).append(".ts\n");
        if (ended) out.append("#EXT-X-ENDLIST\n");
        return out.toString();
    }

    private static byte[] piece(int n) {
        byte[] data = new byte[5];
        Arrays.fill(data, (byte) n);
        return data;
    }

    private static String seg(int n) {
        return "https://pull.example.com/live/s" + n + ".ts";
    }

    @Test public void hlsRecordsOnlyPiecesThatAppearAfterTheStartAndEachOnce() throws IOException {
        FakeHttp http = new FakeHttp()
                .serve(LIST,
                        text(playlist(10, 3, false)),   // the past: 10, 11, 12
                        text(playlist(11, 3, false)),   // 13 is new
                        text(playlist(11, 3, false)),   // nothing new
                        text(playlist(13, 3, true)))    // 14, 15 and the end
                .serve(seg(13), piece(13)).serve(seg(14), piece(14)).serve(seg(15), piece(15))
                // If the past were fetched these would show up in the spool.
                .serve(seg(10), piece(10)).serve(seg(11), piece(11)).serve(seg(12), piece(12));
        Spool spool = new Spool();
        FakeControl control = new FakeControl();
        new LiveStreamSources.Hls(http, LIST).pump(spool, control);
        byte[] expected = new byte[15];
        for (int i = 0; i < 5; i++) {
            expected[i] = 13;
            expected[5 + i] = 14;
            expected[10 + i] = 15;
        }
        assertArrayEquals(expected, spool.bytes.toByteArray());
        assertFalse(http.requested.contains(seg(10)));
        assertFalse(http.requested.contains(seg(11)));
        assertFalse(http.requested.contains(seg(12)));
        assertEquals("each new piece once", 1, java.util.Collections.frequency(http.requested, seg(14)));
        assertEquals("every request was released", control.registered.size(), control.released);
    }

    @Test public void hlsFollowsAMasterPlaylistToItsBestStream() throws IOException {
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000\nhigh.m3u8\n";
        String high = "https://pull.example.com/live/high.m3u8";
        FakeHttp http = new FakeHttp()
                .serve(LIST, text(master))
                .serve(high, text(playlist(1, 2, false)), text(playlist(2, 2, true)))
                .serve("https://pull.example.com/live/s3.ts", piece(3));
        Spool spool = new Spool();
        new LiveStreamSources.Hls(http, LIST).pump(spool, new FakeControl());
        assertEquals(LIST, http.requested.get(0));
        assertEquals(high, http.requested.get(1));
        assertEquals(5, spool.bytes.size());
        assertFalse(http.requested.contains("https://pull.example.com/live/low.m3u8"));
    }

    @Test public void hlsStopsWhenToldTo() throws IOException {
        FakeHttp http = new FakeHttp().serve(LIST, text(playlist(1, 2, false)));
        FakeControl control = new FakeControl();
        control.stopAfterPauses = 3;
        new LiveStreamSources.Hls(http, LIST).pump(new Spool(), control);
        assertTrue(control.stopped);
        assertEquals(3, control.pauses);
    }

    @Test public void hlsGivesUpAfterRepeatedFailuresInsteadOfRecordingNothingForever() {
        FakeHttp http = new FakeHttp(); // every request fails
        try {
            new LiveStreamSources.Hls(http, LIST).pump(new Spool(), new FakeControl());
            fail("a stream that can't be reached is a disconnect");
        } catch (IOException expected) {
            assertEquals(5, http.requested.size());
        }
    }

    @Test public void hlsASinglePieceThatFailsDoesNotEndTheRecording() throws IOException {
        FakeHttp http = new FakeHttp()
                .serve(LIST, text(playlist(1, 1, false)), text(playlist(2, 2, false)), text(playlist(3, 2, true)))
                .serve("https://pull.example.com/live/s3.ts", piece(3))
                .serve("https://pull.example.com/live/s4.ts", piece(4));
        // s2 is listed but never served, so its fetch fails once.
        Spool spool = new Spool();
        new LiveStreamSources.Hls(http, LIST).pump(spool, new FakeControl());
        assertEquals("pieces 3 and 4 arrived, 2 was lost", 10, spool.bytes.size());
    }

    @Test public void hlsRefusesWhatItCannotRewrite() {
        String keyed = "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:3,\ns1.ts\n";
        try {
            new LiveStreamSources.Hls(new FakeHttp().serve(LIST, text(keyed)), LIST).pump(new Spool(), new FakeControl());
            fail("encrypted pieces can't be saved");
        } catch (FlvReader.UnsupportedStream expected) {
            // refused
        } catch (IOException other) {
            fail("expected UnsupportedStream, got " + other);
        }
        String fragmented = "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:3,\ns1.m4s\n";
        try {
            new LiveStreamSources.Hls(new FakeHttp().serve(LIST, text(fragmented)), LIST).pump(new Spool(), new FakeControl());
            fail("fragmented MP4 pieces can't be joined as they are");
        } catch (FlvReader.UnsupportedStream expected) {
            // refused
        } catch (IOException other) {
            fail("expected UnsupportedStream, got " + other);
        }
    }

    @Test public void flvCopiesTheWholeDownloadAndRegistersItsStreamToCloseOnStop() throws IOException {
        byte[] stream = FlvFixtures.stream(
                FlvFixtures.avcConfigTag(0, FlvFixtures.baselineSps(20, 15, 0), FlvFixtures.pps()),
                FlvFixtures.avcFrameTag(0, true, 0, new byte[]{0x65, 1, 2}));
        FakeHttp http = new FakeHttp().serve("https://pull.example.com/room.flv", stream);
        Spool spool = new Spool();
        FakeControl control = new FakeControl();
        new LiveStreamSources.Flv(http, "https://pull.example.com/room.flv").pump(spool, control);
        assertArrayEquals(stream, spool.bytes.toByteArray());
        assertEquals(1, control.registered.size());
        assertEquals(1, control.released);
    }

    @Test public void flvRefusesAnUnsupportedVideoFormatFromTheFirstBytes() {
        byte[] hevc = FlvFixtures.stream(FlvFixtures.tag(9, 0, new byte[]{(byte) 0x9C, 'h', 'v', 'c', '1', 0, 0, 0}));
        FakeHttp http = new FakeHttp().serve("https://pull.example.com/room.flv", hevc);
        try {
            new LiveStreamSources.Flv(http, "https://pull.example.com/room.flv").pump(new Spool(), new FakeControl());
            fail("enhanced FLV can't be saved");
        } catch (FlvReader.UnsupportedStream expected) {
            // refused early
        } catch (IOException other) {
            fail("expected UnsupportedStream, got " + other);
        }
    }

    @Test public void flvStopsCopyingOnceTheRecordingIsStopped() throws IOException {
        byte[] stream = new byte[100_000];
        FakeHttp http = new FakeHttp().serve("https://pull.example.com/room.flv", stream);
        Spool spool = new Spool();
        FakeControl control = new FakeControl();
        control.stopped = true;
        new LiveStreamSources.Flv(http, "https://pull.example.com/room.flv").pump(spool, control);
        assertEquals("nothing is copied after the stop", 0, spool.bytes.size());
    }
}

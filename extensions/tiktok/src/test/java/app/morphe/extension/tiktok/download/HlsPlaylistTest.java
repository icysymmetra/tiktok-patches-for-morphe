package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** #37: reading a LIVE's HLS playlists, and handing out each piece once. */
public class HlsPlaylistTest {
    private static final String BASE = "https://pull.example.com/live/room/index.m3u8";

    private static String media(long sequence, int count, boolean ended) {
        StringBuilder text = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n");
        text.append("#EXT-X-MEDIA-SEQUENCE:").append(sequence).append('\n');
        for (int i = 0; i < count; i++) {
            text.append("#EXTINF:3.003,\nseg").append(sequence + i).append(".ts\n");
        }
        if (ended) text.append("#EXT-X-ENDLIST\n");
        return text.toString();
    }

    @Test public void aMediaPlaylistListsItsSegmentsInOrderWithAbsoluteAddresses() {
        HlsPlaylist playlist = HlsPlaylist.parse(media(40, 3, false), BASE);
        assertFalse(playlist.master);
        assertEquals(40, playlist.mediaSequence);
        assertEquals(4.0, playlist.targetSeconds, 0.001);
        assertFalse(playlist.ended);
        assertEquals(3, playlist.segments.size());
        assertEquals("https://pull.example.com/live/room/seg40.ts", playlist.segments.get(0).uri);
        assertEquals(42, playlist.segments.get(2).sequence);
        assertEquals(3.003, playlist.segments.get(1).seconds, 0.0001);
    }

    @Test public void theEndTagMarksAFinishedStream() {
        assertTrue(HlsPlaylist.parse(media(1, 2, true), BASE).ended);
    }

    @Test public void aMasterPlaylistOffersItsHighestBandwidthVariant() {
        String text = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow/index.m3u8\n"
                + "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=2500000,CODECS=\"avc1.4d401f,mp4a.40.2\"\nhigh/index.m3u8\n";
        HlsPlaylist playlist = HlsPlaylist.parse(text, BASE);
        assertTrue(playlist.master);
        assertEquals(2, playlist.variants.size());
        assertNotNull(playlist.best());
        assertEquals("https://pull.example.com/live/room/high/index.m3u8", playlist.best().uri);
        assertEquals(2_500_000L, playlist.best().bandwidth);
    }

    @Test public void aDiscontinuityTagMarksOnlyTheSegmentThatFollowsIt() {
        String text = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:7\n"
                + "#EXTINF:3,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:3,\nb.ts\n#EXTINF:3,\nc.ts\n";
        List<HlsPlaylist.Segment> segments = HlsPlaylist.parse(text, BASE).segments;
        assertFalse(segments.get(0).discontinuity);
        assertTrue(segments.get(1).discontinuity);
        assertFalse(segments.get(2).discontinuity);
    }

    @Test public void encryptedAndFragmentedMp4StreamsAreFlaggedNotGuessedAt() {
        String keyed = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n#EXTINF:3,\na.ts\n";
        assertTrue(HlsPlaylist.parse(keyed, BASE).encrypted);
        String clear = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:3,\na.ts\n";
        assertFalse(HlsPlaylist.parse(clear, BASE).encrypted);
        String mapped = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:3,\na.m4s\n";
        assertTrue(HlsPlaylist.parse(mapped, BASE).needsInitSection);
    }

    @Test public void garbageParsesToAnEmptyPlaylist() {
        HlsPlaylist playlist = HlsPlaylist.parse(null, BASE);
        assertTrue(playlist.segments.isEmpty());
        assertNull(playlist.best());
        assertTrue(HlsPlaylist.parse("<html>no</html>", BASE).segments.isEmpty());
    }

    @Test public void theFollowerSkipsWhatWasListedAtTheStartAndHandsOutEachNewPieceOnce() {
        HlsPlaylist.Follower follower = new HlsPlaylist.Follower();
        assertTrue("the backlog is the past", follower.fresh(HlsPlaylist.parse(media(100, 5, false), BASE)).isEmpty());
        // Two new pieces appear and the oldest scroll off.
        List<HlsPlaylist.Segment> next = follower.fresh(HlsPlaylist.parse(media(102, 5, false), BASE));
        assertEquals(2, next.size());
        assertEquals(105, next.get(0).sequence);
        assertEquals(106, next.get(1).sequence);
        assertTrue("a refresh with nothing new gives nothing", follower.fresh(HlsPlaylist.parse(media(102, 5, false), BASE)).isEmpty());
        List<HlsPlaylist.Segment> last = follower.fresh(HlsPlaylist.parse(media(103, 5, true), BASE));
        assertEquals(1, last.size());
        assertEquals(107, last.get(0).sequence);
        assertEquals(0, follower.missedSegments());
    }

    @Test public void theFollowerCountsPiecesTheServerDroppedBeforeTheyWereFetched() {
        HlsPlaylist.Follower follower = new HlsPlaylist.Follower();
        follower.fresh(HlsPlaylist.parse(media(10, 3, false), BASE));
        List<HlsPlaylist.Segment> next = follower.fresh(HlsPlaylist.parse(media(20, 3, false), BASE));
        assertEquals(3, next.size());
        assertEquals(20, next.get(0).sequence);
        assertEquals("pieces 13 to 19 were never fetched", 7, follower.missedSegments());
    }

    @Test public void theFollowerCarriesTheDiscontinuityFlagToTheNewPiece() {
        HlsPlaylist.Follower follower = new HlsPlaylist.Follower();
        follower.fresh(HlsPlaylist.parse(media(1, 2, false), BASE));
        String text = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:3,\nseg1.ts\n#EXTINF:3,\nseg2.ts\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:3,\nnew.ts\n";
        List<HlsPlaylist.Segment> fresh = follower.fresh(HlsPlaylist.parse(text, BASE));
        assertEquals(1, fresh.size());
        assertTrue(fresh.get(0).discontinuity);
    }
}

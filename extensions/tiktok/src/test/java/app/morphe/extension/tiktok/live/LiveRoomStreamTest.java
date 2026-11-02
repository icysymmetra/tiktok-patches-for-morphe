package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import app.morphe.extension.tiktok.download.LiveRecorder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.Map;

/** #37: choosing what to record from a room's own stream fields. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class LiveRoomStreamTest {
    private static final String JSON = "{\"common\":{},\"data\":{"
            + "\"sd\":{\"main\":{\"flv\":\"https://p.example.com/sd.flv\",\"hls\":\"https://p.example.com/sd.m3u8\"}},"
            + "\"hd\":{\"main\":{\"flv\":\"https://p.example.com/hd.flv\",\"hls\":\"https://p.example.com/hd.m3u8\"}},"
            + "\"ao\":{\"main\":{\"flv\":\"https://p.example.com/ao.flv\"}},"
            + "\"origin\":{\"main\":{\"flv\":\"https://p.example.com/o.flv\",\"hls\":\"https://p.example.com/o.m3u8\"}}}}";

    @Test public void theBestFlvIsChosenAndAudioOnlyNever() {
        LiveRoomStream.Pick pick = LiveRoomStream.choose(JSON, null, null, null, null, "someone", "123");
        assertNull(pick.problem);
        assertEquals("https://p.example.com/o.flv", pick.target.url);
        assertEquals(LiveRecorder.Transport.FLV, pick.target.transport);
        assertEquals("origin", pick.target.quality);
        assertEquals("someone", pick.target.creator);
        assertEquals("123", pick.target.room);
    }

    @Test public void theQualityBeingWatchedIsChosenWhenTheStreamHasIt() {
        LiveRoomStream.Pick pick = LiveRoomStream.choose(JSON, null, null, null, "hd", null, null);
        assertEquals("https://p.example.com/hd.flv", pick.target.url);
        LiveRoomStream.Pick missing = LiveRoomStream.choose(JSON, null, null, null, "ld", null, null);
        assertEquals("a quality the stream lacks falls to the best", "origin", missing.target.quality);
    }

    @Test public void hlsIsTheFallbackWhenThereIsNoFlv() {
        Map<String, String> hls = new LinkedHashMap<>();
        hls.put("sd", "https://p.example.com/a.m3u8");
        hls.put("hd", "https://p.example.com/b.m3u8");
        LiveRoomStream.Pick pick = LiveRoomStream.choose(null, null, hls, null, null, null, null);
        assertEquals(LiveRecorder.Transport.HLS, pick.target.transport);
        assertEquals("https://p.example.com/b.m3u8", pick.target.url);
        LiveRoomStream.Pick single = LiveRoomStream.choose(null, null, null, "https://p.example.com/one.m3u8", null, null, null);
        assertEquals("https://p.example.com/one.m3u8", single.target.url);
    }

    @Test public void theFlvMapIsUsedWhenTheJsonIsMissingOrBroken() {
        Map<String, String> flv = new LinkedHashMap<>();
        flv.put("sd", "https://p.example.com/x.flv");
        LiveRoomStream.Pick pick = LiveRoomStream.choose("{not json", flv, null, null, null, null, null);
        assertEquals("https://p.example.com/x.flv", pick.target.url);
    }

    @Test public void onlySecureAddressesAreChosenAndNothingIsAProblemNotACrash() {
        Map<String, String> flv = new LinkedHashMap<>();
        flv.put("hd", "http://p.example.com/plain.flv");
        flv.put("sd", null);
        LiveRoomStream.Pick pick = LiveRoomStream.choose(null, flv, null, null, null, null, null);
        assertNull(pick.target);
        assertNotNull(pick.problem);
        assertNotNull(LiveRoomStream.fromRoom(null, null).problem);
        assertNotNull(LiveRoomStream.fromRoom(new Object(), null).problem);
    }
}

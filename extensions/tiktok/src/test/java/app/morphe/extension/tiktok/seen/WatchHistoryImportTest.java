package app.morphe.extension.tiktok.seen;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WatchHistoryImportTest {
    private static final TimeZone ZONE = TimeZone.getTimeZone("America/New_York");
    private static final long NOW = 1_735_776_000_000L;

    /**
     * Exact watch section of Sbell-ppd/TikTokMiniGamesPrivacy/user_data_tiktok.json,
     * commit a7a2898475986804b694e9a9a981e5ebf08ba2b4. File updated 2025-04-18,
     * watches dated 2024-12-15. Other sections, including profile data, are omitted.
     */
    @Test public void theReviewedLiveLinkExportKeepsDatesAndTheLatestDuplicate() throws Exception {
        InputStream input = getClass().getResourceAsStream("/seen/reviewed-watch-history.json");
        assertNotNull(input);
        WatchHistoryImport.Records records = WatchHistoryImport.read(input, ZONE, NOW);
        assertEquals(18, records.total);
        assertEquals(15, records.videos.size());
        assertEquals(3, records.skipped);
        assertEquals(timestamp("2024-12-15 19:03:19", ZONE),
                records.videos.get("7200255970008861998").longValue());
        assertEquals(timestamp("2024-12-15 19:07:30", ZONE),
                records.videos.get("7420104946231577888").longValue());
    }

    @Test public void zoneLessDatesUseTheExplicitCapturedPhoneZone() throws Exception {
        String file = export(new JSONArray().put(row("2024-12-15 19:07:30",
                "https://www.tiktokv.com/share/video/7420104946231577888/")));
        long eastern = read(file).videos.get("7420104946231577888");
        long utc = WatchHistoryImport.read(stream(file), TimeZone.getTimeZone("UTC"), NOW)
                .videos.get("7420104946231577888");
        assertEquals(5 * 60 * 60 * 1000L, eastern - utc);
    }

    @Test public void malformedDatesAndNonTikTokLinksNeverProduceIds() throws Exception {
        JSONArray rows = new JSONArray()
                .put(row("2024-02-30 12:00:00", "https://www.tiktokv.com/share/video/1/"))
                .put(row("2024-12-15 19:07:30 UTC", "https://www.tiktokv.com/share/video/2/"))
                .put(row("2099-12-15 19:07:30", "https://www.tiktokv.com/share/video/3/"))
                .put(row("2024-12-15 19:07:30", "https://www.tiktok.com.evil.test/@me/video/4"))
                .put(row("2024-12-15 19:07:30", "http://www.tiktok.com/@me/video/5"))
                .put(row("2024-12-15 19:07:30", "https://evil@www.tiktok.com/@me/video/6"))
                .put(row("2024-12-15 19:07:30", "https://www.tiktok.com:444/@me/video/7"))
                .put(row("2024-12-15 19:07:30", "https://vm.tiktok.com/short/"))
                .put(row("2024-12-15 19:07:30", "https://www.tiktok.com/@me/video/not-an-id"))
                .put(new JSONObject().put("Date", 123).put("Link",
                        "https://www.tiktokv.com/share/video/10/"))
                .put(JSONObject.NULL);
        WatchHistoryImport.Records records = read(export(rows));
        assertTrue(records.videos.isEmpty());
        assertEquals(11, records.skipped);
    }

    @Test public void likesAndFavoritesAreNotTreatedAsWatching() throws Exception {
        JSONObject unrelated = new JSONObject().put("VideoList", new JSONArray()
                .put(row("2024-12-15 19:07:30", "https://www.tiktokv.com/share/video/1/")));
        JSONObject activity = new JSONObject().put("Watch History",
                new JSONObject().put("VideoList", new JSONArray()))
                .put("Like List", unrelated).put("Favorite Videos", unrelated);
        WatchHistoryImport.Records records = read(new JSONObject()
                .put("Your Activity", activity).toString());
        assertEquals(0, records.total);
        assertTrue(records.videos.isEmpty());
        assertReason(WatchHistoryImport.Reason.UNSUPPORTED,
                "{\"Activity\":{\"Video Browsing History\":{\"VideoList\":[]}}}");
        assertReason(WatchHistoryImport.Reason.UNSUPPORTED, unrelated.toString());
    }

    @Test public void truncatedDuplicateKeyAndInvalidUtf8FilesAreRejectedTogether() throws Exception {
        assertReason(WatchHistoryImport.Reason.DAMAGED, "{\"Your Activity\":");
        assertReason(WatchHistoryImport.Reason.DAMAGED,
                "{\"Your Activity\":{},\"Your Activity\":{}}");
        assertReason(WatchHistoryImport.Reason.DAMAGED, "{}{}");
        try {
            WatchHistoryImport.read(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}), ZONE, NOW);
            fail("invalid UTF-8 was accepted");
        } catch (WatchHistoryImport.Rejected rejected) {
            assertEquals(WatchHistoryImport.Reason.DAMAGED, rejected.reason);
            assertNotNull(rejected.getCause());
        }
    }

    @Test public void byteAndRecordBoundsRejectTheWholeBatchAndCloseTheInput() throws Exception {
        class OwnedInput extends ByteArrayInputStream {
            boolean closed;
            OwnedInput(byte[] bytes) { super(bytes); }
            @Override public void close() throws IOException { closed = true; super.close(); }
        }
        OwnedInput input = new OwnedInput(new byte[WatchHistoryImport.MAX_BYTES + 1]);
        try {
            WatchHistoryImport.read(input, ZONE, NOW);
            fail("oversized file was accepted");
        } catch (WatchHistoryImport.Rejected rejected) {
            assertEquals(WatchHistoryImport.Reason.TOO_LARGE, rejected.reason);
            assertTrue(input.closed);
        }
        JSONArray maximum = new JSONArray();
        for (int index = 0; index < SeenVideoHistory.MAX_RECORDS; index++) maximum.put(JSONObject.NULL);
        assertEquals(SeenVideoHistory.MAX_RECORDS, read(export(maximum)).skipped);
        maximum.put(JSONObject.NULL);
        assertReason(WatchHistoryImport.Reason.TOO_MANY, export(maximum));
        byte[] boundary = new byte[WatchHistoryImport.MAX_BYTES];
        Arrays.fill(boundary, (byte) ' ');
        byte[] empty = export(new JSONArray()).getBytes(StandardCharsets.UTF_8);
        System.arraycopy(empty, 0, boundary, 0, empty.length);
        assertEquals(0, WatchHistoryImport.read(new ByteArrayInputStream(boundary), ZONE, NOW).total);
    }

    @Test public void hugeValuesAndDeepJsonCannotConsumeUnboundedParserWork() throws Exception {
        assertReason(WatchHistoryImport.Reason.DAMAGED, new JSONObject()
                .put("padding", "x".repeat(64 * 1024 + 1)).toString());
        String nested = "[]";
        for (int depth = 0; depth < 30; depth++) nested = "[" + nested + "]";
        assertReason(WatchHistoryImport.Reason.DAMAGED, "{\"padding\":" + nested + "}");
    }

    @Test public void ioFailuresKeepTheirCauseAndTheInputIsClosed() throws Exception {
        final boolean[] closed = {false};
        IOException problem = new IOException("provider read failed");
        InputStream input = new InputStream() {
            @Override public int read() throws IOException { throw problem; }
            @Override public void close() { closed[0] = true; }
        };
        try {
            WatchHistoryImport.read(input, ZONE, NOW);
            fail("broken provider was accepted");
        } catch (WatchHistoryImport.Rejected rejected) {
            assertEquals(WatchHistoryImport.Reason.UNREADABLE, rejected.reason);
            assertSame(problem, rejected.getCause());
            assertTrue(closed[0]);
        }
    }

    private static JSONObject row(String date, String link) throws Exception {
        return new JSONObject().put("Date", date).put("Link", link);
    }

    private static String export(JSONArray list) throws Exception {
        return new JSONObject().put("Your Activity", new JSONObject().put("Watch History",
                new JSONObject().put("VideoList", list))).toString();
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static WatchHistoryImport.Records read(String file) throws IOException {
        return WatchHistoryImport.read(stream(file), ZONE, NOW);
    }

    private static long timestamp(String value, TimeZone zone) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setTimeZone(zone);
        return format.parse(value).getTime();
    }

    private static void assertReason(WatchHistoryImport.Reason reason, String file) throws Exception {
        try {
            read(file);
            fail("file was accepted");
        } catch (WatchHistoryImport.Rejected rejected) {
            assertEquals(reason, rejected.reason);
        }
    }
}

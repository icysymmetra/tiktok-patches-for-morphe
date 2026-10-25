package app.morphe.extension.tiktok.seen;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Hushfeed's seen-history file: what Save writes reads back whole, and a file Restore can't trust
 * is refused for a reason the row can name, before anything changes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SeenHistoryFileTest {
    private static final String HEADER = "{\"format\":\"hushfeed-seen-history\",\"schema\":1,\"exported_at\":1759800000000,";
    private static final String KNOWN_ID = "7420104946231577888";

    @Test public void whatSaveWritesReadsBackWithEveryVideoAndItsTime() throws Exception {
        Map<String, Long> watched = new HashMap<>();
        watched.put(KNOWN_ID, 1_759_790_000_000L);
        watched.put("7420104946231577001", 1_759_780_000_000L);
        watched.put("1", 1L);
        SeenHistoryFile.Encoded encoded = SeenHistoryFile.encode(watched, 1_759_800_000_000L);
        assertEquals(3, encoded.rows);

        SeenHistoryFile.Contents contents = read(encoded.bytes);
        assertEquals(watched, contents.videos);
        assertEquals(3, contents.rows);
        assertEquals(0, contents.repeats);
        WatchHistoryImport.Records records = contents.records();
        assertEquals(watched, records.videos);
        assertEquals(0, records.skipped);
        assertEquals(3, records.total);
    }

    @Test public void theFileHoldsVideoIdsAndTimesNewestFirstAndNothingElse() throws Exception {
        Map<String, Long> watched = new HashMap<>();
        watched.put("300", 1_000L);
        watched.put("100", 3_000L);
        watched.put("250", 2_000L);
        // A tie keeps one order whatever order the map gave.
        watched.put("200", 2_000L);
        String text = new String(SeenHistoryFile.encode(watched, 5_000L).bytes, StandardCharsets.UTF_8);

        JSONObject root = new JSONObject(text);
        assertEquals(new HashSet<>(Arrays.asList("format", "schema", "exported_at", "videos")), names(root));
        assertEquals(SeenHistoryFile.FORMAT, root.getString("format"));
        assertEquals(SeenHistoryFile.SCHEMA, root.getInt("schema"));
        assertEquals(5_000L, root.getLong("exported_at"));
        JSONArray videos = root.getJSONArray("videos");
        List<String> order = new ArrayList<>();
        for (int index = 0; index < videos.length(); index++) {
            JSONObject row = videos.getJSONObject(index);
            assertEquals(new HashSet<>(Arrays.asList("id", "seen")), names(row));
            order.add(row.getString("id"));
        }
        assertEquals(Arrays.asList("100", "200", "250", "300"), order);
    }

    @Test public void anEmptyHistoryWritesAFileThatReadsBackEmpty() throws Exception {
        SeenHistoryFile.Encoded encoded = SeenHistoryFile.encode(new HashMap<>(), 5_000L);
        assertEquals(0, encoded.rows);
        SeenHistoryFile.Contents contents = read(encoded.bytes);
        assertTrue(contents.videos.isEmpty());
        assertEquals(0, contents.rows);
    }

    @Test public void rowsTheReaderWouldRefuseAreLeftOutOfTheFile() throws Exception {
        Map<String, Long> watched = new LinkedHashMap<>();
        watched.put("123", 10L);
        watched.put("not-a-video", 10L);
        watched.put("", 10L);
        watched.put("123456789012345678901", 10L);
        watched.put("456", 0L);
        watched.put("789", -5L);
        watched.put("999", null);
        SeenHistoryFile.Encoded encoded = SeenHistoryFile.encode(watched, 5_000L);
        assertEquals(1, encoded.rows);
        assertEquals(java.util.Collections.singletonMap("123", 10L), read(encoded.bytes).videos);
    }

    @Test public void aHistoryPastTheCapKeepsItsNewestVideos() throws Exception {
        Map<String, Long> watched = new HashMap<>();
        int extra = 5;
        for (int index = 0; index < SeenHistoryFile.MAX_ROWS + extra; index++) {
            watched.put(String.valueOf(1_000_000 + index), 1_000L + index);
        }
        SeenHistoryFile.Encoded encoded = SeenHistoryFile.encode(watched, 5_000_000L);
        assertEquals(SeenHistoryFile.MAX_ROWS, encoded.rows);
        assertTrue("a full history outgrew the size limit", encoded.bytes.length <= SeenHistoryFile.MAX_BYTES);
        Map<String, Long> back = read(encoded.bytes).videos;
        assertEquals(SeenHistoryFile.MAX_ROWS, back.size());
        for (int index = 0; index < extra; index++) {
            assertFalse("an oldest video was kept", back.containsKey(String.valueOf(1_000_000 + index)));
        }
        assertTrue(back.containsKey(String.valueOf(1_000_000 + SeenHistoryFile.MAX_ROWS + extra - 1)));
    }

    @Test public void aVideoListedTwiceKeepsItsNewerTimeAndCountsAsARepeat() throws Exception {
        SeenHistoryFile.Contents contents = read(HEADER + "\"videos\":["
                + "{\"id\":\"5\",\"seen\":100},"
                + "{\"seen\":300,\"id\":\"5\"},"
                + "{\"id\":\"5\",\"seen\":200},"
                + "{\"id\":\"6\",\"seen\":50}]}");
        assertEquals(Long.valueOf(300), contents.videos.get("5"));
        assertEquals(Long.valueOf(50), contents.videos.get("6"));
        assertEquals(4, contents.rows);
        assertEquals(2, contents.repeats);
        assertEquals(2, contents.records().skipped);
        assertEquals(4, contents.records().total);
    }

    @Test public void fieldsMayComeInAnyOrderAndALeadingByteOrderMarkIsFine() throws Exception {
        SeenHistoryFile.Contents contents = read("﻿{\"videos\":[{\"id\":\"7\",\"seen\":9}],"
                + "\"exported_at\":10,\"schema\":1,\"format\":\"hushfeed-seen-history\"}");
        assertEquals(java.util.Collections.singletonMap("7", 9L), contents.videos);
    }

    @Test public void aFileOverTheSizeLimitIsRefusedBeforeItIsParsed() throws Exception {
        byte[] large = new byte[SeenHistoryFile.MAX_BYTES + 1];
        Arrays.fill(large, (byte) ' ');
        assertEquals(SeenHistoryFile.Reason.TOO_LARGE, rejection(large));
    }

    @Test public void aFileWithMoreRowsThanTheCapIsRefused() throws Exception {
        StringBuilder text = new StringBuilder(HEADER).append("\"videos\":[");
        for (int index = 0; index <= SeenHistoryFile.MAX_ROWS; index++) {
            if (index > 0) text.append(',');
            text.append("{\"id\":\"").append(index + 1).append("\",\"seen\":1}");
        }
        text.append("]}");
        assertTrue(text.length() < SeenHistoryFile.MAX_BYTES);
        assertEquals(SeenHistoryFile.Reason.TOO_MANY, rejection(text.toString()));
    }

    @Test public void tiktoksOwnExportAndOtherFilesAreNotOurs() throws Exception {
        byte[] tiktok;
        try (InputStream export = getClass().getResourceAsStream("/seen/reviewed-watch-history.json")) {
            assertNotNull(export);
            tiktok = export.readAllBytes();
        }
        assertEquals(SeenHistoryFile.Reason.UNSUPPORTED, rejection(tiktok));
        assertEquals(SeenHistoryFile.Reason.UNSUPPORTED, rejection(""));
        assertEquals(SeenHistoryFile.Reason.UNSUPPORTED, rejection("plain text"));
        assertEquals(SeenHistoryFile.Reason.UNSUPPORTED, rejection("[]"));
        assertEquals(SeenHistoryFile.Reason.UNSUPPORTED, rejection("{}"));
        assertEquals(SeenHistoryFile.Reason.UNSUPPORTED,
                rejection("{\"format\":\"someone-elses-history\",\"schema\":1,\"exported_at\":1,\"videos\":[]}"));
        assertEquals(SeenHistoryFile.Reason.UNSUPPORTED, rejection(new byte[]{'{', (byte) 0xC3, '(', '}'}));
    }

    @Test public void aFileFromANewerHushfeedIsNamedAsNewerWhateverItsRowsLookLike() throws Exception {
        assertEquals(SeenHistoryFile.Reason.NEWER, rejection("{\"format\":\"hushfeed-seen-history\",\"schema\":2,"
                + "\"accounts\":{\"main\":{\"videos\":[[\"1\",{\"at\":[1,2]}]]}}}"));
        assertEquals(SeenHistoryFile.Reason.NEWER, rejection("{\"schema\":7,\"format\":\"hushfeed-seen-history\"}"));
    }

    @Test public void aDamagedOrEditedFileIsRefused() throws Exception {
        String good = new String(SeenHistoryFile.encode(
                java.util.Collections.singletonMap(KNOWN_ID, 1_759_790_000_000L), 1_759_800_000_000L).bytes,
                StandardCharsets.UTF_8);
        assertEquals("truncated", SeenHistoryFile.Reason.DAMAGED, rejection(good.substring(0, good.length() / 2)));
        assertEquals("an account field", SeenHistoryFile.Reason.DAMAGED,
                rejection(HEADER + "\"account\":\"someone\",\"videos\":[]}"));
        assertEquals("a field a row doesn't have", SeenHistoryFile.Reason.DAMAGED,
                rejection(HEADER + "\"videos\":[{\"id\":\"1\",\"seen\":1,\"handle\":\"someone\"}]}"));
        assertEquals("an id that isn't a video id", SeenHistoryFile.Reason.DAMAGED,
                rejection(HEADER + "\"videos\":[{\"id\":\"abc\",\"seen\":1}]}"));
        assertEquals("an id written as a number", SeenHistoryFile.Reason.DAMAGED,
                rejection(HEADER + "\"videos\":[{\"id\":123,\"seen\":1}]}"));
        assertEquals("a negative time", SeenHistoryFile.Reason.DAMAGED,
                rejection(HEADER + "\"videos\":[{\"id\":\"1\",\"seen\":-5}]}"));
        assertEquals("a fractional time", SeenHistoryFile.Reason.DAMAGED,
                rejection(HEADER + "\"videos\":[{\"id\":\"1\",\"seen\":1.5}]}"));
        assertEquals("a zero time", SeenHistoryFile.Reason.DAMAGED,
                rejection(HEADER + "\"videos\":[{\"id\":\"1\",\"seen\":0}]}"));
        assertEquals("a time past a long", SeenHistoryFile.Reason.DAMAGED,
                rejection(HEADER + "\"videos\":[{\"id\":\"1\",\"seen\":99999999999999999999}]}"));
        assertEquals("a row without its time", SeenHistoryFile.Reason.DAMAGED,
                rejection(HEADER + "\"videos\":[{\"id\":\"1\"}]}"));
        assertEquals("a row that isn't an object", SeenHistoryFile.Reason.DAMAGED,
                rejection(HEADER + "\"videos\":[[\"1\",1]]}"));
        assertEquals("no export time", SeenHistoryFile.Reason.DAMAGED,
                rejection("{\"format\":\"hushfeed-seen-history\",\"schema\":1,\"videos\":[]}"));
        assertEquals("no schema", SeenHistoryFile.Reason.DAMAGED,
                rejection("{\"format\":\"hushfeed-seen-history\",\"exported_at\":1,\"videos\":[]}"));
        assertEquals("no list", SeenHistoryFile.Reason.DAMAGED,
                rejection("{\"format\":\"hushfeed-seen-history\",\"schema\":1,\"exported_at\":1}"));
        assertEquals("a repeated field", SeenHistoryFile.Reason.DAMAGED,
                rejection("{\"format\":\"hushfeed-seen-history\",\"schema\":1,\"schema\":1,\"exported_at\":1,\"videos\":[]}"));
        assertEquals("something after the file", SeenHistoryFile.Reason.DAMAGED,
                rejection(HEADER + "\"videos\":[]}{}"));
        StringBuilder deep = new StringBuilder("{\"format\":\"hushfeed-seen-history\",\"schema\":1,\"x\":");
        for (int index = 0; index < SeenHistoryFile.MAX_DEPTH; index++) deep.append('[');
        for (int index = 0; index < SeenHistoryFile.MAX_DEPTH; index++) deep.append(']');
        deep.append('}');
        assertEquals("nested past the limit", SeenHistoryFile.Reason.DAMAGED, rejection(deep.toString()));
    }

    @Test public void aRefusalNeverCarriesTheFilesValues() throws Exception {
        for (String text : new String[]{
                HEADER + "\"videos\":[{\"id\":\"" + KNOWN_ID + "\",\"seen\":-1}]}",
                HEADER + "\"videos\":[{\"id\":\"" + KNOWN_ID + "x\",\"seen\":1}]}",
                HEADER + "\"videos\":[{\"id\":\"" + KNOWN_ID + "\",\"seen\":1}"}) {
            try {
                read(text);
                fail("a damaged file was read");
            } catch (SeenHistoryFile.Rejected refused) {
                assertNull("a parser's message could quote the file", refused.getCause());
                assertFalse(String.valueOf(refused.getMessage()).contains(KNOWN_ID));
            }
        }
    }

    @Test public void aReadThatFailsIsUnreadableRatherThanDamaged() {
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException {
                throw new IOException("the file app went away");
            }
        };
        assertEquals(SeenHistoryFile.Reason.UNREADABLE, rejection(failing));
        assertEquals(SeenHistoryFile.Reason.UNREADABLE, rejection((InputStream) null));
    }

    private static Set<String> names(JSONObject object) {
        Set<String> names = new HashSet<>();
        for (Iterator<String> keys = object.keys(); keys.hasNext(); ) names.add(keys.next());
        return names;
    }

    private static SeenHistoryFile.Contents read(String text) throws IOException {
        return read(text.getBytes(StandardCharsets.UTF_8));
    }

    private static SeenHistoryFile.Contents read(byte[] bytes) throws IOException {
        return SeenHistoryFile.read(new ByteArrayInputStream(bytes));
    }

    private static SeenHistoryFile.Reason rejection(String text) {
        return rejection(text.getBytes(StandardCharsets.UTF_8));
    }

    private static SeenHistoryFile.Reason rejection(byte[] bytes) {
        return rejection(new ByteArrayInputStream(bytes));
    }

    private static SeenHistoryFile.Reason rejection(InputStream input) {
        try {
            SeenHistoryFile.read(input);
        } catch (SeenHistoryFile.Rejected refused) {
            return refused.reason;
        } catch (IOException other) {
            throw new AssertionError("refused without a reason", other);
        }
        throw new AssertionError("the file was accepted");
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The tags go into moov without moving a single sample: every chunk offset still lands on the
 * same media bytes, whether moov comes before the media data or after it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class Mp4TagsTest {
    private static final byte[] PAYLOAD = "frame one|frame two|frame three".getBytes(StandardCharsets.US_ASCII);
    private static final int[] CHUNKS = {0, 10, 20};

    private File source, target;

    @Before public void setUp() throws Exception {
        File cache = RuntimeEnvironment.getApplication().getCacheDir();
        source = File.createTempFile("tags-in-", ".mp4", cache);
        target = File.createTempFile("tags-out-", ".mp4", cache);
    }

    @After public void tearDown() {
        source.delete();
        target.delete();
    }

    private static Map<String, String> tags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(name(Mp4Tags.TITLE), "First line 🌿");
        tags.put(name(Mp4Tags.ARTIST), "@alice");
        tags.put(name(Mp4Tags.DATE), "2023-11-14T22:13:20Z");
        tags.put(name(Mp4Tags.COMMENT), "https://www.tiktok.com/@alice/video/123");
        tags.put(name(Mp4Tags.DESCRIPTION), "First line 🌿\nSecond line");
        return tags;
    }

    @Test public void aStreamingFileKeepsEverySampleWhereItsOffsetSays() throws Exception {
        write(streaming(false, null));
        assertTrue(Mp4Tags.write(source, target, tags()));
        byte[] out = Files.readAllBytes(target.toPath());
        assertSamplesIntact(out, false);
        assertTagged(out);
    }

    @Test public void aStreamingFileWithWideOffsetsMovesThemToo() throws Exception {
        write(streaming(true, null));
        assertTrue(Mp4Tags.write(source, target, tags()));
        byte[] out = Files.readAllBytes(target.toPath());
        assertSamplesIntact(out, true);
        assertTagged(out);
    }

    @Test public void aFileWithMoovAtTheEndKeepsItsOffsetsAsTheyWere() throws Exception {
        byte[] ftyp = ftyp();
        int mdatStart = ftyp.length;
        byte[] mdat = box("mdat", PAYLOAD);
        byte[] moov = moov(false, mdatStart + 8, null);
        write(concat(ftyp, mdat, moov));
        assertTrue(Mp4Tags.write(source, target, tags()));
        byte[] out = Files.readAllBytes(target.toPath());
        assertArrayEquals("the media data moved", Arrays.copyOfRange(out, 0, ftyp.length + mdat.length),
                concat(ftyp, mdat));
        assertSamplesIntact(out, false);
        assertTagged(out);
    }

    @Test public void tagsAlreadyThereAreKeptUnlessTheseReplaceThem() throws Exception {
        byte[] existing = box("udta", meta("mdir", item(Mp4Tags.TITLE, "old title"), item(name4("©too"), "Lavf")));
        write(streaming(false, existing));
        assertTrue(Mp4Tags.write(source, target, tags()));
        byte[] out = Files.readAllBytes(target.toPath());
        Map<String, String> read = readTags(out);
        assertEquals("First line 🌿", read.get(name(Mp4Tags.TITLE)));
        assertEquals("Lavf", read.get("©too"));
        assertSamplesIntact(out, false);
    }

    @Test public void aKeyListMetaIsLeftAloneAndTheFileGoesUntagged() throws Exception {
        byte[] existing = box("udta", meta("mdta", item(Mp4Tags.TITLE, "keyed")));
        write(streaming(false, existing));
        assertFalse(Mp4Tags.write(source, target, tags()));
    }

    @Test public void aFileWithoutOneReadableMoovIsLeftAlone() throws Exception {
        byte[] ftyp = ftyp();
        write(concat(ftyp, box("mdat", PAYLOAD)));
        assertFalse("no moov", Mp4Tags.write(source, target, tags()));
        byte[] moov = moov(false, 0, null);
        write(concat(ftyp, moov, moov, box("mdat", PAYLOAD)));
        assertFalse("two moov boxes", Mp4Tags.write(source, target, tags()));
        byte[] whole = streaming(false, null);
        write(Arrays.copyOf(whole, whole.length - 5));
        assertFalse("cut off", Mp4Tags.write(source, target, tags()));
    }

    @Test public void aFragmentedFileIsLeftAlone() throws Exception {
        byte[] whole = streaming(false, null);
        write(whole);
        assertTrue("the same file unfragmented", Mp4Tags.write(source, target, tags()));
        byte[] fragment = concat(box("moof", box("mfhd", new byte[8])), box("mdat", PAYLOAD));
        write(concat(whole, fragment));
        assertFalse("a movie fragment", Mp4Tags.write(source, target, tags()));
        write(concat(whole, box("mfra", box("mfro", new byte[8]))));
        assertFalse("a fragment index", Mp4Tags.write(source, target, tags()));
    }

    @Test public void noTagsMeansNoRewrite() throws Exception {
        write(streaming(false, null));
        assertFalse(Mp4Tags.write(source, target, new LinkedHashMap<>()));
    }

    @Test public void aNarrowOffsetThatWouldOverflowStopsTheRewrite() {
        byte[] moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", stco(false, 0xFFFFFF00L))))));
        assertFalse(Mp4Tags.shiftChunkOffsets(moov, 0, moov.length, 0, 0x200, true));
        assertTrue(Mp4Tags.shiftChunkOffsets(moov, 0, moov.length, 0xFFFFFFFFL, 0x200, true));
    }

    @Test public void theDetailsBecomeTheTagsPlayersRead() {
        DownloadDetailsTest.Post post = new DownloadDetailsTest.Post("alice", "123");
        post.desc = "  First line \nSecond line";
        Map<String, String> tags = Mp4Tags.of(new DownloadDetails(post));
        assertEquals("First line", tags.get(name(Mp4Tags.TITLE)));
        assertEquals("@alice", tags.get(name(Mp4Tags.ARTIST)));
        assertEquals("2023-11-14T22:13:20Z", tags.get(name(Mp4Tags.DATE)));
        assertEquals("https://www.tiktok.com/@alice/video/123", tags.get(name(Mp4Tags.COMMENT)));
        // The whole caption as the details file keeps it, trimmed at its ends like every value read off the post.
        assertEquals("First line \nSecond line", tags.get(name(Mp4Tags.DESCRIPTION)));
        post.desc = "";
        post.author.uniqueId = "";
        post.createTime = 0;
        Map<String, String> bare = Mp4Tags.of(new DownloadDetails(post));
        assertNull("an empty caption gives no title", bare.get(name(Mp4Tags.TITLE)));
        assertNull(bare.get(name(Mp4Tags.ARTIST)));
        assertNull(bare.get(name(Mp4Tags.DATE)));
    }

    // A file laid out for streaming: ftyp, moov, then the media data its offsets point into.
    private static byte[] streaming(boolean wide, byte[] udta) {
        byte[] ftyp = ftyp();
        int moovLength = moov(wide, 0, udta).length;
        int payloadStart = ftyp.length + moovLength + 8;
        return concat(ftyp, moov(wide, payloadStart, udta), box("mdat", PAYLOAD));
    }

    private static byte[] ftyp() {
        return box("ftyp", "isom\0\0\2\0isomiso2mp41".getBytes(StandardCharsets.ISO_8859_1));
    }

    private static byte[] moov(boolean wide, long payloadStart, byte[] udta) {
        long[] offsets = new long[CHUNKS.length];
        for (int index = 0; index < CHUNKS.length; index++) offsets[index] = payloadStart + CHUNKS[index];
        byte[] stbl = box("stbl", concat(box("stsd", new byte[8]), stco(wide, offsets)));
        byte[] trak = box("trak", concat(box("tkhd", new byte[84]),
                box("mdia", concat(box("mdhd", new byte[24]), box("minf", stbl)))));
        return box("moov", udta == null ? concat(box("mvhd", new byte[100]), trak)
                : concat(box("mvhd", new byte[100]), udta, trak));
    }

    private static byte[] stco(boolean wide, long... offsets) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        putInt(body, 0);
        putInt(body, offsets.length);
        for (long offset : offsets) {
            if (wide) putInt(body, (int) (offset >>> 32));
            putInt(body, (int) offset);
        }
        return box(wide ? "co64" : "stco", body.toByteArray());
    }

    private static byte[] meta(String handler, byte[]... items) {
        ByteArrayOutputStream hdlr = new ByteArrayOutputStream();
        putInt(hdlr, 0);
        putInt(hdlr, 0);
        hdlr.write(handler.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
        hdlr.write(new byte[13], 0, 13);
        return box("meta", concat(new byte[4], box("hdlr", hdlr.toByteArray()), box("ilst", concat(items))));
    }

    private static byte[] item(byte[] type, String value) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        putInt(data, 1);
        putInt(data, 0);
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        data.write(text, 0, text.length);
        return box(new String(type, StandardCharsets.ISO_8859_1), box("data", data.toByteArray()));
    }

    private void assertSamplesIntact(byte[] out, boolean wide) {
        List<Long> offsets = new ArrayList<>();
        collectOffsets(out, 0, out.length, wide ? "co64" : "stco", offsets);
        assertEquals(CHUNKS.length, offsets.size());
        for (int index = 0; index < CHUNKS.length; index++) {
            int at = (int) (long) offsets.get(index);
            int length = (index + 1 < CHUNKS.length ? CHUNKS[index + 1] : PAYLOAD.length) - CHUNKS[index];
            assertArrayEquals("chunk " + index + " moved", Arrays.copyOfRange(PAYLOAD, CHUNKS[index], CHUNKS[index] + length),
                    Arrays.copyOfRange(out, at, at + length));
        }
    }

    private static void assertTagged(byte[] out) {
        Map<String, String> read = readTags(out);
        assertEquals(tags(), read);
    }

    private static void collectOffsets(byte[] data, int start, int end, String table, List<Long> into) {
        for (int position = start; position < end; ) {
            int size = (int) unsigned(data, position);
            String type = new String(data, position + 4, 4, StandardCharsets.ISO_8859_1);
            if (Arrays.asList("moov", "trak", "mdia", "minf", "stbl").contains(type)) {
                collectOffsets(data, position + 8, position + size, table, into);
            } else if (type.equals(table)) {
                int count = (int) unsigned(data, position + 12);
                boolean wide = table.equals("co64");
                for (int index = 0; index < count; index++) {
                    int at = position + 16 + index * (wide ? 8 : 4);
                    into.add(wide ? (unsigned(data, at) << 32) | unsigned(data, at + 4) : unsigned(data, at));
                }
            }
            position += size;
        }
    }

    /** moov/udta/meta/ilst read back as type to text. */
    private static Map<String, String> readTags(byte[] data) {
        int moov = find(data, 0, data.length, "moov");
        assertTrue("no moov", moov >= 0);
        int udta = find(data, moov + 8, moov + (int) unsigned(data, moov), "udta");
        assertTrue("no udta", udta >= 0);
        int meta = find(data, udta + 8, udta + (int) unsigned(data, udta), "meta");
        assertTrue("no meta", meta >= 0);
        int metaEnd = meta + (int) unsigned(data, meta);
        int hdlr = find(data, meta + 12, metaEnd, "hdlr");
        assertTrue("no hdlr", hdlr >= 0);
        assertEquals("mdir", new String(data, hdlr + 16, 4, StandardCharsets.ISO_8859_1));
        int ilst = find(data, meta + 12, metaEnd, "ilst");
        assertTrue("no ilst", ilst >= 0);
        Map<String, String> tags = new LinkedHashMap<>();
        int end = ilst + (int) unsigned(data, ilst);
        for (int position = ilst + 8; position < end; ) {
            int size = (int) unsigned(data, position);
            String type = new String(data, position + 4, 4, StandardCharsets.ISO_8859_1);
            int box = position + 8;
            assertEquals("data", new String(data, box + 4, 4, StandardCharsets.ISO_8859_1));
            assertEquals("not UTF-8 text", 1, unsigned(data, box + 8));
            int dataSize = (int) unsigned(data, box);
            tags.put(type, new String(data, box + 16, dataSize - 16, StandardCharsets.UTF_8));
            position += size;
        }
        return tags;
    }

    private static int find(byte[] data, int start, int end, String wanted) {
        for (int position = start; position + 8 <= end; ) {
            int size = (int) unsigned(data, position);
            if (size < 8) return -1;
            if (new String(data, position + 4, 4, StandardCharsets.ISO_8859_1).equals(wanted)) return position;
            position += size;
        }
        return -1;
    }

    private void write(byte[] bytes) throws Exception {
        Files.write(source.toPath(), bytes);
    }

    private static String name(byte[] type) {
        return new String(type, StandardCharsets.ISO_8859_1);
    }

    private static byte[] name4(String type) {
        return type.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] box(String type, byte[] body) {
        byte[] box = new byte[8 + body.length];
        long size = box.length;
        box[0] = (byte) (size >>> 24);
        box[1] = (byte) (size >>> 16);
        box[2] = (byte) (size >>> 8);
        box[3] = (byte) size;
        System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, box, 4, 4);
        System.arraycopy(body, 0, box, 8, body.length);
        return box;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static void putInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static long unsigned(byte[] data, int at) {
        return ((data[at] & 0xFFL) << 24) | ((data[at + 1] & 0xFFL) << 16) | ((data[at + 2] & 0xFFL) << 8) | (data[at + 3] & 0xFFL);
    }
}

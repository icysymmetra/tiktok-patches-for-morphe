package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A GIF89a reader written against the specification, not against the encoder, so a file
 * this project wrote is read back by something that never saw how it was written.
 */
final class GifReader {
    final int[] palette;
    final int transparentIndex;
    final List<int[]> frames = new ArrayList<>();
    final List<Integer> delays = new ArrayList<>();
    boolean looping;

    GifReader(byte[] data, int width, int height) {
        assertEquals("GIF89a", new String(data, 0, 6, StandardCharsets.US_ASCII));
        assertEquals("trailer", 0x3B, data[data.length - 1] & 0xFF);
        assertEquals(width, read16(data, 6));
        assertEquals(height, read16(data, 8));
        int packed = data[10] & 0xFF;
        assertTrue("global colour table", (packed & 0x80) != 0);
        int tableSize = 1 << ((packed & 0x07) + 1);
        palette = new int[tableSize];
        int at = 13;
        for (int index = 0; index < tableSize; index++) {
            palette[index] = ((data[at] & 0xFF) << 16) | ((data[at + 1] & 0xFF) << 8) | (data[at + 2] & 0xFF);
            at += 3;
        }

        int transparent = -1;
        while (true) {
            int block = data[at++] & 0xFF;
            if (block == 0x3B) break;
            if (block == 0x21) {
                int label = data[at++] & 0xFF;
                if (label == 0xF9) {
                    assertEquals(4, data[at] & 0xFF);
                    int flags = data[at + 1] & 0xFF;
                    delays.add(read16(data, at + 2));
                    assertTrue("transparency flag", (flags & 0x01) != 0);
                    assertEquals("dispose to background", 2, (flags >> 2) & 0x07);
                    transparent = data[at + 4] & 0xFF;
                    at += 5;
                    assertEquals(0, data[at++] & 0xFF);
                } else if (label == 0xFF) {
                    int size = data[at++] & 0xFF;
                    looping |= "NETSCAPE2.0".equals(new String(data, at, size, StandardCharsets.US_ASCII));
                    at = skipSubBlocks(data, at + size);
                } else {
                    at = skipSubBlocks(data, at + 1);
                }
            } else if (block == 0x2C) {
                assertEquals(0, read16(data, at));
                assertEquals(0, read16(data, at + 2));
                assertEquals(width, read16(data, at + 4));
                assertEquals(height, read16(data, at + 6));
                assertEquals("no local colour table", 0, data[at + 8] & 0x80);
                at += 9;
                int minCodeSize = data[at++] & 0xFF;
                ByteArrayOutputStream lzw = new ByteArrayOutputStream();
                while (true) {
                    int size = data[at++] & 0xFF;
                    if (size == 0) break;
                    lzw.write(data, at, size);
                    at += size;
                }
                frames.add(decode(lzw.toByteArray(), minCodeSize, width * height));
            } else {
                throw new IllegalStateException("unknown block 0x" + Integer.toHexString(block));
            }
        }
        transparentIndex = transparent;
    }

    private static int skipSubBlocks(byte[] data, int at) {
        while (true) {
            int size = data[at++] & 0xFF;
            if (size == 0) return at;
            at += size;
        }
    }

    private static int read16(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
    }

    /**
     * GIF's LZW, codes least significant bit first. The reader is one entry behind the
     * writer, because it only learns an entry once the code after it arrives, which is
     * exactly why the writer widens one code later than looks right.
     */
    private static int[] decode(byte[] lzw, int minCodeSize, int pixels) {
        int clear = 1 << minCodeSize, end = clear + 1;
        List<int[]> dictionary = new ArrayList<>();
        int codeSize = minCodeSize + 1;
        int[] out = new int[pixels];
        int written = 0, bitAt = 0;
        int[] previous = null;
        reset(dictionary, clear);

        while (true) {
            int code = 0;
            for (int bit = 0; bit < codeSize; bit++) {
                int byteAt = (bitAt + bit) >> 3;
                if (byteAt >= lzw.length) throw new IllegalStateException("ran out of LZW data");
                code |= ((lzw[byteAt] >> ((bitAt + bit) & 7)) & 1) << bit;
            }
            bitAt += codeSize;

            if (code == clear) {
                reset(dictionary, clear);
                codeSize = minCodeSize + 1;
                previous = null;
                continue;
            }
            if (code == end) break;

            int[] entry;
            if (code < dictionary.size()) {
                entry = dictionary.get(code);
            } else if (previous != null) {
                entry = append(previous, previous[0]);
            } else {
                throw new IllegalStateException("code " + code + " before anything defined it");
            }
            for (int value : entry) {
                if (written == out.length) throw new IllegalStateException("more pixels than the frame holds");
                out[written++] = value;
            }
            if (previous != null && dictionary.size() < 4096) {
                dictionary.add(append(previous, entry[0]));
                if (dictionary.size() >= (1 << codeSize) && codeSize < 12) codeSize++;
            }
            previous = entry;
        }
        assertEquals("pixels decoded", out.length, written);
        return out;
    }

    private static void reset(List<int[]> dictionary, int clear) {
        dictionary.clear();
        for (int index = 0; index < clear; index++) dictionary.add(new int[]{index});
        dictionary.add(new int[0]);
        dictionary.add(new int[0]);
    }

    private static int[] append(int[] head, int tail) {
        int[] result = new int[head.length + 1];
        System.arraycopy(head, 0, result, 0, head.length);
        result[head.length] = tail;
        return result;
    }
}

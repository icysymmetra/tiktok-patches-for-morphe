/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a saved video's details into the MP4 itself, as the iTunes-style tags media players,
 * gallery apps and ffprobe read: title, artist, date, comment and description.
 *
 * <p>The tags go in moov/udta/meta/ilst. Growing moov moves everything after it, so when moov comes
 * before the media data, as it does in a file built for streaming, every chunk offset that points
 * past it moves by the same amount. A file this can't follow safely is left as it was.
 */
final class Mp4Tags {
    /** A moov larger than this isn't read into memory, and the file is saved without tags. */
    static final int MAX_MOOV = 32 << 20;
    private static final int TEXT = 1;

    static final byte[] TITLE = {(byte) 0xA9, 'n', 'a', 'm'};
    static final byte[] ARTIST = {(byte) 0xA9, 'A', 'R', 'T'};
    static final byte[] DATE = {(byte) 0xA9, 'd', 'a', 'y'};
    static final byte[] COMMENT = {(byte) 0xA9, 'c', 'm', 't'};
    static final byte[] DESCRIPTION = {'d', 'e', 's', 'c'};

    private Mp4Tags() {}

    /** The tags for one save, by item type, in writing order. Empty values are left out. */
    static Map<String, String> of(DownloadDetails details) {
        Map<String, String> tags = new LinkedHashMap<>();
        put(tags, TITLE, details.title());
        put(tags, ARTIST, details.creator());
        put(tags, DATE, details.published());
        put(tags, COMMENT, details.link());
        put(tags, DESCRIPTION, details.caption());
        return tags;
    }

    private static void put(Map<String, String> tags, byte[] type, String value) {
        if (value != null && !value.isEmpty()) tags.put(new String(type, StandardCharsets.ISO_8859_1), value);
    }

    /**
     * Copies {@code source} to {@code target} with {@code tags} written in. Answers false, with
     * {@code target} left empty, when the file has no single moov this can rewrite.
     */
    static boolean write(File source, File target, Map<String, String> tags) throws IOException {
        if (tags.isEmpty()) return false;
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            long length = input.length();
            long moovStart = -1, moovSize = 0;
            long position = 0;
            while (position + 8 <= length) {
                input.seek(position);
                long size = input.readInt() & 0xFFFFFFFFL;
                int type = input.readInt();
                if (size == 1) {
                    if (position + 16 > length) return false;
                    size = input.readLong();
                    if (size < 16) return false;
                } else if (size == 0) {
                    size = length - position;
                } else if (size < 8) {
                    return false;
                }
                if (size > length - position) return false;
                // A fragmented file can carry absolute offsets in its fragments and random-access
                // index that the chunk offset shift below never sees, so it's saved untagged.
                if (type == fourCc("moof") || type == fourCc("mfra")) return false;
                if (type == fourCc("moov")) {
                    if (moovStart >= 0) return false;
                    moovStart = position;
                    moovSize = size;
                }
                position += size;
            }
            if (moovStart < 0 || moovSize > MAX_MOOV || position != length) return false;
            byte[] moov = new byte[(int) moovSize];
            input.seek(moovStart);
            input.readFully(moov);
            byte[] tagged = retag(moov, tags);
            if (tagged == null) return false;
            long delta = tagged.length - moov.length;
            if (!shiftChunkOffsets(tagged, 0, tagged.length, moovStart + moovSize, delta, true)) return false;
            try (OutputStream output = new FileOutputStream(target)) {
                copyRange(input, output, 0, moovStart);
                output.write(tagged);
                copyRange(input, output, moovStart + moovSize, length - moovStart - moovSize);
            }
            return true;
        }
    }

    /** The moov box again with udta/meta/ilst carrying {@code tags}, or null when it can't be read. */
    static byte[] retag(byte[] moov, Map<String, String> tags) {
        int header = headerSize(moov, 0, moov.length);
        if (header < 0 || fourCcAt(moov, 4) != fourCc("moov")) return null;
        ByteArrayOutputStream body = new ByteArrayOutputStream(moov.length + 4096);
        boolean placed = false;
        for (int[] child : children(moov, header, moov.length)) {
            if (child == null) return null;
            if (fourCcAt(moov, child[0] + 4) == fourCc("udta")) {
                if (placed) return null;
                byte[] udta = retagUdta(moov, child[0], child[1], tags);
                if (udta == null) return null;
                body.write(udta, 0, udta.length);
                placed = true;
            } else {
                body.write(moov, child[0], child[1]);
            }
        }
        if (!placed) {
            byte[] udta = box("udta", meta(new ArrayList<>(), tags));
            body.write(udta, 0, udta.length);
        }
        return box("moov", body.toByteArray());
    }

    private static byte[] retagUdta(byte[] data, int start, int size, Map<String, String> tags) {
        int header = headerSize(data, start, start + size);
        if (header < 0) return null;
        ByteArrayOutputStream body = new ByteArrayOutputStream(size + 4096);
        List<byte[]> kept = new ArrayList<>();
        boolean hadMeta = false;
        for (int[] child : children(data, start + header, start + size)) {
            if (child == null) return null;
            if (fourCcAt(data, child[0] + 4) != fourCc("meta")) {
                body.write(data, child[0], child[1]);
                continue;
            }
            if (hadMeta) return null;
            hadMeta = true;
            List<byte[]> items = ilstItems(data, child[0], child[1]);
            if (items == null) return null;
            for (byte[] item : items) {
                String type = new String(item, 4, 4, StandardCharsets.ISO_8859_1);
                if (!tags.containsKey(type)) kept.add(item);
            }
        }
        byte[] meta = meta(kept, tags);
        body.write(meta, 0, meta.length);
        return box("udta", body.toByteArray());
    }

    /**
     * The items of an existing udta/meta that a player would read the same way, or null when the
     * meta is another kind (an mdta key list) that this shouldn't merge into.
     */
    private static List<byte[]> ilstItems(byte[] data, int start, int size) {
        int header = headerSize(data, start, start + size);
        if (header < 0 || size < header + 4) return null;
        List<byte[]> items = new ArrayList<>();
        boolean apple = false;
        for (int[] child : children(data, start + header + 4, start + size)) {
            if (child == null) return null;
            int type = fourCcAt(data, child[0] + 4);
            if (type == fourCc("hdlr")) {
                if (child[1] < 20) return null;
                apple = fourCcAt(data, child[0] + 16) == fourCc("mdir");
            } else if (type == fourCc("ilst")) {
                int itemsHeader = headerSize(data, child[0], child[0] + child[1]);
                if (itemsHeader < 0) return null;
                for (int[] item : children(data, child[0] + itemsHeader, child[0] + child[1])) {
                    if (item == null) return null;
                    items.add(Arrays.copyOfRange(data, item[0], item[0] + item[1]));
                }
            }
        }
        return apple ? items : null;
    }

    private static byte[] meta(List<byte[]> kept, Map<String, String> tags) {
        ByteArrayOutputStream ilst = new ByteArrayOutputStream();
        for (byte[] item : kept) ilst.write(item, 0, item.length);
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            byte[] value = tag.getValue().getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream data = new ByteArrayOutputStream(value.length + 8);
            writeInt(data, TEXT);
            writeInt(data, 0);
            data.write(value, 0, value.length);
            byte[] item = box(tag.getKey(), box("data", data.toByteArray()));
            ilst.write(item, 0, item.length);
        }
        ByteArrayOutputStream hdlr = new ByteArrayOutputStream(25);
        writeInt(hdlr, 0);
        writeInt(hdlr, 0);
        writeInt(hdlr, fourCc("mdir"));
        writeInt(hdlr, fourCc("appl"));
        writeInt(hdlr, 0);
        writeInt(hdlr, 0);
        hdlr.write(0);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeInt(body, 0);
        byte[] handler = box("hdlr", hdlr.toByteArray());
        body.write(handler, 0, handler.length);
        byte[] list = box("ilst", ilst.toByteArray());
        body.write(list, 0, list.length);
        return box("meta", body.toByteArray());
    }

    /**
     * Moves every stco and co64 entry that points at or past {@code after} by {@code delta},
     * walking the boxes that hold sample tables. Answers false when a 32-bit offset would overflow.
     */
    static boolean shiftChunkOffsets(byte[] data, int start, int end, long after, long delta, boolean top) {
        if (delta == 0) return true;
        int position = start;
        while (position < end) {
            int header = headerSize(data, position, end);
            if (header < 0) return false;
            long declared = readUnsigned(data, position);
            int size = (int) (declared == 0 ? end - position : header == 16 ? readLong(data, position + 8) : declared);
            int type = fourCcAt(data, position + 4);
            if (top && type != fourCc("moov")) return false;
            if (type == fourCc("moov") || type == fourCc("trak") || type == fourCc("mdia")
                    || type == fourCc("minf") || type == fourCc("stbl")) {
                if (!shiftChunkOffsets(data, position + header, position + size, after, delta, false)) return false;
            } else if (type == fourCc("stco") || type == fourCc("co64")) {
                boolean wide = type == fourCc("co64");
                int count = (int) readUnsigned(data, position + header + 4);
                int entries = position + header + 8;
                int width = wide ? 8 : 4;
                if (count < 0 || (long) entries + (long) count * width > position + size) return false;
                for (int index = 0; index < count; index++) {
                    int at = entries + index * width;
                    long offset = wide ? readLong(data, at) : readUnsigned(data, at);
                    if (offset < after) continue;
                    long moved = offset + delta;
                    if (wide) {
                        writeLong(data, at, moved);
                    } else {
                        if (moved > 0xFFFFFFFFL) return false;
                        writeUnsigned(data, at, moved);
                    }
                }
            }
            position += size;
        }
        return position == end;
    }

    /** Each child box of the range as {start, size}, with a null where one can't be read. */
    private static List<int[]> children(byte[] data, int start, int end) {
        List<int[]> children = new ArrayList<>();
        int position = start;
        while (position < end) {
            int header = headerSize(data, position, end);
            if (header < 0) {
                children.add(null);
                return children;
            }
            long declared = readUnsigned(data, position);
            long size = declared == 0 ? end - position : header == 16 ? readLong(data, position + 8) : declared;
            children.add(new int[]{position, (int) size});
            position += (int) size;
        }
        return children;
    }

    /** The header length of the box at {@code position}, or -1 when it doesn't fit before {@code end}. */
    private static int headerSize(byte[] data, int position, int end) {
        if (end - position < 8) return -1;
        long size = readUnsigned(data, position);
        int header = 8;
        if (size == 1) {
            if (end - position < 16) return -1;
            size = readLong(data, position + 8);
            header = 16;
        } else if (size == 0) {
            size = end - position;
        }
        if (size < header || size > end - position) return -1;
        return header;
    }

    private static byte[] box(String type, byte[] body) {
        byte[] box = new byte[8 + body.length];
        writeUnsigned(box, 0, box.length);
        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(name, 0, box, 4, 4);
        System.arraycopy(body, 0, box, 8, body.length);
        return box;
    }

    private static void copyRange(RandomAccessFile input, OutputStream output, long from, long count) throws IOException {
        byte[] buffer = new byte[65536];
        input.seek(from);
        while (count > 0) {
            MediaBudget.check(null);
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, count));
            if (read < 0) throw new IOException("The video ended early while its tags were written");
            output.write(buffer, 0, read);
            count -= read;
        }
    }

    static int fourCc(String type) {
        byte[] bytes = type.getBytes(StandardCharsets.ISO_8859_1);
        return (bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
    }

    private static int fourCcAt(byte[] data, int at) {
        return (int) readUnsigned(data, at);
    }

    private static long readUnsigned(byte[] data, int at) {
        return ((data[at] & 0xFFL) << 24) | ((data[at + 1] & 0xFFL) << 16) | ((data[at + 2] & 0xFFL) << 8) | (data[at + 3] & 0xFFL);
    }

    private static long readLong(byte[] data, int at) {
        return (readUnsigned(data, at) << 32) | readUnsigned(data, at + 4);
    }

    private static void writeUnsigned(byte[] data, int at, long value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }

    private static void writeLong(byte[] data, int at, long value) {
        writeUnsigned(data, at, value >>> 32);
        writeUnsigned(data, at + 4, value);
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value >>> 24);
        output.write(value >>> 16);
        output.write(value >>> 8);
        output.write(value);
    }
}

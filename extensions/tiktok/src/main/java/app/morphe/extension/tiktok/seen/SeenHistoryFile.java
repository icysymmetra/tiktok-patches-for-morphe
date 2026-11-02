/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seen;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hushfeed's own seen-history file: one account's seen videos and when each was last seen,
 * written by Save seen history and read back by Restore seen history.
 *
 * <pre>
 * {"format":"hushfeed-seen-history","schema":1,"exported_at":1759800000000,"videos":[
 * {"id":"7420104946231577888","seen":1759790000000},
 * {"id":"7420104946231577001","seen":1759780000000}
 * ]}
 * </pre>
 *
 * <p>It carries video ids and times and nothing else: no account id, handle, token or setting,
 * so the file says nothing about whose it was. Ids are TikTok's numeric video ids and times are
 * epoch milliseconds. TikTok's own data export goes through {@link WatchHistoryImport}, which
 * this leaves alone.
 *
 * <p>A file is read whole and checked before anything changes: its size, its nesting, its row
 * count, every id and every time. The header is read first, so a file from a newer Hushfeed is
 * named as one even when its rows have a shape this version doesn't know. Rejections never carry
 * the file's values, so an id can't reach a log through one.
 */
public final class SeenHistoryFile {
    public static final String FORMAT = "hushfeed-seen-history";
    public static final int SCHEMA = 1;
    /** About twice what a full history of {@link #MAX_ROWS} rows takes. */
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_ROWS = SeenVideoHistory.MAX_RECORDS;
    /**
     * The deepest any file may nest. Schema 1 itself goes three deep (the root, its list, a row)
     * and its rows are checked field by field. The header pass steps over other values down to
     * this depth, so a newer schema that nests deeper is still named as newer, and a file of
     * nothing but brackets can't run the reader up.
     */
    static final int MAX_DEPTH = 32;
    private static final Pattern VIDEO_ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]{1,19}");

    public enum Reason { TOO_LARGE, TOO_MANY, UNSUPPORTED, NEWER, DAMAGED, UNREADABLE }

    public static final class Rejected extends IOException {
        public final Reason reason;

        Rejected(Reason reason) {
            this(reason, null);
        }

        Rejected(Reason reason, Throwable cause) {
            super("Seen-history file rejected: " + reason, cause);
            this.reason = reason;
        }
    }

    /** What a checked file holds: each video once, at the newest time the file gave it. */
    public static final class Contents {
        public final Map<String, Long> videos;
        /** Rows in the file, repeats included. */
        public final int rows;
        /** Rows naming a video an earlier row had already named. Only the newer time is kept. */
        public final int repeats;

        Contents(Map<String, Long> videos, int rows, int repeats) {
            this.videos = Collections.unmodifiableMap(new HashMap<>(videos));
            this.rows = rows;
            this.repeats = repeats;
        }

        /** The batch {@link SeenVideoHistory#importHistory} merges, with repeats as its skipped rows. */
        public WatchHistoryImport.Records records() {
            return new WatchHistoryImport.Records(videos, repeats, rows);
        }
    }

    /** A written file and how many videos went into it. */
    public static final class Encoded {
        public final byte[] bytes;
        public final int rows;

        Encoded(byte[] bytes, int rows) {
            this.bytes = bytes;
            this.rows = rows;
        }
    }

    private SeenHistoryFile() {}

    public static boolean isVideoId(String value) {
        return value != null && VIDEO_ID.matcher(value).matches();
    }

    /**
     * The file for these videos, newest first and at most {@link #MAX_ROWS} of them. A row the
     * reader would refuse (an id that isn't a TikTok video id, a time that isn't positive) is
     * left out, so whatever this writes reads back.
     */
    public static Encoded encode(Map<String, Long> videos, long exportedAt) {
        if (exportedAt <= 0) throw new IllegalArgumentException("Export time must be positive");
        List<Map.Entry<String, Long>> rows = new ArrayList<>();
        for (Map.Entry<String, Long> row : videos.entrySet()) {
            Long seen = row.getValue();
            if (isVideoId(row.getKey()) && seen != null && seen > 0) rows.add(row);
        }
        Collections.sort(rows, (left, right) -> {
            int byTime = Long.compare(right.getValue(), left.getValue());
            return byTime != 0 ? byTime : left.getKey().compareTo(right.getKey());
        });
        int count = Math.min(rows.size(), MAX_ROWS);
        StringBuilder out = new StringBuilder(96 + count * 58);
        out.append("{\"format\":\"").append(FORMAT)
                .append("\",\"schema\":").append(SCHEMA)
                .append(",\"exported_at\":").append(exportedAt)
                .append(",\"videos\":[");
        for (int index = 0; index < count; index++) {
            Map.Entry<String, Long> row = rows.get(index);
            out.append(index == 0 ? "\n" : ",\n")
                    .append("{\"id\":\"").append(row.getKey())
                    .append("\",\"seen\":").append(row.getValue().longValue())
                    .append('}');
        }
        out.append(count == 0 ? "]}\n" : "\n]}\n");
        return new Encoded(out.toString().getBytes(StandardCharsets.UTF_8), count);
    }

    /** Reads and checks a whole file. Nothing is changed here, whatever the file holds. */
    public static Contents read(InputStream input) throws IOException {
        String text = decode(readLimited(input));
        checkHeader(text);
        return readRows(text);
    }

    private static byte[] readLimited(InputStream input) throws Rejected {
        if (input == null) throw new Rejected(Reason.UNREADABLE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream owned = input) {
            byte[] buffer = new byte[8192];
            for (;;) {
                int count = owned.read(buffer);
                if (count < 0) break;
                if (count == 0) {
                    int value = owned.read();
                    if (value < 0) break;
                    if (bytes.size() == MAX_BYTES) throw new Rejected(Reason.TOO_LARGE);
                    bytes.write(value);
                    continue;
                }
                if (count > MAX_BYTES - bytes.size()) throw new Rejected(Reason.TOO_LARGE);
                bytes.write(buffer, 0, count);
            }
        } catch (Rejected refused) {
            throw refused;
        } catch (IOException failure) {
            throw new Rejected(Reason.UNREADABLE, failure);
        }
        return bytes.toByteArray();
    }

    private static String decode(byte[] bytes) throws Rejected {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notText) {
            throw new Rejected(Reason.UNSUPPORTED);
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * The format and schema, wherever they sit in the root object, with every other value
     * stepped over no deeper than {@link #MAX_DEPTH}. A file that never says it's ours is
     * unsupported; one that does and then breaks off is damaged.
     */
    private static void checkHeader(String text) throws Rejected {
        String format = null;
        Long schema = null;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new Rejected(Reason.UNSUPPORTED);
            reader.beginObject();
            Set<String> names = new HashSet<>();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!names.add(name)) throw new IOException("Repeated field");
                JsonToken next = reader.peek();
                if ("format".equals(name) && next == JsonToken.STRING) {
                    format = reader.nextString();
                } else if ("schema".equals(name) && next == JsonToken.NUMBER) {
                    schema = wholeNumber(reader);
                } else {
                    skip(reader, 1);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing content");
        } catch (Rejected refused) {
            // A file refused before it said it's ours is simply not one.
            throw FORMAT.equals(format) ? refused : new Rejected(Reason.UNSUPPORTED);
        } catch (IOException | RuntimeException broken) {
            // No cause: a parser's message can quote the value it stopped at.
            throw new Rejected(FORMAT.equals(format) ? Reason.DAMAGED : Reason.UNSUPPORTED);
        }
        if (!FORMAT.equals(format)) throw new Rejected(Reason.UNSUPPORTED);
        if (schema == null || schema < 1) throw new Rejected(Reason.DAMAGED);
        if (schema > SCHEMA) throw new Rejected(Reason.NEWER);
    }

    /** Steps over one value, refusing anything nested deeper than {@link #MAX_DEPTH}. */
    private static void skip(JsonReader reader, int depth) throws IOException {
        int open = 0;
        do {
            switch (reader.peek()) {
                case BEGIN_ARRAY:
                    if (depth + ++open > MAX_DEPTH) throw new Rejected(Reason.DAMAGED);
                    reader.beginArray();
                    break;
                case BEGIN_OBJECT:
                    if (depth + ++open > MAX_DEPTH) throw new Rejected(Reason.DAMAGED);
                    reader.beginObject();
                    break;
                case END_ARRAY:
                    reader.endArray();
                    open--;
                    break;
                case END_OBJECT:
                    reader.endObject();
                    open--;
                    break;
                case NAME:
                    reader.nextName();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        } while (open > 0);
    }

    /** Schema 1, field by field. Anything the format doesn't have is refused rather than ignored. */
    private static Contents readRows(String text) throws Rejected {
        Map<String, Long> videos = new HashMap<>();
        int rows = 0;
        int repeats = 0;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            reader.beginObject();
            boolean format = false;
            boolean schema = false;
            boolean exported = false;
            boolean list = false;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("format".equals(name) && !format) {
                    format = true;
                    reader.nextString();
                } else if ("schema".equals(name) && !schema) {
                    schema = true;
                    wholeNumber(reader);
                } else if ("exported_at".equals(name) && !exported) {
                    exported = true;
                    if (wholeNumber(reader) <= 0) throw new Rejected(Reason.DAMAGED);
                } else if ("videos".equals(name) && !list) {
                    list = true;
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) throw new Rejected(Reason.DAMAGED);
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (++rows > MAX_ROWS) throw new Rejected(Reason.TOO_MANY);
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new Rejected(Reason.DAMAGED);
                        reader.beginObject();
                        String id = null;
                        Long seen = null;
                        while (reader.hasNext()) {
                            String field = reader.nextName();
                            if ("id".equals(field) && id == null && reader.peek() == JsonToken.STRING) {
                                id = reader.nextString();
                            } else if ("seen".equals(field) && seen == null) {
                                seen = wholeNumber(reader);
                            } else {
                                throw new Rejected(Reason.DAMAGED);
                            }
                        }
                        reader.endObject();
                        if (!isVideoId(id) || seen == null || seen <= 0) throw new Rejected(Reason.DAMAGED);
                        Long previous = videos.get(id);
                        if (previous != null) repeats++;
                        if (previous == null || previous < seen) videos.put(id, seen);
                    }
                    reader.endArray();
                } else {
                    throw new Rejected(Reason.DAMAGED);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new Rejected(Reason.DAMAGED);
            if (!format || !schema || !exported || !list) throw new Rejected(Reason.DAMAGED);
        } catch (Rejected refused) {
            throw refused;
        } catch (IOException | RuntimeException broken) {
            throw new Rejected(Reason.DAMAGED);
        }
        return new Contents(videos, rows, repeats);
    }

    /** A plain non-negative whole number, as this format writes every number. */
    private static long wholeNumber(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new Rejected(Reason.DAMAGED);
        String token = reader.nextString();
        if (!WHOLE_NUMBER.matcher(token).matches()) throw new Rejected(Reason.DAMAGED);
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException tooLarge) {
            throw new Rejected(Reason.DAMAGED);
        }
    }
}

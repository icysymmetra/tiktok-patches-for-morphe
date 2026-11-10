/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.settings;

import android.util.JsonReader;
import android.util.JsonToken;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Parses settings without rounding numeric tokens before type validation. */
public final class SettingsJson {
    public static final class Limits {
        public final int maxDepth;
        public final int maxNodes;
        public final int maxStringChars;
        public final int maxArrayItems;
        public final int maxBytes;

        public Limits(int maxDepth, int maxNodes, int maxStringChars, int maxArrayItems) {
            this(maxDepth, maxNodes, maxStringChars, maxArrayItems, Integer.MAX_VALUE);
        }

        public Limits(int maxDepth, int maxNodes, int maxStringChars, int maxArrayItems, int maxBytes) {
            if (maxDepth < 1 || maxNodes < 1 || maxStringChars < 1
                    || maxArrayItems < 1 || maxBytes < 1) {
                throw new IllegalArgumentException("JSON limits must be positive");
            }
            this.maxDepth = maxDepth;
            this.maxNodes = maxNodes;
            this.maxStringChars = maxStringChars;
            this.maxArrayItems = maxArrayItems;
            this.maxBytes = maxBytes;
        }
    }

    private static final Limits DEFAULT_LIMITS = new Limits(
            64, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    private SettingsJson() {}

    public static JSONObject parseObject(String text) throws IOException, JSONException {
        return parseObject(text, DEFAULT_LIMITS);
    }

    public static JSONObject parseObject(byte[] utf8, Limits limits) throws IOException, JSONException {
        if (utf8 == null) throw new IOException("Invalid settings JSON");
        if (limits == null) throw new IOException("Missing JSON limits");
        if (utf8.length > limits.maxBytes) throw new IOException("Settings JSON is too large");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IOException("Invalid settings UTF-8", error);
        }
        return parseObject(text, limits);
    }

    public static JSONObject parseObject(String text, Limits limits) throws IOException, JSONException {
        if (text == null || text.indexOf('\0') >= 0) throw new IOException("Invalid settings JSON");
        if (limits == null) throw new IOException("Missing JSON limits");
        if (text.getBytes(StandardCharsets.UTF_8).length > limits.maxBytes) {
            throw new IOException("Settings JSON is too large");
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            Object value = read(reader, 0, new State(limits));
            if (!(value instanceof JSONObject) || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Expected one settings object");
            }
            return (JSONObject) value;
        }
    }

    private static Object read(JsonReader reader, int depth, State state) throws IOException, JSONException {
        if (depth > state.limits.maxDepth) throw new IOException("Settings JSON is nested too deeply");
        if (++state.nodes > state.limits.maxNodes) throw new IOException("Settings JSON has too many values");
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                reader.beginObject();
                JSONObject object = new JSONObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    checkString(name, state);
                    if (object.has(name)) throw new IOException("Duplicate setting: " + name);
                    object.put(name, read(reader, depth + 1, state));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                reader.beginArray();
                JSONArray array = new JSONArray();
                int count = 0;
                while (reader.hasNext()) {
                    if (++count > state.limits.maxArrayItems) {
                        throw new IOException("Settings JSON array is too large");
                    }
                    array.put(read(reader, depth + 1, state));
                }
                reader.endArray();
                return array;
            }
            case STRING: {
                String value = reader.nextString();
                checkString(value, state);
                return value;
            }
            case BOOLEAN: return reader.nextBoolean();
            case NULL:
                reader.nextNull();
                return JSONObject.NULL;
            case NUMBER: {
                String token = reader.nextString();
                checkString(token, state);
                try {
                    long value = Long.parseLong(token);
                    if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) return Integer.valueOf((int) value);
                    return Long.valueOf(value);
                } catch (NumberFormatException decimal) {
                    return new BigDecimal(token);
                }
            }
            default: throw new IOException("Invalid settings value");
        }
    }

    private static void checkString(String value, State state) throws IOException {
        if (value.length() > state.limits.maxStringChars) {
            throw new IOException("Settings JSON string is too long");
        }
    }

    private static final class State {
        final Limits limits;
        int nodes;

        State(Limits limits) {
            this.limits = limits;
        }
    }
}

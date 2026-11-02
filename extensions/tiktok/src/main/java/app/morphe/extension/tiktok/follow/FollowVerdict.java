/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.follow;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * What the server said about a follow.
 *
 * A follow that does not land comes back as an ordinary HTTP 200 carrying a refusal in the
 * body, so the only way to attribute a report is to read {@code status_code} and
 * {@code status_msg} out of it. The body reaches this class either as a parsed model, whose
 * field names TikTok leaves alone, or as the raw JSON text, so both shapes are read here.
 */
public final class FollowVerdict {
    public static final String UNKNOWN = "unknown";

    private static final String[] CODE_NAMES = {"status_code", "statusCode", "error_code", "errorCode"};
    private static final String[] MESSAGE_NAMES = {"status_msg", "statusMsg", "error_msg", "errorMsg"};

    public final String statusCode;
    public final String statusMsg;

    private FollowVerdict(String statusCode, String statusMsg) {
        this.statusCode = statusCode == null || statusCode.isEmpty() ? UNKNOWN : statusCode;
        this.statusMsg = statusMsg == null || statusMsg.isEmpty() ? UNKNOWN : statusMsg;
    }

    /** True once the server named a code other than zero, which is its "accepted". */
    public boolean isRefusal() {
        return isRefusalCode(statusCode);
    }

    /**
     * Whether a status code means the server said no. The code arrives as text because the
     * model may hold it as any numeric type, so 0, "0" and "0.0" all have to read as accepted.
     */
    public static boolean isRefusalCode(String code) {
        if (code == null || UNKNOWN.equals(code)) return false;
        try {
            double parsed = Double.parseDouble(code.trim());
            return !Double.isNaN(parsed) && !Double.isInfinite(parsed) && parsed != 0d;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public boolean isEmpty() {
        return UNKNOWN.equals(statusCode) && UNKNOWN.equals(statusMsg);
    }

    @Override
    public String toString() {
        return "status_code=" + statusCode + " status_msg=" + statusMsg;
    }

    /** Reads a response body of either shape. */
    public static FollowVerdict of(Object body) {
        if (body == null) return new FollowVerdict(null, null);
        if (body instanceof CharSequence) return parse(body.toString());

        FollowVerdict read = new FollowVerdict(member(body, CODE_NAMES), member(body, MESSAGE_NAMES));
        if (!read.isEmpty()) return read;

        // Some responses hand the body over as an unparsed blob wrapped in a holder.
        return parse(String.valueOf(body));
    }

    /** Reads the two fields out of a JSON body without pulling in a parser. */
    public static FollowVerdict parse(String body) {
        if (body == null) return new FollowVerdict(null, null);
        return new FollowVerdict(value(body, CODE_NAMES), value(body, MESSAGE_NAMES));
    }

    private static String member(Object body, String[] names) {
        Class<?> type = body.getClass();
        for (String name : names) {
            String value = fieldValue(body, type, name);
            if (value != null) return value;
        }
        for (String name : names) {
            String value = getterValue(body, type, getter(name));
            if (value != null) return value;
        }
        return null;
    }

    /** {@code status_msg} is exposed as {@code getStatusMsg}, so the name has to be folded. */
    private static String getter(String name) {
        StringBuilder builder = new StringBuilder("get");
        boolean upper = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                upper = true;
                continue;
            }
            builder.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return builder.toString();
    }

    private static String fieldValue(Object body, Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                return text(field.get(body));
            } catch (NoSuchFieldException ignored) {
                // keep climbing
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String getterValue(Object body, Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                // A static getter answers for the class, not for this response.
                if (Modifier.isStatic(method.getModifiers())) continue;
                method.setAccessible(true);
                return text(method.invoke(body));
            } catch (NoSuchMethodException ignored) {
                // keep climbing
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }

    /**
     * The value of the first of {@code names} present at the top level of the JSON, quoted or
     * bare. Depth matters: TikTok wraps a per-item status under {@code data} and an object
     * that carried its own status_code would otherwise answer for the whole response.
     */
    private static String value(String json, String[] names) {
        for (String name : names) {
            String found = topLevelValue(json, name);
            if (found != null) return found;
        }
        return null;
    }

    private static String topLevelValue(String json, String name) {
        String key = '"' + name + '"';
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                if (depth == 1 && json.startsWith(key, i)) {
                    String read = readValue(json, i + key.length());
                    if (read != null) return read;
                }
                i = endOfString(json, i);
                if (i < 0) return null;
                continue;
            }
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
        }
        return null;
    }

    /** The index of the closing quote of the string that starts at {@code from}. */
    private static int endOfString(String json, int from) {
        for (int i = from + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '"') return i;
        }
        return -1;
    }

    /** The value after a key, or null when what follows is not a plain value. */
    private static String readValue(String json, int after) {
        int cursor = after;
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) cursor++;
        if (cursor >= json.length() || json.charAt(cursor) != ':') return null;
        cursor++;
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) cursor++;
        if (cursor >= json.length()) return null;

        if (json.charAt(cursor) == '"') return unescaped(json, cursor + 1);
        if (json.charAt(cursor) == '{' || json.charAt(cursor) == '[') return null;

        int end = cursor;
        while (end < json.length() && ",}] \t\r\n".indexOf(json.charAt(end)) < 0) end++;
        String bare = json.substring(cursor, end).trim();
        return bare.isEmpty() || "null".equals(bare) ? null : bare;
    }

    private static String unescaped(String json, int from) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                if (++i >= json.length()) return null;
                char escaped = json.charAt(i);
                switch (escaped) {
                    case 'n': builder.append('\n'); break;
                    case 'r': builder.append('\r'); break;
                    case 't': builder.append('\t'); break;
                    case 'u':
                        if (i + 4 >= json.length()) return null;
                        try {
                            builder.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        } catch (NumberFormatException ignored) {
                            return null;
                        }
                        i += 4;
                        break;
                    default: builder.append(escaped);
                }
                continue;
            }
            if (c == '"') return builder.toString();
            builder.append(c);
        }
        return null;
    }
}

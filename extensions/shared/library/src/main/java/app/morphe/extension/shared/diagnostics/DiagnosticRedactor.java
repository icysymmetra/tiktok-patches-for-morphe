/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.diagnostics;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes request addresses, credentials and device identifiers before diagnostics are stored. */
public final class DiagnosticRedactor {
    private static final int MAX_CHARS = 64_000;
    private static final String TRUNCATED = "\n[diagnostic text truncated]";
    private static final String HOST_SUFFIXES =
            "(?:tiktokv?\\.com|tiktokv\\.(?:us|eu)|tiktokcdn(?:-us|-eu)?\\.com|byteoversea\\.com"
                    + "|bytedance\\.com|musical\\.ly|ibyteimg\\.com)";
    private static final String[] CREDENTIAL_NAMES = {"token", "session", "sid", "secret",
            "password", "passwd", "signature", "cookie", "auth", "credential", "device_id",
            "deviceid", "install_id", "installid", "iid", "openudid", "odin", "ttwid", "uid",
            "sec_user_id", "secuid"};
    /**
     * Names carrying the id of one video, comment or message. Each of these resolves to a post
     * somebody can open, so a shared report would otherwise carry a slice of what was watched.
     * They are matched whole rather than as a substring, so an ordinary setting such as
     * {@code hide_paid_partnership} keeps its value.
     */
    private static final String[] CONTENT_ID_NAMES = {"aid", "awemeid", "aweme_id", "itemid",
            "item_id", "groupid", "group_id", "cid", "commentid", "comment_id", "msgid",
            "msg_id", "messageid", "message_id"};
    /**
     * A bare id, for the places that print a list of them with no name in front. TikTok's ids run
     * to nineteen digits; nothing else these reports carry is a number that long.
     */
    private static final String BARE_CONTENT_ID = "(?<!\\d)\\d{17,21}(?!\\d)";
    /**
     * A network address, the way Java prints one: "failed to connect to host/1.2.3.4 (port 443)
     * from /2607:fb90:... (port 38754)". The second is the phone's own, and on an IPv6 network it
     * is routable and names the subscriber. IPv6 needs "::" or four colons, so a clock time such
     * as 10:23:11 is not taken for one.
     */
    private static final String IPV4 = "(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w.])";
    private static final String IPV6 = "(?<![\\w:.])(?=[0-9A-Fa-f:]*::|(?:[0-9A-Fa-f]{0,4}:){4})"
            + "[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?:%[\\w.]+)?(?![\\w:])";
    /**
     * A creator's name, as the bundle writes it into a toast or a banner: between Unicode's
     * first-strong isolate U+2068 and its pop U+2069. Every toast is written to the buffer as it
     * is shown, in whatever language the phone speaks, so the name cannot be found by the words
     * around it. The isolate pair marks it in every language. A run with no pop is cut to the
     * end of the line, so a name that lost its closing mark is still not printed.
     */
    private static final String ISOLATED_NAME = "⁨[^⁩\\n]*⁩?";
    /**
     * An account handle, which starts with @ and stands on its own. One glued to something in
     * front of it is not a handle: an email address, or the identity hash Java prints after a
     * class name.
     */
    private static final String HANDLE = "(?<![\\w.@/:])@[A-Za-z0-9_.]{2,}";

    private static final Pattern NAMES = Pattern.compile(ISOLATED_NAME);
    private static final Pattern HANDLES = Pattern.compile(HANDLE);
    private static final Pattern URLS = Pattern.compile(
            "(?i)(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*:(?:\\\\?/){2}[^\\s\"'<>]+");
    // A flat character class avoids the recursive (label.)+ match. A long dotted value could
    // overflow the regex stack before either an export or a logging hook finished.
    private static final Pattern HOSTS = Pattern.compile(
            "(?i)(?<![a-z0-9.-])[a-z0-9.-]+\\." + HOST_SUFFIXES + "\\b(?:[:/][^\\s\"'<>]*)?");
    private static final Pattern IDS = Pattern.compile(BARE_CONTENT_ID);
    private static final Pattern ADDRESSES = Pattern.compile(IPV4 + "|" + IPV6);
    private static final Pattern ASSIGNMENTS = Pattern.compile(
            "(?<![A-Za-z0-9_-])([A-Za-z0-9_-]+)(\\\\*[\"'])?\\s*[=:]\\s*");

    private DiagnosticRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            boolean truncated = text.length() > MAX_CHARS;
            if (truncated) text = prefix(text, MAX_CHARS);
            text = NAMES.matcher(text).replaceAll("[name omitted]");
            text = HANDLES.matcher(text).replaceAll("[handle omitted]");
            text = redactFields(text);
            text = URLS.matcher(text).replaceAll("[url omitted]");
            text = HOSTS.matcher(text).replaceAll("[host omitted]");
            text = ADDRESSES.matcher(text).replaceAll("[address omitted]");
            text = IDS.matcher(text).replaceAll("[id omitted]");
            return truncated || text.length() > MAX_CHARS
                    ? prefix(text, MAX_CHARS - TRUNCATED.length()) + TRUNCATED : text;
        } catch (Throwable failure) {
            // A diagnostic must neither escape into a host hook nor fall back to its raw input.
            return "[diagnostic text omitted after redaction failure]";
        }
    }

    private static String redactFields(String text) {
        Matcher fields = ASSIGNMENTS.matcher(text);
        StringBuilder result = new StringBuilder();
        int copied = 0;
        while (fields.find()) {
            String name = fields.group(1).toLowerCase(Locale.ROOT);
            boolean credential = false;
            for (String token : CREDENTIAL_NAMES) {
                if (name.contains(token)) { credential = true; break; }
            }
            boolean contentId = isContentId(name);
            if (!credential && !contentId) continue;
            boolean quotedName = fields.group(2) != null;
            boolean header = !quotedName && (name.equals("authorization")
                    || name.equals("proxy-authorization") || name.equals("cookie") || name.equals("set-cookie"));
            int start = fields.end();
            if (start == text.length()) continue;
            int quote = start;
            while (quote < text.length() && text.charAt(quote) == '\\') quote++;
            boolean quoted = quote < text.length()
                    && (text.charAt(quote) == '"' || text.charAt(quote) == '\'');
            int end;
            String replacement = "[omitted]";
            if (header) {
                // HTTP header values occupy the line even if an opaque token happens to start
                // with a quote, bracket or our own replacement marker.
                end = start;
                while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
            } else if (quoted) {
                end = quotedEnd(text, start, quote);
                String delimiter = text.substring(start, quote + 1);
                replacement = delimiter + replacement + delimiter;
            } else if (quotedName && (text.charAt(start) == '{' || text.charAt(start) == '[')) {
                end = structuredEnd(text, start);
                if (!text.regionMatches(start, "[omitted]", 0, end - start)
                        || end - start != "[omitted]".length()) replacement = "\"[omitted]\"";
            } else {
                end = start;
                while (end < text.length()) {
                    char c = text.charAt(end);
                    if (Character.isWhitespace(c) || ";&\"'<>".indexOf(c) >= 0
                            || (quotedName && (c == ']' || c == '}'))
                            || ((!contentId || quotedName) && c == ',')) break;
                    end++;
                }
            }
            if (end == start) continue;
            result.append(text, copied, start).append(replacement);
            copied = end;
            fields.region(end, text.length());
        }
        return copied == 0 ? text : result.append(text, copied, text.length()).toString();
    }

    private static boolean isContentId(String name) {
        for (String suffix : CONTENT_ID_NAMES) {
            if (name.equals(suffix)) return true;
            if (!name.endsWith("_" + suffix)) continue;
            String prefix = name.substring(0, name.length() - suffix.length() - 1);
            if (!prefix.isEmpty() && prefix.charAt(0) != '_' && !prefix.contains("__")
                    && prefix.indexOf('-') < 0 && prefix.charAt(prefix.length() - 1) != '_') return true;
        }
        return false;
    }

    private static int quotedEnd(String text, int start, int quote) {
        char delimiter = text.charAt(quote);
        int escaping = quote - start;
        int slashes = 0;
        for (int i = quote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') { slashes++; continue; }
            if (c == delimiter && slashes % (2 * (escaping + 1)) == escaping) return i + 1;
            slashes = 0;
        }
        return text.length();
    }

    private static int structuredEnd(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') { i = quotedEnd(text, i, i) - 1; continue; }
            if (c == '{' || c == '[') depth++;
            else if ((c == '}' || c == ']') && --depth == 0) return i + 1;
        }
        return text.length();
    }

    private static String prefix(String text, int limit) {
        limit = Math.max(0, limit);
        if (text.length() <= limit) return text;
        if (limit > 0 && Character.isHighSurrogate(text.charAt(limit - 1))) limit--;
        return text.substring(0, limit);
    }

    /** A bounded copy for our sinks. Never mutate or pass a replacement into TikTok's handler. */
    public static String redactThrowable(Throwable failure) {
        if (failure == null) return "";
        StringBuilder text = new StringBuilder();
        try {
            ArrayDeque<Throwable> pending = new ArrayDeque<>();
            ArrayDeque<String> labels = new ArrayDeque<>();
            IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
            pending.add(failure);
            labels.add("");
            int frames = 0;
            boolean truncated = false;
            while (!pending.isEmpty()) {
                if (seen.size() >= 32 || text.length() >= 32_000) { truncated = true; break; }
                Throwable next = pending.removeFirst();
                String label = labels.removeFirst();
                if (seen.put(next, Boolean.TRUE) != null) {
                    text.append("[circular throwable omitted]\n");
                    continue;
                }
                text.append(label).append(next.getClass().getName());
                try {
                    String message = next.getMessage();
                    if (message != null) {
                        int available = Math.max(0, 32_000 - text.length() - 3);
                        text.append(": ").append(prefix(message, available));
                        if (message.length() > available) truncated = true;
                    }
                } catch (Throwable unreadable) { text.append(": [message unavailable]"); }
                text.append('\n');
                try {
                    for (StackTraceElement frame : next.getStackTrace()) {
                        if (++frames > 128 || text.length() >= 32_000) { truncated = true; break; }
                        String frameText = frame.toString();
                        int available = Math.max(0, 32_000 - text.length() - 5);
                        text.append("\tat ").append(prefix(frameText, available)).append('\n');
                        if (frameText.length() > available) truncated = true;
                    }
                } catch (Throwable unreadable) { text.append("[stack unavailable]\n"); }
                try {
                    Throwable cause = next.getCause();
                    if (cause != null) { pending.addLast(cause); labels.addLast("Caused by: "); }
                } catch (Throwable unreadable) { text.append("[cause unavailable]\n"); }
                for (Throwable suppressed : next.getSuppressed()) {
                    if (pending.size() >= 32) { truncated = true; break; }
                    pending.addLast(suppressed);
                    labels.addLast("Suppressed: ");
                }
            }
            if (truncated) text.append("[throwable trace truncated]\n");
        } catch (Throwable unreadable) {
            text.append("[throwable detail unavailable]\n");
        }
        return redact(text.toString());
    }
}

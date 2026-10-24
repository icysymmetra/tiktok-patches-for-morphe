/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.privacy;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The requests TikTok's own API client sends, by domain and kind, for the diagnostic export.
 *
 * <p>Every call TikTok's Retrofit fork makes runs {@code SsHttpCall.getResponseWithInterceptorChain}
 * once per attempt, from {@code execute} and from {@code enqueue} alike, and both build the call's
 * request before it runs. The patch hands this the call there; the request's host and body are
 * read off real-named getters. Video and image downloads and other companies' SDKs keep
 * connections of their own and are not counted, and the section says so.
 *
 * <p>The export takes out every TikTok host name, since a whole one carries region detail. So a
 * request is kept by its registrable domain and a kind read off the host's first label (API, log,
 * monitoring, messages, LIVE or other), neither of which carries any.
 */
public final class NetworkRequests implements LogBufferManager.ReportSection {
    static final String TITLE = "API REQUESTS";
    /** Past this many domain and kind pairs, new ones are counted together as "other domains". */
    static final int MAX_BUCKETS = 48;
    static final String OVERFLOW = "other domains";

    private static final NetworkRequests INSTANCE = new NetworkRequests();
    /** Per bucket: requests, bytes sent, and requests whose body length was not known. */
    private static final Map<String, long[]> COUNTS = new HashMap<>();
    private static volatile boolean installed;
    private static volatile boolean warned;
    private static volatile boolean bound;
    private static volatile Field requestField;
    private static volatile Class<?> requestFieldOwner;

    private NetworkRequests() {
    }

    /**
     * Called with TikTok's SsHttpCall as each attempt starts. It only reads, and nothing it does
     * may reach the request: every failure stays in here.
     */
    public static void onCall(Object call) {
        try {
            if (!installed) {
                installed = true;
                LogBufferManager.registerReportSection(INSTANCE);
            }
            Object request = request(call);
            if (request == null) return;
            String host = invokeString(request, "getHost");
            record(host, bodyLength(request));
            if (!bound) {
                bound = true;
                HookStatus.bound("api requests", "SsHttpCall chain");
            }
            if (BaseSettings.DEBUG.get() && isLogHost(host)) notePath(request, bucket(host));
        } catch (Throwable failure) {
            if (!warned) {
                warned = true;
                Logger.printException(() -> "Could not count an API request", failure);
            }
        }
    }

    static void record(String host, long sent) {
        String key = bucket(host);
        synchronized (COUNTS) {
            long[] count = COUNTS.get(key);
            if (count == null) {
                if (COUNTS.size() >= MAX_BUCKETS) key = OVERFLOW;
                count = COUNTS.get(key);
                if (count == null) {
                    count = new long[3];
                    COUNTS.put(key, count);
                }
            }
            count[0]++;
            if (sent >= 0) count[1] += sent;
            else count[2]++;
        }
    }

    /**
     * With debug on, the path of a request to a log host, in the log: which SDK sends to those
     * hosts is what a reader of the report wants to know, and the path names it where the count
     * cannot. The path only, never the query, and the host only as its domain and kind. Read on
     * its own so a build whose request has no path getter still counts the request.
     */
    private static void notePath(Object request, String bucket) {
        try {
            String path = invokeString(request, "getPath");
            if (path == null) return;
            int query = path.indexOf('?');
            String shown = query >= 0 ? path.substring(0, query) : path;
            Logger.printDebug(() -> "[Morphe NetworkRequests] log host path: " + bucket + " " + shown);
        } catch (Throwable ignored) {
            // A path getter that is missing or fails leaves the count as it is.
        }
    }

    /** Whether the host's first label says log: the kind the telemetry patches are about. */
    static boolean isLogHost(String host) {
        if (host == null || host.trim().isEmpty()) return false;
        return "log".equals(kind(host.trim().toLowerCase(Locale.ROOT).split("\\.")));
    }

    /** "tiktokv.com api": the registrable domain and the kind the first label names. */
    static String bucket(String host) {
        if (host == null || host.trim().isEmpty()) return "unknown host";
        String[] labels = host.trim().toLowerCase(Locale.ROOT).split("\\.");
        return domain(labels) + " " + kind(labels);
    }

    /** The last two labels, or three where the second-to-last is a short second level (co.jp). */
    static String domain(String[] labels) {
        int n = labels.length;
        boolean secondLevel = n > 2 && labels[n - 1].length() == 2 && labels[n - 2].length() <= 3;
        int keep = Math.min(n, secondLevel ? 3 : 2);
        StringBuilder out = new StringBuilder();
        for (int i = n - keep; i < n; i++) {
            if (out.length() > 0) out.append('.');
            out.append(labels[i]);
        }
        return out.toString();
    }

    static String kind(String[] labels) {
        if (labels.length <= 2) return "other";
        String first = labels[0].replaceAll("[0-9]", "");
        if (first.startsWith("log") || first.contains("applog")) return "log";
        if (first.startsWith("mon")) return "monitoring";
        if (first.startsWith("api")) return "api";
        if (first.startsWith("im")) return "messages";
        if (first.startsWith("webcast")) return "LIVE";
        return "other";
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public List<String> lines() {
        List<Map.Entry<String, long[]>> entries;
        synchronized (COUNTS) {
            entries = new ArrayList<>();
            for (Map.Entry<String, long[]> entry : COUNTS.entrySet()) {
                entries.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue().clone()));
            }
        }
        List<String> lines = new ArrayList<>();
        if (entries.isEmpty()) return lines;
        // Collections.sort, not List.sort: the payload's floor is API 23.
        Collections.sort(entries, (a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        lines.add("TikTok's own API client since this start; downloads and other companies' SDKs are not counted");
        for (Map.Entry<String, long[]> entry : entries) {
            long[] count = entry.getValue();
            StringBuilder line = new StringBuilder(entry.getKey()).append(": ")
                    .append(count[0]).append(count[0] == 1 ? " request, " : " requests, ")
                    .append(size(count[1])).append(" sent");
            if (count[2] > 0) line.append(" (").append(count[2]).append(" of unknown length)");
            lines.add(line.toString());
        }
        return lines;
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static Object request(Object call) throws ReflectiveOperationException {
        if (call == null) return null;
        Class<?> type = call.getClass();
        Field field = requestField;
        if (field == null || requestFieldOwner != type) {
            field = null;
            for (Class<?> c = type; c != null && field == null; c = c.getSuperclass()) {
                try {
                    field = c.getDeclaredField("originalRequest");
                } catch (NoSuchFieldException notHere) {
                    // up the chain
                }
            }
            if (field == null) throw new NoSuchFieldException("originalRequest on " + type.getName());
            field.setAccessible(true);
            requestField = field;
            requestFieldOwner = type;
        }
        return field.get(call);
    }

    private static String invokeString(Object target, String getter) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(getter);
        method.setAccessible(true);
        Object value = method.invoke(target);
        return value == null ? null : value.toString();
    }

    /** The body's own length, or -1 when there is none to read or it does not know. */
    private static long bodyLength(Object request) throws ReflectiveOperationException {
        Method getBody = request.getClass().getMethod("getBody");
        getBody.setAccessible(true);
        Object body = getBody.invoke(request);
        if (body == null) return 0;
        // The body is some implementation of TypedOutput, not always a public class.
        Method length = body.getClass().getMethod("length");
        length.setAccessible(true);
        Object value = length.invoke(body);
        return value instanceof Number ? ((Number) value).longValue() : -1;
    }

    /** The registered section, for a test to read. */
    static NetworkRequests sectionForTests() {
        return INSTANCE;
    }

    /** Forgets every count, between tests. */
    static void resetForTests() {
        synchronized (COUNTS) {
            COUNTS.clear();
        }
        installed = false;
        warned = false;
        bound = false;
    }
}

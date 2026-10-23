/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.repost;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.follow.FollowDiagnostics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/** Records a repost's native request, server answer, and the next repost-list read. */
public final class RepostDiagnostics {
    private static final String TAG = "[Morphe TikTok RepostProbe] ";
    private static final int MAX_EVENTS = 160;
    private static final long READBACK_WINDOW_MS = 120_000L;
    private static final AtomicInteger events = new AtomicInteger();

    private static volatile String recentItemHash;
    private static volatile long recentRequestAtMs;

    private RepostDiagnostics() {}

    /** The first native argument is annotated item_id; text is never recorded. */
    public static void onPublishRequested(String itemId, String text) {
        if (!reserveEvent()) return;
        recentItemHash = FollowDiagnostics.pseudonym(itemId);
        recentRequestAtMs = System.currentTimeMillis();
        String hash = recentItemHash;
        Logger.printDebug(() -> TAG + "request itemHash=" + hash + " hasNote="
                + (text != null && !text.isEmpty()));
    }

    public static void onNetworkRequest(Object request) {
        if (!loggingEnabled()) return;
        if (!"publish".equals(pathKind(request)) || !reserveEvent()) return;
        int id = System.identityHashCode(request);
        Logger.printDebug(() -> TAG + "network request id=" + id + " path=upvote/publish");
    }

    public static void onNetworkResponse(Object request, Object response) {
        if (!loggingEnabled()) return;
        if (!"publish".equals(pathKind(request)) || !reserveEvent()) return;
        int id = System.identityHashCode(request);
        String code = rawHttpCode(response);
        Logger.printDebug(() -> TAG + "network response id=" + id + " http=" + code);
    }

    public static void onNetworkThrowable(Object request, Throwable error) {
        logError(request, error, "network");
    }

    public static void onParseThrowable(Object request, Throwable error) {
        logError(request, error, "parse");
    }

    private static void logError(Object request, Throwable error, String stage) {
        if (!loggingEnabled()) return;
        if (!"publish".equals(pathKind(request)) || !reserveEvent()) return;
        int id = System.identityHashCode(request);
        String status = errorStatus(error);
        Logger.printDebug(() -> TAG + stage + " error id=" + id + " class="
                + (error == null ? "null" : error.getClass().getName())
                + " status_code=" + status);
    }

    public static void onParsedResponse(Object request, Object response) {
        if (!loggingEnabled()) return;
        String kind = pathKind(request);
        if (kind == null || !reserveEvent()) return;

        Object body = field(response, "LIZIZ");
        String http = value(response, "LIZ");
        String success = value(response, "LIZJ");
        String code = String.valueOf(field(body, "status_code"));
        String errorCode = String.valueOf(field(body, "error_code"));
        int id = System.identityHashCode(request);

        if ("publish".equals(kind)) {
            String outcome = outcome(http, success, code, errorCode);
            Logger.printDebug(() -> TAG + "parsed response id=" + id + " http=" + http
                    + " status_code=" + code + " error_code=" + errorCode
                    + " outcome=" + outcome);
        } else {
            String target = readbackTarget(body, recentItemHash, recentRequestAtMs,
                    System.currentTimeMillis());
            int count = listSize(field(body, "upvoteLists"));
            if (count < 0) count = listSize(field(body, "repostList"));
            int finalCount = count;
            Logger.printDebug(() -> TAG + "readback id=" + id + " path=" + kind
                    + " http=" + http + " status_code=" + code + " items=" + finalCount
                    + " target=" + target);
        }
    }

    /**
     * The HTTP code of the raw response the request stage sees. On 47.1.4 that class keeps it in
     * the int field LIZIZ and has no LIZ() to call, unlike the parsed response, whose LIZ()
     * answers it; both are tried so a build that adds the call is read too.
     */
    static String rawHttpCode(Object response) {
        Object code = field(response, "LIZIZ");
        if (code instanceof Number) return String.valueOf(code);
        return value(response, "LIZ");
    }

    /** A server refusal names getErrorCode, an HTTP failure getStatusCode. */
    static String errorStatus(Object error) {
        String code = value(error, "getErrorCode");
        return "unknown".equals(code) ? value(error, "getStatusCode") : code;
    }

    static String outcome(String http, String success, String statusCode, String errorCode) {
        if (!"200".equals(http) || !"true".equals(success)) return "transport_refused";
        if ("null".equals(statusCode) || "null".equals(errorCode)) return "unknown_body";
        if (!"0".equals(statusCode) || !"0".equals(errorCode)) return "server_refused";
        return "server_accepted";
    }

    static String readbackTarget(Object body, String itemHash, long requestedAt, long now) {
        if (itemHash == null || "empty".equals(itemHash) || requestedAt <= 0
                || now - requestedAt > READBACK_WINDOW_MS) return "no_recent_request";
        boolean sawPage = false;
        for (String listName : new String[] {"upvoteLists", "repostList"}) {
            Object entries = field(body, listName);
            if (!(entries instanceof Collection<?>)) continue;
            sawPage = true;
            for (Object entry : (Collection<?>) entries) {
                Object id = field(entry, "itemId");
                if (id == null) id = call(entry, "getAid");
                if (!(id instanceof String) || ((String) id).isEmpty()) continue;
                if (itemHash.equals(FollowDiagnostics.pseudonym((String) id))) return "found";
            }
        }
        return sawPage ? "not_in_page" : "unknown";
    }

    private static int listSize(Object value) {
        return value instanceof Collection<?> ? ((Collection<?>) value).size() : -1;
    }

    static String pathKind(Object request) {
        Object path = Reflect.required(request, "getPath", "repost diagnostics");
        if (!(path instanceof String)) return null;
        String value = (String) path;
        if (value.contains("/upvote/publish") && !value.contains("/publish_comment")) return "publish";
        if (value.contains("/upvote/batch_publish")) return "publish";
        if (value.contains("/upvote/item/list")) return "item/list";
        if (value.contains("/upvote/batch_list")) return "batch_list";
        if (value.contains("/upvote/list")) return "list";
        return null;
    }

    private static boolean reserveEvent() {
        if (!loggingEnabled()) return false;
        while (true) {
            int current = events.get();
            if (current >= MAX_EVENTS) return false;
            if (events.compareAndSet(current, current + 1)) return true;
        }
    }

    private static boolean loggingEnabled() {
        try {
            return BaseSettings.DEBUG.get();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Object field(Object target, String name) {
        for (Class<?> type = target == null ? null : target.getClass();
                type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                // A response model may omit this field.
            }
        }
        return null;
    }

    private static Object call(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String value(Object target, String method) {
        Object result = call(target, method);
        return result == null ? "unknown" : String.valueOf(result);
    }

}

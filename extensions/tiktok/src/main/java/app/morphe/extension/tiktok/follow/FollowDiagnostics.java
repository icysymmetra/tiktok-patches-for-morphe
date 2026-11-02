/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.follow;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.featurecontrols.CaptchaGate;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

@SuppressWarnings("unused")
public final class FollowDiagnostics {
    private static final String HOOK_FAMILY = "follow diagnostics";

    static {
        // Gated on the debug switch, which keeps its value while paused, so Pause leaves these
        // diagnostics as they were; the export says so.
        app.morphe.extension.shared.diagnostics.HookStatus.runsWhilePaused(HOOK_FAMILY);
    }
    private static final int MAX_EVENTS_PER_SESSION = 160;
    private static final long READBACK_WINDOW_MS = 30_000L;
    private static final AtomicInteger eventCount = new AtomicInteger();
    private static final AtomicInteger callId = new AtomicInteger();
    // The patched Common and Jedi calls do not nest. Entries clear failed calls;
    // each return consumes its admission even when logging was switched off.
    private static final ThreadLocal<Integer> activeCallId = new ThreadLocal<>();
    private static final Object networkContextLock = new Object();
    /**
     * The requests in flight, by identity. A request leaves when its lifecycle ends (parsed
     * response, parse error or transport error), and one that never gets there is held weakly,
     * so a request graph with its bodies and buffers is never kept for the process lifetime: the
     * 160 that used to sit here after 160 events stayed reachable until TikTok was killed.
     */
    private static final java.util.HashMap<WeakIdentityKey, FollowRequestContext> networkContexts = new java.util.HashMap<>();

    /** Identity of the request, held weakly, so the map neither confuses two requests nor keeps one alive. */
    private static final class WeakIdentityKey extends java.lang.ref.WeakReference<Object> {
        private final int hash;

        WeakIdentityKey(Object referent) {
            super(referent);
            hash = System.identityHashCode(referent);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof WeakIdentityKey)) return false;
            Object mine = get();
            return mine != null && mine == ((WeakIdentityKey) other).get();
        }
    }

    /** The readback context is worth a readback for one window; after it, a profile fetch is somebody else's. */
    private static volatile long activeReadbackSetAtMs;
    private static volatile boolean loggedSettingsSnapshot;
    private static volatile long followReadbackWindowUntil;
    private static volatile FollowRequestContext activeReadbackContext;
    /**
     * The room the last LIVE room enter named, as a hash of its id, and when it was answered.
     * A comment that the server takes but never shows (#110: from a room opened on the feed,
     * fine from the profile) may be going to a room this session never entered.
     */
    private static volatile int lastEnteredRoom;
    private static volatile long lastEnterAtMs;
    /** Query keys whose value says how a room was opened, as a word like homepage_hot. */
    private static final String[] LIVE_ROUTE_KEYS =
            {"enter_from_merge", "enter_method", "enter_from", "enter_source", "enter_type"};

    private FollowDiagnostics() {}

    static final class FollowRequestContext {
        final int id;
        final String path;
        String action = "unknown";
        String uidHash = "empty";
        String secUidHash = "empty";
        String followFrom = "unknown";
        String source = "unknown";
        String enterFrom = "unknown";
        String payload = "none";
        String responseSuccess = "unknown";
        String responseCode = "unknown";
        String bodyFollowStatus = "unknown";
        String bodyFollowerStatus = "unknown";
        String bodyIsFollowSuccess = "unknown";
        String statusCode = FollowVerdict.UNKNOWN;
        String statusMsg = FollowVerdict.UNKNOWN;
        String riskCheck = "none";
        final long createdAtMs = System.currentTimeMillis();

        FollowRequestContext(int id, String path) {
            this.id = id;
            this.path = path;
        }

        boolean hasTarget() {
            return !"empty".equals(uidHash) || !"empty".equals(secUidHash);
        }

        String summary() {
            return "id=" + id
                    + " action=" + action
                    + " uidHash=" + uidHash
                    + " secUidHash=" + secUidHash
                    + " followFrom=" + followFrom
                    + " source=" + source
                    + " enterFrom=" + enterFrom;
        }

        void copyMissingFrom(FollowRequestContext other) {
            if (other == null) return;
            if ("unknown".equals(action)) action = other.action;
            if ("empty".equals(uidHash)) uidHash = other.uidHash;
            if ("empty".equals(secUidHash)) secUidHash = other.secUidHash;
            if ("unknown".equals(followFrom)) followFrom = other.followFrom;
            if ("unknown".equals(source)) source = other.source;
            if ("unknown".equals(enterFrom)) enterFrom = other.enterFrom;
        }
    }

    private static volatile FollowRequestContext recentDirectContext;
    private static final AtomicBoolean warnedAboutRefusedFollow = new AtomicBoolean();

    public static void logCommonFollowRequest(
            int action,
            int followFrom,
            int channelId,
            int fromPre,
            String uid,
            String secUid,
            String itemId,
            String city,
            String recType,
            Map<?, ?> extra
    ) {
        activeCallId.remove();
        if (!shouldLog()) return;

        try {
            int id = nextCallId();
            if (id < 0) return;
            activeCallId.set(id);
            rememberDirectContext(id, "CommonFollowApi", action, String.valueOf(followFrom), uid, secUid, recType, null);
            logSettingsSnapshotOnce();
            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] request"
                    + " id=" + id
                    + " api=CommonFollowApi"
                    + " action=" + action
                    + " followFrom=" + followFrom
                    + " channelId=" + channelId
                    + " fromPre=" + fromPre
                    + " uidHash=" + hash(uid)
                    + " secUidHash=" + hash(secUid)
                    + " itemIdHash=" + hash(itemId)
                    + " city=" + safeShort(city)
                    + " recType=" + safeShort(recType)
                    + " extraKeys=" + describeMapKeys(extra));
        } catch (Exception ex) {
            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] common request log failed", ex);
        }
    }

    public static void logJediFollowRequest(
            String uid,
            String secUid,
            int action,
            int followFrom,
            Integer fromPre,
            String source,
            Integer followerStatus,
            String enterFrom,
            String previousPage,
            String recType,
            Integer extraStatus
    ) {
        activeCallId.remove();
        if (!shouldLog()) return;

        try {
            int id = nextCallId();
            if (id < 0) return;
            activeCallId.set(id);
            rememberDirectContext(id, "JediFollowApi", action, String.valueOf(followFrom), uid, secUid, source, enterFrom);
            logSettingsSnapshotOnce();
            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] request"
                    + " id=" + id
                    + " api=JediFollowApi"
                    + " action=" + action
                    + " followFrom=" + followFrom
                    + " fromPre=" + safeInteger(fromPre)
                    + " followerStatus=" + safeInteger(followerStatus)
                    + " extraStatus=" + safeInteger(extraStatus)
                    + " uidHash=" + hash(uid)
                    + " secUidHash=" + hash(secUid)
                    + " source=" + safeShort(source)
                    + " enterFrom=" + safeShort(enterFrom)
                    + " previousPage=" + safeShort(previousPage)
                    + " recType=" + safeShort(recType));
        } catch (Exception ex) {
            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] jedi request log failed", ex);
        }
    }

    public static void logFollowStream(Object stream) {
        Integer id = activeCallId.get();
        activeCallId.remove();
        if (id == null || !shouldLog()) return;

        try {
            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] stream"
                    + " id=" + id
                    + " class=" + (stream == null ? "null" : stream.getClass().getName()));
        } catch (Exception ex) {
            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] stream log failed", ex);
        }
    }

    public static void logFollowResult(Object followStatus) {
        Integer id = activeCallId.get();
        activeCallId.remove();
        if (id == null || !shouldLog()) return;

        try {
            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] result"
                    + " id=" + id
                    + " status=" + describeFollowStatus(followStatus));
        } catch (Exception ex) {
            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] result log failed", ex);
        }
    }

    public static void logNetworkRequest(Object request) {
        String path = followPath(request);
        if (path == null || !reserveNetworkEvent()) return;

        FollowRequestContext context = rememberNetworkContext(request, path);
        context.payload = describeRequestPayload(request, context);

        Logger.printDebug(() -> "[Morphe TikTok FollowProbe] network request"
                + " " + context.summary()
                + " path=" + path
                + " requestClass=" + className(request)
                + " requestPayload=" + context.payload
                + " requestFields=" + describeObjectFields(request));
    }

    public static void logNetworkResponse(Object request, Object response) {
        String path = followPath(request);
        if (path == null || !reserveNetworkEvent()) return;

        FollowRequestContext context = contextForRequest(request, path);

        Logger.printDebug(() -> "[Morphe TikTok FollowProbe] network response"
                + " " + context.summary()
                + " path=" + path
                + " class=" + (response == null ? "null" : response.getClass().getName()));
    }

    public static void logNetworkThrowable(Object request, Throwable throwable) {
        String path = followPath(request);
        if (path == null || !reserveNetworkEvent()) return;

        FollowRequestContext context = contextForRequest(request, path);

            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] network error"
                + " " + context.summary()
                + " path=" + path
                + " class=" + (throwable == null ? "null" : throwable.getClass().getName())
                + " message=" + safeShort(throwable == null ? null : throwable.getMessage())
                + " outcome=network_error");
        forgetRequest(request);
    }

    public static void logParsedResponse(Object request, Object response) {
        String followPath = followPath(request);
        if (followPath != null) {
            final String finalPath = followPath;
            boolean logging = reserveNetworkEvent();

            // The verdict is read whether or not logging is on: a refused follow is the
            // thing users report, and it looks like nothing happened at all. With logging
            // off or exhausted the context stays local, so the retained request map stays
            // bounded. Nothing here may throw into TikTok's network stack.
            FollowRequestContext context = logging
                    ? contextForRequest(request, finalPath)
                    : new FollowRequestContext(0, finalPath);
            // The parsed response is the end of the request's life whether or not it is logged;
            // the context in hand is a handful of short strings and the request graph goes.
            forgetRequest(request);
            try {
                readServerVerdict(response, context);
                warnAboutRefusedFollowOnce(context);
            } catch (Throwable throwable) {
                Logger.printDebug(() -> "[Morphe TikTok FollowProbe] verdict read failed: " + throwable);
            }

            if (!logging) return;
            followReadbackWindowUntil = System.currentTimeMillis() + READBACK_WINDOW_MS;
            setActiveReadback(context);

            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] parsed response"
                    + " " + context.summary()
                    + " path=" + finalPath
                    + " " + describeParsedResponse(response, context)
                    + " outcome=" + serverOutcome(context));
            return;
        }

        String chatPath = liveChatPath(request);
        if (chatPath != null) {
            logLiveChatResponse(chatPath, requestPath(request), response);
            return;
        }

        String fullPath = requestPath(request);
        if (fullPath != null && fullPath.toLowerCase(Locale.ROOT).contains("/webcast/room/enter/")) {
            logLiveEnterResponse(fullPath, response);
            return;
        }

        String readbackPath = followReadbackPath(request);
        if (readbackPath == null || !reserveNetworkEvent()) return;
        final String finalPath = readbackPath;
        FollowRequestContext context = activeReadbackIfFresh();

        Logger.printDebug(() -> "[Morphe TikTok FollowProbe] readback response"
                + " afterId=" + (context == null ? "unknown" : context.id)
                + " target=" + (context == null ? "unknown" : context.summary())
                + " path=" + finalPath
                + " " + describeReadbackResponse(response, context));
    }

    /**
     * A LIVE comment that does not post leaves no trace on the app side, so the answer to the
     * send is recorded: the transport result, the server's status code and message, and whether
     * a risk check was hidden just before. Nothing the user typed and no account id is read.
     */
    private static void logLiveChatResponse(String path, String fullPath, Object response) {
        if (!reserveNetworkEvent()) return;

        try {
            Object body = response == null ? null : readField(response, "LIZIZ");
            FollowVerdict verdict = FollowVerdict.of(body);
            String suppressed = CaptchaGate.recentlySuppressedCheckId();
            String line = "[Morphe TikTok FollowProbe] live comment response"
                    + " path=" + path
                    + " responseSuccess=" + (response == null ? "n/a" : invokeValue(response, "LIZJ"))
                    + " responseCode=" + (response == null ? "n/a" : invokeValue(response, "LIZ"))
                    + " status_code=" + verdict.statusCode
                    + " status_msg=" + safeShort(verdict.statusMsg)
                    + " riskCheck=" + (suppressed == null ? "none" : safeShort(suppressed))
                    + " room=" + roomAgainstLastEnter(fullPath)
                    + " route=" + liveRoute(fullPath)
                    + " bodyClass=" + className(body)
                    + " bodyFieldNames=" + describeFieldNames(body)
                    + " outcome=" + (FollowVerdict.isRefusalCode(verdict.statusCode) ? "refused" : "server_accept");
            Logger.printDebug(() -> line);
        } catch (Throwable throwable) {
            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] live comment response read failed: " + throwable);
        }
    }

    /**
     * The answer to TikTok entering a LIVE room: its status and how the room was opened, and the
     * room kept as a hash so a later comment can say whether it went to the same one. Recorded
     * with logging off too, so turning logging on inside a room still compares the next comment.
     */
    private static void logLiveEnterResponse(String fullPath, Object response) {
        String room = queryValue(fullPath, "room_id");
        if (room != null) {
            lastEnteredRoom = room.hashCode();
            lastEnterAtMs = System.currentTimeMillis();
        }
        if (!reserveNetworkEvent()) return;

        try {
            Object body = response == null ? null : readField(response, "LIZIZ");
            FollowVerdict verdict = FollowVerdict.of(body);
            String line = "[Morphe TikTok FollowProbe] live room enter response"
                    + " responseCode=" + (response == null ? "n/a" : invokeValue(response, "LIZ"))
                    + " status_code=" + verdict.statusCode
                    + " status_msg=" + safeShort(verdict.statusMsg)
                    + " room=" + (room == null ? "not_in_query" : "noted")
                    + " route=" + liveRoute(fullPath)
                    + " queryKeys=" + queryKeys(fullPath);
            Logger.printDebug(() -> line);
        } catch (Throwable throwable) {
            Logger.printDebug(() -> "[Morphe TikTok FollowProbe] live room enter response read failed: " + throwable);
        }
    }

    /** Whether a comment's room is the one entered last, and how long ago, never the id itself. */
    static String roomAgainstLastEnter(String fullPath) {
        String room = queryValue(fullPath, "room_id");
        if (room == null) return "not_in_query";
        if (lastEnterAtMs == 0L) return "no_enter_seen";
        long ago = Math.max(0L, (System.currentTimeMillis() - lastEnterAtMs) / 1000L);
        return (room.hashCode() == lastEnteredRoom ? "last_entered" : "other_than_last_entered")
                + ",enteredSecondsAgo=" + ago;
    }

    /** The route keys present in a LIVE request, each with its value when it is a plain word. */
    static String liveRoute(String fullPath) {
        StringBuilder route = new StringBuilder();
        for (String key : LIVE_ROUTE_KEYS) {
            String value = queryValue(fullPath, key);
            if (value == null) continue;
            if (route.length() > 0) route.append(',');
            // A word like homepage_hot or live_cell says how the room opened; anything else
            // (digits, an encoded blob) could carry an id, so only that the key was there.
            route.append(key).append(':').append(value.matches("(?=.*[a-z])[a-z0-9_]{1,40}") ? value : "set");
        }
        return route.length() == 0 ? "none" : route.toString();
    }

    /** The query's key names, never their values. */
    static String queryKeys(String fullPath) {
        int start = fullPath == null ? -1 : fullPath.indexOf('?');
        if (start < 0) return "none";
        StringBuilder keys = new StringBuilder();
        for (String pair : fullPath.substring(start + 1).split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (key.isEmpty() || !key.matches("[A-Za-z0-9_.-]{1,40}")) continue;
            if (keys.length() > 0) keys.append(',');
            keys.append(key);
            if (keys.length() > 400) break;
        }
        return keys.length() == 0 ? "none" : keys.toString();
    }

    static String queryValue(String fullPath, String key) {
        int start = fullPath == null ? -1 : fullPath.indexOf('?');
        if (start < 0) return null;
        for (String pair : fullPath.substring(start + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(key)) {
                String value = pair.substring(equals + 1);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String liveChatPath(Object request) {
        String path = requestPath(request);
        if (path == null || !path.toLowerCase(Locale.ROOT).contains("/webcast/room/chat/")) return null;
        // The query carries the room id, which a shared report has no use for.
        int query = path.indexOf('?');
        return safeShort(query < 0 ? path : path.substring(0, query));
    }

    /** Field names only, never values, so a chat body cannot carry its text or ids into a report. */
    private static String describeFieldNames(Object target) {
        if (target == null) return "null";

        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Class<?> current = target.getClass();
             current != null && current != Object.class && count < 12;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (count >= 12) break;
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (count > 0) builder.append(',');
                builder.append(field.getName());
                count++;
            }
        }
        return count == 0 ? "none" : builder.toString();
    }

    public static void logParseThrowable(Object request, Throwable throwable) {
        String path = followPath(request);
        if (path == null || !reserveNetworkEvent()) return;

        FollowRequestContext context = contextForRequest(request, path);

        Logger.printDebug(() -> "[Morphe TikTok FollowProbe] parse error"
                + " " + context.summary()
                + " path=" + path
                + " class=" + (throwable == null ? "null" : throwable.getClass().getName())
                + " message=" + safeShort(throwable == null ? null : throwable.getMessage())
                + " outcome=parse_error");
        forgetRequest(request);
    }

    private static boolean shouldLog() {
        try {
            return BaseSettings.DEBUG.get();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Clears bounded diagnostics state between deterministic runtime tests. */
    static void resetForTests() {
        eventCount.set(0);
        callId.set(0);
        activeCallId.remove();
        synchronized (networkContextLock) {
            networkContexts.clear();
        }
        loggedSettingsSnapshot = false;
        followReadbackWindowUntil = 0L;
        activeReadbackContext = null;
        recentDirectContext = null;
        warnedAboutRefusedFollow.set(false);
    }

    static int eventCountForTests() {
        return eventCount.get();
    }

    private static boolean reserveNetworkEvent() {
        try {
            if (!BaseSettings.DEBUG.get()) return false;
            while (true) {
                int current = eventCount.get();
                if (current >= MAX_EVENTS_PER_SESSION) return false;
                if (eventCount.compareAndSet(current, current + 1)) return true;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int nextCallId() {
        while (true) {
            int current = eventCount.get();
            if (current >= MAX_EVENTS_PER_SESSION) return -1;
            if (eventCount.compareAndSet(current, current + 1)) {
                return callId.incrementAndGet();
            }
        }
    }

    private static FollowRequestContext rememberNetworkContext(Object request, String path) {
        FollowRequestContext context = contextForRequest(request, path);
        setActiveReadback(context);
        return context;
    }

    private static FollowRequestContext contextForRequest(Object request, String path) {
        synchronized (networkContextLock) {
            FollowRequestContext context = request == null ? null : networkContexts.get(new WeakIdentityKey(request));
            if (context == null) {
                context = new FollowRequestContext(callId.incrementAndGet(), path);
                context.copyMissingFrom(recentDirectContextIfFresh());
                if (request != null) {
                    purgeCollectedRequestsLocked();
                    networkContexts.put(new WeakIdentityKey(request), context);
                }
            }
            return context;
        }
    }

    /** The request's lifecycle ended: nothing else will ask for its context by the request. */
    private static void forgetRequest(Object request) {
        if (request == null) return;
        synchronized (networkContextLock) {
            networkContexts.remove(new WeakIdentityKey(request));
        }
    }

    /** Keys whose request has been collected can never be looked up again, so they go on the next insert. */
    private static void purgeCollectedRequestsLocked() {
        java.util.Iterator<WeakIdentityKey> keys = networkContexts.keySet().iterator();
        while (keys.hasNext()) {
            if (keys.next().get() == null) keys.remove();
        }
    }

    /** How many requests are held, for the test that proves completed ones are not. */
    static int heldRequestsForTests() {
        synchronized (networkContextLock) {
            return networkContexts.size();
        }
    }

    private static void setActiveReadback(FollowRequestContext context) {
        activeReadbackContext = context;
        activeReadbackSetAtMs = System.currentTimeMillis();
    }

    private static FollowRequestContext activeReadbackIfFresh() {
        FollowRequestContext context = activeReadbackContext;
        if (context == null) return null;
        if (System.currentTimeMillis() - activeReadbackSetAtMs > READBACK_WINDOW_MS) {
            activeReadbackContext = null;
            return null;
        }
        return context;
    }

    private static void rememberDirectContext(
            int id,
            String api,
            int action,
            String followFrom,
            String uid,
            String secUid,
            String source,
            String enterFrom
    ) {
        FollowRequestContext context = new FollowRequestContext(id, api);
        context.action = String.valueOf(action);
        context.followFrom = safeShort(followFrom);
        context.uidHash = hash(uid);
        context.secUidHash = hash(secUid);
        context.source = safeShort(source);
        context.enterFrom = safeShort(enterFrom);
        recentDirectContext = context;
        setActiveReadback(context);
    }

    private static FollowRequestContext recentDirectContextIfFresh() {
        FollowRequestContext context = recentDirectContext;
        if (context == null) return null;
        return System.currentTimeMillis() - context.createdAtMs <= 10_000L ? context : null;
    }

    private static void logSettingsSnapshotOnce() {
        if (loggedSettingsSnapshot) return;
        loggedSettingsSnapshot = true;

        Logger.printDebug(() -> "[Morphe TikTok FollowProbe] settings"
                + " simSpoof=" + Settings.SIM_SPOOF.get()
                + " simIso=" + safeShort(Settings.SIM_SPOOF_ISO.get())
                + " simMccMnc=" + safeShort(Settings.SIMSPOOF_MCCMNC.get())
                + " simOperator=" + safeShort(Settings.SIMSPOOF_OP_NAME.get()));
    }

    private static String describeFollowStatus(Object followStatus) {
        if (followStatus == null) return "null";

        return "class=" + followStatus.getClass().getName()
                + " userIdHash=" + hash(invokeString(followStatus, "getUserId"))
                + " followStatus=" + invokeValue(followStatus, "getFollowStatus")
                + " followerStatus=" + invokeValue(followStatus, "getFollowerStatus")
                + " followFrom=" + invokeValue(followStatus, "getFollowFrom")
                + " errorCode=" + invokeValue(followStatus, "getErrorCode");
    }

    /**
     * Fills the context with what the server actually said. A refused follow comes back as an
     * ordinary success carrying a status code in the body, so this reads that code and message
     * and notes whether a risk check was hidden just before it.
     */
    private static void readServerVerdict(Object response, FollowRequestContext context) {
        if (response == null) return;

        Object body = readField(response, "LIZIZ");
        context.responseSuccess = invokeValue(response, "LIZJ");
        context.responseCode = invokeValue(response, "LIZ");
        context.bodyFollowStatus = describeSimpleValue(firstPresentValue(body, "getFollowStatus", "followStatus"));
        context.bodyFollowerStatus = describeSimpleValue(firstPresentValue(body, "getFollowerStatus", "followerStatus"));
        context.bodyIsFollowSuccess = describeSimpleValue(firstPresentValue(body, "isFollowSuccess", "getFollowSuccess", "isFollow_success", "isFollowSuccess"));

        FollowVerdict verdict = FollowVerdict.of(body);
        context.statusCode = verdict.statusCode;
        context.statusMsg = safeShort(verdict.statusMsg);

        String suppressed = CaptchaGate.recentlySuppressedCheckId();
        context.riskCheck = suppressed == null ? "none" : safeShort(suppressed);
    }

    private static String describeParsedResponse(Object response, FollowRequestContext context) {
        if (response == null) return "response=null";

        Object body = readField(response, "LIZIZ");
        Object rawResponse = readField(response, "LIZ");

        return "responseClass=" + response.getClass().getName()
                + " responseSuccess=" + context.responseSuccess
                + " responseCode=" + context.responseCode
                + " status_code=" + context.statusCode
                + " status_msg=" + context.statusMsg
                + " riskCheck=" + context.riskCheck
                + " bodyClass=" + className(body)
                + " bodyFields=" + describeObjectFields(body)
                + " rawClass=" + className(rawResponse)
                + " rawFields=" + describeObjectFields(rawResponse);
    }

    /** True only when the server said no in a way that cannot be read as anything else. */
    static boolean followWasRefused(FollowRequestContext context) {
        return context != null && (FollowVerdict.isRefusalCode(context.statusCode)
                || "false".equals(context.bodyIsFollowSuccess));
    }

    /**
     * A refused follow leaves the button looking as though it worked, so the first one of a
     * session says what happened. Once only: the same refusal repeats on every retry.
     */
    static void warnAboutRefusedFollowOnce(FollowRequestContext context) {
        if (!followWasRefused(context) || !warnedAboutRefusedFollow.compareAndSet(false, true)) return;

        // One whole sentence per shape rather than a reason, a code and a tail glued together:
        // a translator sees the sentence, and the language decides where the pieces go.
        boolean captcha = !"none".equals(context.riskCheck);
        String message;
        if (FollowVerdict.UNKNOWN.equals(context.statusMsg)) {
            message = captcha
                    ? L10n.f("TikTok refused the follow with code %1$s. A hidden CAPTCHA may be the cause.",
                            context.statusCode)
                    : L10n.f("TikTok refused the follow with code %1$s", context.statusCode);
        } else {
            message = captcha
                    ? L10n.f("TikTok refused the follow: %1$s A hidden CAPTCHA may be the cause.",
                            context.statusMsg)
                    : L10n.f("TikTok refused the follow: %1$s", context.statusMsg);
        }
        Utils.showToastLong(message);
        Logger.printInfo(() -> "[Morphe TikTok FollowProbe] refused"
                + " " + context.summary()
                + " path=" + context.path
                + " status_code=" + context.statusCode
                + " status_msg=" + context.statusMsg
                + " riskCheck=" + context.riskCheck);
    }

    private static String describeReadbackResponse(Object response, FollowRequestContext context) {
        if (response == null) return "response=null";

        Object body = readField(response, "LIZIZ");
        String userSignals = describeUserSignals(body);
        String match = targetMatch(userSignals, context);

        return "responseClass=" + response.getClass().getName()
                + " responseSuccess=" + invokeValue(response, "LIZJ")
                + " responseCode=" + invokeValue(response, "LIZ")
                + " bodyClass=" + className(body)
                + " bodyFields=" + describeObjectFields(body)
                + " targetMatch=" + match
                + " readbackOutcome=" + readbackOutcome(userSignals, context, match)
                + " userSignals=" + userSignals;
    }

    private static String describeRequestPayload(Object request, FollowRequestContext context) {
        Object payload = requestPayloadRoot(request);
        if (payload == null) return "none";

        StringBuilder builder = new StringBuilder();
        builder.append("bodyClass=").append(className(payload));
        StringBuilder params = new StringBuilder();
        int[] count = new int[]{0};
        String encodedBody = encodedFormBody(payload);
        if (encodedBody != null) {
            appendEncodedPayloadValues(encodedBody, params, context, count);
            builder.append(",encodedBody=").append(count[0] > 0 ? "parsed" : "noInterestingKeys");
        }
        appendRequestPayloadValues(payload, params, context, new IdentityHashMap<>(), 0, count);
        if (params.length() > 0) {
            builder.append(",params=").append(params);
        }
        return builder.toString();
    }

    private static Object requestPayloadRoot(Object request) {
        Object direct = firstPresentValue(request,
                "getBody", "getTypedOutput", "getRequestBody", "body", "requestBody", "typedOutput");
        if (isLikelyPayloadObject(direct)) return direct;

        if (request == null) return null;

        Class<?> currentClass = request.getClass();
        while (currentClass != null) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;

                try {
                    field.setAccessible(true);
                    Object value = field.get(request);
                    if (isLikelyPayloadObject(value)) return value;
                } catch (Exception ignored) {
                    // Ignore fields that cannot be read safely.
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        return null;
    }

    private static boolean isLikelyPayloadObject(Object value) {
        if (value == null || isSimpleValue(value)) return false;
        if (isReflectionMetadata(value)) return false;
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) return true;

        String className = value.getClass().getName().toLowerCase(Locale.US);
        return className.contains("body")
                || className.contains("form")
                || className.contains("mime")
                || className.contains("output")
                || className.contains("param")
                || className.contains("pair");
    }

    private static void appendRequestPayloadValues(
            Object target,
            StringBuilder builder,
            FollowRequestContext context,
            IdentityHashMap<Object, Boolean> seen,
            int depth,
            int[] count
    ) {
        if (target == null || depth > 4 || count[0] >= 12) return;
        if (target instanceof String) {
            appendEncodedPayloadValues((String) target, builder, context, count);
            return;
        }
        if (isSimpleValue(target)) return;
        if (isReflectionMetadata(target)) return;
        if (seen.put(target, Boolean.TRUE) != null) return;

        if (target instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) target).entrySet()) {
                if (count[0] >= 12) break;
                appendNamedPayloadValue(String.valueOf(entry.getKey()), entry.getValue(), builder, context, count);
                appendRequestPayloadValues(entry.getValue(), builder, context, seen, depth + 1, count);
            }
            return;
        }

        if (target instanceof Collection<?>) {
            int itemCount = 0;
            for (Object item : (Collection<?>) target) {
                if (itemCount++ >= 12 || count[0] >= 12) break;
                appendRequestPayloadValues(item, builder, context, seen, depth + 1, count);
            }
            return;
        }

        Object key = firstPresentValue(target, "getName", "getKey", "name", "key");
        Object value = firstPresentValue(target, "getValue", "value");
        if (key instanceof String && appendNamedPayloadValue((String) key, value, builder, context, count)) {
            return;
        }

        Class<?> currentClass = target.getClass();
        while (currentClass != null && depth < 4 && count[0] < 12) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (count[0] >= 12) break;
                if (Modifier.isStatic(field.getModifiers())) continue;

                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(target);
                    if (shouldSkipPayloadField(field.getName(), fieldValue)) continue;
                    if (appendNamedPayloadValue(field.getName(), fieldValue, builder, context, count)) {
                        continue;
                    }
                    appendRequestPayloadValues(fieldValue, builder, context, seen, depth + 1, count);
                } catch (Exception ignored) {
                    // Ignore fields that cannot be read safely.
                }
            }
            currentClass = currentClass.getSuperclass();
        }
    }

    private static boolean appendNamedPayloadValue(
            String name,
            Object value,
            StringBuilder builder,
            FollowRequestContext context,
            int[] count
    ) {
        if (name == null) return false;
        String key = normalizePayloadKey(name);
        if (!isInterestingPayloadKey(key)) return false;

        if (builder.length() > 0) builder.append(',');
        builder.append(safeShort(name)).append('=').append(describePayloadValue(key, value));
        recordPayloadTarget(context, key, value);
        count[0]++;
        return true;
    }

    private static boolean isInterestingPayloadKey(String key) {
        return key.equals("uid")
                || key.equals("user_id")
                || key.equals("userid")
                || key.equals("to_user_id")
                || key.equals("from_user_id")
                || key.equals("sec_uid")
                || key.equals("secuid")
                || key.equals("sec_user_id")
                || key.equals("secuserid")
                || key.equals("action")
                || key.equals("type")
                || key.equals("follow_status")
                || key.equals("followstatus")
                || key.equals("follow_from")
                || key.equals("followfrom")
                || key.equals("from_pre")
                || key.equals("frompre")
                || key.equals("enter_from")
                || key.equals("enterfrom")
                || key.equals("source")
                || key.equals("previous_page")
                || key.equals("previouspage")
                || key.equals("channel_id")
                || key.equals("channelid")
                || key.equals("item_id")
                || key.equals("itemid")
                || key.equals("aweme_id")
                || key.equals("awemeid")
                || key.equals("follower_status")
                || key.equals("followerstatus");
    }

    private static String describePayloadValue(String key, Object value) {
        if (value == null) return "null";
        if (isSensitivePayloadKey(key)) {
            String stringValue = String.valueOf(value);
            return "stringHash=" + hash(stringValue) + "/len=" + stringValue.length();
        }
        if (isSimpleValue(value)) {
            return safeShort(String.valueOf(value));
        }
        return "class=" + value.getClass().getName();
    }

    private static boolean isSensitivePayloadKey(String key) {
        if (key.contains("action") || key.contains("status") || key.contains("type") || key.contains("channel")) {
            return false;
        }
        return key.contains("uid")
                || key.contains("user")
                || key.contains("sec")
                || key.contains("item")
                || key.contains("aweme");
    }

    private static void recordPayloadTarget(FollowRequestContext context, String key, Object value) {
        if (context == null || value == null) return;

        String stringValue = String.valueOf(value);
        if (stringValue.isEmpty()) return;

        if (key.equals("sec_uid") || key.equals("secuid") || key.equals("sec_user_id") || key.equals("secuserid")) {
            context.secUidHash = hash(stringValue);
        } else if (key.equals("uid") || key.equals("user_id") || key.equals("userid") || key.equals("to_user_id")) {
            context.uidHash = hash(stringValue);
        } else if (key.equals("action") || key.equals("follow_status") || key.equals("followstatus") || key.equals("type")) {
            context.action = safeShort(stringValue);
        } else if (key.equals("follow_from") || key.equals("followfrom")) {
            context.followFrom = safeShort(stringValue);
        } else if (key.equals("source")) {
            context.source = safeShort(stringValue);
        } else if (key.equals("enter_from") || key.equals("enterfrom")) {
            context.enterFrom = safeShort(stringValue);
        }
    }

    private static String serverOutcome(FollowRequestContext context) {
        if (context == null) return "unknown";
        if (!"true".equals(context.responseSuccess)) return "response_not_success";
        if (!"200".equals(context.responseCode)) return "http_" + safeShort(context.responseCode);
        if (followWasRefused(context)) return "server_refused_" + context.statusCode;
        if ("unknown".equals(context.bodyFollowStatus) || "null".equals(context.bodyFollowStatus)) return "body_missing_follow_status";
        return "server_accept_" + context.bodyFollowStatus;
    }

    private static String readbackOutcome(String userSignals, FollowRequestContext context, String match) {
        if (context == null) return "no_request_context";
        if ("unknown".equals(match)) return context.hasTarget() ? "no_matching_readback_target" : "no_request_target";
        if ("false".equals(match)) return "target_mismatch";
        if (!context.bodyFollowStatus.matches("[0-9]+")) return "target_found_state_unclear";

        String observed = null;
        for (String signal : userSignals.split(" \\| ")) {
            if (!"true".equals(targetMatch(signal, context))) continue;
            String field = ",followStatus=";
            int start = signal.indexOf(field);
            if (start < 0) continue;
            start += field.length();
            int end = signal.indexOf(',', start);
            String state = signal.substring(start, end < 0 ? signal.length() : end);
            if (!state.matches("[0-9]+")) continue;
            if (observed != null && !observed.equals(state)) return "target_found_state_unclear";
            observed = state;
        }
        if (observed == null) return "target_found_state_unclear";
        return observed.equals(context.bodyFollowStatus)
                ? "confirmed_" + observed
                : "observed_" + observed + "_expected_" + context.bodyFollowStatus;
    }

    private static String encodedFormBody(Object payload) {
        if (payload == null) return null;
        String className = payload.getClass().getName();
        if (!className.contains("FormUrlEncodedTypedOutput")) return null;

        try {
            Method writeTo = payload.getClass().getMethod("writeTo", OutputStream.class);
            writeTo.setAccessible(true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeTo.invoke(payload, output);
            return output.toString("UTF-8");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void appendEncodedPayloadValues(
            String encodedBody,
            StringBuilder builder,
            FollowRequestContext context,
            int[] count
    ) {
        if (encodedBody == null || encodedBody.isEmpty()) return;

        String[] parts = encodedBody.split("&");
        for (String part : parts) {
            if (count[0] >= 12) break;
            int equalsIndex = part.indexOf('=');
            if (equalsIndex <= 0) continue;

            String key = decodeUrlComponent(part.substring(0, equalsIndex));
            String value = decodeUrlComponent(part.substring(equalsIndex + 1));
            appendNamedPayloadValue(key, value, builder, context, count);
        }
    }

    private static String decodeUrlComponent(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String normalizePayloadKey(String key) {
        return key.replace('-', '_').toLowerCase(Locale.US);
    }

    private static boolean shouldSkipPayloadField(String fieldName, Object fieldValue) {
        if (fieldName == null) return true;
        if (isReflectionMetadata(fieldValue)) return true;

        String key = normalizePayloadKey(fieldName);
        return key.startsWith("shadow$_")
                || key.contains("dextype")
                || key.contains("primitive")
                || key.contains("componenttype")
                || key.contains("resolved")
                || key.equals("status");
    }

    private static boolean isReflectionMetadata(Object value) {
        return value instanceof Class<?>
                || value instanceof Method
                || value instanceof Field
                || value instanceof ClassLoader
                || value instanceof Package;
    }

    private static String targetMatch(String userSignals, FollowRequestContext context) {
        if (context == null || !context.hasTarget()) return "unknown";
        if (userSignals == null || "none".equals(userSignals)) return "unknown";

        if (!"empty".equals(context.uidHash) && userSignals.contains("uidHash=" + context.uidHash)) {
            return "true";
        }
        if (!"empty".equals(context.secUidHash) && userSignals.contains("secUidHash=" + context.secUidHash)) {
            return "true";
        }
        return "false";
    }

    private static String invokeString(Object target, String methodName) {
        Object value = invokeValueObject(target, methodName);
        return value instanceof String ? (String) value : null;
    }

    private static String invokeValue(Object target, String methodName) {
        Object value = invokeValueObject(target, methodName);
        return value == null ? "n/a" : describeSimpleValue(value);
    }

    private static Object invokeValueObject(Object target, String methodName) {
        if (target == null) return null;

        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception ignored) {
            Class<?> currentClass = target.getClass();
            while (currentClass != null) {
                try {
                    Method method = currentClass.getDeclaredMethod(methodName);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Exception ignoredAgain) {
                    currentClass = currentClass.getSuperclass();
                }
            }
        }

        return null;
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null) return null;

        Class<?> currentClass = target.getClass();
        while (currentClass != null) {
            try {
                Field field = currentClass.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Exception ignored) {
                currentClass = currentClass.getSuperclass();
            }
        }

        return null;
    }

    private static String describeObjectFields(Object target) {
        if (target == null) return "null";

        StringBuilder builder = new StringBuilder();
        int count = 0;
        Class<?> currentClass = target.getClass();

        while (currentClass != null && count < 10) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (count >= 10) break;
                if (Modifier.isStatic(field.getModifiers())) continue;

                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (count > 0) builder.append(',');
                    builder.append(field.getName()).append('=').append(describeSimpleValue(value));
                    count++;
                } catch (Exception ignored) {
                    // Ignore fields that cannot be read safely.
                }
            }

            currentClass = currentClass.getSuperclass();
        }

        return count == 0 ? "none" : builder.toString();
    }

    private static String describeUserSignals(Object target) {
        StringBuilder builder = new StringBuilder();
        appendUserSignals(target, builder, new IdentityHashMap<>(), 0, new int[]{0});
        return builder.length() == 0 ? "none" : builder.toString();
    }

    private static void appendUserSignals(
            Object target,
            StringBuilder builder,
            IdentityHashMap<Object, Boolean> seen,
            int depth,
            int[] count
    ) {
        if (target == null || depth > 3 || count[0] >= 8 || isSimpleValue(target)) return;
        if (seen.put(target, Boolean.TRUE) != null) return;

        if (target instanceof Collection<?>) {
            int itemCount = 0;
            for (Object item : (Collection<?>) target) {
                if (itemCount++ >= 8) break;
                appendUserSignals(item, builder, seen, depth + 1, count);
            }
            return;
        }

        if (target instanceof Map<?, ?>) {
            int itemCount = 0;
            for (Object value : ((Map<?, ?>) target).values()) {
                if (itemCount++ >= 8) break;
                appendUserSignals(value, builder, seen, depth + 1, count);
            }
            return;
        }

        if (looksLikeUserState(target)) {
            if (builder.length() > 0) builder.append(" | ");
            builder.append(describeUserState(target));
            count[0]++;
        }

        Class<?> currentClass = target.getClass();
        while (currentClass != null && depth < 3 && count[0] < 8) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (count[0] >= 8) break;
                if (Modifier.isStatic(field.getModifiers())) continue;

                try {
                    field.setAccessible(true);
                    appendUserSignals(field.get(target), builder, seen, depth + 1, count);
                } catch (Exception ignored) {
                    // Ignore fields that cannot be read safely.
                }
            }
            currentClass = currentClass.getSuperclass();
        }
    }

    private static boolean looksLikeUserState(Object target) {
        String className = target.getClass().getName().toLowerCase(Locale.US);
        if (className.contains("user") || className.contains("follow")) return true;

        return firstPresentValue(target,
                "getUid", "getSecUid", "getUniqueId", "getFollowStatus", "getFollowerStatus",
                "uid", "secUid", "uniqueId", "followStatus", "followerStatus") != null;
    }

    private static String describeUserState(Object target) {
        Object identity = target;
        Object relation = target;
        // This profile schema splits one account's identity and relation under the same common.
        // Do not pair arbitrary sibling objects: a readback may contain several different users.
        if ("com.ss.android.ugc.profile.platform.base.data.ProfileUser".equals(target.getClass().getName())) {
            Object common = readField(target, "common");
            identity = firstPresentValue(common, "getUserProfileInfo", "userProfileInfo");
            relation = firstPresentValue(common, "getUserRelationInfo", "userRelationInfo");
        }
        return "class=" + target.getClass().getName()
                + ",uidHash=" + hash(firstPresentString(identity, "getUid", "uid", "userId", "getUserId"))
                + ",secUidHash=" + hash(firstPresentString(identity, "getSecUid", "secUid"))
                + ",uniqueIdHash=" + hash(firstPresentString(identity, "getUniqueId", "uniqueId", "nickname"))
                + ",followStatus=" + describeSimpleValue(firstPresentValue(relation, "getFollowStatus", "followStatus"))
                + ",followerStatus=" + describeSimpleValue(firstPresentValue(relation, "getFollowerStatus", "followerStatus"))
                + ",followed=" + describeSimpleValue(firstPresentValue(relation, "isFollowing", "isFollowed", "following"));
    }

    private static String firstPresentString(Object target, String... names) {
        Object value = firstPresentValue(target, names);
        return value instanceof String ? (String) value : null;
    }

    private static Object firstPresentValue(Object target, String... names) {
        if (target == null) return null;

        for (String name : names) {
            Object value = invokeValueObject(target, name);
            if (value != null) return value;

            value = readField(target, name);
            if (value != null) return value;
        }

        return null;
    }

    private static String describeSimpleValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) {
            return safeShort(String.valueOf(value));
        }
        if (value instanceof String) {
            String stringValue = (String) value;
            return "stringHash=" + hash(stringValue) + "/len=" + stringValue.length();
        }
        if (value instanceof Enum<?>) {
            return safeShort(((Enum<?>) value).name());
        }

        return "class=" + value.getClass().getName();
    }

    private static boolean isSimpleValue(Object value) {
        return value instanceof Number
                || value instanceof Boolean
                || value instanceof String
                || value instanceof Enum<?>
                || value instanceof Character;
    }

    private static String className(Object target) {
        return target == null ? "null" : target.getClass().getName();
    }

    private static String followPath(Object request) {
        String path = requestPath(request);
        return "follow".equals(CaptchaGate.writeActionFor(path)) ? safeShort(path) : null;
    }

    private static String followReadbackPath(Object request) {
        String path = requestPath(request);
        if (path == null) return null;

        if (followPath(request) != null) return null;

        boolean hasUserArea = path.contains("user") || path.contains("profile") || path.contains("relation");
        if (hasUserArea && System.currentTimeMillis() <= followReadbackWindowUntil) {
            return safeShort(path);
        }

        boolean hasFollowStateClue = path.contains("profile")
                || path.contains("detail")
                || path.contains("relation")
                || path.contains("follow")
                || path.contains("friend");

        return hasUserArea && hasFollowStateClue ? safeShort(path) : null;
    }

    private static String requestPath(Object request) {
        // Through the cached lookup, not the uncached one below. This runs for every request
        // and every response the app makes, before the logging gate, because a refused follow
        // has to be noticed whether or not logging is on; an uncached getMethod on each of
        // them, and a superclass walk throwing per level on a build that renamed getPath,
        // was a cost every network call paid for the life of the process.
        Object value = app.morphe.extension.tiktok.blockauthor.Reflect.required(
                request, "getPath", HOOK_FAMILY);
        if (!(value instanceof String)) return null;

        return (String) value;
    }

    private static String describeMapKeys(Map<?, ?> map) {
        if (map == null) return "null";
        if (map.isEmpty()) return "empty";

        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Object key : map.keySet()) {
            if (count >= 8) {
                builder.append(",...");
                break;
            }
            if (count > 0) builder.append(',');
            builder.append(safeShort(String.valueOf(key)));
            count++;
        }
        return builder.toString();
    }

    private static String safeInteger(Integer value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static String safeShort(String value) {
        if (value == null) return "null";
        String sanitized = value.replace('\n', ' ').replace('\r', ' ');
        if (sanitized.length() > 48) {
            return sanitized.substring(0, 48) + "...";
        }
        return sanitized;
    }

    /**
     * The same pseudonym for other reports about an account, so a creator blocked from the feed
     * and a creator followed read as one account across the two families of lines.
     */
    public static String pseudonym(String accountId) {
        return hash(accountId);
    }

    /**
     * A stable per-install pseudonym. Prefixing FNV with a secret still lets one known account
     * reveal the intermediate state and identify other accounts. HMAC keeps that secret as a
     * key instead of mixing it into a reversible hash prefix. The key never enters a report.
     */
    private static String hash(String value) {
        if (value == null || value.isEmpty()) return "empty";

        try {
            Mac digest = Mac.getInstance("HmacSHA256");
            digest.init(new SecretKeySpec(salt().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(digest.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            Logger.printException(() -> "Could not create a diagnostic account pseudonym", failure);
            return "unavailable";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value & 0xff) >>> 4, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    private static volatile String salt;

    private static String salt() {
        String known = salt;
        if (known != null) return known;

        synchronized (FollowDiagnostics.class) {
            if (salt != null) return salt;
            String stored = Settings.DIAGNOSTIC_REPORT_SALT.get();
            if (stored.isEmpty()) {
                byte[] key = new byte[32];
                new java.security.SecureRandom().nextBytes(key);
                stored = hex(key);
                Settings.DIAGNOSTIC_REPORT_SALT.save(stored);
            }
            salt = stored;
            return stored;
        }
    }
}

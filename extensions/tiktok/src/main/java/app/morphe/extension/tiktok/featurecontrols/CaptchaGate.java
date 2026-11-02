/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows na-x-ril/onlynazril-patches (GPL-3.0).
 */
package app.morphe.extension.tiktok.featurecontrols;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The single place that decides whether one of TikTok's risk control puzzles may be hidden.
 *
 * A challenge is shown when it can stand in front of something the reader asked for: a write
 * of theirs went out in the last {@link #WRITE_WINDOW_MS} (see {@link #heldActionFor}: a follow,
 * like, comment, repost, save, post, message, gift, purchase or sign-in change, which fails with
 * nothing on screen when its puzzle is hidden), it belongs to an account screen or route
 * (sign-in, SMS, two-factor), it is TikTok Shop's own verification, or nobody is signed in.
 * What's left is a puzzle TikTok raised while the reader was only browsing, and that is what
 * the switch hides.
 * The rule follows na-x-ril/onlynazril-patches' Hide CAPTCHA popups (GPL-3.0), with the write
 * window added. Every hook shares the decision and its diagnostic record.
 */
public final class CaptchaGate {
    private static final String HOOK_FAMILY = "CAPTCHA account state";
    private static final String INSTALLED = "installed";
    private static final String REQUEST_RECORDER = "request recorder";
    private static final String RISK_CHECK_HOOKS = "risk check hooks";
    /**
     * The Hook status generation the family was last reported into. A diagnostic clear drops
     * every bound entry, and a family that is then absent reads as "this build has no gate",
     * so the next request or check puts it back. The two flags say which log lines this
     * generation has already had: the report gives counts, not names, so the line is where the
     * state is legible.
     */
    private static volatile long reportedGeneration = -1;
    private static volatile boolean recorderReported;
    private static volatile boolean hooksReported;
    /**
     * How long a write request keeps a risk check attached to it. The server raises the
     * check in its response, so the request has always just been sent, but the puzzle can
     * take a moment to reach the UI thread.
     */
    static final long WRITE_WINDOW_MS = 15_000L;

    private static final String ACCOUNT_ACTIVITY_PREFIX = "com.ss.android.ugc.aweme.account.";
    private static final String SERVICE_MANAGER_CLASS =
            "com.ss.android.ugc.aweme.framework.services.ServiceManager";
    private static final String ACCOUNT_USER_SERVICE_CLASS =
            "com.ss.android.ugc.aweme.IAccountUserService";

    /** Written whole so a reader never sees one call's action with another call's time. */
    private static final class Stamped {
        final String value;
        final long atMs;

        Stamped(String value, long atMs) {
            this.value = value;
            this.atMs = atMs;
        }
    }

    private static volatile Stamped pendingWrite;
    private static volatile Stamped lastSuppressed;
    private static final AtomicBoolean warnedThisSession = new AtomicBoolean();
    /** A native-shaped account service for decision tests; never set by a runtime entry point. */
    static Object accountServiceForTests;

    private CaptchaGate() {
    }

    /**
     * The clock the windows are measured against. Wall time can jump when the network or the
     * user corrects it, which would either close a window early or hold it open forever.
     */
    static long now() {
        return SystemClock.elapsedRealtime();
    }

    /**
     * Called where the settings load, so the family is in the export before any check fires.
     *
     * <p>A report of a comment or a follow that went nowhere used to carry no CAPTCHA line at
     * all, and a family that is simply absent says both "this build has no gate" and "no
     * puzzle was raised". Three states are separable now: the family with only {@code
     * installed}, the recorder having seen a write, and a check having reached the decision.
     */
    public static void installed() {
        reportedGeneration = HookStatus.generation();
        recorderReported = false;
        hooksReported = false;
        HookStatus.bound(HOOK_FAMILY, INSTALLED);
    }

    /** Puts the family back after a clear, which is the only way it leaves the export. */
    private static void ensureInstalled() {
        if (HookStatus.generation() != reportedGeneration) installed();
    }

    // ---------------------------------------------------------------- what the check gates

    /** Called for every outbound request by the network interceptor hook. */
    public static void recordRequest(Object request) {
        Object path = Reflect.invoke(request, "getPath");
        if (path instanceof String) {
            recordRequestPath((String) path);
        }
    }

    static void recordRequestPath(String path) {
        String action = heldActionFor(path);
        if (action != null) {
            pendingWrite = new Stamped(action, now());
            // The recorder is what tells a puzzle over a write from a browsing one, so an
            // export has to say it is seeing writes at all. A repeat is one hash lookup.
            ensureInstalled();
            HookStatus.bound(HOOK_FAMILY, REQUEST_RECORDER);
            if (!recorderReported) {
                recorderReported = true;
                // The path alone, never its query, which carries device and account ids.
                int query = path.indexOf('?');
                String first = action + " (" + (query < 0 ? path : path.substring(0, query)) + ")";
                Logger.printInfo(() -> "CAPTCHA gate saw its first write go out: " + first);
            }
        }
    }

    /**
     * The user action an API path performs, or null for anything that only reads.
     *
     * A story like is a like on the story's own Aweme, so it arrives on the ordinary digg
     * path rather than one of its own.
     */
    public static String writeActionFor(String path) {
        if (path == null) return null;
        String value = path.toLowerCase(Locale.ROOT);

        // Shared with follow diagnostics. These are the write families in TikTok 46.2.3;
        // following/follower lists only read state and must not open a write window.
        if (value.contains("/commit/follow/")
                || value.contains("/f2f/follow/")
                || value.contains("/remove/follower/")) {
            return "follow";
        }
        if (value.contains("/commit/item/digg/")
                || value.contains("/commit/game/item/digg/")
                || value.contains("/comment/digg/")
                || value.contains("/unlogged/digg/")
                || value.contains("/danmaku/digg")
                || value.contains("/upvote/digg")
                || value.contains("/upvote/comment_digg")) {
            return "like";
        }
        // Checked before the repost paths: publish_comment also contains upvote/publish.
        if (value.contains("/comment/publish")
                || value.contains("/comment/delete/")
                || value.contains("/comment/pin")
                || value.contains("/upvote/publish_comment")
                || value.contains("/webcast/room/chat/")) {
            return "comment";
        }
        if (value.contains("/upvote/publish")
                || value.contains("/upvote/batch_publish")
                || value.contains("/repost/create/")) {
            return "repost";
        }
        if (value.contains("/story/maf/mute") || value.contains("/story/maf/unmute")) {
            return "story";
        }
        return null;
    }

    /**
     * Every action a puzzle can stand in front of: the shared write families above plus the
     * others a hidden puzzle would fail with nothing on screen. The paths are TikTok 47.1.4's.
     * Telemetry also POSTs all the time, so the request method alone can't open the window.
     */
    static String heldActionFor(String path) {
        String action = writeActionFor(path);
        if (action != null || path == null) return action;
        String value = path.toLowerCase(Locale.ROOT);
        if (value.contains("/aweme/collect/")
                || value.contains("/music/collect")
                || value.contains("/effect/favorite")
                || value.contains("/collection/item_archive/manage/")) {
            return "save";
        }
        if (value.contains("/commit/user")
                || value.contains("/privacy/user/settings/update/")
                || value.contains("/privacy/item/settings/update/")
                || value.contains("/kids/edit/user")) {
            return "profile edit";
        }
        if (value.contains("/aweme/v1/create/")
                || value.contains("/aweme/v2/create/")
                || value.contains("/thought/create/")
                || value.contains("/aweme/delete/")) {
            return "post";
        }
        if (value.contains("/message/send") || value.contains("/conversation/create")) {
            return "message";
        }
        if (value.contains("/gift/send/")) {
            return "gift";
        }
        if (value.contains("/order/")
                || value.contains("/cart/")
                || value.contains("/checkout")
                || value.contains("/payment/")
                || value.contains("/cashier/")
                || value.contains("/iap/pay")) {
            return "purchase";
        }
        if ((value.contains("/passport/") && !isPassportBackground(value)) || value.contains("/user/mobile/")) {
            return "sign-in change";
        }
        return null;
    }

    /**
     * Account calls TikTok makes by itself, at every start and on a timer: the session heartbeat,
     * the account info refresh, region, device, two-step and sign-in option lookups, and the sync
     * of the account switcher's saved one-tap sign-in. None is something the user did, and
     * holding the window on them kept every puzzle of a cold start's first 15 seconds on screen,
     * which is when a link opened from another app meets one (Login2, 47.4.3, 2026-10-17: the
     * first write after a start was always a sign-in change, and with the lookups left out it was
     * the one-tap blob). A puzzle hidden over the blob sync leaves at most TikTok's saved copy stale. Any
     * other passport path still holds the window. The paths are 47.4.3's.
     */
    private static final String[] PASSPORT_BACKGROUND = {
            "/passport/token/beat/",
            "/passport/account/info/",
            "/passport/account/attributes/",
            "/passport/user/basic_info/",
            "/passport/user/device_record_status/get/",
            "/passport/app/region",
            "/passport/app/store_region/",
            "/passport/auth/available_ways/",
            "/passport/reauth_settings/state/",
            "/passport/safe/login_device/list/",
            "/passport/safe/recommend_device/list/",
            "/passport/safe/get_user_worth/",
            "/passport/safe/two_step_verification/get_",
            "/passport/security_checkup/info/",
            "/passport/device/trust_users/",
            "/passport/identity/get_fields/",
            "/passport/password/has_set",
            "/passport/totp/status/",
            "/passport/popup/configuration/",
            "/passport/mobile/gsma/status",
            "/passport/sotl/blobstore/",
            "/passport/user/check_",
            "/passport/login_name/check",
            "/passport/mobile/can_send_voice_code",
    };

    private static boolean isPassportBackground(String lowerCasePath) {
        for (String call : PASSPORT_BACKGROUND) {
            if (lowerCasePath.contains(call)) return true;
        }
        return false;
    }

    /** The write still inside its window, or null when the app is only browsing. */
    public static String pendingWriteAction() {
        return pendingWriteAction(now());
    }

    static String pendingWriteAction(long nowMs) {
        return fresh(pendingWrite, nowMs);
    }

    static void resetForTests() {
        pendingWrite = null;
        lastSuppressed = null;
        warnedThisSession.set(false);
        accountServiceForTests = null;
    }

    private static String fresh(Stamped stamped, long nowMs) {
        if (stamped == null) return null;
        return nowMs - stamped.atMs <= WRITE_WINDOW_MS ? stamped.value : null;
    }

    // ---------------------------------------------------------------- the decision

    /**
     * Why this risk check has to be shown, or null when it may be hidden.
     *
     * @param detail the check's own description: the risk info, the service type or the
     *               verification scene, depending on which hook asked.
     */
    static String showReason(Activity activity, String detail, long nowMs) {
        if (!Settings.HIDE_CAPTCHA_POPUPS.get()) return "the setting is off";

        String writeAction = pendingWriteAction(nowMs);
        if (writeAction != null) return "it gates a " + writeAction;

        if (isAccountVerification(activity, detail)) return "it is account verification";
        // Signed out, a puzzle can be the sign-in's own, raised before any account screen opens.
        if (!isLoggedIn()) return "no account is signed in";
        return null;
    }

    /** The whole decision for one check. True hides it. */
    public static boolean shouldHide(Activity activity, String checkId, String detail) {
        return decide(activity, checkId, detail, null);
    }

    /**
     * @param unreadable why the check has to be shown when nothing else stops it, or null when
     *                   its description could be read.
     */
    private static boolean decide(Activity activity, String checkId, String detail, String unreadable) {
        ensureInstalled();
        HookStatus.bound(HOOK_FAMILY, RISK_CHECK_HOOKS);
        if (!hooksReported) {
            hooksReported = true;
            Logger.printInfo(() -> "A risk check reached the CAPTCHA gate: " + checkId);
        }
        String reason = showReason(activity, detail, now());
        if (reason == null) reason = unreadable;
        if (reason != null) {
            // At the level the export keeps, the same as a hidden check: a report of a write
            // that failed has to show whether the puzzle reached the reader and why.
            String shown = reason;
            Logger.printInfo(() -> "Showing risk check " + checkId + " because " + shown);
            return false;
        }
        noteSuppressed(checkId);
        return true;
    }

    /**
     * Records a hidden check. The log line carries its id so a report can be traced back to
     * one puzzle, and the first suppression of a session says so on screen, because the
     * effect of a hidden check is otherwise invisible.
     */
    public static void noteSuppressed(String checkId) {
        lastSuppressed = new Stamped(checkId, now());
        Logger.printInfo(() -> "Hid risk check " + checkId);

        if (!warnedThisSession.compareAndSet(false, true)) return;
        Utils.showToastLong(L10n.t("Metra hid a TikTok CAPTCHA. Turn off Hide CAPTCHA popups if something stops working."));
    }

    /** The id of the last hidden check, or null when none was hidden recently. */
    public static String recentlySuppressedCheckId() {
        return fresh(lastSuppressed, now());
    }

    // ---------------------------------------------------------------- the hooks

    /** {@code SecApiImpl.popCaptchaV2} and the LIVE host's copy of it. */
    public static boolean shouldHideCaptchaPopup(Activity activity, String riskInfo) {
        return shouldHide(activity, checkId("risk", riskInfo), riskInfo);
    }

    /** {@code SecApiImpl.popCaptcha}, which carries only the server's error code. */
    public static boolean shouldHideLegacyCaptchaPopup(Activity activity, int errorCode) {
        return shouldHide(activity, "error " + errorCode, null);
    }

    /**
     * The oecverify risk control service, TikTok Shop's. It hands over no screen, so nothing
     * can tell a checkout's puzzle from a browsing one, and it is always shown.
     */
    public static boolean shouldHideOecCaptchaPopup(Object verifyRequest) {
        String scene = verificationScene(verifyRequest);
        return decide(null, checkId("scene", scene), scene, "it is TikTok Shop's verification");
    }

    /** {@code BdTuring.showVerifyDialog}, reached when network verification skips SecApiImpl. */
    public static boolean shouldHideTuringDialog(Activity activity, Object verifyRequest) {
        return shouldHideVerifyRequest(activity, verifyRequest);
    }

    /**
     * A request whose scene cannot be read is shown, not hidden: without the scene there is
     * no way to tell an account check from a browsing one.
     */
    private static boolean shouldHideVerifyRequest(Activity activity, Object verifyRequest) {
        String scene = verificationScene(verifyRequest);
        boolean read = scene != null && !scene.trim().isEmpty();
        return decide(activity, checkId("scene", scene), scene, read ? null : "its scene can't be read");
    }

    /**
     * The bdturing risk control service. SMS and two factor checks are account security and
     * are never touched, whatever else is going on.
     */
    public static boolean shouldHideTuringCaptchaPopup(Activity activity, String serviceType) {
        return shouldHide(activity, checkId("service", serviceType), serviceType);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A short name for one check. ByteDance's risk info is a long JSON blob, so the named
     * fields inside it are preferred over a truncated copy of the whole thing.
     */
    static String checkId(String kind, String detail) {
        if (detail == null || detail.isEmpty()) return kind + " unknown";

        String named = jsonValue(detail, "subtype");
        if (named == null) named = jsonValue(detail, "scene");
        if (named == null) named = jsonValue(detail, "verify_way");
        if (named != null) return kind + " " + named;

        String single = detail.replace('\n', ' ').replace('\r', ' ').trim();
        return kind + " " + (single.length() > 80 ? single.substring(0, 80) : single);
    }

    /**
     * The string value of {@code key} in a JSON-ish blob, without parsing the whole thing. A
     * value that is not a string, a number say, is no answer: the first quote after the colon
     * was the next key's, so {"subtype":3,"scene":"login"} named the check "scene".
     */
    private static String jsonValue(String json, String key) {
        int at = json.indexOf('"' + key + '"');
        if (at < 0) return null;
        int colon = json.indexOf(':', at + key.length() + 2);
        if (colon < 0) return null;
        int open = colon + 1;
        while (open < json.length() && Character.isWhitespace(json.charAt(open))) open++;
        if (open >= json.length() || json.charAt(open) != '"') return null;
        int close = json.indexOf('"', open + 1);
        if (close <= open + 1) return null;
        return json.substring(open + 1, close);
    }

    private static String verificationScene(Object verifyRequest) {
        Object value = Reflect.invoke(verifyRequest, "LJIIJ");
        return value instanceof String ? (String) value : null;
    }

    private static boolean isAccountVerification(Activity activity, String detail) {
        if (activity != null
                && activity.getClass().getName().startsWith(ACCOUNT_ACTIVITY_PREFIX)) {
            return true;
        }
        Intent intent = activity == null ? null : activity.getIntent();
        String route = intent == null ? null : intent.getDataString();
        return isAccountRoute(route) || isAccountRoute(detail);
    }

    /** Words that name sign-in, SMS, two-step or account recovery anywhere in a check. */
    private static final Set<String> ACCOUNT_WORDS = new HashSet<>(Arrays.asList(
            "login", "logout", "signin", "signup", "register", "passport", "sms", "otp", "twice",
            "2fa", "mobile", "phone", "email", "password", "pwd"));

    /**
     * Whether a route, risk info or scene names account verification anywhere in it. Split on
     * everything but letters and digits, so {"verify_way":"sms"}, sms_verify and
     * /passport/mobile/ all match while slide_captcha and common_verify don't. A miss hides the
     * puzzle, so the list leans wide: a word here only ever makes a puzzle show. verify_way
     * itself isn't one: it's the key every risk info names its puzzle type under.
     */
    static boolean isAccountRoute(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String word : normalized.split("[^a-z0-9]+")) {
            if (ACCOUNT_WORDS.contains(word)) return true;
        }
        return false;
    }

    private static boolean isLoggedIn() {
        if (accountServiceForTests != null) {
            Method isLogin = Reflect.requiredMethod(
                    accountServiceForTests.getClass(), "isLogin", HOOK_FAMILY);
            return Boolean.TRUE.equals(Reflect.invokeRequired(
                    isLogin, accountServiceForTests, HOOK_FAMILY));
        }
        Class<?> serviceManagerClass = Reflect.requiredClass(SERVICE_MANAGER_CLASS, HOOK_FAMILY);
        Method getManager = Reflect.requiredMethod(
                serviceManagerClass, "get", HOOK_FAMILY);
        Object serviceManager = Reflect.invokeRequired(getManager, null, HOOK_FAMILY);
        if (serviceManager == null) return false;

        Class<?> accountServiceClass = Reflect.requiredClass(
                ACCOUNT_USER_SERVICE_CLASS, HOOK_FAMILY);
        if (accountServiceClass == null) return false;
        Method getService = Reflect.requiredMethod(
                serviceManagerClass, "getService", HOOK_FAMILY, Class.class);
        Object accountService = Reflect.invokeRequired(
                getService, serviceManager, HOOK_FAMILY, accountServiceClass);
        if (accountService == null) return false;

        Method isLogin = Reflect.requiredMethod(
                accountServiceClass, "isLogin", HOOK_FAMILY);
        return Boolean.TRUE.equals(Reflect.invokeRequired(
                isLogin, accountService, HOOK_FAMILY));
    }
}

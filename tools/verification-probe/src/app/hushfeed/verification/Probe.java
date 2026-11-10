package app.hushfeed.verification;

import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reaches Hushfeed's own settings on a phone, for the device checks in Roadmap_Blocked.md.
 *
 * <p>Hushfeed hangs its settings screen off TikTok's ad personalisation activity, which is not
 * exported, so adb cannot start it and the row that leads to it sits inside TikTok's Settings and
 * privacy. On an account TikTok has asked to answer a content-reuse migration, that page cannot be
 * opened without answering, and that answer is the account owner's to give. This runs inside
 * TikTok's own process instead, where starting the activity and reading or writing a setting are
 * ordinary calls.
 *
 * <h2>Why the work does not happen in onCreate</h2>
 *
 * <p>The obvious shape, do the work in {@link #onCreate} and answer through {@code am instrument
 * -w}, does not run at all on this target. TikTok replaces the thread's Instrumentation with its
 * own: the clean 46.2.3 build reflects on {@code mInstrumentation} in eight places, among them
 * {@code SharedPreferencesManager.hookInstrumentation} and {@code BTMInstrumentationProxy.hook},
 * and its replacement does not pass onCreate on. So nothing after the swap is a reliable entry
 * point, and there is no result bundle to answer with either.
 *
 * <p>{@link #newApplication} is before the swap. The framework calls it while it is building the
 * Application, which is what runs the app's own attachBaseContext, so it is the last callback that
 * is certainly still this class. That starts a thread, the thread waits for Hushfeed to have a
 * context, and then it registers a receiver. Everything after that arrives as a broadcast and is
 * answered in the log, because the instrumentation has no way back to the shell by then.
 *
 * <p>Nothing here is part of the bundle. It is a test tool, it is signed with the same key as the
 * sideloaded build so the platform lets it in, and it should be uninstalled when the checks are
 * done.
 *
 * <pre>
 *   adb -s S shell am instrument app.hushfeed.verification/.Probe
 *   adb -s S shell am broadcast -a app.hushfeed.verification.PROBE -p com.zhiliaoapp.musically \
 *       -e action dump
 *       -e action secure-popup
 *       -e action secure-popup-close
 *       -e action labrule -e manager abmock -e key favorite_reverse -e type INT -e value 1
 *       -e action labclear
 *       -e action mediacache -e op plant
 *   adb -s S logcat -d | grep HushfeedProbe
 * </pre>
 */
public final class Probe extends Instrumentation {

    static final String TAG = "HushfeedProbe";
    private static final String ACTION = "app.hushfeed.verification.PROBE";
    private static final String SETTING = "app.morphe.extension.shared.settings.Setting";
    private static final String SETTINGS = "app.morphe.extension.tiktok.settings.Settings";
    private static final String UTILS = "app.morphe.extension.shared.Utils";
    private static final java.util.Set<String> OVERLAY_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "block_author_button", "local_hide_button", "block_sound_button",
            "not_interested_button", "feed_mute_button"));
    private static final String SETTINGS_ACTIVITY =
            "com.bytedance.ies.ugc.aweme.commercialize.compliance.personalization."
                    + "AdPersonalizationActivity";
    /** How long to let TikTok finish starting before giving up on it. */
    private static final long READY_TIMEOUT_MS = 120_000L;

    private static volatile boolean started;

    @Override
    public Application newApplication(ClassLoader loader, String className, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Log.i(TAG, "newApplication " + className);
        Application application = super.newApplication(loader, className, context);
        listen(application);
        return application;
    }

    @Override
    public void onCreate(Bundle arguments) {
        // Reached only if the app leaves the thread's instrumentation alone, which this one does
        // not. Kept because it costs nothing and says in the log which path a build took.
        Log.i(TAG, "onCreate");
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Log.i(TAG, "onStart");
    }

    /** Waits for Hushfeed, then takes instructions by broadcast for as long as the app lives. */
    private static synchronized void listen(final Context context) {
        if (started) return;
        started = true;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ClassLoader loader = waitForHushfeed(context);
                    BudgetFocusProbe.recoverOnStart(context, loader);
                    IntentFilter filter = new IntentFilter(ACTION);
                    BroadcastReceiver receiver = new Commands(context, loader);
                    // The sender is adb, which is another uid, so the receiver has to be
                    // exported. Exported to everything, any app on the phone could drive
                    // Hushfeed's settings for as long as the probe is loaded, so the sender is
                    // held to a permission only the shell and the platform can carry: DUMP is
                    // signature-or-privileged, the shell requests it for dumpsys, and no app
                    // from a store can be granted it. Root passes every permission check.
                    // Asking the broadcast who sent it does not work here: the public
                    // getSentFromUid answers -1 for the shell, whose package TikTok cannot see.
                    if (Build.VERSION.SDK_INT >= 33) {
                        context.registerReceiver(
                                receiver, filter, SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED);
                    } else {
                        context.registerReceiver(receiver, filter, SENDER_PERMISSION, null);
                    }
                    Log.i(TAG, "ready");
                } catch (Throwable error) {
                    Log.e(TAG, "probe could not start", error);
                }
            }
        }, "hushfeed-probe");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Blocks until Hushfeed has a context, and answers with the class loader that has it.
     *
     * <p>This runs while the application is still being built, so the extension's classes are
     * loadable well before any of them has been initialised. Waiting on the context is waiting on
     * the thing every setting read needs.
     */
    private static ClassLoader waitForHushfeed(Context context) throws Exception {
        ClassLoader loader = context.getClassLoader();
        Method getContext;
        try {
            getContext = loader.loadClass(UTILS).getMethod("getContext");
        } catch (ClassNotFoundException absent) {
            // The one message this method exists to produce, and it was unreachable: loadClass
            // throws before the wait ever starts, so an unpatched build got a raw stack trace.
            throw new IllegalStateException("no Hushfeed in this build; is it patched?", absent);
        }
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (getContext.invoke(null) != null) return loader;
            Thread.sleep(250L);
        }
        throw new IllegalStateException("Hushfeed never took a context; is this build patched?");
    }

    /** The permission a sender has to hold: the shell has it, an app from a store cannot. */
    private static final String SENDER_PERMISSION = "android.permission.DUMP";

    /** One broadcast, one action, one line in the log. */
    private static final class Commands extends BroadcastReceiver {
        private final Context app;
        private final ClassLoader loader;

        Commands(Context app, ClassLoader loader) {
            this.app = app;
            this.loader = loader;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            // Who may send is settled where the receiver is registered, by the permission the
            // sender has to hold. Nothing here needs to ask again.
            String action = intent.getStringExtra("action");
            if (action == null) action = "dump";
            try {
                switch (action) {
                    case "settings":
                        openSettings();
                        Log.i(TAG, "ok settings opened");
                        break;
                    case "settings-notice": {
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getVisibleActivity").invoke(null);
                        if (activity == null || !SETTINGS_ACTIVITY.equals(activity.getClass().getName())) {
                            throw new IllegalStateException("Hushfeed settings must be in front");
                        }
                        loader.loadClass("app.morphe.extension.tiktok.settings.preference.SettingsActionBanner")
                                .getMethod("showNotice", Context.class, String.class)
                                .invoke(null, activity, "Settings notice.");
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            android.view.View banner = activity.findViewById(android.R.id.content)
                                    .findViewWithTag("hushfeed_settings_action_banner");
                            boolean focused = banner != null && banner.performAccessibilityAction(
                                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
                            Log.i(TAG, "ok settings-notice focused=" + focused);
                        }, 250);
                        break;
                    }
                    case "dump":
                        Log.i(TAG, "ok dump\n" + dump());
                        break;
                    case "refresh-shortcuts":
                        refreshShortcuts();
                        Log.i(TAG, "ok refresh-shortcuts asked for");
                        break;
                    case "comments":
                        Log.i(TAG, "ok comments\n" + openCommentsReport());
                        break;
                    case "feed":
                        Log.i(TAG, "ok feed\n" + feedReport());
                        break;
                    case "inbox-query":
                        Log.i(TAG, "ok inbox-query\n" + InboxQuerySnapshot.read(loader));
                        break;
                    case "ad-evidence":
                        // Technical booleans only. Never emit the video id, creator, visible
                        // disclosure text or the raw anchors JSON into device evidence.
                        Log.i(TAG, "ok ad-evidence\n" + adEvidence());
                        break;
                    case "search-lynx-evidence":
                        for (String line : SearchLynxSnapshot.read(loader)) Log.i(TAG, "search-lynx " + line);
                        Log.i(TAG, "ok search-lynx-evidence");
                        break;
                    case "ad-boundary":
                        Log.i(TAG, "ok ad-boundary\n" + adBoundary());
                        break;
                    case "location-evidence":
                        Log.i(TAG, "ok location-evidence\n" + locationEvidence(intent.getStringExtra("aid")));
                        break;
                    case "banner-evidence":
                        Log.i(TAG, "ok banner-evidence\n" + bannerEvidence(intent.getStringExtra("aid")));
                        break;
                    case "stripkeys":
                        Log.i(TAG, "ok stripkeys " + stripKeys());
                        break;
                    case "mediacache":
                        Log.i(TAG, "ok mediacache " + mediaCache(intent.getStringExtra("op")));
                        break;
                    case "storage":
                        StorageProbe.request(app, intent, this);
                        break;
                    case "webviews": {
                        // Which page a WebView is showing and what it was built with. Hosts and
                        // paths only: a query can carry tokens, so it is never printed.
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        StringBuilder out = new StringBuilder();
                        if (activity != null) {
                            out.append("activity=").append(activity.getClass().getName());
                            android.net.Uri data = activity.getIntent() == null ? null : activity.getIntent().getData();
                            out.append("\nintentData=").append(hostAndPath(data));
                            if (data != null && data.isHierarchical()) {
                                String inner = data.getQueryParameter("url");
                                out.append("\nintentUrlParam=").append(inner == null ? "none"
                                        : hostAndPath(android.net.Uri.parse(inner)));
                            }
                            android.os.Bundle extras = activity.getIntent() == null ? null : activity.getIntent().getExtras();
                            out.append("\nextraKeys=").append(extras == null ? "none" : String.valueOf(extras.keySet()));
                        }
                        for (android.view.View root : windowRoots()) {
                            java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>();
                            queue.add(root);
                            while (!queue.isEmpty()) {
                                android.view.View view = queue.poll();
                                if (view instanceof android.webkit.WebView) {
                                    android.webkit.WebView web = (android.webkit.WebView) view;
                                    out.append("\nwebview=").append(web.getClass().getName())
                                            .append(" url=").append(hostAndPath(web.getUrl() == null
                                                    ? null : android.net.Uri.parse(web.getUrl())))
                                            .append(" context=");
                                    Context holder = web.getContext();
                                    for (int depth = 0; holder != null && depth < 6; depth++) {
                                        out.append(holder.getClass().getName()).append(" > ");
                                        if (holder instanceof android.app.Activity) {
                                            Intent hosting = ((android.app.Activity) holder).getIntent();
                                            android.net.Uri hostData = hosting == null ? null : hosting.getData();
                                            out.append("[data=").append(hostAndPath(hostData));
                                            if (hostData != null && hostData.isHierarchical()
                                                    && hostData.getQueryParameter("url") != null) {
                                                out.append(" urlParam=").append(hostAndPath(
                                                        android.net.Uri.parse(hostData.getQueryParameter("url"))));
                                            }
                                            android.os.Bundle held = hosting == null ? null : hosting.getExtras();
                                            if (held != null) {
                                                for (String key : held.keySet()) {
                                                    Object value = held.get(key);
                                                    out.append(' ').append(key).append('=');
                                                    String text = value instanceof String ? (String) value : null;
                                                    out.append(text != null && text.contains("://")
                                                            ? hostAndPath(android.net.Uri.parse(text))
                                                            : value == null ? "null" : value.getClass().getName());
                                                }
                                            }
                                            out.append("] ");
                                        }
                                        holder = holder instanceof android.content.ContextWrapper
                                                ? ((android.content.ContextWrapper) holder).getBaseContext() : null;
                                    }
                                }
                                if (view instanceof android.view.ViewGroup) {
                                    android.view.ViewGroup group = (android.view.ViewGroup) view;
                                    for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
                                }
                            }
                        }
                        Log.i(TAG, "ok webviews\n" + out);
                        break;
                    }
                    case "webview-bridges": {
                        // BrowserPrivacyGuard keeps the objects so it can restore them on the
                        // next trusted page. Read only their registered names, then ask the live
                        // page which names resolve. The probe never calls a bridge or reads page
                        // content. Interface names are technical APK anchors, not account data.
                        Class<?> guard = loader.loadClass(
                                "app.morphe.extension.tiktok.privacy.BrowserPrivacyGuard");
                        Field statesField = guard.getDeclaredField("STATES");
                        statesField.setAccessible(true);
                        Map<?, ?> states = (Map<?, ?>) statesField.get(null);
                        int scheduled = 0;
                        for (android.view.View root : windowRoots()) {
                            java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>();
                            queue.add(root);
                            while (!queue.isEmpty()) {
                                android.view.View view = queue.poll();
                                if (view instanceof android.webkit.WebView) {
                                    android.webkit.WebView web = (android.webkit.WebView) view;
                                    // Each tracked name with its object's @JavascriptInterface
                                    // methods. A page script may define a same-named object of
                                    // its own (TikTok's JS SDK does, ToutiaoJSBridge), so only a
                                    // name whose Java methods resolve counts as the bridge itself.
                                    JSONArray tracked = new JSONArray();
                                    Object state = null;
                                    synchronized (states) {
                                        Object reference = states.get(web);
                                        if (reference instanceof java.lang.ref.WeakReference) {
                                            state = ((java.lang.ref.WeakReference<?>) reference).get();
                                        }
                                    }
                                    if (state != null) {
                                        Field interfacesField = state.getClass().getDeclaredField("interfaces");
                                        interfacesField.setAccessible(true);
                                        synchronized (state) {
                                            Map<?, ?> interfaces = (Map<?, ?>) interfacesField.get(state);
                                            for (Map.Entry<?, ?> binding : interfaces.entrySet()) {
                                                if (!(binding.getKey() instanceof String)) continue;
                                                JSONArray methods = new JSONArray();
                                                if (binding.getValue() != null) {
                                                    for (Method method : binding.getValue().getClass().getMethods()) {
                                                        if (method.isAnnotationPresent(
                                                                android.webkit.JavascriptInterface.class)) {
                                                            methods.put(method.getName());
                                                        }
                                                    }
                                                }
                                                tracked.put(new JSONArray().put(binding.getKey()).put(methods));
                                            }
                                        }
                                    }

                                    String script = "(function(){var n=" + tracked
                                            + ";var exposed=[];for(var i=0;i<n.length;i++){var name=n[i][0];"
                                            + "if(typeof window[name]!=='undefined'){var v=window[name],k=[],j=[];"
                                            + "try{k=Object.getOwnPropertyNames(v).sort();}catch(e){}"
                                            + "for(var m=0;m<n[i][1].length;m++){try{if(typeof v[n[i][1][m]]==='function')"
                                            + "j.push(n[i][1][m]);}catch(e){}}"
                                            + "exposed.push([name,typeof v,Object.prototype.toString.call(v),k,j]);}}"
                                            + "return JSON.stringify(exposed);})()";
                                    final int index = ++scheduled;
                                    final int trackedCount = tracked.length();
                                    final String origin = hostAndPath(web.getUrl() == null
                                            ? null : android.net.Uri.parse(web.getUrl()));
                                    web.post(() -> web.evaluateJavascript(script, value ->
                                            Log.i(TAG, "bridge-exposure webview=" + index
                                                    + " origin=" + origin + " tracked=" + trackedCount
                                                    + " java=" + javaBridgesExposed(value)
                                                    + " exposed=" + value)));
                                }
                                if (view instanceof android.view.ViewGroup) {
                                    android.view.ViewGroup group = (android.view.ViewGroup) view;
                                    for (int i = 0; i < group.getChildCount(); i++) {
                                        queue.add(group.getChildAt(i));
                                    }
                                }
                            }
                        }
                        Log.i(TAG, "ok webview-bridges scheduled=" + scheduled);
                        break;
                    }
                    case "series-evidence":
                        Log.i(TAG, "ok series-evidence\n" + seriesEvidence());
                        break;
                    case "marker-corpus": {
                        // One log line per loaded video, so no line nears logcat's size limit.
                        String route = intent.getStringExtra("route");
                        if (route == null || !route.matches("[a-z-]{1,24}")) {
                            throw new IllegalArgumentException("marker-corpus needs a short route name");
                        }
                        List<String> lines = markerCorpus(route);
                        for (String line : lines) Log.i(TAG, "corpus\t" + route + "\t" + line);
                        Log.i(TAG, "ok marker-corpus " + route + " items=" + lines.size());
                        break;
                    }
                    case "marker-token-selftest": {
                        JSONObject tokens = new JSONObject();
                        tokens.put("longNumeral", token("1234567"));
                        tokens.put("leadingPlus", token("+1"));
                        tokens.put("intOverflow", token(4_294_967_296L));
                        tokens.put("doubleEpisode", token(1.0d));
                        tokens.put("blankObject", token(new Object() {
                            @Override public String toString() {
                                return "  ";
                            }
                        }));
                        Log.i(TAG, "ok marker-token-selftest " + tokens);
                        break;
                    }
                    case "commerce-evidence":
                        // Each line is deliberately structural. Strings are represented only by
                        // length, hash and fixed marker booleans so a diagnostic cannot collect
                        // captions, creator names, product names or other visible content.
                        for (String line : commerceEvidence()) {
                            Log.i(TAG, "commerce " + line);
                        }
                        Log.i(TAG, "ok commerce-evidence");
                        break;
                    case "commission-view":
                        for (String line : commissionViewEvidence()) {
                            Log.i(TAG, "commission-view " + line);
                        }
                        Log.i(TAG, "ok commission-view");
                        break;
                    case "video-link": {
                        String link = copiedTikTokVideoLink();
                        // What else is there, without the link itself: a short link and a
                        // missing one need different fixes.
                        if ("not-a-tiktok-video-url".equals(link)) link += " " + copiedLinkShape();
                        Log.i(TAG, "ok video-link " + link);
                        break;
                    }
                    case "clear-test-clipboard": {
                        String aid = required(intent, "aid");
                        if (!aid.matches("[0-9]+")) throw new IllegalArgumentException("invalid video id");
                        String copied = copiedTikTokVideoLink();
                        String copiedPath = android.net.Uri.parse(copied).getPath();
                        if (copiedPath != null && copiedPath.endsWith("/video/" + aid)) {
                            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                                    app.getSystemService(Context.CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
                            Log.i(TAG, "ok test clipboard cleared");
                        } else {
                            Log.i(TAG, "test clipboard no longer matches; left unchanged");
                        }
                        break;
                    }
                    case "open-copied-video": {
                        String link = copiedTikTokVideoLink();
                        if (!link.startsWith("https://www.tiktok.com/")) {
                            throw new IllegalStateException("no copied TikTok video URL");
                        }
                        Intent video = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link));
                        video.setPackage("com.zhiliaoapp.musically");
                        video.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        app.startActivity(video);
                        Log.i(TAG, "ok open-copied-video");
                        break;
                    }
                    case "open-public-video": {
                        String aid = required(intent, "aid");
                        if (!aid.matches("[0-9]+")) throw new IllegalArgumentException("invalid public video id");
                        Intent video = new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("snssdk1233://aweme/detail/" + aid));
                        video.setPackage("com.zhiliaoapp.musically");
                        video.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        app.startActivity(video);
                        Log.i(TAG, "ok open-public-video");
                        break;
                    }
                    case "open-video": {
                        String user = required(intent, "user");
                        String aid = required(intent, "aid");
                        if (!user.matches("[A-Za-z0-9._]+") || !aid.matches("[0-9]+")) {
                            throw new IllegalArgumentException("invalid public video route");
                        }
                        String link = "https://www.tiktok.com/@" + user + "/video/" + aid;
                        Intent video = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link));
                        video.setPackage("com.zhiliaoapp.musically");
                        video.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        app.startActivity(video);
                        Log.i(TAG, "ok open-video");
                        break;
                    }
                    case "hooks": {
                        // HookStatus contains only the names and counts of Hushfeed's own
                        // technical anchors. Keep this separate from the full diagnostic
                        // export so a compatibility pass never has to collect account or
                        // content-linked events just to find a renamed view or member.
                        List<?> lines = (List<?>) loader.loadClass(
                                "app.morphe.extension.shared.diagnostics.HookStatus")
                                .getMethod("report").invoke(null);
                        for (Object line : lines) Log.i(TAG, "hook " + line);
                        Log.i(TAG, "ok hooks " + lines.size());
                        break;
                    }
                    case "hookdetails": {
                        // The names behind each family's counts: which anchors bound and which
                        // were missed. "tako AI: 2 found" cannot say whether the comments bar
                        // guard ran; "comment bar hidden" can. Technical names only, the same
                        // as the counts above. With -e family <name> only that family prints.
                        Class<?> status = loader.loadClass(
                                "app.morphe.extension.shared.diagnostics.HookStatus");
                        Field familiesField = status.getDeclaredField("FAMILIES");
                        familiesField.setAccessible(true);
                        Map<?, ?> families = (Map<?, ?>) familiesField.get(null);
                        String only = intent.getStringExtra("family");
                        List<String> names = new ArrayList<>();
                        for (Object key : families.keySet()) names.add(String.valueOf(key));
                        Collections.sort(names);
                        int printed = 0;
                        for (String name : names) {
                            if (only != null && !only.equals(name)) continue;
                            Object family = families.get(name);
                            Field boundField = family.getClass().getDeclaredField("bound");
                            boundField.setAccessible(true);
                            Field orderField = family.getClass().getDeclaredField("order");
                            orderField.setAccessible(true);
                            StringBuilder line = new StringBuilder("hookdetail ").append(name)
                                    .append(" bound=").append(boundField.get(family));
                            List<?> misses = (List<?>) orderField.get(family);
                            if (!misses.isEmpty()) {
                                line.append(" missed=[");
                                for (int i = 0; i < misses.size(); i++) {
                                    Object miss = misses.get(i);
                                    Field keyField = miss.getClass().getDeclaredField("key");
                                    keyField.setAccessible(true);
                                    if (i > 0) line.append(", ");
                                    line.append(keyField.get(miss));
                                }
                                line.append(']');
                            }
                            Log.i(TAG, line.toString());
                            printed++;
                        }
                        Log.i(TAG, "ok hookdetails " + printed);
                        break;
                    }
                    case "topbar": {
                        // The comment sheet's server-driven top bar components on the current
                        // video, by biz type and component name only: which of them TikTok
                        // offered says whether a guard on one of them could have run at all.
                        // No text, id or url leaves the phone.
                        Class<?> author = loader.loadClass(
                                "app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor");
                        Object aweme = author.getMethod("getAweme").invoke(null);
                        if (aweme == null) throw new IllegalStateException("no current video");
                        // The resolver reads AwemeCommentConfig.commentTopBarComponent; the
                        // model's own getCommentTopBarStructList is a different, older list.
                        // Each member is looked up on its own, so a build that renames one
                        // still reports the others rather than nothing at all.
                        Object config = null, components = null, legacy = null;
                        String configState;
                        try {
                            config = aweme.getClass().getMethod("getCommentConfig").invoke(aweme);
                            configState = config == null ? "null" : "present";
                        } catch (NoSuchMethodException missing) {
                            configState = "no getter";
                        }
                        if (config != null) {
                            try {
                                components = config.getClass().getField("commentTopBarComponent").get(config);
                            } catch (NoSuchFieldException missing) {
                                configState += ", no commentTopBarComponent field";
                            }
                        }
                        String legacyState;
                        try {
                            legacy = aweme.getClass().getMethod("getCommentTopBarStructList").invoke(aweme);
                            legacyState = legacy == null ? "null" : String.valueOf(((List<?>) legacy).size());
                        } catch (NoSuchMethodException missing) {
                            legacyState = "no getter";
                        }
                        StringBuilder out = new StringBuilder("config=").append(configState)
                                .append(" legacyList=").append(legacyState)
                                .append(" components=");
                        if (components == null) {
                            out.append("null");
                        } else {
                            List<?> list = (List<?>) components;
                            out.append(list.size());
                            for (Object component : list) {
                                Object bizType = null, name = null;
                                try {
                                    Field field = component.getClass().getField("bizType");
                                    bizType = field.get(component);
                                } catch (NoSuchFieldException ignored) { }
                                try {
                                    Field field = component.getClass().getField("name");
                                    name = field.get(component);
                                } catch (NoSuchFieldException ignored) { }
                                out.append("\n  bizType=").append(bizType).append(" name=").append(name);
                            }
                        }
                        Log.i(TAG, "ok topbar\n" + out);
                        break;
                    }
                    case "videoinfo": {
                        // Technical facts about the current video that decide which surfaces
                        // TikTok offers on it: the caption's language code and whether TikTok
                        // marks the caption as translatable. No caption text, id or creator.
                        Class<?> author = loader.loadClass(
                                "app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor");
                        Object aweme = author.getMethod("getAweme").invoke(null);
                        if (aweme == null) throw new IllegalStateException("no current video");
                        Object language = aweme.getClass().getMethod("getDescLanguage").invoke(aweme);
                        Object translatable = aweme.getClass().getMethod("isDescTranslatable").invoke(aweme);
                        Object desc = aweme.getClass().getMethod("getDesc").invoke(aweme);
                        // A photo post the way Hushfeed's photo filter tells one, and the video's
                        // length (TikTok draws a seek bar on long ones).
                        Object images = aweme.getClass().getMethod("getImageInfos").invoke(aweme);
                        boolean photo = (images instanceof java.util.Collection && !((java.util.Collection<?>) images).isEmpty())
                                || aweme.getClass().getMethod("getPhotoModeImageInfo").invoke(aweme) != null
                                || aweme.getClass().getMethod("getPhotoModeTextInfo").invoke(aweme) != null;
                        Object video = aweme.getClass().getMethod("getVideo").invoke(aweme);
                        Object duration = video == null ? null : video.getClass().getMethod("getDuration").invoke(video);
                        // The facts the feed filters decide by, read the way TikTok's model
                        // offers them, as yes or no: a verified author, an AI label, a LIVE
                        // item, a sound named "original sound". No names and no ids.
                        Object creator = optional(aweme, "getAuthor");
                        Object verificationType = optional(creator, "getVerificationType");
                        boolean verified = (verificationType instanceof Number && ((Number) verificationType).intValue() != 0)
                                || !blank(optional(creator, "getCustomVerify"))
                                || !blank(optional(creator, "getEnterpriseVerifyReason"));
                        Object aigc = optional(aweme, "getAigcInfo");
                        Object aigcLabel = optional(aigc, "getAIGCLabelType");
                        Object moderation = optional(aweme, "getModerationAigcInfo");
                        Object moderationLabel = optional(moderation, "getModerationAigcLabelType");
                        Object moderationStatus = optional(moderation, "getModerationUserLabelStatus");
                        boolean aiLabel = (aigcLabel instanceof Number && ((Number) aigcLabel).intValue() != 0)
                                || (moderationLabel instanceof Number && ((Number) moderationLabel).longValue() != 0)
                                || (moderationStatus instanceof Number && ((Number) moderationStatus).longValue() != 0);
                        // TikTok 47.0.3 reports a profile view only for an account of 5,000
                        // followers or fewer: a bucket, not the count.
                        Object followerCount = optional(creator, "getFollowerCount");
                        String followers = !(followerCount instanceof Number) ? "unknown"
                                : ((Number) followerCount).intValue() <= 0 ? "zero"
                                : ((Number) followerCount).intValue() <= 5000 ? "5000orFewer" : "over5000";
                        // The feed leaves the author's follower count at zero (only the profile
                        // fills it), so the video's likes stand in for a small creator: a bucket.
                        Object statistics = optional(aweme, "getStatistics");
                        String likes = countBucket(optional(statistics, "getDiggCount"));
                        // The comment sheet's title shows this one, so its check needs a big one.
                        String comments = countBucket(optional(statistics, "getCommentCount"));
                        Object awemeType = optional(aweme, "getAwemeType");
                        Object liveId = optional(aweme, "getLiveId");
                        Object music = optional(aweme, "getMusic");
                        Object soundName = optional(music, "getMusicName");
                        if (blank(soundName)) soundName = optional(music, "getTitle");
                        boolean originalSound = !blank(soundName)
                                && String.valueOf(soundName).toLowerCase(java.util.Locale.ROOT).contains("original sound");
                        Log.i(TAG, "ok videoinfo descLanguage=" + language
                                + " descTranslatable=" + translatable
                                + " hasDesc=" + (desc != null && String.valueOf(desc).trim().length() > 0)
                                + " photo=" + photo + " durationMs=" + duration
                                + " verified=" + verified + " aiLabel=" + aiLabel + " authorFollowers=" + followers
                                + " likes=" + likes + " comments=" + comments
                                // The author's story status as stored, then as the getter the
                                // story ring switch filters answers it: a ring check needs a
                                // creator with a story and can't see the drawable.
                                + " storyStatus=" + (creator == null ? null : fieldValue(creator, "storyStatus"))
                                + "/" + optional(creator, "getStoryStatus")
                                // Whether TikTok serves it as separate video and audio (DASH),
                                // the case a chosen-quality download muxes itself.
                                + " dash=" + optional(video, "hasDashBitrate")
                                + " gears=" + gearList(loader, video)
                                // The frame TikTok's own download addresses say they are (clean,
                                // then watermarked), which a chosen size can defer to.
                                + " own=" + frame(optional(video, "getDownloadNoWatermarkAddr"))
                                + "/" + frame(optional(video, "getDownloadAddr"))
                                + " captions=" + captionList(loader, video)
                                // The test account's own state on this video: liked, following.
                                + " liked=" + optional(aweme, "isLike")
                                + " following=" + (optional(creator, "getFollowStatus") instanceof Number
                                        ? ((Number) optional(creator, "getFollowStatus")).intValue() != 0 : "unknown")
                                + " awemeType=" + awemeType
                                + " ad=" + optional(aweme, "isAd")
                                + " live=" + (liveId instanceof Number && ((Number) liveId).longValue() > 0)
                                + " originalSound=" + originalSound
                                // What the video says about duets and stitches, as its getters
                                // hand it back: 0 anyone, 1 the creator's friends only, 2 closed
                                // for music or a partnership. Allow Duet and Stitch answers 1 as 0
                                // while it is on, so read these with the switch off to find a
                                // video its creator closed.
                                + " duetSetting=" + optional(aweme, "getDuetSetting")
                                + " stitchSetting=" + optional(aweme, "getStitchSetting")
                                // The other two places TikTok decides a duet or a stitch: the
                                // creator's account-wide choice, and the permission block the
                                // server sends with the video.
                                + " authorDuet=" + optional(creator, "getDuetSetting")
                                + " authorStitch=" + optional(creator, "getStitchSetting")
                                + " permDuet=" + optional(optional(aweme, "getInteractPermission"), "getDuet")
                                + " permStitch=" + optional(optional(aweme, "getInteractPermission"), "getStitch"));
                        break;
                    }
                    case "labreport": {
                        // The Feature Gate Lab's learn mode from the shell: op=begin starts a
                        // recording, op=stop ends it and logs the report in numbered parts, since
                        // one log line holds about 4 KB and a report runs longer.
                        Class<?> learn = loader.loadClass(
                                "app.morphe.extension.tiktok.featuregatelab.FeatureGateLearnMode");
                        if ("begin".equals(intent.getStringExtra("op"))) {
                            learn.getMethod("begin").invoke(null);
                            Log.i(TAG, "ok labreport recording=" + learn.getMethod("isRecording").invoke(null));
                            break;
                        }
                        String report = String.valueOf(learn.getMethod("stopAndBuildReport").invoke(null));
                        // -e key <name>: only that gate's entry, or read=false when the recording
                        // never saw it. A whole report runs to 30 parts and more.
                        String key = intent.getStringExtra("key");
                        if (key != null) {
                            int at = report.indexOf("\"key\": \"" + key + "\"");
                            if (at < 0) {
                                Log.i(TAG, "ok labreport key " + key + " read=false");
                            } else {
                                int open = report.lastIndexOf('{', at);
                                int close = report.indexOf('}', at);
                                Log.i(TAG, "ok labreport key " + key + " read=true "
                                        + report.substring(open, close + 1).replaceAll("\\s+", " "));
                            }
                            break;
                        }
                        int size = 3000;
                        int parts = Math.max(1, (report.length() + size - 1) / size);
                        for (int part = 0; part < parts; part++) {
                            Log.i(TAG, "ok labreport part " + (part + 1) + "/" + parts + " "
                                    + report.substring(part * size, Math.min(report.length(), (part + 1) * size)));
                        }
                        break;
                    }
                    case "feedmute": {
                        // What Mute feed videos matches on: each engine it has seen with the
                        // source id TikTok's player reports for it, whether it counts as a feed
                        // engine and whether the engine is muted right now, the videos the
                        // controller asked for, and the one on screen. Ids by their last six
                        // digits only.
                        Class<?> mute = loader.loadClass("app.morphe.extension.tiktok.playback.FeedMute");
                        Field enginesField = mute.getDeclaredField("ENGINES");
                        enginesField.setAccessible(true);
                        Field feedEnginesField = mute.getDeclaredField("FEED_ENGINES");
                        feedEnginesField.setAccessible(true);
                        Field playsField = mute.getDeclaredField("PLAYS");
                        playsField.setAccessible(true);
                        Method sourceId = mute.getDeclaredMethod("engineSourceId", Object.class);
                        sourceId.setAccessible(true);
                        Method isMute = mute.getDeclaredMethod("engineIsMute", Object.class);
                        isMute.setAccessible(true);
                        StringBuilder out = new StringBuilder("engines=");
                        Map<?, ?> engines = (Map<?, ?>) enginesField.get(null);
                        Map<?, ?> feedEngines = (Map<?, ?>) feedEnginesField.get(null);
                        List<Object> seen;
                        synchronized (engines) {
                            seen = new ArrayList<>(engines.keySet());
                        }
                        for (Object engine : seen) {
                            boolean feed;
                            synchronized (feedEngines) {
                                feed = feedEngines.containsKey(engine);
                            }
                            out.append(Integer.toHexString(System.identityHashCode(engine))).append(':')
                                    .append(tail(sourceId.invoke(null, engine)))
                                    .append(feed ? ":feed" : "")
                                    .append(Boolean.TRUE.equals(isMute.invoke(null, engine)) ? ":muted" : ":sound")
                                    .append(' ');
                        }
                        out.append("| plays=");
                        Map<?, ?> plays = (Map<?, ?>) playsField.get(null);
                        synchronized (plays) {
                            for (Map.Entry<?, ?> play : plays.entrySet()) {
                                out.append(tail(play.getKey())).append('=').append(play.getValue()).append(' ');
                            }
                        }
                        Object aweme = loader.loadClass("app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                                .getMethod("getAweme").invoke(null);
                        out.append("| current=").append(tail(optional(aweme, "getAid")));
                        Log.i(TAG, "ok feedmute " + out);
                        break;
                    }
                    case "pitaya": {
                        // Whether Pitaya came up: the Pitaya libraries loaded into TikTok, the
                        // real core provider its plugin hands over (null while every core is
                        // TikTok's "host not ready" stand-in), the cores asked for so far and
                        // what answers each, and the boot loader's state. PitayaLite is left
                        // out on purpose: reading its fields runs its class initializer, which
                        // loads libAndroidPitayaProxy, so the library list answers for it.
                        StringBuilder out = new StringBuilder("libs=");
                        java.util.Set<String> libs = new java.util.TreeSet<>();
                        try (java.io.BufferedReader maps = new java.io.BufferedReader(
                                new java.io.FileReader("/proc/self/maps"))) {
                            for (String line; (line = maps.readLine()) != null; ) {
                                int slash = line.lastIndexOf('/');
                                if (slash >= 0 && line.toLowerCase(java.util.Locale.ROOT).contains("pitaya")) {
                                    libs.add(line.substring(slash + 1));
                                }
                            }
                        }
                        out.append(libs).append(' ');
                        for (String name : new String[] {
                                "com.bytedance.pitaya.api.mutilinstance.DelegateCoreProvider",
                                "com.bytedance.pitaya.api.PitayaBootLoader"}) {
                            Class<?> type = loader.loadClass(name);
                            out.append(type.getSimpleName()).append('{');
                            for (Field field : type.getDeclaredFields()) {
                                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                                field.setAccessible(true);
                                Object value = field.get(null);
                                String shown;
                                if (value == null || value instanceof Boolean || value instanceof Number) {
                                    shown = String.valueOf(value);
                                } else if (value instanceof Map) {
                                    StringBuilder entries = new StringBuilder("[");
                                    synchronized (value) {
                                        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                                            entries.append(entry.getKey()).append('=')
                                                    .append(entry.getValue() == null ? "null" : entry.getValue().getClass().getSimpleName())
                                                    .append(' ');
                                        }
                                    }
                                    shown = entries.append(']').toString();
                                } else {
                                    shown = value.getClass().getName();
                                }
                                out.append(field.getName()).append('=').append(shown).append(' ');
                            }
                            out.append("} ");
                        }
                        Log.i(TAG, "ok pitaya " + out);
                        break;
                    }
                    case "addrs": {
                        // Which addresses the current post's video carries and where they point,
                        // to tell a photo post's server-side render from an empty shell. Host and
                        // path only, since a query can carry tokens.
                        Object aweme = loader.loadClass(
                                "app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                                .getMethod("getAweme").invoke(null);
                        if (aweme == null) throw new IllegalStateException("no current video");
                        Object video = optional(aweme, "getVideo");
                        Object images = optional(aweme, "getImageInfos");
                        StringBuilder out = new StringBuilder("awemeType=").append(optional(aweme, "getAwemeType"))
                                .append(" images=").append(images instanceof java.util.Collection
                                        ? String.valueOf(((java.util.Collection<?>) images).size()) : "none");
                        for (String getter : new String[]{"getPlayAddr", "getPlayAddrH264", "getPlayAddrBytevc1",
                                "getDownloadAddr", "getDownloadNoWatermarkAddr"}) {
                            out.append(' ').append(getter.substring(3)).append('=')
                                    .append(addressReport(optional(video, getter)));
                        }
                        Log.i(TAG, "ok addrs " + out);
                        break;
                    }
                    case "collect": {
                        // Adds or removes one favourite through TikTok's own AwemeCollectionAgent,
                        // found by its parameter shape since the method's name changes by build,
                        // and logs what TikTok answers. Extras: aid, on (true or false).
                        String aid = intent.getStringExtra("aid");
                        boolean on = Boolean.parseBoolean(intent.getStringExtra("on"));
                        if ("current".equals(aid)) {
                            Object aweme = loader.loadClass(
                                    "app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                                    .getMethod("getAweme").invoke(null);
                            aid = aweme == null ? null : String.valueOf(optional(aweme, "getAid"));
                        }
                        if (aid == null || aid.isEmpty()) throw new IllegalArgumentException("no aid");
                        Class<?> agentClass = loader.loadClass(
                                "com.ss.android.ugc.aweme.favorites.business.aweme.AwemeCollectionAgent");
                        Class<?> function2 = loader.loadClass("kotlin.jvm.functions.Function2");
                        java.lang.reflect.Method call = null;
                        for (java.lang.reflect.Method m : agentClass.getMethods()) {
                            Class<?>[] p = m.getParameterTypes();
                            if (p.length == 5 && p[0] == String.class && p[1] == boolean.class
                                    && p[2] == java.util.Map.class && p[3] == function2 && p[4] == function2) {
                                call = m;
                            }
                        }
                        if (call == null) throw new IllegalStateException("no collect method on the agent");
                        // R8 renames kotlin.Unit's INSTANCE, so take its one static Unit field.
                        Class<?> unitClass = loader.loadClass("kotlin.Unit");
                        Object unit = null;
                        for (java.lang.reflect.Field field : unitClass.getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                                    && field.getType() == unitClass) {
                                field.setAccessible(true);
                                unit = field.get(null);
                            }
                        }
                        final Object done = unit;
                        Object success = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{function2},
                                (proxy, method, a) -> answer(method, a, done, "success"));
                        Object failure = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{function2},
                                (proxy, method, a) -> answer(method, a, done, "failure"));
                        Object agent = agentClass.getConstructor().newInstance();
                        call.invoke(agent, aid, on, new java.util.HashMap<String, Object>(), success, failure);
                        Log.i(TAG, "ok collect sent aid=" + aid + " on=" + on + " via " + call.getName());
                        break;
                    }
                    case "textviews": {
                        // Every shown TextView on screen, id or not, with its class chain, place,
                        // size and text length. The caption renderer's text view may carry no id
                        // on a given build, which the views action (ids only) cannot show. The
                        // text itself never leaves the phone.
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        StringBuilder out = new StringBuilder();
                        for (android.view.View root : windowRoots()) {
                            walkTextViews(root, 0, out, activity.getResources());
                        }
                        String text = out.toString();
                        int pieces = 0;
                        for (int at = 0; at < text.length(); at += 3000, pieces++) {
                            Log.i(TAG, "textviews[" + pieces + "] " + text.substring(at, Math.min(text.length(), at + 3000)));
                        }
                        Log.i(TAG, "ok textviews " + text.length() + " chars in " + pieces + " pieces");
                        break;
                    }
                    case "layouts": {
                        // Every shown, on-screen view that draws a Layout of its own rather than
                        // being a TextView (TikTok's X.09F7 draws captions and descriptions this
                        // way), with its class, place, size, text size in px and the layout's
                        // lines and width. How the caption size was shown to stay off the other
                        // text; the text itself never leaves the phone.
                        StringBuilder out = new StringBuilder();
                        java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>(windowRoots());
                        while (!queue.isEmpty()) {
                            android.view.View view = queue.removeFirst();
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
                            }
                            if (view instanceof android.widget.TextView || !view.isShown()
                                    || !view.getGlobalVisibleRect(new android.graphics.Rect())) continue;
                            android.text.Layout layout = layoutOf(view);
                            if (layout == null) continue;
                            int[] at = new int[2];
                            view.getLocationOnScreen(at);
                            out.append('\n').append(view.getClass().getName()).append(" at=").append(at[0]).append(',').append(at[1])
                                    .append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
                                    .append(" textPx=").append(Math.round(layout.getPaint().getTextSize()))
                                    .append(" textSp=").append(textSp(view, layout.getPaint().getTextSize()))
                                    .append(' ').append(layoutReport(layout));
                        }
                        // In pieces like the other reports: one line truncates near 4 KB and a
                        // busy screen's report is longer, which silently lost its tail.
                        String report = out.toString();
                        int pieces = 0;
                        for (int at = 0; at < report.length(); at += 3000, pieces++) {
                            Log.i(TAG, "layouts[" + pieces + "] "
                                    + report.substring(at, Math.min(report.length(), at + 3000)));
                        }
                        Log.i(TAG, "ok layouts " + report.length() + " chars in " + pieces + " pieces");
                        break;
                    }
                    case "opendetail": {
                        // Asks TikTok's detail route (snssdk1233://aweme/detail/<id>) for the video on
                        // screen, to check the detail page's pager away from the profile it would
                        // come from. On 47.0.3 the route, like a www.tiktok.com video link, played
                        // the video in the home feed rather than opening the detail page. The id
                        // stays in the phone.
                        Class<?> author = loader.loadClass("app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor");
                        Object aweme = author.getMethod("getAweme").invoke(null);
                        Object aid = optional(aweme, "getAid");
                        if (blank(aid)) throw new IllegalStateException("no current video");
                        android.content.Intent open = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("snssdk1233://aweme/detail/" + aid));
                        open.setPackage(context.getPackageName());
                        open.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(open);
                        Log.i(TAG, "ok opendetail");
                        break;
                    }
                    case "profileviewgates": {
                        // What TikTok 47.0.3 checks before it reports a profile view
                        // (ProfilePlatformViewModel.a73 before ProfileViewerApiService.reportView),
                        // read in this process: the feature gate X.0mpc.LIZ(), the under-16 flag,
                        // the viewer's "profile_view_history" privacy value (1 reports), the
                        // cooldown flag X.0mD8 and the follower cap it replaces. Names are this
                        // build's; each read says what it found or why it could not.
                        StringBuilder out = new StringBuilder();
                        String[][] reads = {
                                {"featureGate", "X.0mpc", "LIZ"},
                        };
                        for (String[] read : reads) {
                            try {
                                out.append(' ').append(read[0]).append('=')
                                        .append(loader.loadClass(read[1]).getMethod(read[2]).invoke(null));
                            } catch (Throwable failure) {
                                out.append(' ').append(read[0]).append("=?(").append(failure.getClass().getSimpleName()).append(')');
                            }
                        }
                        try {
                            Object feature = loader.loadClass("X.07So").getMethod("LIZIZ").invoke(null);
                            out.append(" under16=").append(feature.getClass().getMethod("LIZIZ").invoke(feature));
                        } catch (Throwable failure) {
                            out.append(" under16=?(").append(failure.getClass().getSimpleName()).append(')');
                        }
                        try {
                            Class<?> managerType = loader.loadClass("com.ss.android.ugc.aweme.framework.services.ServiceManager");
                            Object manager = managerType.getMethod("get").invoke(null);
                            Class<?> privacyType = loader.loadClass("com.ss.android.ugc.aweme.compliance.api.services.privacy.IPrivacyService");
                            Object privacy = managerType.getMethod("getService", Class.class).invoke(manager, privacyType);
                            Object settings = privacyType.getMethod("LJIIJ").invoke(privacy);
                            out.append(" privacySettings=").append(settings == null ? "null" : "present");
                            if (settings != null) {
                                for (String key : new String[]{"profile_view_history", "viewer_history", "post_view_history"}) {
                                    out.append(' ').append(key).append('=')
                                            .append(settings.getClass().getMethod("LIZ", String.class).invoke(settings, key));
                                }
                            }
                        } catch (Throwable failure) {
                            out.append(" privacy=?(").append(failure.getClass().getSimpleName()).append(": ").append(failure.getMessage()).append(')');
                        }
                        try {
                            Object lazy = loader.loadClass("X.0mD8").getField("LIZ").get(null);
                            out.append(" cooldownFlag=").append(lazy.getClass().getMethod("getValue").invoke(lazy));
                        } catch (Throwable failure) {
                            out.append(" cooldownFlag=?(").append(failure.getClass().getSimpleName()).append(')');
                        }
                        try {
                            Object lazy = loader.loadClass("X.0mD7").getField("LIZIZ").get(null);
                            Object config = lazy.getClass().getMethod("getValue").invoke(lazy);
                            Object cap = config == null ? null : config.getClass().getField("enabledMaxFollowers").get(config);
                            Object me = loader.loadClass("X.02y0").getMethod("LIZ").invoke(null);
                            Object followers = me == null ? null : me.getClass().getField("historyMaxFollowerCount").get(me);
                            out.append(" followerCap=").append(cap == null ? "5000 (default)" : cap).append(" myHistoryMaxFollowers=").append(followers);
                        } catch (Throwable failure) {
                            out.append(" followerCap=?(").append(failure.getClass().getSimpleName()).append(')');
                        }
                        Log.i(TAG, "ok profileviewgates" + out);
                        break;
                    }
                    case "finddesc": {
                        // Shown views whose content description is exactly -e desc, with class and
                        // bounds; -e click true also performs a click on the first, the listener a
                        // tap would run. For pressing one of Hushfeed's own overlay buttons (they
                        // carry no ids) and never the one beside it.
                        String desc = required(intent, "desc");
                        boolean click = "true".equals(intent.getStringExtra("click"));
                        // -e match prefix: descriptions that start with the text, for a control
                        // whose description carries a count after its name; -e match contains: a
                        // description with the text anywhere, for one whose count comes first.
                        boolean prefix = "prefix".equals(intent.getStringExtra("match"));
                        boolean contains = "contains".equals(intent.getStringExtra("match"));
                        StringBuilder out = new StringBuilder();
                        android.view.View first = null;
                        java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>(windowRoots());
                        while (!queue.isEmpty()) {
                            android.view.View view = queue.removeFirst();
                            // A view with no description is never a match (it read as "null", which
                            // a contains match found), and neither is one entirely off the screen:
                            // the feed keeps the cells either side of the current one laid out, and
                            // the first match was the previous post's button.
                            CharSequence raw = view.getContentDescription();
                            String described = raw == null ? null : withoutFormatMarks(raw.toString());
                            if (described != null && view.isShown()
                                    && view.getGlobalVisibleRect(new android.graphics.Rect())
                                    && (contains ? described.contains(desc)
                                    : prefix ? described.startsWith(desc) : desc.equals(described))) {
                                int[] at = new int[2];
                                view.getLocationOnScreen(at);
                                out.append("\n  ").append(view.getClass().getName()).append(" at=").append(at[0]).append(',').append(at[1])
                                        .append(" size=").append(view.getWidth()).append('x').append(view.getHeight());
                                if (first == null) first = view;
                            }
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
                            }
                        }
                        String clicked = "";
                        if (click && first != null) clicked = " clicked=" + first.performClick();
                        Log.i(TAG, "ok finddesc " + (first == null ? "none" : "found") + clicked + out);
                        break;
                    }
                    case "findtext": {
                        // A shown TextView whose text is exactly -e text (a UI label such as a share
                        // sheet action's), with its bounds; -e click true performs a click on its
                        // nearest clickable ancestor, the cell a tap would hit. Only bounds are
                        // printed.
                        String wanted = required(intent, "text");
                        boolean click = "true".equals(intent.getStringExtra("click"));
                        // -e match regex: -e text is a pattern found anywhere in the text, for a
                        // label that varies (a comment's time, "3d" or "10-07").
                        java.util.regex.Pattern pattern = "regex".equals(intent.getStringExtra("match"))
                                ? java.util.regex.Pattern.compile(wanted) : null;
                        // -e hold N: a finger held N ms on the match, through its own window, for
                        // a long-press menu. The comment sheet is a dialog with a window of its
                        // own, and uiautomator can't dump it while the video plays behind it.
                        String holdExtra = intent.getStringExtra("hold");
                        long holdMs = holdExtra == null ? 0L : Long.parseLong(holdExtra);
                        // -e index N: the Nth match in tree order, for a label that repeats (a tab
                        // and the badges below it); every match's place is printed either way.
                        String indexExtra = intent.getStringExtra("index");
                        int index = indexExtra == null ? 1 : Integer.parseInt(indexExtra);
                        // A click or a hold only goes to a match on the screen: the comment list
                        // keeps rows laid out above and below what shows. Counting still takes
                        // every match, so a "none" check can't pass on a row scrolled out of view.
                        boolean pressing = click || holdMs > 0;
                        int matches = 0;
                        int pressable = 0;
                        StringBuilder places = new StringBuilder();
                        android.view.View found = null;
                        java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>(windowRoots());
                        while (!queue.isEmpty()) {
                            android.view.View view = queue.removeFirst();
                            // An empty field shows its hint ("Add comment..."), which is the label a
                            // person sees there.
                            String shownText = null;
                            if (view instanceof android.widget.TextView) {
                                android.widget.TextView textView = (android.widget.TextView) view;
                                CharSequence shown = textView.getText();
                                if ((shown == null || shown.length() == 0) && textView.getHint() != null) shown = textView.getHint();
                                shownText = withoutFormatMarks(String.valueOf(shown));
                            }
                            if (shownText != null && view.isShown()
                                    && (pattern == null ? wanted.contentEquals(shownText) : pattern.matcher(shownText).find())) {
                                matches++;
                                int[] place = new int[2];
                                view.getLocationOnScreen(place);
                                places.append(" #").append(matches).append('=').append(place[0]).append(',').append(place[1]);
                                boolean onScreen = view.getGlobalVisibleRect(new android.graphics.Rect());
                                if (!onScreen) places.append("(off screen)");
                                if (found == null && (!pressing || onScreen) && ++pressable == index) found = view;
                            }
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
                            }
                        }
                        if (found == null) {
                            Log.i(TAG, "ok findtext none (" + matches + " matches" + places + ")");
                            break;
                        }
                        // -e sibling next: the view right after the match in its parent, for an
                        // icon with no text or description beside a label (the comment sort glyph
                        // after "189 comments"). Its id is renamed every build; its place isn't.
                        String sibling = "";
                        if ("next".equals(intent.getStringExtra("sibling"))) {
                            android.view.View next = null;
                            if (found.getParent() instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) found.getParent();
                                int position = group.indexOfChild(found);
                                if (position >= 0 && position + 1 < group.getChildCount()) next = group.getChildAt(position + 1);
                            }
                            if (next == null || !next.isShown()) {
                                Log.i(TAG, "ok findtext none (no shown view after the match; " + matches + " matches" + places + ")");
                                break;
                            }
                            found = next;
                            sibling = " sibling=" + next.getClass().getSimpleName();
                        }
                        int[] at = new int[2];
                        found.getLocationOnScreen(at);
                        String clicked = "";
                        if (click) {
                            android.view.View target = found;
                            while (target != null && !target.isClickable()) {
                                target = target.getParent() instanceof android.view.View ? (android.view.View) target.getParent() : null;
                            }
                            clicked = target == null ? " clicked=no clickable ancestor" : " clicked=" + target.performClick();
                        }
                        if (holdMs > 0) {
                            android.view.View root = found.getRootView();
                            int[] inWindow = new int[2];
                            found.getLocationInWindow(inWindow);
                            float x = inWindow[0] + found.getWidth() / 2f;
                            float y = inWindow[1] + found.getHeight() / 2f;
                            long down = android.os.SystemClock.uptimeMillis();
                            touch(root, down, down, android.view.MotionEvent.ACTION_DOWN, x, y);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> touch(root, down,
                                    android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, x, y), holdMs);
                            clicked += " held=" + holdMs + "ms";
                        }
                        Log.i(TAG, "ok findtext found at=" + at[0] + "," + at[1] + " size=" + found.getWidth() + "x" + found.getHeight() + sibling + clicked
                                + " (" + matches + " matches" + places + ")");
                        break;
                    }
                    case "textwords": {
                        // How many shown TextViews carrying one id contain a phrase, compared the
                        // way the keyword lists compare (lower-cased, anywhere in the text):
                        // -e id f4t -e word the counts 47.0.3's comment bodies holding "the".
                        // Counts only; the text never leaves the phone.
                        String idName = required(intent, "id");
                        String word = required(intent, "word").toLowerCase(java.util.Locale.ROOT);
                        // -e onscreen true: only views on the screen. The feed keeps the cells
                        // above and below laid out, so a rail count matched the next video's too.
                        boolean onScreenOnly = "true".equals(intent.getStringExtra("onscreen"));
                        int[] counts = new int[2];
                        java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>(windowRoots());
                        while (!queue.isEmpty()) {
                            android.view.View view = queue.removeFirst();
                            if (view instanceof android.widget.TextView && view.isShown()
                                    && (!onScreenOnly || view.getGlobalVisibleRect(new android.graphics.Rect()))
                                    && safeResourceName(view).endsWith("/" + idName)) {
                                counts[0]++;
                                CharSequence text = ((android.widget.TextView) view).getText();
                                if (text != null && text.toString().toLowerCase(java.util.Locale.ROOT).contains(word)) counts[1]++;
                            }
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
                            }
                        }
                        Log.i(TAG, "ok textwords id=" + idName + " shown=" + counts[0] + " containing=" + counts[1]);
                        break;
                    }
                    case "doubletap": {
                        // Two taps on TikTok's own window, timed inside the double-tap window.
                        // "input tap" twice from adb spawns a process per tap and lands inside or
                        // outside TikTok's window depending on how busy the phone is, which made
                        // the same check pass and fail on different runs. Dispatching the events
                        // on the main thread pins the timing.
                        float x = Float.parseFloat(required(intent, "x"));
                        float y = Float.parseFloat(required(intent, "y"));
                        String gapText = intent.getStringExtra("gap");
                        long gap = gapText == null ? 90L : Long.parseLong(gapText);
                        // How long the second touch stays down, for #63: a finger that rests on
                        // the second tap also reaches the long press timers.
                        String holdText = intent.getStringExtra("hold");
                        long hold = holdText == null ? 30L : Long.parseLong(holdText);
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        android.view.View decor = activity.getWindow().getDecorView();
                        tap(decor, x, y);
                        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                        main.postDelayed(() -> {
                            long down = android.os.SystemClock.uptimeMillis();
                            touch(decor, down, down, android.view.MotionEvent.ACTION_DOWN, x, y);
                            main.postDelayed(() -> {
                                touch(decor, down, android.os.SystemClock.uptimeMillis(),
                                        android.view.MotionEvent.ACTION_UP, x, y);
                                Log.i(TAG, "ok doubletap at " + x + "," + y + " gap=" + gap + "ms hold="
                                        + hold + "ms on " + activity.getClass().getSimpleName());
                            }, hold);
                        }, gap);
                        break;
                    }
                    case "report": {
                        // The diagnostic export, which is the extension's own record of what it
                        // did. Logcat is not: the release extension is minified and its Log calls
                        // do not reach the buffer, so this is how a device check reads it. The
                        // log takes about 4 KB a line, so a long export goes out in pieces.
                        // Its list of registered switches is filled in when Hushfeed's settings
                        // open, where a person exports the log. A check asks from any screen, so
                        // the list is filled in here the same way first.
                        Method registered = loader.loadClass(
                                "app.morphe.extension.tiktok.settings.SettingsStatus")
                                .getDeclaredMethod("registered");
                        registered.setAccessible(true);
                        loader.loadClass("app.morphe.extension.shared.diagnostics.BuildDetails")
                                .getMethod("setRegisteredSwitches", java.util.Collection.class)
                                .invoke(null, registered.invoke(null));
                        String text = String.valueOf(loader.loadClass(
                                "app.morphe.extension.shared.settings.preference.LogBufferManager")
                                .getMethod("buildExportText").invoke(null));
                        int pieces = 0;
                        for (int at = 0; at < text.length(); at += 3000, pieces++) {
                            Log.i(TAG, "report[" + pieces + "] " + text.substring(at, Math.min(text.length(), at + 3000)));
                        }
                        Log.i(TAG, "ok report " + text.length() + " chars in " + pieces + " pieces");
                        break;
                    }
                    case "get": {
                        String key = required(intent, "key");
                        Log.i(TAG, "ok get " + key + "=" + valueOf(find(key)));
                        break;
                    }
                    case "set": {
                        String key = required(intent, "key");
                        String value = required(intent, "value");
                        String before = valueOf(find(key));
                        write(key, value);
                        // The same five the settings page refreshes the feed controls for. A set
                        // step writes several keys in turn, so refreshing on only the first one
                        // left the others' buttons up until the next creator.
                        if (OVERLAY_KEYS.contains(key)) {
                            loader.loadClass("app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay")
                                    .getMethod("refresh").invoke(null);
                        }
                        Log.i(TAG, "ok set " + key + " " + before + " -> " + valueOf(find(key)));
                        break;
                    }
                    case "secure-popup": {
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        Log.i(TAG, "ok secure-popup "
                                + SecurePopupProbe.show(loader, activity));
                        break;
                    }
                    case "secure-popup-close":
                        Log.i(TAG, "ok secure-popup-close " + SecurePopupProbe.close());
                        break;
                    case "seam": {
                        // A public static int or boolean test seam on one of Hushfeed's own
                        // classes, for a device check that needs a state the phone will not
                        // produce on demand. Nothing outside the extension is reachable.
                        String className = required(intent, "class");
                        if (!className.startsWith("app.morphe.extension.")) {
                            throw new IllegalArgumentException("seam reaches app.morphe.extension classes only");
                        }
                        String fieldName = required(intent, "field");
                        String value = required(intent, "value");
                        Field field = loader.loadClass(className).getField(fieldName);
                        if (!Modifier.isStatic(field.getModifiers())) {
                            throw new IllegalArgumentException(fieldName + " is not static");
                        }
                        Object before = field.get(null);
                        if (field.getType() == int.class) {
                            field.setInt(null, Integer.parseInt(value));
                        } else if (field.getType() == boolean.class) {
                            field.setBoolean(null, Boolean.parseBoolean(value));
                        } else {
                            throw new IllegalArgumentException(fieldName + " is a " + field.getType().getName());
                        }
                        Log.i(TAG, "ok seam " + className + "." + fieldName + " " + before + " -> " + field.get(null));
                        break;
                    }
                    case "labrule": {
                        // A Feature Gate Lab override, written the way the Lab's own detail page
                        // writes it, with the Lab switched on. Lets a device check force a gate
                        // without driving the Lab's screens: issue #4 was traced to two favourites
                        // gates, and this is how the S22 reproduces them.
                        Class<?> store = loader.loadClass(
                                "app.morphe.extension.tiktok.featuregatelab.FeatureGateLabStore");
                        String manager = required(intent, "manager");
                        String key = required(intent, "key");
                        String type = required(intent, "type");
                        String value = required(intent, "value");
                        boolean enabled = !"false".equals(intent.getStringExtra("enabled"));
                        Object saved = store.getMethod("saveRule", String.class, String.class,
                                String.class, String.class, boolean.class)
                                .invoke(null, manager, key, type, value, enabled);
                        store.getMethod("setMasterEnabled", boolean.class).invoke(null, true);
                        Log.i(TAG, "ok labrule " + manager + " " + key + " " + type + "=" + value
                                + " enabled=" + enabled + " saved=" + saved + " master=on");
                        break;
                    }
                    case "labclear": {
                        Class<?> store = loader.loadClass(
                                "app.morphe.extension.tiktok.featuregatelab.FeatureGateLabStore");
                        store.getMethod("resetAllLabData").invoke(null);
                        Log.i(TAG, "ok labclear");
                        break;
                    }
                    case "views": {
                        // The current activity's view tree, one line per view that carries an
                        // id, with its resource name, visibility, place, size and scale. With
                        // -e find <name> only the views carrying that id name are printed, each
                        // with the chain of id names above it. uiautomator cannot dump TikTok's
                        // feed, and this is how a hider's target ids are checked against what is
                        // really on screen. The log takes about 4 KB a line, so it goes out in
                        // pieces the way the report does.
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        String find = intent.getStringExtra("find");
                        StringBuilder out = new StringBuilder();
                        walkViews(activity.getWindow().getDecorView(), 0, find, out, activity.getResources());
                        String text = out.toString();
                        int pieces = 0;
                        for (int at = 0; at < text.length(); at += 3000, pieces++) {
                            Log.i(TAG, "views[" + pieces + "] " + text.substring(at, Math.min(text.length(), at + 3000)));
                        }
                        Log.i(TAG, "ok views " + text.length() + " chars in " + pieces + " pieces");
                        break;
                    }
                    case "strings": {
                        // TikTok's own UI strings by resource id, resolved the way TikTok resolves
                        // them. Most of 47.0.3's strings are not in the APK's resource table (its
                        // string type holds 964 entries); TikTok serves them at run time, so the
                        // phone is the only place to read what a given id says. UI text only.
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        StringBuilder out = new StringBuilder();
                        for (String id : required(intent, "ids").split(",")) {
                            int value = Integer.decode(id.trim());
                            String text;
                            try {
                                text = activity.getString(value);
                            } catch (RuntimeException missing) {
                                text = "<" + missing.getClass().getSimpleName() + ">";
                            }
                            out.append('\n').append(id.trim()).append('=').append(text);
                        }
                        Log.i(TAG, "ok strings" + out);
                        break;
                    }
                    case "backgrounds": {
                        // Every shown view under a screen point, outermost first, with its id name
                        // and what its background paints: -e x 540 -e y 1600. A theme patch that
                        // changes nothing on a surface needs to know which view draws that surface
                        // and from what, and a screenshot only gives the colour.
                        int x = Integer.parseInt(required(intent, "x"));
                        int y = Integer.parseInt(required(intent, "y"));
                        StringBuilder out = new StringBuilder();
                        for (android.view.View root : windowRoots()) {
                            describeBackgroundsAt(root, x, y, 0, out, root.getResources());
                        }
                        String text = out.toString();
                        int pieces = 0;
                        for (int at = 0; at < text.length(); at += 3000, pieces++) {
                            Log.i(TAG, "backgrounds[" + pieces + "] " + text.substring(at, Math.min(text.length(), at + 3000)));
                        }
                        Log.i(TAG, "ok backgrounds " + text.length() + " chars in " + pieces + " pieces");
                        break;
                    }
                    case "hasids": {
                        // Whether views with the named ids are in the current windows and shown:
                        // -e names f7u,fo,d4. Each name is looked up in TikTok's own package and
                        // in the search module's (visual search lives there), and each id found
                        // is reported as views/shown. One walk for all of them, so a scan over
                        // hundreds of videos costs one round trip a video.
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        android.content.res.Resources resources = activity.getResources();
                        String app = activity.getPackageName();
                        java.util.LinkedHashMap<Integer, String> wanted = new java.util.LinkedHashMap<>();
                        List<String> unknown = new ArrayList<>();
                        for (String name : required(intent, "names").split(",")) {
                            boolean known = false;
                            for (String pkg : new String[]{app, app + ".df_search_biz"}) {
                                int id = resources.getIdentifier(name.trim(), "id", pkg);
                                if (id != 0) wanted.put(id, name.trim() + (pkg.equals(app) ? "" : "@search"));
                                known |= id != 0;
                            }
                            // A name this build lacks must not read the same as one that is not on screen.
                            if (!known) unknown.add(name.trim());
                        }
                        java.util.Map<Integer, int[]> counts = new java.util.HashMap<>();
                        java.util.ArrayDeque<android.view.View> pending = new java.util.ArrayDeque<>(windowRoots());
                        while (!pending.isEmpty()) {
                            android.view.View view = pending.removeFirst();
                            if (wanted.containsKey(view.getId())) {
                                int[] count = counts.computeIfAbsent(view.getId(), key -> new int[2]);
                                count[0]++;
                                if (view.isShown() && view.getWidth() > 0 && view.getHeight() > 0) count[1]++;
                            }
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
                            }
                        }
                        StringBuilder out = new StringBuilder();
                        for (java.util.Map.Entry<Integer, String> entry : wanted.entrySet()) {
                            int[] count = counts.getOrDefault(entry.getKey(), new int[2]);
                            out.append(' ').append(entry.getValue()).append('=').append(count[0]).append('/').append(count[1]);
                        }
                        for (String name : unknown) out.append(' ').append(name).append("=missing");
                        Log.i(TAG, "ok hasids" + out);
                        break;
                    }
                    case "surprisestruct": {
                        // Builds TikTok's CommentSurpriseStruct around a CommentSurprise, the way
                        // TikTok wraps a surprise its server sent with a comment, and says what the
                        // struct kept. Every popup ad path reads the surprise from here. The surprise
                        // carries -e keyword (default "probe") and -e type (default 3): with Hide
                        // comment popup ads on, one a keyword set off is dropped, and type 1, TikTok's
                        // own first-comment celebration, is kept. Building one marks the popup ads
                        // hook as reached in Hook status, so read a diagnostic export before this.
                        Class<?> surpriseType = loader.loadClass("com.ss.android.ugc.aweme.comment.model.CommentSurprise");
                        Class<?> commentType = loader.loadClass("com.ss.android.ugc.aweme.comment.model.Comment");
                        Class<?> structType = loader.loadClass("com.ss.android.ugc.aweme.comment.model.CommentSurpriseStruct");
                        Object surprise = surpriseType.getConstructor().newInstance();
                        String keyword = intent.getStringExtra("keyword");
                        String kind = intent.getStringExtra("type");
                        Field keywordField = surpriseType.getDeclaredField("keyword");
                        keywordField.setAccessible(true);
                        keywordField.set(surprise, keyword == null ? "probe" : keyword);
                        Field typeField = surpriseType.getDeclaredField("surpriseType");
                        typeField.setAccessible(true);
                        typeField.set(surprise, Integer.valueOf(kind == null ? "3" : kind));
                        Object struct = structType.getConstructor(commentType, surpriseType, boolean.class)
                                .newInstance(null, surprise, false);
                        Object kept = structType.getField("commentSurprise").get(struct);
                        Log.i(TAG, "ok surprisestruct kept=" + (kept == surprise) + " dropped=" + (kept == null));
                        break;
                    }
                    case "pagerwatch": {
                        // Watches TikTok's main pager (the one a left swipe slides to the creator's
                        // profile: its class chain alone declares setPagingMainValve). Adds a page
                        // change listener through the pager's own add method, never the setter that
                        // would replace TikTok's, and logs each scroll state change and page
                        // selection with the stack that caused it, so the path that moves the pager
                        // can be read off a real swipe.
                        android.view.View pager = null;
                        java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>(windowRoots());
                        while (!queue.isEmpty() && pager == null) {
                            android.view.View view = queue.removeFirst();
                            for (Class<?> c = view.getClass(); c != null && pager == null; c = c.getSuperclass()) {
                                for (Method m : c.getDeclaredMethods()) {
                                    if (m.getName().equals("setPagingMainValve")) { pager = view; break; }
                                }
                            }
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
                            }
                        }
                        if (pager == null) throw new IllegalStateException("no main pager on screen");
                        // The listener type is setOnPageChangeListener's parameter; the list the pager
                        // tells every added listener from is its one CopyOnWriteArrayList field. Adding
                        // to that list leaves TikTok's own listeners where they are.
                        Class<?> listenerType = null;
                        Field listeners = null;
                        for (Class<?> c = pager.getClass(); c != null; c = c.getSuperclass()) {
                            for (Method m : c.getDeclaredMethods()) {
                                if (listenerType == null && m.getName().equals("setOnPageChangeListener")
                                        && m.getParameterTypes().length == 1) {
                                    listenerType = m.getParameterTypes()[0];
                                }
                            }
                            for (Field f : c.getDeclaredFields()) {
                                if (listeners == null && f.getType() == java.util.concurrent.CopyOnWriteArrayList.class) {
                                    listeners = f;
                                }
                            }
                        }
                        if (listenerType == null || listeners == null) {
                            throw new IllegalStateException("no listener type or list: " + listenerType + ", " + listeners);
                        }
                        final android.view.View watched = pager;
                        Object proxy = java.lang.reflect.Proxy.newProxyInstance(listenerType.getClassLoader(),
                                new Class<?>[]{listenerType}, (self, method, args) -> {
                                    if (method.getDeclaringClass() == Object.class) {
                                        return method.getName().equals("equals") ? self == args[0]
                                                : method.getName().equals("hashCode") ? System.identityHashCode(self)
                                                : "pagerwatch";
                                    }
                                    if (args != null && args.length == 1 && args[0] instanceof Integer) {
                                        StringBuilder stack = new StringBuilder();
                                        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
                                        for (int i = 3; i < Math.min(frames.length, 40); i++) {
                                            stack.append("\n    ").append(frames[i].getClassName()).append('.')
                                                    .append(frames[i].getMethodName());
                                        }
                                        Log.i(TAG, "pagerwatch " + method.getName() + "(" + args[0] + ") on "
                                                + watched.getClass().getName() + stack);
                                    }
                                    return null;
                                });
                        listeners.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        java.util.concurrent.CopyOnWriteArrayList<Object> list =
                                (java.util.concurrent.CopyOnWriteArrayList<Object>) listeners.get(pager);
                        if (list == null) {
                            list = new java.util.concurrent.CopyOnWriteArrayList<>();
                            listeners.set(pager, list);
                        }
                        list.add(proxy);
                        Log.i(TAG, "ok pagerwatch on " + pager.getClass().getName() + " via " + listeners.getName()
                                + " (" + list.size() + " listeners) for " + listenerType.getName());
                        break;
                    }
                    case "pagertouch": {
                        // Every touch TikTok's main pager dispatches, and every intercept check it
                        // makes past touch slop, as its own listeners see them: adds a logging
                        // listener to the list its getOnInterceptTouchEventListeners returns (the
                        // pager calls that list's dispatchTouchEvent on each event and G2 on each
                        // intercept check). One line an event: method, action, x, y.
                        android.view.View pager = null;
                        java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>(windowRoots());
                        while (!queue.isEmpty() && pager == null) {
                            android.view.View view = queue.removeFirst();
                            for (Class<?> c = view.getClass(); c != null && pager == null; c = c.getSuperclass()) {
                                for (Method m : c.getDeclaredMethods()) {
                                    if (m.getName().equals("getOnInterceptTouchEventListeners")) { pager = view; break; }
                                }
                            }
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
                            }
                        }
                        if (pager == null) throw new IllegalStateException("no main pager on screen");
                        Method getter = null;
                        for (Class<?> c = pager.getClass(); c != null && getter == null; c = c.getSuperclass()) {
                            for (Method m : c.getDeclaredMethods()) {
                                if (m.getName().equals("getOnInterceptTouchEventListeners")) getter = m;
                            }
                        }
                        getter.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        java.util.List<Object> touchListeners = (java.util.List<Object>) getter.invoke(pager);
                        Class<?> touchType = null;
                        for (Object existing : touchListeners) {
                            for (Class<?> i : existing.getClass().getInterfaces()) {
                                for (Method m : i.getDeclaredMethods()) {
                                    if (m.getName().equals("dispatchTouchEvent")) touchType = i;
                                }
                            }
                        }
                        if (touchType == null) throw new IllegalStateException("no listener interface among " + touchListeners.size());
                        Object touchProxy = java.lang.reflect.Proxy.newProxyInstance(touchType.getClassLoader(),
                                new Class<?>[]{touchType}, (self, method, args) -> {
                                    if (method.getDeclaringClass() == Object.class) {
                                        return method.getName().equals("equals") ? self == args[0]
                                                : method.getName().equals("hashCode") ? System.identityHashCode(self)
                                                : "pagertouch";
                                    }
                                    if (args != null && args.length > 0 && args[0] instanceof android.view.MotionEvent) {
                                        android.view.MotionEvent e = (android.view.MotionEvent) args[0];
                                        Log.i(TAG, "pagertouch " + method.getName() + " action=" + e.getActionMasked()
                                                + " x=" + (int) e.getX() + " y=" + (int) e.getY());
                                    }
                                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                                });
                        touchListeners.add(touchProxy);
                        Log.i(TAG, "ok pagertouch on " + pager.getClass().getName() + " for " + touchType.getName()
                                + " (" + touchListeners.size() + " listeners)");
                        break;
                    }
                    case "pagerstate": {
                        // TikTok's main pager: its current item, its adapter's page count, and each
                        // page laid out in it (left edge, width, class) with whether it holds the
                        // vertical feed pager, so which index is the feed can be read, not assumed.
                        // The shown one: a video opened from a profile, search or a link sits in a
                        // second pager of the same base on the detail page, over the home one.
                        android.view.View pager = null;
                        java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>(windowRoots());
                        while (!queue.isEmpty() && pager == null) {
                            android.view.View view = queue.removeFirst();
                            for (Class<?> c = view.getClass(); c != null && pager == null && view.isShown(); c = c.getSuperclass()) {
                                for (Method m : c.getDeclaredMethods()) {
                                    if (m.getName().equals("setPagingMainValve")) { pager = view; break; }
                                }
                            }
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
                            }
                        }
                        if (pager == null) throw new IllegalStateException("no main pager on screen");
                        Object current = pager.getClass().getMethod("getCurrentItem").invoke(pager);
                        Object adapter = pager.getClass().getMethod("getAdapter").invoke(pager);
                        Object count = adapter == null ? null : adapter.getClass().getMethod("getCount").invoke(adapter);
                        StringBuilder out = new StringBuilder(" current=").append(current)
                                .append(" count=").append(count)
                                .append(" adapter=").append(adapter == null ? null : adapter.getClass().getName())
                                .append(" width=").append(pager.getWidth()).append(" scrollX=").append(pager.getScrollX());
                        // Every boolean the pager's own classes declare (its paging valve and the
                        // flags its page-enabled check reads among them), class by class.
                        for (Class<?> c = pager.getClass(); c != null && c != android.view.ViewGroup.class; c = c.getSuperclass()) {
                            StringBuilder flags = new StringBuilder();
                            for (Field field : c.getDeclaredFields()) {
                                if (field.getType() != boolean.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                                field.setAccessible(true);
                                flags.append(' ').append(field.getName()).append('=').append(field.getBoolean(pager));
                            }
                            if (flags.length() > 0) out.append("\n  flags ").append(c.getName()).append(':').append(flags);
                        }
                        android.view.ViewGroup group = (android.view.ViewGroup) pager;
                        for (int i = 0; i < group.getChildCount(); i++) {
                            android.view.View child = group.getChildAt(i);
                            boolean feed = false;
                            java.util.ArrayDeque<android.view.View> inside = new java.util.ArrayDeque<>();
                            inside.add(child);
                            while (!inside.isEmpty() && !feed) {
                                android.view.View v = inside.removeFirst();
                                feed = v.getClass().getName().endsWith("VerticalViewPager");
                                if (v instanceof android.view.ViewGroup) {
                                    android.view.ViewGroup g = (android.view.ViewGroup) v;
                                    for (int j = 0; j < g.getChildCount(); j++) inside.add(g.getChildAt(j));
                                }
                            }
                            out.append("\n  child ").append(i).append(' ').append(child.getClass().getName())
                                    .append(" left=").append(child.getLeft()).append(" width=").append(child.getWidth())
                                    .append(feed ? " holds the vertical feed" : "");
                        }
                        Log.i(TAG, "ok pagerstate" + out);
                        break;
                    }
                    case "captionstate": {
                        // The caption strip (dlk) and its text (dlr) in one read. A caption line is
                        // on screen only while it is spoken, and separate reads kept landing after
                        // it had gone. For each shown view with either id: place, size and
                        // background, and for the text its size in pixels and its line count.
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        android.content.res.Resources resources = activity.getResources();
                        // 47.1.4 renamed them to dm5 and dmb: -e strip dm5 -e text dmb.
                        String stripName = intent.getStringExtra("strip");
                        String textName = intent.getStringExtra("text");
                        if (stripName == null) stripName = "dlk";
                        if (textName == null) textName = "dlr";
                        int strip = resources.getIdentifier(stripName, "id", activity.getPackageName());
                        int text = resources.getIdentifier(textName, "id", activity.getPackageName());
                        StringBuilder out = new StringBuilder();
                        java.util.ArrayDeque<android.view.View> pending = new java.util.ArrayDeque<>(windowRoots());
                        while (!pending.isEmpty()) {
                            android.view.View view = pending.removeFirst();
                            int id = view.getId();
                            if (id != 0 && (id == strip || id == text) && view.isShown()) {
                                int[] where = new int[2];
                                view.getLocationOnScreen(where);
                                out.append(' ').append(id == strip ? "strip" : "text")
                                        .append("[at=").append(where[0]).append(',').append(where[1])
                                        .append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
                                        .append(" bg=").append(describeDrawable(view.getBackground(), 0));
                                if (view instanceof android.widget.TextView) {
                                    android.widget.TextView label = (android.widget.TextView) view;
                                    out.append(" textPx=").append(label.getTextSize())
                                            .append(" textSp=").append(textSp(label, label.getTextSize()))
                                            .append(" lines=").append(label.getLineCount());
                                }
                                if (id == strip && view instanceof android.view.ViewGroup) {
                                    // On 47.0.3 the text view stays gone and a sibling draws the
                                    // caption's layout, so the children say what is really shown.
                                    android.view.ViewGroup group = (android.view.ViewGroup) view;
                                    for (int i = 0; i < group.getChildCount(); i++) {
                                        android.view.View child = group.getChildAt(i);
                                        out.append(" child=").append(child.getClass().getName())
                                                .append('/').append(idName(child, resources))
                                                .append("/vis").append(child.getVisibility())
                                                .append('/').append(child.getWidth()).append('x').append(child.getHeight());
                                        android.text.Layout drawn = layoutOf(child);
                                        if (drawn != null) out.append("/layout:").append(layoutReport(drawn));
                                    }
                                }
                                out.append(']');
                            }
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
                            }
                        }
                        // Hushfeed's own caption line for clear display, which TikTok's strip gives
                        // way to: shown or not, its size and how long its text is, never the text.
                        // Then what the line waits on: cleared, a cue (its length), the cue's video
                        // being the current one, and TikTok's caption view still in the window.
                        try {
                            Class<?> tools = loader.loadClass("app.morphe.extension.tiktok.captions.CaptionTools");
                            Object line = staticField(tools, "overlay") instanceof java.lang.ref.Reference
                                    ? ((java.lang.ref.Reference<?>) staticField(tools, "overlay")).get() : null;
                            if (line instanceof android.widget.TextView) {
                                android.widget.TextView label = (android.widget.TextView) line;
                                int[] where = new int[2];
                                label.getLocationOnScreen(where);
                                out.append(" overlay[shown=").append(label.isShown())
                                        .append(" vis=").append(label.getVisibility())
                                        .append(" at=").append(where[0]).append(',').append(where[1])
                                        .append(" size=").append(label.getWidth()).append('x').append(label.getHeight())
                                        .append(" chars=").append(label.getText().length())
                                        .append(" stripGap=").append(staticField(tools, "stripGap")).append(']');
                            } else {
                                out.append(" overlay=none");
                            }
                            Object source = ((java.lang.ref.Reference<?>) staticField(tools, "captionSource")).get();
                            out.append(" waits[clear=").append(staticField(tools, "clear"))
                                    .append(" cue=").append(String.valueOf(staticField(tools, "cue")).length())
                                    .append(" sameVideo=").append(java.util.Objects.equals(staticField(tools, "cueId"), staticField(tools, "currentId")))
                                    .append(" source=").append(source == null ? "none"
                                            : ((android.view.View) source).isAttachedToWindow() ? "attached" : "detached")
                                    .append(" focus=").append(activity.hasWindowFocus()).append(']');
                        } catch (ReflectiveOperationException missing) {
                            out.append(" overlay=unreadable");
                        }
                        // An id this build lacks must not read the same as no caption on screen.
                        if (strip == 0) out.append(' ').append(stripName).append("=missing");
                        if (text == 0) out.append(' ').append(textName).append("=missing");
                        Log.i(TAG, "ok captionstate" + (out.length() == 0 ? " none" : out.toString()));
                        break;
                    }
                    case "overlap": {
                        // Whether two kinds of view cross on screen: -e a <id name> -e b <id name>.
                        // Only views shown in a visible window and inside the screen count, so the
                        // cells above and below the current video and a stopped activity behind
                        // an opened one stay out. Places come from getLocationOnScreen, offsets
                        // included, which is where a hider's lift actually draws them. Rects that
                        // only touch don't cross (#145: the like count on the time).
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        android.content.res.Resources resources = activity.getResources();
                        String aName = intent.getStringExtra("a");
                        String bName = intent.getStringExtra("b");
                        if (aName == null || bName == null) throw new IllegalArgumentException("overlap needs -e a and -e b");
                        int aId = resources.getIdentifier(aName, "id", activity.getPackageName());
                        int bId = resources.getIdentifier(bName, "id", activity.getPackageName());
                        android.util.DisplayMetrics metrics = resources.getDisplayMetrics();
                        android.graphics.Rect screen = new android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
                        java.util.List<android.graphics.Rect> aRects = new java.util.ArrayList<>();
                        java.util.List<android.graphics.Rect> bRects = new java.util.ArrayList<>();
                        java.util.ArrayDeque<android.view.View> pending = new java.util.ArrayDeque<>(windowRoots());
                        while (!pending.isEmpty()) {
                            android.view.View view = pending.removeFirst();
                            int id = view.getId();
                            if (id != 0 && (id == aId || id == bId) && view.isShown()
                                    && view.getWindowVisibility() == android.view.View.VISIBLE
                                    && view.getWidth() > 0 && view.getHeight() > 0) {
                                int[] where = new int[2];
                                view.getLocationOnScreen(where);
                                android.graphics.Rect rect = new android.graphics.Rect(where[0], where[1],
                                        where[0] + view.getWidth(), where[1] + view.getHeight());
                                if (android.graphics.Rect.intersects(rect, screen)) {
                                    if (id == aId) aRects.add(rect);
                                    if (id == bId) bRects.add(rect);
                                }
                            }
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
                            }
                        }
                        StringBuilder out = new StringBuilder();
                        int crossing = 0;
                        for (android.graphics.Rect a : aRects) {
                            for (android.graphics.Rect b : bRects) {
                                if (!android.graphics.Rect.intersects(a, b)) continue;
                                crossing++;
                                out.append(" cross[").append(aName).append(' ').append(a.toShortString())
                                        .append(" over ").append(bName).append(' ').append(b.toShortString())
                                        .append(" by ").append(Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top)).append("px]");
                            }
                        }
                        for (android.graphics.Rect a : aRects) out.append(" a").append(a.toShortString());
                        for (android.graphics.Rect b : bRects) out.append(" b").append(b.toShortString());
                        // An id this build lacks must not read the same as nothing crossing.
                        if (aId == 0) out.append(' ').append(aName).append("=missing");
                        if (bId == 0) out.append(' ').append(bName).append("=missing");
                        Log.i(TAG, "ok overlap a=" + aName + " b=" + bName + " shownA=" + aRects.size()
                                + " shownB=" + bRects.size() + " crossing=" + crossing + out);
                        break;
                    }
                    case "fadestate": {
                        // The feed controls Fade the video controls and Clear display both write
                        // opacity to (#84): for each shown rail column, caption, anchor frame, search
                        // bar, music disc and like button, its own alpha and visibility, every
                        // ancestor's up to the content view, and its children's. Clear display hides
                        // the controls by opacity, so this is what says which view it took to 0.
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        android.content.res.Resources resources = activity.getResources();
                        java.util.Map<Integer, String> wanted = new java.util.HashMap<>();
                        for (String name : new String[]{"llj", "desc", "bqv", "ll8", "videomusiccoverblock", "g85"}) {
                            int id = resources.getIdentifier(name, "id", activity.getPackageName());
                            if (id != 0) wanted.put(id, name);
                        }
                        StringBuilder out = new StringBuilder();
                        try {
                            Class<?> clear = loader.loadClass("app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch");
                            out.append(" clearNow=").append(clear.getMethod("isClearDisplayNow").invoke(null));
                        } catch (ReflectiveOperationException missing) {
                            out.append(" clearNow=unreadable");
                        }
                        android.util.DisplayMetrics screen = resources.getDisplayMetrics();
                        java.util.ArrayDeque<android.view.View> pending = new java.util.ArrayDeque<>(windowRoots());
                        while (!pending.isEmpty()) {
                            android.view.View view = pending.removeFirst();
                            String name = wanted.get(view.getId());
                            int[] where = new int[2];
                            view.getLocationOnScreen(where);
                            if (name != null && view.isAttachedToWindow() && view.getWidth() > 0
                                    && where[0] >= 0 && where[0] < screen.widthPixels
                                    && where[1] >= 0 && where[1] < screen.heightPixels) {
                                out.append("\n").append(name).append(" at=").append(where[0]).append(',').append(where[1])
                                        .append(' ').append(opacity(view));
                                android.view.ViewParent parent = view.getParent();
                                for (int depth = 1; parent instanceof android.view.View; depth++) {
                                    android.view.View up = (android.view.View) parent;
                                    out.append("\n  up").append(depth).append(' ').append(up.getClass().getSimpleName())
                                            .append('/').append(idName(up, resources)).append(' ').append(opacity(up));
                                    if (up.getId() == android.R.id.content) break;
                                    parent = up.getParent();
                                }
                                if (view instanceof android.view.ViewGroup) {
                                    android.view.ViewGroup group = (android.view.ViewGroup) view;
                                    for (int i = 0; i < group.getChildCount(); i++) {
                                        android.view.View child = group.getChildAt(i);
                                        out.append("\n  child ").append(child.getClass().getSimpleName())
                                                .append('/').append(idName(child, resources)).append(' ').append(opacity(child));
                                    }
                                }
                            }
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
                            }
                        }
                        Log.i(TAG, "ok fadestate" + out);
                        break;
                    }
                    case "fadewatch": {
                        // The rail column's children, the caption frame and the music disc sampled on
                        // every frame for -e for ms (8000 by default), one line each time any of them
                        // changes, so the order TikTok's Clear display writes them in, on the way in
                        // and out, is on record (#84). Each entry is alpha/visibility.
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        String forText = intent.getStringExtra("for");
                        long span = forText == null ? 8000L : Long.parseLong(forText);
                        Method clearNow = loader.loadClass("app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch")
                                .getMethod("isClearDisplayNow");
                        android.content.res.Resources resources = activity.getResources();
                        int[] ids = new int[3];
                        String[] names = {"llj", "bqv", "videomusiccoverblock"};
                        for (int i = 0; i < names.length; i++) {
                            ids[i] = resources.getIdentifier(names[i], "id", activity.getPackageName());
                        }
                        long start = android.os.SystemClock.uptimeMillis();
                        String[] last = {null};
                        int[] lines = {0};
                        android.view.Choreographer.FrameCallback sample = new android.view.Choreographer.FrameCallback() {
                            @Override public void doFrame(long frameNanos) {
                                long at = android.os.SystemClock.uptimeMillis() - start;
                                try {
                                    StringBuilder now = new StringBuilder("clear=").append(clearNow.invoke(null));
                                    for (int i = 0; i < ids.length; i++) {
                                        android.view.View view = onScreen(activity.getWindow().getDecorView(), ids[i], resources);
                                        now.append(' ').append(names[i]).append('=');
                                        if (view == null) {
                                            now.append("none");
                                            continue;
                                        }
                                        now.append(shortOpacity(view));
                                        if (i == 0 && view instanceof android.view.ViewGroup) {
                                            android.view.ViewGroup group = (android.view.ViewGroup) view;
                                            now.append(" [");
                                            for (int c = 0; c < group.getChildCount(); c++) {
                                                now.append(c == 0 ? "" : " ").append(shortOpacity(group.getChildAt(c)));
                                            }
                                            now.append(']');
                                        }
                                    }
                                    String state = now.toString();
                                    if (!state.equals(last[0]) && lines[0] < 300) {
                                        last[0] = state;
                                        lines[0]++;
                                        Log.i(TAG, "fadewatch +" + at + "ms " + state);
                                    }
                                } catch (Exception error) {
                                    Log.e(TAG, "failed fadewatch", error);
                                    return;
                                }
                                if (at < span) android.view.Choreographer.getInstance().postFrameCallback(this);
                                else Log.i(TAG, "ok fadewatch lines=" + lines[0]);
                            }
                        };
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() {
                                android.view.Choreographer.getInstance().postFrameCallback(sample);
                            }
                        });
                        break;
                    }
                    case "captionwatch": {
                        // The caption views sampled every 100 ms on the phone for -e for ms (15000
                        // by default) and reported once, so a short cue isn't lost between two adb
                        // round trips. Counts: cleared (the controls hidden), strip (TikTok's own
                        // caption drawn), line (Hushfeed's kept caption shown with text) and cue
                        // (Hushfeed holds a cue for the video on screen). clearedCue is the control:
                        // a line never seen while cleared means nothing without a cue to show.
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        String forText = intent.getStringExtra("for");
                        long span = forText == null ? 15000L : Long.parseLong(forText);
                        Class<?> tools = loader.loadClass("app.morphe.extension.tiktok.captions.CaptionTools");
                        Method clearNow = loader.loadClass("app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch")
                                .getMethod("isClearDisplayNow");
                        android.content.res.Resources resources = activity.getResources();
                        int strip = resources.getIdentifier("dlk", "id", activity.getPackageName());
                        int drawn = resources.getIdentifier("dls", "id", activity.getPackageName());
                        int[] counts = new int[9];
                        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                        long start = android.os.SystemClock.uptimeMillis();
                        main.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    boolean cleared = Boolean.TRUE.equals(clearNow.invoke(null));
                                    boolean stripDrawn = captionDrawn(strip, drawn);
                                    Object held = staticField(tools, "overlay");
                                    Object line = held instanceof java.lang.ref.Reference
                                            ? ((java.lang.ref.Reference<?>) held).get() : null;
                                    boolean lineShown = line instanceof android.widget.TextView
                                            && ((android.view.View) line).isShown() && ((android.view.View) line).getHeight() > 0
                                            && ((android.widget.TextView) line).getText().length() > 0;
                                    boolean cue = String.valueOf(staticField(tools, "cue")).length() > 0
                                            && java.util.Objects.equals(staticField(tools, "cueId"), staticField(tools, "currentId"));
                                    counts[0]++;
                                    if (cleared) counts[1]++;
                                    if (stripDrawn) counts[2]++;
                                    if (lineShown) counts[3]++;
                                    if (cue) counts[4]++;
                                    if (cleared && stripDrawn) counts[5]++;
                                    if (cleared && lineShown) counts[6]++;
                                    if (cleared && cue) counts[7]++;
                                    if (!cleared && lineShown) counts[8]++;
                                } catch (Exception error) {
                                    Log.e(TAG, "failed captionwatch", error);
                                    return;
                                }
                                if (android.os.SystemClock.uptimeMillis() - start < span) {
                                    main.postDelayed(this, 100);
                                    return;
                                }
                                Log.i(TAG, "ok captionwatch samples=" + counts[0] + " cleared=" + counts[1]
                                        + " strip=" + counts[2] + " line=" + counts[3] + " cue=" + counts[4]
                                        + " clearedStrip=" + counts[5] + " clearedLine=" + counts[6]
                                        + " clearedCue=" + counts[7] + " lineWithControls=" + counts[8]
                                        + (strip == 0 || drawn == 0 ? " dlk/dls=missing" : ""));
                            }
                        });
                        break;
                    }
                    case "fields": {
                        // Static fields of one of Hushfeed's own classes, by name, for checking
                        // what a hook recorded: -e class app.morphe.extension.tiktok.speed.
                        // PlaybackSpeedPatch -e names manualSpeed,currentVideoId. Hushfeed's
                        // classes only, and a field named like an id is shown as a short hash, so
                        // two reads can be compared without a video id reaching the log.
                        String className = required(intent, "class");
                        if (!className.startsWith("app.morphe.extension.")) {
                            throw new IllegalArgumentException("Hushfeed classes only: " + className);
                        }
                        Class<?> owner = loader.loadClass(className);
                        StringBuilder out = new StringBuilder();
                        for (String name : required(intent, "names").split(",")) {
                            Field field = owner.getDeclaredField(name.trim());
                            field.setAccessible(true);
                            String shown = String.valueOf(field.get(null));
                            if (name.trim().endsWith("Id") && !shown.isEmpty()) shown = "<id " + (shown.hashCode() & 0xffff) + ">";
                            out.append(' ').append(name.trim()).append('=').append(shown);
                        }
                        Log.i(TAG, "ok fields" + out);
                        break;
                    }
                    case "call": {
                        // A public static method of one of Hushfeed's own classes that takes no
                        // arguments, and what it returned when that is a number or a yes/no:
                        // -e class app.morphe.extension.tiktok.seen.SeenVideoHistory -e method
                        // size. For reading a count a check needs and for undoing what a check
                        // left behind (that class's clear).
                        String className = required(intent, "class");
                        if (!className.startsWith("app.morphe.extension.")) {
                            throw new IllegalArgumentException("Hushfeed classes only: " + className);
                        }
                        Method target = loader.loadClass(className).getMethod(required(intent, "method"));
                        if (!java.lang.reflect.Modifier.isStatic(target.getModifiers())) {
                            throw new IllegalArgumentException("static methods only: " + target);
                        }
                        Object result = target.invoke(null);
                        // A number or a yes/no is shown; anything else only by its type, since an
                        // object's own text can carry ids, names or a caption (an Aweme's does).
                        String shown = target.getReturnType() == void.class ? "void"
                                : result == null ? "null"
                                : result instanceof Number || result instanceof Boolean ? String.valueOf(result)
                                : "<" + result.getClass().getName() + ", value not shown>";
                        Log.i(TAG, "ok call " + target.getName() + " -> " + shown);
                        break;
                    }
                    case "idreset": {
                        // The device-ID reset experiment (ROADMAP "Reset the device identifiers
                        // TikTok registers"), for a throwaway emulator install. Reads the ids TikTok
                        // registered, asks AppLog.resetAndReInstall for a fresh register with
                        // -e timeout <ms> (30000 by default), and reads them again once its callback
                        // answers. -e read 1 only reads. -e where 1 only names the files in TikTok's
                        // data that hold the device or install id. Ids are logged as short hashes,
                        // enough to tell whether one changed. Its own thread: the callback comes on
                        // the main looper, where this receiver runs.
                        final long timeout = Long.parseLong(intent.getStringExtra("timeout") == null
                                ? "30000" : intent.getStringExtra("timeout"));
                        final boolean readOnly = "1".equals(intent.getStringExtra("read"));
                        final boolean where = "1".equals(intent.getStringExtra("where"));
                        final Context appContext = app;
                        final ClassLoader appLoader = loader;
                        new Thread(() -> {
                            if (where) idFiles(appContext, appLoader);
                            else idReset(appContext, appLoader, timeout, readOnly);
                        }, "hushfeed-probe-idreset").start();
                        Log.i(TAG, "ok idreset started");
                        break;
                    }
                    case "countformat": {
                        // One of TikTok's static count formatters run with a count the check
                        // picks, and the text it wrote: -e class X.0EYv -e method LIZIZ -e count
                        // 123456. One that takes (long) gets just the count, one that takes
                        // (Aweme, long) the video on screen as well. A check can read what Show
                        // exact counts makes of a big count whatever the feed serves. The class
                        // is TikTok's obfuscated name, new with every build.
                        Class<?> owner = loader.loadClass(required(intent, "class"));
                        String name = required(intent, "method");
                        Class<?> awemeClass = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme");
                        Method target;
                        Object[] arguments;
                        long count = Long.parseLong(required(intent, "count"));
                        try {
                            target = owner.getDeclaredMethod(name, long.class);
                            arguments = new Object[]{count};
                        } catch (NoSuchMethodException notCountOnly) {
                            target = owner.getDeclaredMethod(name, awemeClass, long.class);
                            Object aweme = loader.loadClass("app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                                    .getMethod("getAweme").invoke(null);
                            if (aweme == null) throw new IllegalStateException("no current video");
                            arguments = new Object[]{aweme, count};
                        }
                        if (!java.lang.reflect.Modifier.isStatic(target.getModifiers()) || target.getReturnType() != String.class) {
                            throw new IllegalArgumentException("a static String count formatter only: " + target);
                        }
                        target.setAccessible(true);
                        Log.i(TAG, "ok countformat " + owner.getSimpleName() + "." + target.getName() + " count=" + count
                                + " -> " + target.invoke(null, arguments));
                        break;
                    }
                    case "tiktoktheme": {
                        // TikTok's own Display choice, which it reads once at start-up and hands
                        // to the night mode: dark_mode in its sp_dark_mode store, 1 when unset.
                        // Without it set to dark, TikTok stays light whatever the phone's night
                        // mode is (seen on the S22, 2026-10-16). Read through TikTok's own store
                        // factory, the static (int, Context, String) -> SharedPreferences method
                        // TikTok reads it with (-e store X.02s6 -e method LIZJ on 47.1.4), since
                        // that one may route the store to Keva rather than a plain file; its
                        // sibling LIZIZ skips that. -e set N writes N, which the next start applies;
                        // -e set unset removes it.
                        String storeClass = intent.getStringExtra("store");
                        if (storeClass == null) storeClass = "X.02s6";
                        String storeMethod = intent.getStringExtra("method");
                        if (storeMethod == null) storeMethod = "LIZJ";
                        Method factory = loader.loadClass(storeClass)
                                .getDeclaredMethod(storeMethod, int.class, Context.class, String.class);
                        if (!Modifier.isStatic(factory.getModifiers())
                                || factory.getReturnType() != android.content.SharedPreferences.class) {
                            throw new IllegalStateException("not a store factory: " + factory);
                        }
                        factory.setAccessible(true);
                        android.content.SharedPreferences store = (android.content.SharedPreferences)
                                factory.invoke(null, 0, app, "sp_dark_mode");
                        boolean stored = store.contains("dark_mode");
                        int was = store.getInt("dark_mode", 1);
                        String set = intent.getStringExtra("set");
                        String wrote = "";
                        if ("unset".equals(set)) {
                            // Back to never chosen, for undoing a check on a phone that had no choice.
                            boolean committed = store.edit().remove("dark_mode").commit();
                            wrote = " set=unset committed=" + committed + " now=" + store.getInt("dark_mode", 1);
                        } else if (set != null) {
                            int value = Integer.parseInt(set);
                            boolean committed = store.edit().putInt("dark_mode", value).commit();
                            wrote = " set=" + value + " committed=" + committed
                                    + " now=" + store.getInt("dark_mode", 1);
                        }
                        Log.i(TAG, "ok tiktoktheme dark_mode=" + was + " stored=" + stored + wrote
                                + " via=" + storeClass + "." + factory.getName()
                                + " night=" + (app.getResources().getConfiguration().uiMode
                                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK));
                        break;
                    }
                    case "fieldswatch": {
                        // The same static fields sampled every 40 ms for a few seconds, logging
                        // each change with its time, so a value a hook writes and another wipes
                        // within a second shows up. Hushfeed classes only, like "fields".
                        String className = required(intent, "class");
                        if (!className.startsWith("app.morphe.extension.")) {
                            throw new IllegalArgumentException("Hushfeed classes only: " + className);
                        }
                        Class<?> owner = loader.loadClass(className);
                        String[] names = required(intent, "names").split(",");
                        String forText = intent.getStringExtra("for");
                        long span = forText == null ? 4000L : Long.parseLong(forText);
                        new Thread(() -> {
                            try {
                                long start = android.os.SystemClock.uptimeMillis();
                                String last = null;
                                StringBuilder out = new StringBuilder();
                                while (android.os.SystemClock.uptimeMillis() - start < span) {
                                    StringBuilder now = new StringBuilder();
                                    for (String name : names) {
                                        Field field = owner.getDeclaredField(name.trim());
                                        field.setAccessible(true);
                                        Object value = field.get(null);
                                        String shown = String.valueOf(value);
                                        if (name.trim().endsWith("Id") && !shown.isEmpty()) shown = "<id " + (shown.hashCode() & 0xffff) + ">";
                                        now.append(' ').append(name.trim()).append('=').append(shown);
                                    }
                                    try {
                                        Object onScreen = loader.loadClass("app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                                                .getMethod("getAweme").invoke(null);
                                        Object aid = onScreen == null ? null : onScreen.getClass().getMethod("getAid").invoke(onScreen);
                                        now.append(" onScreen=").append(aid == null ? "none" : "<id " + (String.valueOf(aid).hashCode() & 0xffff) + ">");
                                    } catch (ReflectiveOperationException unreadable) {
                                        now.append(" onScreen=?");
                                    }
                                    String state = now.toString();
                                    if (!state.equals(last)) {
                                        out.append("\n+").append(android.os.SystemClock.uptimeMillis() - start).append("ms").append(state);
                                        last = state;
                                    }
                                    android.os.SystemClock.sleep(40);
                                }
                                Log.i(TAG, "ok fieldswatch" + out);
                            } catch (ReflectiveOperationException error) {
                                Log.e(TAG, "failed fieldswatch", error);
                            }
                        }, "hushfeed-probe-fieldswatch").start();
                        break;
                    }
                    case "session-hold": {
                        SessionHoldProbe.run(loader, intent.getStringExtra("op"), intent.getIntExtra("seconds", 15));
                        break;
                    }
                    case "budget-focus": {
                        BudgetFocusProbe.run(app, loader, intent.getStringExtra("op"), intent.getStringExtra("mode"));
                        break;
                    }
                    case "edge-drag": {
                        EdgeDragProbe.run(app, loader, intent);
                        break;
                    }
                    case "native-media": {
                        NativeMediaProbe.run(app, loader, intent);
                        break;
                    }
                    case "playerspeed": {
                        // The feed player's own speed and how far its position moves over a gap,
                        // read through the player TikTok's static getter hands out. The holder
                        // class and getter are renamed on every build, so they come in as extras
                        // (47.0.3: -e holder X.037m -e getter LJLJJLL); getSpeed and
                        // getCurrentPosition keep their names. Nothing about the video leaves.
                        Method getter = loader.loadClass(required(intent, "holder"))
                                .getDeclaredMethod(required(intent, "getter"));
                        getter.setAccessible(true);
                        Object player = getter.invoke(null);
                        if (player == null) throw new IllegalStateException("no player");
                        Method speed = player.getClass().getMethod("getSpeed");
                        Method position = player.getClass().getMethod("getCurrentPosition");
                        String gapText = intent.getStringExtra("gap");
                        long gap = gapText == null ? 1000L : Long.parseLong(gapText);
                        long startedAt = android.os.SystemClock.uptimeMillis();
                        long startPosition = (Long) position.invoke(player);
                        float startSpeed = (Float) speed.invoke(player);
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                long elapsed = android.os.SystemClock.uptimeMillis() - startedAt;
                                long moved = (Long) position.invoke(player) - startPosition;
                                Log.i(TAG, "ok playerspeed speed=" + startSpeed + " then " + speed.invoke(player)
                                        + " moved=" + moved + "ms in " + elapsed + "ms rate="
                                        + String.format(Locale.ROOT, "%.2f", moved / (double) elapsed)
                                        + " from=" + startPosition + "ms");
                            } catch (ReflectiveOperationException error) {
                                Log.e(TAG, "failed playerspeed", error);
                            }
                        }, gap);
                        break;
                    }
                    case "startwatch": {
                        // How each video starts, for a stall after it begins (#150): samples the
                        // feed player's position on the main thread every 50 ms for a while and
                        // logs, per video, when it became current, when its position first moved,
                        // every hold of 250 ms or more after that, every jump the position made
                        // that playing doesn't explain, and every sample the main thread was late
                        // for. Same holder and getter extras as playerspeed (47.1.4: -e holder
                        // X.037t -e getter LJIILIIL). No id, caption or creator leaves.
                        Method getter = loader.loadClass(required(intent, "holder"))
                                .getDeclaredMethod(required(intent, "getter"));
                        getter.setAccessible(true);
                        Method current = loader.loadClass("app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                                .getMethod("getAweme");
                        String secondsText = intent.getStringExtra("seconds");
                        long until = android.os.SystemClock.uptimeMillis()
                                + 1000L * (secondsText == null ? 25L : Long.parseLong(secondsText));
                        long began = android.os.SystemClock.uptimeMillis();
                        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                        StringBuilder out = new StringBuilder();
                        long[] state = {0L, -1L, -1L, -1L, -1L, 0L, 0L};
                        // state: video identity, selected at, first move at, last position,
                        // position unchanged since, last sample at, videos seen
                        StringBuilder[] line = {null};
                        Runnable sample = new Runnable() {
                            @Override public void run() {
                                long now = android.os.SystemClock.uptimeMillis();
                                try {
                                    if (state[5] > 0 && now - state[5] > 150 && line[0] != null) {
                                        line[0].append(" late=").append(now - state[5]).append("ms@").append(now - began);
                                    }
                                    state[5] = now;
                                    Object aweme = current.invoke(null);
                                    long identity = aweme == null ? 0L : System.identityHashCode(aweme);
                                    if (identity != state[0]) {
                                        if (line[0] != null) out.append(line[0]).append('\n');
                                        state[6]++;
                                        line[0] = new StringBuilder("video ").append(state[6])
                                                .append(" current@").append(now - began);
                                        state[0] = identity;
                                        state[1] = now;
                                        state[2] = -1L;
                                        state[3] = -1L;
                                        state[4] = now;
                                    }
                                    Object player = getter.invoke(null);
                                    if (player != null) {
                                        Method position = player.getClass().getMethod("getCurrentPosition");
                                        position.setAccessible(true);
                                        long at = (Long) position.invoke(player);
                                        if (state[3] >= 0 && at < state[3] && at < 500) {
                                            // The next video starting over, or this one looping.
                                            line[0].append(" restart@").append(now - began);
                                            state[4] = now;
                                        } else if (state[3] >= 0 && at != state[3]) {
                                            if (state[2] < 0) {
                                                state[2] = now;
                                                line[0].append(" moved@").append(now - began)
                                                        .append(" (+").append(now - state[1]).append(')');
                                            } else if (now - state[4] >= 250) {
                                                line[0].append(" held=").append(now - state[4]).append("ms@")
                                                        .append(state[4] - began).append(" at ").append(state[3]);
                                            }
                                            long expected = now - state[4] + 150;
                                            if (at < state[3] || at - state[3] > expected + 500) {
                                                line[0].append(" jump ").append(state[3]).append("->").append(at)
                                                        .append('@').append(now - began);
                                            }
                                            state[4] = now;
                                        }
                                        state[3] = at;
                                    }
                                } catch (Throwable error) {
                                    if (line[0] != null) line[0].append(" err=").append(error.getClass().getSimpleName());
                                }
                                if (now < until) {
                                    main.postDelayed(this, 50);
                                } else {
                                    if (line[0] != null) out.append(line[0]);
                                    Log.i(TAG, "ok startwatch done\n" + out);
                                }
                            }
                        };
                        main.post(sample);
                        Log.i(TAG, "ok startwatch until +" + (until - began) + "ms");
                        break;
                    }
                    case "holdslide": {
                        // A press held still long enough for TikTok's hold gesture, then a slide
                        // and a release. Injected through the input system like a finger (an app
                        // may inject into its own windows), from a thread of its own because
                        // each injection waits for the main thread to take it. Separate "input
                        // motionevent" calls from adb arrive a process launch apart, and the
                        // feed took that slow drag as a scroll.
                        float x = Float.parseFloat(required(intent, "x"));
                        float y = Float.parseFloat(required(intent, "y"));
                        float dy = Float.parseFloat(required(intent, "dy"));
                        String holdText = intent.getStringExtra("hold");
                        long hold = holdText == null ? 1500L : Long.parseLong(holdText);
                        String slideText = intent.getStringExtra("slide");
                        long slide = slideText == null ? 600L : Long.parseLong(slideText);
                        new Thread(() -> {
                            try {
                                Instrumentation input = new Instrumentation();
                                long down = android.os.SystemClock.uptimeMillis();
                                input.sendPointerSync(android.view.MotionEvent.obtain(
                                        down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0));
                                android.os.SystemClock.sleep(hold);
                                int steps = 24;
                                for (int step = 1; step <= steps; step++) {
                                    input.sendPointerSync(android.view.MotionEvent.obtain(down,
                                            android.os.SystemClock.uptimeMillis(),
                                            android.view.MotionEvent.ACTION_MOVE, x, y + dy * step / steps, 0));
                                    android.os.SystemClock.sleep(slide / steps);
                                }
                                android.os.SystemClock.sleep(300);
                                input.sendPointerSync(android.view.MotionEvent.obtain(down,
                                        android.os.SystemClock.uptimeMillis(),
                                        android.view.MotionEvent.ACTION_UP, x, y + dy, 0));
                                Log.i(TAG, "ok holdslide at " + x + "," + y + " by " + dy + " after "
                                        + hold + "ms, sliding " + slide + "ms");
                            } catch (RuntimeException error) {
                                Log.e(TAG, "failed holdslide", error);
                            }
                        }, "hushfeed-probe-holdslide").start();
                        break;
                    }
                    case "tabbadges": {
                        // TikTok's bottom tab icons draw their unread badges through their own
                        // setters (setCountDotText, setCountDotVisibility, setTabDotVisibility),
                        // and the hide switch answers inside those setters. Nothing makes the
                        // server send the test account an unread item on demand, so this calls
                        // the setters the way TikTok does, on every tab icon on screen, and reads
                        // the badge views back half a second later: with the switch off the count
                        // and the dot are visible (0), with it on they stay GONE (8).
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        List<android.view.View> icons = new ArrayList<>();
                        collectTabIcons(activity.getWindow().getDecorView(), icons, new java.util.HashMap<>());
                        if (icons.isEmpty()) throw new IllegalStateException("no tab icon with the badge setters on screen");
                        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                        main.post(() -> {
                            try {
                                for (android.view.View icon : icons) {
                                    Class<?> type = icon.getClass();
                                    type.getMethod("setCountDotText", String.class).invoke(icon, "7");
                                    type.getMethod("setCountDotVisibility", int.class).invoke(icon, android.view.View.VISIBLE);
                                    type.getMethod("setTabDotVisibility", int.class).invoke(icon, android.view.View.VISIBLE);
                                }
                            } catch (Throwable error) {
                                Log.e(TAG, "failed tabbadges", error);
                                return;
                            }
                            main.postDelayed(() -> {
                                StringBuilder out = new StringBuilder();
                                for (android.view.View icon : icons) {
                                    out.append(" [").append(badgeState(icon, "getCountDotView"))
                                            .append(' ').append(badgeState(icon, "getRedDotVIew")).append(']');
                                }
                                Log.i(TAG, "ok tabbadges " + icons.size() + " icons:" + out);
                            }, 500);
                        });
                        break;
                    }
                    case "block-flow-test": {
                        BlockFlowProbe.run(loader, intent.getStringExtra("value"));
                        break;
                    }
                    case "like-targets": {
                        for (android.view.View root : windowRoots()) readLikeTargets(root);
                        Log.i(TAG, "ok like-targets");
                        break;
                    }
                    case "padtop": {
                        // Gives the first shown view with -e id <name> a top padding of -e value
                        // pixels, to stand in for the strip TikTok keeps over a LIVE room on phones
                        // that get it (#137). Prints the padding it had.
                        String idName = required(intent, "id");
                        int value = Integer.parseInt(required(intent, "value"));
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        int id = activity.getResources().getIdentifier(idName, "id", activity.getPackageName());
                        android.view.View found = null;
                        java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>(windowRoots());
                        while (!queue.isEmpty() && found == null) {
                            android.view.View view = queue.removeFirst();
                            if (id != 0 && view.getId() == id && view.isShown()) found = view;
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
                            }
                        }
                        if (found == null) {
                            Log.i(TAG, "ok padtop none for " + idName);
                            break;
                        }
                        int before = found.getPaddingTop();
                        found.setPadding(found.getPaddingLeft(), value, found.getPaddingRight(), found.getPaddingBottom());
                        Log.i(TAG, "ok padtop " + idName + " " + before + " -> " + value);
                        break;
                    }
                    case "topviews": {
                        // Wide views across the top of the window, for the LIVE room's shade under
                        // a see-through bar (#137): each view at least 80% of the window wide that
                        // starts above -e below (default 400 px), with its place, padding, offset,
                        // alpha and background. Technical fields only, no text.
                        String belowText = intent.getStringExtra("below");
                        int below = belowText == null ? 400 : Integer.parseInt(belowText);
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        StringBuilder out = new StringBuilder();
                        for (android.view.View root : windowRoots()) {
                            out.append("root ").append(root.getClass().getSimpleName()).append('\n');
                            topViews(root, 0, below, root.getWidth(), out, activity.getResources());
                        }
                        String text = out.toString();
                        int pieces = 0;
                        for (int at = 0; at < text.length(); at += 3000, pieces++) {
                            Log.i(TAG, "topviews[" + pieces + "] "
                                    + text.substring(at, Math.min(text.length(), at + 3000)));
                        }
                        Log.i(TAG, "ok topviews " + text.length() + " chars in " + pieces + " pieces");
                        break;
                    }
                    case "liveroom": {
                        // What the LIVE status bar code holds for each room in front (#137): the
                        // view it lifted and how, its searches and relifts, the pages and layers it
                        // changed. Technical fields only, read by reflection.
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        android.content.res.Resources resources = activity == null ? null : activity.getResources();
                        Class<?> owner = loader.loadClass("app.morphe.extension.tiktok.feed.LiveStatusBar");
                        Field hiddenField = owner.getDeclaredField("HIDDEN");
                        hiddenField.setAccessible(true);
                        Map<?, ?> hidden = (Map<?, ?>) hiddenField.get(null);
                        StringBuilder out = new StringBuilder();
                        for (Map.Entry<?, ?> entry : new java.util.HashMap<>(hidden).entrySet()) {
                            Object room = entry.getValue();
                            out.append("room of ").append(entry.getKey().getClass().getSimpleName());
                            for (Field field : room.getClass().getDeclaredFields()) {
                                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                                field.setAccessible(true);
                                Object value = field.get(room);
                                out.append("\n  ").append(field.getName()).append('=');
                                if (value instanceof java.lang.ref.Reference) value = ((java.lang.ref.Reference<?>) value).get();
                                if (value instanceof android.view.View) {
                                    out.append(liveRoomView((android.view.View) value, resources));
                                } else if (value instanceof Map) {
                                    out.append(((Map<?, ?>) value).size()).append(" [");
                                    for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                                        Object key = item.getKey();
                                        out.append(key instanceof android.view.View ? liveRoomView((android.view.View) key, resources) : String.valueOf(key))
                                                .append("->").append(item.getValue()).append("; ");
                                    }
                                    out.append(']');
                                } else if (value != null && value.getClass().getName().endsWith("$Offset")) {
                                    Field viewField = value.getClass().getDeclaredField("view");
                                    Field paddingField = value.getClass().getDeclaredField("padding");
                                    Field valueField = value.getClass().getDeclaredField("value");
                                    viewField.setAccessible(true);
                                    paddingField.setAccessible(true);
                                    valueField.setAccessible(true);
                                    Object view = ((java.lang.ref.Reference<?>) viewField.get(value)).get();
                                    out.append("Offset(").append(paddingField.get(value) == Boolean.TRUE ? "padding " : "margin ")
                                            .append(valueField.get(value)).append(" on ")
                                            .append(view instanceof android.view.View ? liveRoomView((android.view.View) view, resources) : "gone")
                                            .append(')');
                                } else if (value instanceof Number || value instanceof Boolean || value == null) {
                                    out.append(value);
                                } else {
                                    out.append('<').append(value.getClass().getSimpleName()).append('>');
                                }
                            }
                            // What findShade makes of each layer in front now, and every gradient
                            // under it with the values the search goes by.
                            Field loweredField = room.getClass().getDeclaredField("lowered");
                            loweredField.setAccessible(true);
                            java.lang.reflect.Method findShade = owner.getDeclaredMethod("findShade", android.view.ViewGroup.class);
                            findShade.setAccessible(true);
                            for (Object key : new java.util.ArrayList<>(((Map<?, ?>) loweredField.get(room)).keySet())) {
                                if (!(key instanceof android.view.ViewGroup) || !((android.view.View) key).isAttachedToWindow()) continue;
                                android.view.ViewGroup layer = (android.view.ViewGroup) key;
                                Object found = findShade.invoke(null, layer);
                                out.append("\n  findShade(").append(liveRoomView(layer, resources)).append(")=")
                                        .append(found instanceof android.view.View ? liveRoomView((android.view.View) found, resources) : "null");
                                java.util.ArrayDeque<android.view.View> walk = new java.util.ArrayDeque<>();
                                walk.add(layer);
                                while (!walk.isEmpty()) {
                                    android.view.View at = walk.poll();
                                    if (at != layer && at.getBackground() instanceof android.graphics.drawable.GradientDrawable) {
                                        int top = 0, depth = 0;
                                        for (android.view.View up = at; up != layer && up.getParent() instanceof android.view.View; up = (android.view.View) up.getParent()) {
                                            top += up.getTop();
                                            depth++;
                                        }
                                        out.append("\n    gradient ").append(liveRoomView(at, resources)).append(" depth=").append(depth)
                                                .append(" vis=").append(at.getVisibility()).append(" topIn=").append(top)
                                                .append(" w=").append(at.getWidth()).append(" h=").append(at.getHeight())
                                                .append(" layerPad=").append(layer.getPaddingTop()).append(" layerH=").append(layer.getHeight());
                                    }
                                    if (at instanceof android.view.ViewGroup) {
                                        android.view.ViewGroup group = (android.view.ViewGroup) at;
                                        for (int i = 0; i < group.getChildCount(); i++) walk.add(group.getChildAt(i));
                                    }
                                }
                            }
                            out.append('\n');
                        }
                        String text = out.toString();
                        int pieces = 0;
                        for (int at = 0; at < text.length(); at += 3000, pieces++) {
                            Log.i(TAG, "liveroom[" + pieces + "] " + text.substring(at, Math.min(text.length(), at + 3000)));
                        }
                        Log.i(TAG, "ok liveroom " + hidden.size() + " rooms");
                        break;
                    }
                    case "livechip": {
                        // Each check the Record button's watcher makes on the LIVE room page it
                        // holds (#37), so a button that never stays up shows which one says no.
                        Class<?> owner = loader.loadClass("app.morphe.extension.tiktok.live.LiveRecordChip");
                        StringBuilder out = new StringBuilder();
                        Object fragment = null;
                        android.app.Activity activity = null;
                        for (String name : new String[]{"page", "host"}) {
                            Field field = owner.getDeclaredField(name);
                            field.setAccessible(true);
                            Object held = ((java.lang.ref.Reference<?>) field.get(null)).get();
                            if ("page".equals(name)) fragment = held; else activity = (android.app.Activity) held;
                            out.append(' ').append(name).append('=').append(held == null ? "null" : held.getClass().getName());
                        }
                        if (fragment != null) {
                            for (String name : new String[]{"isResumed", "getUserVisibleHint", "isRemoving", "isDetached",
                                    "isHidden", "isVisible", "isAdded", "getLifecycle"}) {
                                Object value;
                                try {
                                    value = fragment.getClass().getMethod(name).invoke(fragment);
                                    if (name.equals("getLifecycle") && value != null) {
                                        value = value.getClass().getMethod("getCurrentState").invoke(value);
                                    }
                                } catch (NoSuchMethodException missing) {
                                    value = "no such method";
                                }
                                out.append(' ').append(name).append('=').append(value);
                            }
                        }
                        if (activity != null) {
                            android.view.View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                            out.append(" finishing=").append(activity.isFinishing())
                                    .append(" destroyed=").append(activity.isDestroyed())
                                    .append(" decorShown=").append(decor != null && decor.isShown());
                            // Every room page the activity's fragment managers hold, with its
                            // lifecycle and how much of its view is on screen: whether the pager
                            // resumes the rooms beside the one in front.
                            // TikTok renames FragmentManager.getFragments, so the list is found by
                            // shape: a no-argument method of the manager that returns fragments.
                            Class<?> fragmentType = loader.loadClass("androidx.fragment.app.Fragment");
                            Class<?> managerType = loader.loadClass("androidx.fragment.app.FragmentManager");
                            java.util.ArrayDeque<Object> managers = new java.util.ArrayDeque<>();
                            managers.add(activity.getClass().getMethod("getSupportFragmentManager").invoke(activity));
                            int seen = 0;
                            while (!managers.isEmpty() && seen < 200) {
                                Object manager = managers.poll();
                                java.util.List<?> children = java.util.Collections.emptyList();
                                for (Method method : managerType.getDeclaredMethods()) {
                                    if (method.getParameterCount() != 0 || !java.util.List.class.isAssignableFrom(method.getReturnType())
                                            || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                                    method.setAccessible(true);
                                    Object list = method.invoke(manager);
                                    if (!(list instanceof java.util.List) || ((java.util.List<?>) list).isEmpty()) continue;
                                    boolean fragments = true;
                                    for (Object item : (java.util.List<?>) list) fragments &= fragmentType.isInstance(item);
                                    if (fragments) {
                                        children = (java.util.List<?>) list;
                                        break;
                                    }
                                }
                                for (Object child : children) {
                                    seen++;
                                    if (child == null) continue;
                                    if (Boolean.TRUE.equals(child.getClass().getMethod("isAdded").invoke(child))) {
                                        // The child manager is the one that isn't the manager
                                        // holding this fragment, whatever the method is called.
                                        for (Method method : fragmentType.getDeclaredMethods()) {
                                            if (method.getParameterCount() != 0 || !managerType.isAssignableFrom(method.getReturnType())
                                                    || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                                            method.setAccessible(true);
                                            Object other;
                                            try {
                                                other = method.invoke(child);
                                            } catch (java.lang.reflect.InvocationTargetException refused) {
                                                continue;
                                            }
                                            if (other != null && other != manager && !managers.contains(other)) {
                                                managers.add(other);
                                                break;
                                            }
                                        }
                                    }
                                    if (!child.getClass().getName().endsWith("LivePlayFragment")) continue;
                                    Object lifecycle = child.getClass().getMethod("getLifecycle").invoke(child);
                                    android.view.View view = (android.view.View) child.getClass().getMethod("getView").invoke(child);
                                    android.graphics.Rect rect = new android.graphics.Rect();
                                    boolean onScreen = view != null && view.getGlobalVisibleRect(rect);
                                    out.append("\n  room page ").append(Integer.toHexString(System.identityHashCode(child)))
                                            .append(child == fragment ? " (held)" : "")
                                            .append(" state=").append(lifecycle.getClass().getMethod("getCurrentState").invoke(lifecycle))
                                            .append(" hint=").append(child.getClass().getMethod("getUserVisibleHint").invoke(child))
                                            .append(" shown=").append(view != null && view.isShown())
                                            .append(" visibleRect=").append(onScreen ? rect.toShortString() : "none");
                                }
                            }
                        }
                        Log.i(TAG, "ok livechip" + out);
                        break;
                    }
                    case "livestream": {
                        // What the Record button would record in the room it holds (#37): each
                        // quality TikTok offers with the video codec it names, the pick, and the
                        // first video tag of the picked FLV stream, which is what the recorder
                        // checks. Addresses carry tokens, so only the host is printed.
                        Class<?> owner = loader.loadClass("app.morphe.extension.tiktok.live.LiveRecordChip");
                        Field held = owner.getDeclaredField("page");
                        held.setAccessible(true);
                        Object fragment = ((java.lang.ref.Reference<?>) held.get(null)).get();
                        if (fragment == null) throw new IllegalStateException("no room page held");
                        Method roomOf = owner.getDeclaredMethod("roomOf", Object.class);
                        roomOf.setAccessible(true);
                        Object room = roomOf.invoke(null, fragment);
                        if (room == null) throw new IllegalStateException("no room on the page");
                        StringBuilder out = new StringBuilder();
                        Object streamUrl = fieldValue(room, "streamUrl");
                        Object core = streamUrl == null ? null : fieldValue(streamUrl, "liveCoreSDKData");
                        Object pull = core == null ? null : fieldValue(core, "pullData");
                        Object streamData = pull == null ? null : fieldValue(pull, "streamData");
                        if (streamData instanceof String) {
                            org.json.JSONObject data = new org.json.JSONObject((String) streamData).optJSONObject("data");
                            java.util.Iterator<String> keys = data == null ? java.util.Collections.<String>emptyIterator() : data.keys();
                            while (keys.hasNext()) {
                                String quality = keys.next();
                                org.json.JSONObject entry = data.optJSONObject(quality);
                                org.json.JSONObject main = entry == null ? null : entry.optJSONObject("main");
                                if (main == null) continue;
                                String params = main.optString("sdk_params", "");
                                org.json.JSONObject sdk = params.startsWith("{") ? new org.json.JSONObject(params) : null;
                                out.append("\n  ").append(quality)
                                        .append(" vcodec=").append(sdk == null ? "?" : sdk.optString("VCodec", "?"))
                                        .append(" res=").append(sdk == null ? "?" : sdk.optString("resolution", "?"))
                                        .append(" flv=").append(main.optString("flv", "").isEmpty() ? "none"
                                                : android.net.Uri.parse(main.optString("flv")).getLastPathSegment())
                                        .append(" hls=").append(!main.optString("hls", "").isEmpty())
                                        .append(" keys=").append(main.names());
                            }
                        } else {
                            out.append(" streamData=").append(streamData == null ? "null" : streamData.getClass().getName());
                        }
                        Object flvMap = streamUrl == null ? null : fieldValue(streamUrl, "flvPullUrl");
                        if (flvMap instanceof java.util.Map) {
                            for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) flvMap).entrySet()) {
                                if (!(entry.getValue() instanceof String)) continue;
                                String address = (String) entry.getValue();
                                String label = "flvPullUrl " + entry.getKey() + " file=" + android.net.Uri.parse(address).getLastPathSegment();
                                out.append("\n  ").append(label);
                                new Thread(() -> Log.i(TAG, "livestream map " + label + " " + streamHead(address)), "probe-livestream").start();
                            }
                        }
                        Object preferred = null;
                        try {
                            Method last = loader.loadClass("app.morphe.extension.tiktok.live.LiveControls").getDeclaredMethod("lastQualityPick");
                            last.setAccessible(true);
                            preferred = last.invoke(null);
                        } catch (NoSuchMethodException missing) {
                            preferred = "no such method";
                        }
                        Object pick = loader.loadClass("app.morphe.extension.tiktok.live.LiveRoomStream")
                                .getMethod("fromRoom", Object.class, String.class)
                                .invoke(null, room, preferred instanceof String && !"no such method".equals(preferred) ? preferred : null);
                        Object target = pick.getClass().getField("target").get(pick);
                        out.append("\n  preferred=").append(preferred).append(" problem=").append(pick.getClass().getField("problem").get(pick));
                        if (target != null) {
                            String address = (String) target.getClass().getField("url").get(target);
                            out.append(" transport=").append(target.getClass().getField("transport").get(target))
                                    .append(" quality=").append(target.getClass().getField("quality").get(target))
                                    .append(" host=").append(android.net.Uri.parse(address).getHost())
                                    .append(" file=").append(android.net.Uri.parse(address).getLastPathSegment());
                            new Thread(() -> Log.i(TAG, "livestream head " + streamHead(address)), "probe-livestream").start();
                        }
                        Log.i(TAG, "ok livestream" + out);
                        break;
                    }
                    case "click": {
                        // Clicks one shown view by its id name (-e find bottom_tab_home), or by a
                        // content description regex (-e desc "^Profile$"), through performClick on
                        // its nearest clickable ancestor, so a scripted check moves between TikTok's
                        // tabs and sheets without a coordinate guess. More than one match is
                        // refused unless -e index picks one, and so is a match off screen.
                        String find = intent.getStringExtra("find");
                        String desc = intent.getStringExtra("desc");
                        if (find == null && desc == null) throw new IllegalArgumentException("-e find or -e desc is required");
                        java.util.regex.Pattern descPattern = desc == null ? null : java.util.regex.Pattern.compile(desc);
                        android.content.res.Resources resources = app.getResources();
                        List<android.view.View> matches = new ArrayList<>();
                        java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>(windowRoots());
                        while (!queue.isEmpty()) {
                            android.view.View view = queue.removeFirst();
                            if (!view.isShown()) continue;
                            boolean match = find != null ? find.equals(idName(view, resources))
                                    : view.getContentDescription() != null
                                            && descPattern.matcher(view.getContentDescription()).find();
                            android.graphics.Rect onScreen = new android.graphics.Rect();
                            if (match && view.getGlobalVisibleRect(onScreen) && !onScreen.isEmpty()) matches.add(view);
                            if (view instanceof android.view.ViewGroup) {
                                android.view.ViewGroup group = (android.view.ViewGroup) view;
                                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
                            }
                        }
                        String indexText = intent.getStringExtra("index");
                        if (matches.isEmpty()) throw new IllegalStateException("no shown view matches");
                        if (indexText == null && matches.size() > 1) {
                            throw new IllegalStateException(matches.size() + " shown views match; pass -e index");
                        }
                        android.view.View target = matches.get(indexText == null ? 0 : Integer.parseInt(indexText));
                        android.view.View clickable = target;
                        while (clickable != null && !clickable.isClickable()) {
                            clickable = clickable.getParent() instanceof android.view.View
                                    ? (android.view.View) clickable.getParent() : null;
                        }
                        if (clickable == null) throw new IllegalStateException("nothing clickable holds the match");
                        int[] where = new int[2];
                        clickable.getLocationOnScreen(where);
                        boolean handled = clickable.performClick();
                        Log.i(TAG, "ok click " + idName(clickable, resources) + " at " + where[0] + ',' + where[1]
                                + " size " + clickable.getWidth() + 'x' + clickable.getHeight() + " handled=" + handled);
                        break;
                    }
                    case "windowviews": {
                        // Dialogs and bottom sheets can live in a separate WindowManager root.
                        // TikTok 47.0.3's comment sheet leaves MainActivity's own content view
                        // empty while its dialog window is visible, so the ordinary views action
                        // cannot observe or verify its resource anchors. This emits the same
                        // technical-only fields for every root, without text or descriptions.
                        android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                                .getMethod("getActivity").invoke(null);
                        if (activity == null) throw new IllegalStateException("no current activity");
                        String find = intent.getStringExtra("find");
                        java.util.List<android.view.View> roots = windowRoots();
                        StringBuilder out = new StringBuilder();
                        for (int i = 0; i < roots.size(); i++) {
                            android.view.View root = roots.get(i);
                            out.append("root ").append(i).append(' ')
                                    .append(root.getClass().getSimpleName()).append('\n');
                            walkViews(root, 0, find, out, activity.getResources());
                        }
                        String text = out.toString();
                        int pieces = 0;
                        for (int at = 0; at < text.length(); at += 3000, pieces++) {
                            Log.i(TAG, "windowviews[" + pieces + "] "
                                    + text.substring(at, Math.min(text.length(), at + 3000)));
                        }
                        Log.i(TAG, "ok windowviews " + roots.size() + " roots "
                                + text.length() + " chars in " + pieces + " pieces");
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("unknown action: " + action);
                }
            } catch (Throwable error) {
                Log.e(TAG, "failed " + action, error);
            }
        }

        /** Read-only 47.0.3 acceptance data. No comment text, ids or account data is emitted. */
        private void readLikeTargets(android.view.View view) throws Exception {
            if (view.isShown() && "ncu".equals(idName(view, view.getResources()))) {
                int[] position = new int[2];
                view.getLocationOnScreen(position);
                StringBuilder out = new StringBuilder("like-target at=").append(position[0])
                        .append(',').append(position[1]).append(" size=").append(view.getWidth())
                        .append('x').append(view.getHeight());
                android.view.ViewParent parent = view.getParent();
                boolean modelFound = false;
                while (parent instanceof android.view.View) {
                    android.view.View ancestor = (android.view.View) parent;
                    if (!modelFound) {
                        for (Field field : ancestor.getClass().getDeclaredFields()) {
                            if (!field.getType().getName().equals(
                                    "com.ss.android.ugc.aweme.comment.model.Comment")) continue;
                            field.setAccessible(true);
                            Object comment = field.get(ancestor);
                            if (comment != null) {
                                out.append(" liked=").append(comment.getClass()
                                        .getMethod("isUserDigged").invoke(comment));
                                modelFound = true;
                            }
                        }
                    }
                    android.view.TouchDelegate delegate = ancestor.getTouchDelegate();
                    if (delegate != null) {
                        ancestor.getLocationOnScreen(position);
                        out.append(" delegate=").append(delegate.getClass().getName())
                                .append(" hostAt=").append(position[0]).append(',').append(position[1]);
                        if (Build.VERSION.SDK_INT >= 29) {
                            android.view.accessibility.AccessibilityNodeInfo.TouchDelegateInfo info =
                                    delegate.getTouchDelegateInfo();
                            for (int i = 0; i < info.getRegionCount(); i++) {
                                out.append(" localArea=").append(info.getRegionAt(i));
                            }
                        }
                    }
                    parent = parent.getParent();
                }
                Log.i(TAG, out.toString());
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) readLikeTargets(group.getChildAt(i));
            }
        }

        /** One press and release at a point, delivered the way the window would get it. */
        private static void dispatch(android.view.View decor, long down, long when, int action, float x, float y) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(down, when, action, x, y, 0);
            try {
                decor.dispatchTouchEvent(event);
            } finally {
                event.recycle();
            }
        }

        private static void tap(android.view.View decor, float x, float y) {
            long down = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent press = android.view.MotionEvent.obtain(
                    down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
            android.view.MotionEvent release = android.view.MotionEvent.obtain(
                    down, down + 30, android.view.MotionEvent.ACTION_UP, x, y, 0);
            try {
                decor.dispatchTouchEvent(press);
                decor.dispatchTouchEvent(release);
            } finally {
                press.recycle();
                release.recycle();
            }
        }

        private static void touch(android.view.View decor, long down, long at, int action, float x, float y) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(down, at, action, x, y, 0);
            try {
                decor.dispatchTouchEvent(event);
            } finally {
                event.recycle();
            }
        }

        private String required(Intent intent, String name) {
            String value = intent.getStringExtra(name);
            if (value == null) throw new IllegalArgumentException("-e " + name + " is required");
            return value;
        }

        /** One line for each shown view under x,y, from this view down: its class, id, bounds and background. */
        private static void describeBackgroundsAt(android.view.View view, int x, int y, int depth,
                StringBuilder out, android.content.res.Resources resources) {
            if (!view.isShown()) return;
            int[] where = new int[2];
            view.getLocationOnScreen(where);
            if (x < where[0] || y < where[1] || x >= where[0] + view.getWidth() || y >= where[1] + view.getHeight()) return;
            android.graphics.drawable.Drawable background = view.getBackground();
            out.append(depth).append(' ').append(view.getClass().getName())
                    .append(" id=").append(idName(view, resources))
                    .append(" at=").append(where[0]).append(',').append(where[1])
                    .append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
                    .append(" bg=").append(describeDrawable(background, 0));
            if (view.getBackgroundTintList() != null) {
                out.append(" tint=").append(Integer.toHexString(view.getBackgroundTintList().getDefaultColor()));
            }
            if (view instanceof android.widget.ImageView) {
                android.widget.ImageView image = (android.widget.ImageView) view;
                android.graphics.drawable.Drawable source = image.getDrawable();
                out.append(" src=").append(describeDrawable(source, 0));
                if (source != null) {
                    out.append(' ').append(source.getIntrinsicWidth()).append('x').append(source.getIntrinsicHeight())
                            .append(' ').append(image.getScaleType());
                }
            }
            if (view.getAlpha() < 1f) out.append(" alpha=").append(view.getAlpha());
            // What this view and everything under it paints at the point, drawn again into a
            // single pixel: the deepest view that gives the colour while its children don't is
            // the one painting it, background or onDraw. A hardware bitmap can't be drawn this
            // way and says so.
            out.append(" draws=").append(drawnAt(view, x - where[0], y - where[1]));
            out.append('\n');
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    describeBackgroundsAt(group.getChildAt(i), x, y, depth + 1, out, resources);
                }
            }
        }

        /** The colour a view draws at a point in its own coordinates, or why it couldn't be drawn. */
        private static String drawnAt(android.view.View view, int x, int y) {
            try {
                android.graphics.Bitmap pixel = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(pixel);
                canvas.translate(-x, -y);
                view.draw(canvas);
                int color = pixel.getPixel(0, 0);
                pixel.recycle();
                return Integer.toHexString(color);
            } catch (Throwable error) {
                return error.getClass().getSimpleName();
            }
        }

        /** A drawable's class and whatever colour it will say, layers and states included. */
        private static String describeDrawable(android.graphics.drawable.Drawable drawable, int depth) {
            if (drawable == null) return "none";
            StringBuilder out = new StringBuilder(drawable.getClass().getSimpleName());
            if (drawable instanceof android.graphics.drawable.ColorDrawable) {
                out.append('#').append(Integer.toHexString(((android.graphics.drawable.ColorDrawable) drawable).getColor()));
            } else if (drawable instanceof android.graphics.drawable.GradientDrawable) {
                android.graphics.drawable.GradientDrawable gradient = (android.graphics.drawable.GradientDrawable) drawable;
                android.content.res.ColorStateList colors = gradient.getColor();
                out.append('#').append(colors == null ? "none" : Integer.toHexString(colors.getDefaultColor()));
                int[] stops = android.os.Build.VERSION.SDK_INT >= 24 ? gradient.getColors() : null;
                if (stops != null) {
                    out.append(" stops=");
                    for (int i = 0; i < stops.length; i++) out.append(i == 0 ? "" : "/").append(Integer.toHexString(stops[i]));
                    out.append(' ').append(gradient.getOrientation()).append(" alpha=").append(gradient.getAlpha());
                }
            } else if (drawable.getClass().getSimpleName().equals("BarShade")) {
                try {
                    java.lang.reflect.Method color = drawable.getClass().getDeclaredMethod("color");
                    color.setAccessible(true);
                    out.append(" paints=").append(Integer.toHexString((Integer) color.invoke(drawable)));
                } catch (ReflectiveOperationException e) {
                    out.append(" paints=?");
                }
            } else if (drawable instanceof android.graphics.drawable.LayerDrawable && depth < 3) {
                android.graphics.drawable.LayerDrawable layers = (android.graphics.drawable.LayerDrawable) drawable;
                out.append('[');
                for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                    out.append(i == 0 ? "" : ", ").append(describeDrawable(layers.getDrawable(i), depth + 1));
                }
                out.append(']');
            } else if (drawable instanceof android.graphics.drawable.DrawableContainer && depth < 3) {
                out.append('{').append(describeDrawable(drawable.getCurrent(), depth + 1)).append('}');
            } else {
                // Material and TikTok's own shape drawables keep the fill behind a getter.
                for (String getter : new String[]{"getFillColor", "getColor", "getBackgroundColor"}) {
                    try {
                        Object value = drawable.getClass().getMethod(getter).invoke(drawable);
                        if (value instanceof android.content.res.ColorStateList) {
                            value = ((android.content.res.ColorStateList) value).getDefaultColor();
                        }
                        if (value instanceof Integer) {
                            out.append(' ').append(getter).append("=#").append(Integer.toHexString((Integer) value));
                            break;
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // Not this getter.
                    }
                }
            }
            return out.toString();
        }

        /** A view's own opacity and visibility, and whether a running animation is about to change them. */
        private static String opacity(android.view.View view) {
            return "alpha=" + view.getAlpha() + " vis=" + view.getVisibility()
                    + (view.getAnimation() != null ? " animating" : "")
                    + (view.hasTransientState() ? " transient" : "");
        }

        /** Alpha to two places and visibility as V, I or G, for lines that list many views. */
        private static String shortOpacity(android.view.View view) {
            int visibility = view.getVisibility();
            return String.format(java.util.Locale.US, "%.2f%s", view.getAlpha(),
                    visibility == android.view.View.VISIBLE ? "V" : visibility == android.view.View.INVISIBLE ? "I" : "G");
        }

        /** The first view with {@code id} under {@code root} whose top left corner is on the screen. */
        private static android.view.View onScreen(android.view.View root, int id, android.content.res.Resources resources) {
            if (id == 0) return null;
            android.util.DisplayMetrics screen = resources.getDisplayMetrics();
            java.util.ArrayDeque<android.view.View> pending = new java.util.ArrayDeque<>();
            pending.add(root);
            int[] where = new int[2];
            while (!pending.isEmpty()) {
                android.view.View view = pending.removeFirst();
                if (view.getId() == id) {
                    view.getLocationOnScreen(where);
                    if (where[0] >= 0 && where[0] < screen.widthPixels && where[1] >= 0 && where[1] < screen.heightPixels) {
                        return view;
                    }
                }
                if (view instanceof android.view.ViewGroup) {
                    android.view.ViewGroup group = (android.view.ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
                }
            }
            return null;
        }

        /** A view for the liveroom action: class, id, place on screen and top padding. */
        private static String liveRoomView(android.view.View view, android.content.res.Resources resources) {
            int[] at = new int[2];
            view.getLocationInWindow(at);
            return view.getClass().getSimpleName() + " id=" + (resources == null ? view.getId() : idName(view, resources))
                    + " at=" + at[0] + "," + at[1] + " h=" + view.getHeight() + " padTop=" + view.getPaddingTop()
                    + " attached=" + view.isAttachedToWindow();
        }

        /** The resource entry name of a view's id, the raw number for an id without one. */
        private static String idName(android.view.View view, android.content.res.Resources resources) {
            int id = view.getId();
            if (id == android.view.View.NO_ID) return null;
            try {
                return resources.getResourceEntryName(id);
            } catch (android.content.res.Resources.NotFoundException missing) {
                return "0x" + Integer.toHexString(id);
            }
        }

        private static void walkTextViews(android.view.View view, int depth, StringBuilder out,
                android.content.res.Resources resources) {
            if (view instanceof android.widget.TextView && view.isShown()
                    && ((android.widget.TextView) view).getText() != null
                    && ((android.widget.TextView) view).getText().length() > 0) {
                int[] where = new int[2];
                view.getLocationOnScreen(where);
                StringBuilder chain = new StringBuilder();
                for (Class<?> c = view.getClass(); c != null && !c.getName().startsWith("android."); c = c.getSuperclass()) {
                    if (chain.length() > 0) chain.append('>');
                    chain.append(c.getSimpleName());
                }
                String id = idName(view, resources);
                android.view.ViewParent parent = view.getParent();
                String parentName = parent instanceof android.view.View
                        ? String.valueOf(idName((android.view.View) parent, resources)) + '/' + parent.getClass().getSimpleName()
                        : "none";
                out.append(depth).append(' ').append(chain)
                        .append(" id=").append(id)
                        .append(" at=").append(where[0]).append(',').append(where[1])
                        .append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
                        .append(" textLength=").append(((android.widget.TextView) view).getText().length())
                        .append(" textSizePx=").append((int) ((android.widget.TextView) view).getTextSize())
                        .append(" textSizeSp=").append(textSp(view, ((android.widget.TextView) view).getTextSize()))
                        .append(" parent=").append(parentName)
                        .append('\n');
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0, count = group.getChildCount(); i < count; i++) {
                    walkTextViews(group.getChildAt(i), depth + 1, out, resources);
                }
            }
        }

        /**
         * Every view in the tree whose class carries both badge setters, which on 46.2.3 and
         * 47.0.3 is the bottom tab icon and nothing else. The answer is cached per class: the
         * tree holds thousands of views and a failed getMethod is an exception each.
         */
        private static void collectTabIcons(android.view.View view, List<android.view.View> icons,
                Map<Class<?>, Boolean> known) {
            Class<?> type = view.getClass();
            Boolean icon = known.get(type);
            if (icon == null) {
                try {
                    type.getMethod("setCountDotVisibility", int.class);
                    type.getMethod("setTabDotVisibility", int.class);
                    icon = true;
                } catch (NoSuchMethodException missing) {
                    icon = false;
                }
                known.put(type, icon);
            }
            if (icon) icons.add(view);
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0, count = group.getChildCount(); i < count; i++) {
                    collectTabIcons(group.getChildAt(i), icons, known);
                }
            }
        }

        /** "getter=visibility" for one badge view, with its text when it is a TextView. */
        private static String badgeState(android.view.View icon, String getter) {
            try {
                Object badge = icon.getClass().getMethod(getter).invoke(icon);
                if (!(badge instanceof android.view.View)) return getter + "=none";
                android.view.View view = (android.view.View) badge;
                String state = getter + "=" + view.getVisibility();
                if (view instanceof android.widget.TextView) {
                    state += "(" + ((android.widget.TextView) view).getText() + ")";
                }
                return state;
            } catch (Exception error) {
                return getter + "=" + error.getClass().getSimpleName();
            }
        }

        private static void topViews(android.view.View view, int depth, int below, int width, StringBuilder out,
                android.content.res.Resources resources) {
            if (view.getVisibility() == android.view.View.GONE) return;
            int[] where = new int[2];
            view.getLocationOnScreen(where);
            if (where[1] < below && view.getWidth() * 10 >= width * 8) {
                out.append(depth).append(' ').append(view.getClass().getName())
                        .append(" id=").append(idName(view, resources))
                        .append(" vis=").append(view.getVisibility())
                        .append(" at=").append(where[0]).append(',').append(where[1])
                        .append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
                        .append(" padTop=").append(view.getPaddingTop())
                        .append(" ty=").append(view.getTranslationY())
                        .append(" alpha=").append(view.getAlpha())
                        .append(" bg=").append(describeDrawable(view.getBackground(), 0));
                if (view instanceof android.widget.ImageView) {
                    out.append(" img=").append(describeDrawable(((android.widget.ImageView) view).getDrawable(), 0));
                }
                out.append('\n');
            }
            if (view instanceof android.view.ViewGroup && depth < 30) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    topViews(group.getChildAt(i), depth + 1, below, width, out, resources);
                }
            }
        }

        private static void walkViews(android.view.View view, int depth, String find, StringBuilder out,
                android.content.res.Resources resources) {
            String name = idName(view, resources);
            boolean print = find == null ? name != null : find.equals(name);
            if (print) {
                int[] where = new int[2];
                view.getLocationOnScreen(where);
                // The obfuscated class says nothing; the first framework class above it says
                // whether this is an ImageView, a TextView or a plain ViewGroup.
                Class<?> framework = view.getClass();
                while (framework != null && !framework.getName().startsWith("android.")) {
                    framework = framework.getSuperclass();
                }
                out.append(depth).append(' ').append(view.getClass().getSimpleName())
                        .append(" is=").append(framework == null ? "?" : framework.getSimpleName())
                        .append(" id=").append(name)
                        .append(" vis=").append(view.getVisibility())
                        .append(" at=").append(where[0]).append(',').append(where[1])
                        .append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
                        .append(" scale=").append(view.getScaleX());
                appendLayoutInfo(view, out);
                if (find != null) {
                    out.append(" shown=").append(view.isShown())
                            .append(" clickable=").append(view.isClickable())
                            .append(" a11y=").append(view.getImportantForAccessibility());
                    out.append(" under=");
                    android.view.ViewParent parent = view.getParent();
                    while (parent instanceof android.view.View) {
                        android.view.View ancestor = (android.view.View) parent;
                        String above = idName(ancestor, resources);
                        out.append(above == null ? ancestor.getClass().getSimpleName() : above)
                                .append("(vis=").append(ancestor.getVisibility())
                                .append(",a11y=").append(ancestor.getImportantForAccessibility())
                                .append(") < ");
                        parent = parent.getParent();
                    }
                }
                out.append('\n');
                if (find != null && view instanceof android.view.ViewGroup) {
                    appendLayoutChildren((android.view.ViewGroup) view, 0, out, resources);
                }
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0, count = group.getChildCount(); i < count; i++) {
                    walkViews(group.getChildAt(i), depth + 1, find, out, resources);
                }
            }
        }

        private static void appendLayoutInfo(android.view.View view, StringBuilder out) {
            android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                out.append(" lp=").append(params.width).append('x').append(params.height);
                if (params instanceof android.widget.LinearLayout.LayoutParams) {
                    out.append(" weight=").append(((android.widget.LinearLayout.LayoutParams) params).weight);
                }
                if (params instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams margins =
                            (android.view.ViewGroup.MarginLayoutParams) params;
                    out.append(" margins=").append(margins.leftMargin).append(',')
                            .append(margins.topMargin).append(',').append(margins.rightMargin)
                            .append(',').append(margins.bottomMargin);
                }
            }
            if (view instanceof android.widget.LinearLayout) {
                out.append(" orientation=").append(((android.widget.LinearLayout) view).getOrientation());
            }
        }

        private static void appendLayoutChildren(android.view.ViewGroup parent, int depth,
                StringBuilder out, android.content.res.Resources resources) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                android.view.View child = parent.getChildAt(i);
                out.append(" child ").append(depth).append('.').append(i).append(' ')
                        .append(child.getClass().getSimpleName()).append(" id=")
                        .append(idName(child, resources)).append(" size=")
                        .append(child.getWidth()).append('x').append(child.getHeight());
                appendLayoutInfo(child, out);
                out.append('\n');
                if (depth < 2 && child instanceof android.view.ViewGroup) {
                    appendLayoutChildren((android.view.ViewGroup) child, depth + 1, out, resources);
                }
            }
        }

        /** A field by name anywhere up the class chain, or null. */
        private static Object fieldValue(Object target, String name) {
            for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException next) {
                    // keep climbing
                } catch (IllegalAccessException refused) {
                    return null;
                }
            }
            return null;
        }

        /**
         * The status, content type and start of a LIVE address: the FLV header, then the first
         * video and audio tags' leading bytes (codec 7 is H.264, 12 and the 0x80 bit are HEVC).
         */
        private static String streamHead(String address) {
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                connection.setRequestProperty("Accept-Encoding", "identity");
                StringBuilder out = new StringBuilder();
                out.append("status=").append(connection.getResponseCode())
                        .append(" type=").append(connection.getContentType());
                java.io.DataInputStream in = new java.io.DataInputStream(connection.getInputStream());
                byte[] header = new byte[9];
                in.readFully(header);
                if (header[0] != 'F' || header[1] != 'L' || header[2] != 'V') {
                    out.append(" notFlv=");
                    for (byte b : header) out.append(String.format("%02x", b));
                    return out.toString();
                }
                out.append(" flvFlags=").append(header[4]);
                in.skipBytes(Math.max(0, (((header[5] & 0xFF) << 24) | ((header[6] & 0xFF) << 16)
                        | ((header[7] & 0xFF) << 8) | (header[8] & 0xFF)) - 9));
                boolean video = false, audio = false;
                for (int tags = 0; tags < 40 && !(video && audio); tags++) {
                    byte[] tag = new byte[15];
                    in.readFully(tag);
                    int type = tag[4] & 0x1F;
                    int size = ((tag[5] & 0xFF) << 16) | ((tag[6] & 0xFF) << 8) | (tag[7] & 0xFF);
                    byte[] body = new byte[size];
                    in.readFully(body);
                    if (type == 9 && !video && size > 1) {
                        video = true;
                        int first = body[0] & 0xFF;
                        out.append(" video0=0x").append(Integer.toHexString(first))
                                .append(" codec=").append(first & 0x0F)
                                .append(" enhanced=").append((first & 0x80) != 0);
                        if ((first & 0x80) != 0 && size >= 5) {
                            out.append(" fourcc=").append(new String(body, 1, 4, java.nio.charset.StandardCharsets.US_ASCII));
                        }
                    } else if (type == 8 && !audio && size > 0) {
                        audio = true;
                        out.append(" audioFormat=").append((body[0] & 0xFF) >> 4);
                    } else if (type == 18) {
                        out.append(" meta=").append(size);
                    }
                }
                return out.toString();
            } catch (Throwable failure) {
                return "failed " + failure;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        @SuppressWarnings("unchecked")
        /**
         * Text without invisible format characters. TikTok 47.1.4 wraps numbers in direction
         * marks, so "189 comments" and a comment's "3d" each start with one and a pattern
         * anchored at a digit never matched.
         */
        private static String withoutFormatMarks(String text) {
            return text.replaceAll("\\p{Cf}", "");
        }

        /**
         * A text size back in sp, the unit the size patches are set in. Pixels depend on the
         * phone's density (24 sp is 72 px at 3.0 and 67 px on a 450 dpi S22), so a check
         * written against pixels only passed on the screen it was written on.
         */
        private static long textSp(android.view.View view, float px) {
            android.util.DisplayMetrics metrics = view.getResources().getDisplayMetrics();
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                return Math.round(android.util.TypedValue.deriveDimension(android.util.TypedValue.COMPLEX_UNIT_SP, px, metrics));
            }
            return Math.round(px / metrics.scaledDensity);
        }

        private static java.util.List<android.view.View> windowRoots() throws Exception {
            Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
            java.lang.reflect.Method getInstance = globalClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object global = getInstance.invoke(null);
            Object value;
            try {
                java.lang.reflect.Method getWindowViews =
                        globalClass.getDeclaredMethod("getWindowViews");
                getWindowViews.setAccessible(true);
                value = getWindowViews.invoke(global);
            } catch (NoSuchMethodException missingMethod) {
                java.lang.reflect.Field views = globalClass.getDeclaredField("mViews");
                views.setAccessible(true);
                value = views.get(global);
            }
            if (value instanceof java.util.List) {
                return (java.util.List<android.view.View>) value;
            }
            throw new IllegalStateException("WindowManagerGlobal returned no root list");
        }

        /** Opens the settings screen the same way the row inside TikTok's own settings does. */
        private void openSettings() throws Exception {
            Intent intent = new Intent(app, loader.loadClass(SETTINGS_ACTIVITY));
            intent.setAction("morphe_settings");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(intent);
        }

        /**
         * Asks TikTok to publish its launcher shortcuts again.
         *
         * <p>Needed because the app publishes them about once, on the first start after an
         * install, and keeps a flag saying it has. The platform carries the published list across
         * a package replace and only rewrites its timestamps, so reinstalling does not exercise
         * the publish path either. Without this there is nothing to watch.
         *
         * <p>The service interface keeps its name; its methods do not. The one wanted is the only
         * one on it taking a scene and a flag, so it is picked by that shape rather than by a
         * name that means nothing and would change with the next build.
         */
        private void refreshShortcuts() throws Exception {
            Class<?> services = loader.loadClass(
                    "com.ss.android.ugc.aweme.framework.services.ServiceManager");
            Object manager = services.getMethod("get").invoke(null);
            Class<?> shortcuts = loader.loadClass(
                    "com.ss.android.ugc.aweme.launcher.service.shortcut.IShortcutService");
            Object service = services.getMethod("getService", Class.class)
                    .invoke(manager, shortcuts);
            if (service == null) throw new IllegalStateException("no IShortcutService");
            Method refresh = null;
            for (Method candidate : shortcuts.getMethods()) {
                Class<?>[] takes = candidate.getParameterTypes();
                if (candidate.getReturnType() == void.class && takes.length == 2
                        && takes[0] == String.class && takes[1] == boolean.class) {
                    if (refresh != null) {
                        throw new IllegalStateException(
                                "IShortcutService has more than one (String, boolean) method, so "
                                        + "the refresh cannot be told apart by its shape any more");
                    }
                    refresh = candidate;
                }
            }
            if (refresh == null) throw new IllegalStateException("no refresh on IShortcutService");
            Log.i(TAG, "refresh is " + refresh.getName());
            refresh.invoke(service, "hushfeed-check", true);
        }

        /**
         * What the double-tap comments action would do right now, and what it sees.
         *
         * <p>The gesture's own path has nothing to say on a phone: a click that lands on a view
         * whose listener does not open the sheet returns true and logs nothing. This lists every
         * comment control the extension has registered, whether the one for the current video is
         * attached, shown and clickable, and then calls the same {@code openComments} the gesture
         * calls, on the main thread, which is the thread a broadcast receiver runs on.
         */
        private String openCommentsReport() throws Exception {
            Class<?> gestures = loader.loadClass(
                    "app.morphe.extension.tiktok.interaction.GestureActions");
            Class<?> author = loader.loadClass(
                    "app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor");
            Class<?> reflect = loader.loadClass("app.morphe.extension.tiktok.blockauthor.Reflect");
            Object aweme = author.getMethod("getAweme").invoke(null);
            Object aid = reflect.getMethod("string", Object.class, String.class, String.class)
                    .invoke(null, aweme, "getAid", "aid");

            java.lang.reflect.Field registry = gestures.getDeclaredField("COMMENTS");
            registry.setAccessible(true);
            java.util.Map<?, ?> controls = (java.util.Map<?, ?>) registry.get(null);
            StringBuilder out = new StringBuilder("aweme=" + (aweme == null ? "null" : aweme.getClass().getName())
                    + " aid=" + aid + " registered=" + controls.size());
            for (java.util.Map.Entry<?, ?> entry : controls.entrySet()) {
                Object control = entry.getValue();
                java.lang.reflect.Field viewField = control.getClass().getDeclaredField("view");
                java.lang.reflect.Field idField = control.getClass().getDeclaredField("videoId");
                viewField.setAccessible(true);
                idField.setAccessible(true);
                android.view.View view = ((java.lang.ref.WeakReference<android.view.View>)
                        viewField.get(control)).get();
                out.append("\n  owner=").append(entry.getKey().getClass().getSimpleName())
                        .append(" videoId=").append(idField.get(control));
                if (view == null) {
                    out.append(" view=collected");
                    continue;
                }
                String resource = "none";
                try {
                    if (view.getId() != android.view.View.NO_ID) {
                        resource = view.getResources().getResourceEntryName(view.getId());
                    }
                } catch (Exception ignored) {
                    resource = String.valueOf(view.getId());
                }
                out.append(" view=").append(view.getClass().getSimpleName())
                        .append(" id=").append(resource)
                        .append(" attached=").append(view.isAttachedToWindow())
                        .append(" shown=").append(view.isShown())
                        .append(" clickable=").append(view.isClickable())
                        .append(" hasClickListener=").append(view.hasOnClickListeners())
                        .append(" desc=").append(view.getContentDescription())
                        .append(" children=").append(view instanceof android.view.ViewGroup
                                ? ((android.view.ViewGroup) view).getChildCount() : 0);
            }

            Method open = gestures.getDeclaredMethod("openComments", String.class);
            open.setAccessible(true);
            Object result = open.invoke(null, aid);
            out.append("\n  openComments(").append(aid).append(")=").append(result);
            return out.toString();
        }

        /** Privacy-safe classifier state for the video currently bound to TikTok's player. */
        private String adEvidence() throws Exception {
            Class<?> author = loader.loadClass(
                    "app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor");
            Object aweme = author.getMethod("getAweme").invoke(null);
            if (aweme == null) return "aweme=null";

            Class<?> awemeClass = aweme.getClass();
            Object anchorsValue = awemeClass.getMethod("getAnchorsExtras").invoke(aweme);
            String anchors = anchorsValue instanceof String ? (String) anchorsValue : null;
            Object rawAd = awemeClass.getMethod("getAwemeRawAd").invoke(aweme);
            boolean nativeAd = (Boolean) awemeClass.getMethod("isAd").invoke(aweme);
            boolean softAd = (Boolean) awemeClass.getMethod("isSoftAd").invoke(aweme);

            Class<?> ads = loader.loadClass(
                    "app.morphe.extension.tiktok.feedfilter.AdsFilter");
            Class<?> awemeModel = loader.loadClass(
                    "com.ss.android.ugc.aweme.feed.model.Aweme");
            Method commission = ads.getDeclaredMethod(
                    "hasCreatorCommissionDisclosure", awemeModel);
            commission.setAccessible(true);
            boolean commissionMatched = (Boolean) commission.invoke(null, aweme);
            Class<?> reflect = loader.loadClass(
                    "app.morphe.extension.tiktok.blockauthor.Reflect");
            Method invoke = reflect.getMethod("invoke", Object.class, String.class);
            Method field = reflect.getMethod("readField", Object.class, String.class);
            Object content = invoke.invoke(null, aweme, "getContentModel");
            Object business = field.invoke(null, content, "standardBusinessModel");
            Object alliance = invoke.invoke(null, business, "getLocalAllianceInfo");
            Object show = invoke.invoke(null, alliance, "showBottomLabel");
            Object type = field.invoke(null, alliance, "allianceItemLabelType");
            Object label = field.invoke(null, alliance, "allianceItemLabelText");

            StringBuilder out = new StringBuilder();
            out.append("awemeClass=").append(awemeClass.getName())
                    .append("\nnativeAd=").append(nativeAd)
                    .append("\nsoftAd=").append(softAd)
                    .append("\nrawAdPresent=").append(rawAd != null)
                    .append("\nanchorsPresent=").append(anchors != null && !anchors.isEmpty())
                    .append("\nanchorsLength=").append(anchors == null ? 0 : anchors.length())
                    .append("\nanchorsHash=")
                    .append(anchors == null ? "none" : Integer.toHexString(anchors.hashCode()))
                    .append("\npanelDisclosureKey=")
                    .append(anchors != null && anchors.contains("panel_top_disclosure_label"))
                    .append("\ncommissionMatched=").append(commissionMatched)
                    .append("\ncontentPresent=").append(content != null)
                    .append("\nbusinessPresent=").append(business != null)
                    .append("\nlocalAlliancePresent=").append(alliance != null)
                    .append("\nlocalAllianceShowLabel=").append(show)
                    .append("\nlocalAllianceLabelType=").append(type)
                    .append("\nlocalAllianceLabelLength=")
                    .append(label instanceof String ? ((String) label).length() : 0)
                    .append("\nremoveAds=").append(valueOf(find("remove_ads")))
                    .append("\nhidePaidPartnership=")
                    .append(valueOf(find("hide_paid_partnership")));
            return out.toString();
        }

        /** Runs the real ad-only list boundary on a detached list, never the visible pager. */
        private String adBoundary() throws Exception {
            Object aweme = loader.loadClass(
                    "app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                    .getMethod("getAweme").invoke(null);
            if (aweme == null) return "aweme=null";
            Class<?> ads = loader.loadClass("app.morphe.extension.tiktok.feedfilter.AdsFilter");
            Class<?> model = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme");
            boolean classified = (Boolean) ads.getMethod("getFiltered", model)
                    .invoke(ads.getConstructor().newInstance(), aweme);
            List<Object> detached = new ArrayList<>();
            detached.add(aweme);
            List<?> remaining = (List<?>) loader.loadClass(
                    "app.morphe.extension.tiktok.feedfilter.FeedItemsFilter")
                    .getMethod("filterProfileAds", List.class).invoke(null, detached);
            Class<?> feed = loader.loadClass("com.ss.android.ugc.aweme.feed.model.FeedItemList");
            Object response = feed.getConstructor().newInstance();
            Field items = feed.getDeclaredField("items");
            items.setAccessible(true);
            items.set(response, new ArrayList<>(Collections.singletonList(aweme)));
            List<?> read = (List<?>) feed.getMethod("getItems").invoke(response);
            return "classified=" + classified + "\nremoveAds=" + valueOf(find("remove_ads"))
                    + "\ninput=1\nremaining=" + remaining.size()
                    + "\nnativeGetterRemaining=" + read.size();
        }

        /**
         * Privacy-safe shape of commerce data attached to the video currently bound to TikTok.
         *
         * <p>47.0.3 renders the creator-commission label while the Aweme exposed by
         * CurrentVideoAuthor has no anchorsExtras. This probe finds which other stable model
         * property carries that decision without ever logging a user or server supplied string.
         */
        private List<String> commerceEvidence() throws Exception {
            Class<?> author = loader.loadClass(
                    "app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor");
            Object aweme = author.getMethod("getAweme").invoke(null);
            if (aweme == null) return Collections.singletonList("aweme=null");

            List<String> out = new ArrayList<>();
            out.add("awemeClass=" + aweme.getClass().getName());
            addRelevantMembers("aweme", aweme, out, true);
            return out;
        }

        /** Exact technical banner counts only. Never collect the search suggestion or other text. */
        private String bannerEvidence(String expectedId) throws Exception {
            Object aweme = loader.loadClass("app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                    .getMethod("getAweme").invoke(null);
            if (aweme == null) return "aweme=null";
            Class<?> model = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme");
            Object raw = model.getMethod("getBanners").invoke(aweme);
            List<?> banners = raw instanceof List ? (List<?>) raw : Collections.emptyList();
            int search = 0, tako = 0, unknown = 0;
            for (Object banner : banners) {
                if (banner == null) { unknown++; continue; }
                Object key = banner.getClass().getField("bannerKey").get(banner);
                Object component = key == null ? null : key.getClass().getField("componentKey").get(key);
                if ("bottom_banner_search_rs".equals(component)) search++;
                else if ("bottom_banner_tako".equals(component)) tako++;
                else unknown++;
            }
            return "banners=" + banners.size() + "\nsearchBanners=" + search + "\ntakoBanners=" + tako
                    + "\notherBanners=" + unknown + "\nexpectedPublicVideo=" + (expectedId == null ? "not checked" :
                    String.valueOf(expectedId.equals(model.getMethod("getAid").invoke(aweme))));
        }

        /**
         * Plants, lists or clears two files in Hushfeed's media cache, one two days old and one
         * an hour old, for the start's sweep of files older than a day. Nothing but TikTok's own
         * uid can age a file there on a release build, so the probe does it from inside.
         * op=plant writes both, op=clear removes whichever is left, anything else lists them.
         */
        private String mediaCache(String op) {
            java.io.File directory = new java.io.File(app.getCacheDir(), "hushfeed-media");
            java.io.File dayOld = new java.io.File(directory, "probe-day-old.bin");
            java.io.File hourOld = new java.io.File(directory, "probe-hour-old.bin");
            long now = System.currentTimeMillis();
            if ("plant".equals(op)) {
                if (!directory.isDirectory() && !directory.mkdirs()) return "no cache directory";
                for (java.io.File file : new java.io.File[]{dayOld, hourOld}) {
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                        out.write(new byte[]{1});
                    } catch (java.io.IOException error) {
                        return "could not write " + file.getName() + ": " + error;
                    }
                }
                // Both, whatever the first answers, so a failure leaves neither at "now".
                boolean agedDay = dayOld.setLastModified(now - 48L * 3_600_000L);
                boolean agedHour = hourOld.setLastModified(now - 3_600_000L);
                if (!agedDay || !agedHour) return "could not age the files";
            } else if ("clear".equals(op)) {
                dayOld.delete();
                hourOld.delete();
            }
            return describe(dayOld, now) + " " + describe(hourOld, now);
        }

        private static String describe(java.io.File file, long now) {
            if (!file.exists()) return file.getName() + "=absent";
            return file.getName() + "=" + ((now - file.lastModified()) / 60_000L) + "min";
        }

        /**
         * The component keys of the current video's anchors (the strips above the caption) and
         * bottom banners, as TikTok names them: anchor_poi, anchor_3rdparty, bottom_banner_search_rs.
         * Identifiers only, never a strip's text.
         */
        private String stripKeys() throws Exception {
            Object aweme = loader.loadClass("app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                    .getMethod("getAweme").invoke(null);
            if (aweme == null) return "aweme=null";
            Class<?> model = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme");
            List<String> anchorKeys = new ArrayList<>();
            Object anchors = model.getMethod("getAnchors").invoke(aweme);
            if (anchors instanceof List) {
                for (Object anchor : (List<?>) anchors) {
                    Object key = anchor == null ? null : anchor.getClass().getMethod("getComponentKey").invoke(anchor);
                    Object type = anchor == null ? null : optionalCall(anchor, "getType");
                    anchorKeys.add(safeKey(key) + "/" + (type instanceof Number ? type : "?"));
                }
            }
            List<String> bannerKeys = new ArrayList<>();
            Object banners = model.getMethod("getBanners").invoke(aweme);
            if (banners instanceof List) {
                for (Object banner : (List<?>) banners) {
                    Object key = banner == null ? null : banner.getClass().getField("bannerKey").get(banner);
                    Object component = key == null ? null : key.getClass().getField("componentKey").get(key);
                    bannerKeys.add(safeKey(component));
                }
            }
            return "anchors=" + anchorKeys + " banners=" + bannerKeys;
        }

        /** A component key as TikTok spells them, or a placeholder for anything that is not one. */
        private static String safeKey(Object key) {
            if (!(key instanceof String)) return "none";
            String text = (String) key;
            return text.matches("[a-z0-9_]{1,80}") ? text : "not-a-key";
        }

        private static Object optionalCall(Object target, String method) {
            try {
                return target.getClass().getMethod(method).invoke(target);
            } catch (Throwable ignored) {
                return null;
            }
        }

        /** Real current model and patched native getter, on a detached list. No place or creator text. */
        private String locationEvidence(String expectedId) throws Exception {
            Object aweme = loader.loadClass("app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                    .getMethod("getAweme").invoke(null);
            if (aweme == null) return "aweme=null";
            Class<?> model = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme");
            Object raw = model.getMethod("getAnchors").invoke(aweme);
            List<?> anchors = raw instanceof List ? (List<?>) raw : Collections.emptyList();
            int locations = 0;
            for (Object anchor : anchors) {
                if (anchor != null && "anchor_poi".equals(anchor.getClass().getMethod("getComponentKey").invoke(anchor))) locations++;
            }
            Class<?> rule = loader.loadClass("app.morphe.extension.tiktok.feedfilter.LocationBadgeFilter");
            boolean classified = (Boolean) rule.getMethod("hasBadge", model).invoke(null, aweme);
            List<?> display = (List<?>) rule.getMethod("visibleAnchors", List.class).invoke(null, anchors);
            Class<?> feed = loader.loadClass("com.ss.android.ugc.aweme.feed.model.FeedItemList");
            Object response = feed.getConstructor().newInstance();
            Field items = feed.getDeclaredField("items");
            items.setAccessible(true);
            items.set(response, new ArrayList<>(Collections.singletonList(aweme)));
            List<?> remaining = (List<?>) feed.getMethod("getItems").invoke(response);
            Object originalAfter = model.getMethod("getAnchors").invoke(aweme);
            return "anchors=" + anchors.size() + "\nlocationAnchors=" + locations
                    + "\nclassified=" + classified + "\ndisplayAnchors=" + display.size()
                    + "\noriginalAnchorsAfter=" + (originalAfter instanceof List ? ((List<?>) originalAfter).size() : 0)
                    + "\nfilterEnabled=" + valueOf(find("filter_location_videos"))
                    + "\nhideEnabled=" + valueOf(find("hide_location_labels"))
                    + "\nnativeGetterInput=1\nnativeGetterRemaining=" + remaining.size()
                    + "\nexpectedPublicVideo=" + (expectedId == null ? "not checked" :
                    String.valueOf(expectedId.equals(model.getMethod("getAid").invoke(aweme))));
        }

        private static String hostAndPath(android.net.Uri uri) {
            if (uri == null) return "null";
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        }

        /**
         * How many webview-bridges entries reach their Java object, read from the page's answer:
         * a JSON string holding the array, whose fifth element per entry lists the Java methods
         * that resolve. -1 when the answer can't be read.
         */
        private static int javaBridgesExposed(String value) {
            try {
                Object decoded = new org.json.JSONTokener(value).nextValue();
                if (!(decoded instanceof String)) return -1;
                JSONArray entries = new JSONArray((String) decoded);
                int count = 0;
                for (int i = 0; i < entries.length(); i++) {
                    JSONArray methods = entries.getJSONArray(i).optJSONArray(4);
                    if (methods != null && methods.length() > 0) count++;
                }
                return count;
            } catch (Exception unreadable) {
                return -1;
            }
        }

        /**
         * Which paid-series markers the current video carries. Strings are reported by length and
         * by whether they are a bare number, never by content, so no collection name is recorded.
         */
        private String seriesEvidence() throws Exception {
            Object aweme = loader.loadClass("app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                    .getMethod("getAweme").invoke(null);
            if (aweme == null) return "aweme=null";
            Class<?> model = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme");
            Object info = model.getMethod("getMPaidContentInfo").invoke(aweme);
            StringBuilder out = new StringBuilder("isPaidContent=" + model.getMethod("isPaidContent").invoke(aweme));
            out.append("\ninfoPresent=").append(info != null);
            if (info != null) {
                for (String getter : new String[] {"getPaidCollectionId", "getCategory", "getDisplayPrompt",
                        "isPaidCollectionIntro", "isLimitedFreeShortDrama", "getShowSeriesPurchaseLabel",
                        "getShouldShowPreview", "getHasPurchased"}) {
                    out.append('\n').append(getter).append('=').append(info.getClass().getMethod(getter).invoke(info));
                }
                for (String getter : new String[] {"getCollectionName", "getEpisodeNumber", "getMiniDramaInfo",
                        "getBottomButtonText", "getVoucherId"}) {
                    Object value = info.getClass().getMethod(getter).invoke(info);
                    String text = value == null ? null : value.toString();
                    out.append('\n').append(getter).append('=').append(text == null ? "null"
                            : "length " + text.length() + (text.matches("-?[0-9]+") ? " number " + text : ""));
                }
                for (String getter : new String[] {"getPrice", "getCoverUrl", "getMiniDramaCardInfo"}) {
                    out.append('\n').append(getter).append("Present=")
                            .append(info.getClass().getMethod(getter).invoke(info) != null);
                }
            }
            try {
                Object filter = loader.loadClass(
                        "app.morphe.extension.tiktok.feedfilter.ContentMarkerFilters$SeriesFilter")
                        .getConstructor().newInstance();
                Method filtered = filter.getClass().getMethod("getFiltered", model);
                out.append("\nclassifiedSeries=").append(filtered.invoke(filter, aweme));
            } catch (Throwable unavailable) {
                out.append("\nclassifiedSeries=unavailable ").append(unavailable.getClass().getSimpleName());
            }
            out.append("\nhideSeries=").append(valueOf(find("hide_series")));
            // The playlist filter reads strings the same way, so its defaults are recorded too.
            Object mix = model.getMethod("getMixInfo").invoke(aweme);
            out.append("\nmixPresent=").append(mix != null);
            if (mix != null) {
                for (String name : new String[] {"mixId", "mixName"}) {
                    Object value = null;
                    for (Class<?> type = mix.getClass(); type != null && value == null; type = type.getSuperclass()) {
                        try {
                            Field field = type.getDeclaredField(name);
                            field.setAccessible(true);
                            value = field.get(mix);
                        } catch (NoSuchFieldException absent) {
                            // keep climbing
                        }
                    }
                    String text = value == null ? null : value.toString();
                    out.append('\n').append(name).append('=').append(text == null ? "null"
                            : "length " + text.length() + (text.matches("-?[0-9]+") && text.length() < 3 ? " number " + text : ""));
                }
            }
            try {
                Object filter = loader.loadClass(
                        "app.morphe.extension.tiktok.feedfilter.ContentMarkerFilters$PlaylistFilter")
                        .getConstructor().newInstance();
                out.append("\nclassifiedPlaylist=")
                        .append(filter.getClass().getMethod("getFiltered", model).invoke(filter, aweme));
            } catch (Throwable unavailable) {
                out.append("\nclassifiedPlaylist=unavailable ").append(unavailable.getClass().getSimpleName());
            }
            return out.toString();
        }

        /**
         * The marker fields the content filters read, for every video TikTok has loaded on the
         * current screen, one line each: a key for dropping repeats, the verdicts of the live
         * filters, and the shape of every field they read.
         *
         * <p>Only shapes leave the phone. Booleans stay. Numbers record whether their int and
         * long views are zero plus one of four text classes. Text and unknown objects record only
         * blank, positive integer, non-positive integer or other. Collection and map sizes are
         * capped. The key is the first 12 hex digits of a SHA-256 of the video id, which the host
         * drops before anything is committed. Every field is read through Hushfeed's own Reflect,
         * getter first and field second, which is exactly the read the filters make.
         */
        private List<String> markerCorpus(String route) throws Exception {
            android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                    .getMethod("getActivity").invoke(null);
            if (activity == null) return Collections.singletonList("{\"error\":\"no activity\"}");
            Class<?> model = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme");
            java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
            List<Object> videos = new ArrayList<>();
            List<android.view.View> views = new ArrayList<>();
            views.add(activity.getWindow().getDecorView());
            for (int index = 0; index < views.size(); index++) {
                android.view.View view = views.get(index);
                if (view instanceof android.view.ViewGroup) {
                    android.view.ViewGroup group = (android.view.ViewGroup) view;
                    for (int child = 0; child < group.getChildCount(); child++) views.add(group.getChildAt(child));
                }
                Object adapter = null;
                try {
                    adapter = view.getClass().getMethod("getAdapter").invoke(view);
                } catch (NoSuchMethodException none) {
                    // not an adapter view
                }
                if (adapter != null) collectVideos(adapter, model, seen, videos);
            }
            // The feed pager's adapter does not hold its list where a walk can reach it, so the
            // videos Hushfeed saw bound (up to 16, CurrentVideoAuthor.RECENT) are added as well.
            try {
                Field recent = loader.loadClass("app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor")
                        .getDeclaredField("RECENT");
                recent.setAccessible(true);
                Map<?, ?> map = (Map<?, ?>) recent.get(null);
                List<Object> items;
                synchronized (map) {
                    items = new ArrayList<>(map.values());
                }
                for (Object item : items) {
                    Field aweme = item.getClass().getDeclaredField("aweme");
                    aweme.setAccessible(true);
                    Object video = aweme.get(item);
                    if (model.isInstance(video) && seen.put(video, Boolean.TRUE) == null) videos.add(video);
                }
            } catch (Throwable unavailable) {
                Log.w(TAG, "marker-corpus: recent binds unavailable, adapters only", unavailable);
            }
            String[] filterNames = {"AiGeneratedFilter", "PaidPartnershipFilter", "SeriesFilter", "PlaylistFilter",
                    "DramaFilter"};
            String[] markerNames = {"ai", "paid", "series", "playlist", "drama"};
            Object[] filters = new Object[filterNames.length];
            for (int i = 0; i < filterNames.length; i++) {
                try {
                    filters[i] = loader.loadClass("app.morphe.extension.tiktok.feedfilter.ContentMarkerFilters$"
                            + filterNames[i]).getConstructor().newInstance();
                } catch (ClassNotFoundException olderBuild) {
                    // A Hushfeed build from before this filter: its verdict is left out, not guessed.
                    Log.w(TAG, "marker-corpus: this build has no " + filterNames[i]);
                }
            }
            Method property = loader.loadClass("app.morphe.extension.tiktok.blockauthor.Reflect")
                    .getMethod("property", Object.class, String.class, String.class);
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            List<String> lines = new ArrayList<>();
            for (Object video : videos) {
                JSONArray markers = new JSONArray();
                for (int i = 0; i < filters.length; i++) {
                    if (filters[i] == null) continue;
                    Object matched = filters[i].getClass().getMethod("getFiltered", model).invoke(filters[i], video);
                    if (Boolean.TRUE.equals(matched)) markers.put(markerNames[i]);
                }
                JSONObject line = new JSONObject();
                Object aid = model.getMethod("getAid").invoke(video);
                byte[] digest = sha.digest(String.valueOf(aid).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder key = new StringBuilder();
                for (int i = 0; i < 6; i++) key.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
                line.put("key", key.toString());
                line.put("markers", markers);
                line.put("shape", markerShape(video, property));
                lines.add(line.toString());
            }
            return lines;
        }

        /** Breadth-first through an adapter's own objects, at most four levels, for loaded videos. */
        private static void collectVideos(Object root, Class<?> model,
                java.util.IdentityHashMap<Object, Boolean> seen, List<Object> videos) {
            List<Object> level = new ArrayList<>();
            level.add(root);
            for (int depth = 0; depth < 5 && !level.isEmpty(); depth++) {
                List<Object> next = new ArrayList<>();
                for (Object node : level) {
                    if (node == null || seen.put(node, Boolean.TRUE) != null || seen.size() > 50_000) continue;
                    if (model.isInstance(node)) {
                        videos.add(node);
                        continue;
                    }
                    if (node instanceof Collection) {
                        next.addAll((Collection<?>) node);
                    } else if (node instanceof Map) {
                        next.addAll(((Map<?, ?>) node).values());
                    } else if (node.getClass().isArray() && !node.getClass().getComponentType().isPrimitive()) {
                        for (int i = 0; i < Array.getLength(node); i++) next.add(Array.get(node, i));
                    } else if (depth < 4 && worthOpening(node)) {
                        for (Class<?> type = node.getClass(); type != null && worthOpening(type.getName()); type = type.getSuperclass()) {
                            for (Field field : type.getDeclaredFields()) {
                                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                                try {
                                    field.setAccessible(true);
                                    next.add(field.get(node));
                                } catch (Throwable unreadable) {
                                    // skip it
                                }
                            }
                        }
                    }
                }
                level = next;
            }
        }

        /** TikTok's own model and adapter classes, never views, contexts or the platform. */
        private static boolean worthOpening(Object node) {
            if (node instanceof android.view.View || node instanceof Context) return false;
            return worthOpening(node.getClass().getName());
        }

        private static boolean worthOpening(String name) {
            return name.startsWith("com.ss.") || name.startsWith("com.bytedance.") || name.startsWith("X.")
                    || name.startsWith("androidx.recyclerview.") || name.startsWith("androidx.viewpager");
        }

        /** The fields ContentMarkerFilters reads, as privacy-safe typed tokens. */
        private static JSONObject markerShape(Object video, Method property) throws Exception {
            JSONObject shape = new JSONObject();
            shape.put("aigcInfo", struct(read(property, video, "getAigcInfo", "aigcInfo"), property,
                    "getAIGCLabelType", "aigcLabelType"));
            shape.put("moderationAigcInfo", struct(read(property, video, "getModerationAigcInfo", "moderationAigcInfo"),
                    property, "getModerationAigcLabelType", "moderationAigcLabelType",
                    "getModerationUserLabelStatus", "moderationUserLabelStatus"));
            shape.put("brandContentAccounts", token(read(property, video, "getBrandContentAccounts", "brandContentAccounts")));
            shape.put("commerceVideoAuthInfo", struct(read(property, video, "getCommerceVideoAuthInfo", "commerceVideoAuthInfo"),
                    property, "isBrandedContent", "isBrandedContent", "isBrandOrganicContent", "isBrandOrganicContent",
                    "getBrandedContentType", "brandedContentType", "getBrandOrganicType", "brandOrganicType",
                    "getEcSearchBoBcLabelText", "ecSearchBoBcLabelText", "isCommerce", "isCommerce"));
            shape.put("commercialVideoInfo", token(read(property, video, "getCommercialVideoInfo", "commercialVideoInfo")));
            shape.put("isPaidContent", token(read(property, video, "isPaidContent", "isPaidContent")));
            Object paidInfo = read(property, video, "getMPaidContentInfo", "mPaidContentInfo");
            Object paidShape = struct(paidInfo,
                    property, "getPaidCollectionId", "paidCollectionId", "getCollectionName", "collectionName",
                    "getEpisodeNumber", "episodeNumber", "isPaidCollectionIntro", "isPaidCollectionIntro",
                    "isLimitedFreeShortDrama", "isLimitedFreeShortDrama", "getMiniDramaInfo", "miniDramaInfo");
            // The Series category is compared with one value (MINI_DRAMA, 1), so it is kept exactly.
            if (paidShape instanceof JSONObject) {
                ((JSONObject) paidShape).put("category", exact(read(property, paidInfo, "getCategory", "category")));
            }
            shape.put("mPaidContentInfo", paidShape);
            // The inserted card's type is compared with one value too (92 is a drama card).
            Object insert = read(property, video, "getCardInsertInfo", "cardInsertInfo");
            shape.put("cardInsertInfo", insert == null ? JSONObject.NULL
                    : new JSONObject().put("cardType", exact(read(property, insert, "getCardType", "cardType"))));
            // The drama card hangs off PaidContentInfo. It is recorded as a field of its own so a
            // shape stays one struct deep.
            shape.put("miniDramaCardInfo", struct(paidInfo == null ? null
                    : read(property, paidInfo, "getMiniDramaCardInfo", "miniDramaCardInfo"),
                    property, "getCardType", "cardType", "getDramas", "dramas"));
            shape.put("playlist_info", struct(read(property, video, "getPlaylist_info", "playlist_info"),
                    property, "getMixId", "mixId"));
            shape.put("mixInfo", struct(read(property, video, "getMixInfo", "mixInfo"), property,
                    "getMixId", "mixId", "getMixName", "mixName"));
            return shape;
        }

        private static Object read(Method property, Object target, String getter, String field) throws Exception {
            return property.invoke(null, target, getter, field);
        }

        /** A struct as {field: token}, from getter and field name pairs, or JSON null when absent. */
        private static Object struct(Object value, Method property, String... pairs) throws Exception {
            if (value == null) return JSONObject.NULL;
            JSONObject out = new JSONObject();
            for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i + 1], token(read(property, value, pairs[i], pairs[i + 1])));
            return out;
        }

        /**
         * A small whole number kept as it is, for the enum-like values a filter compares with one
         * value. Anything else falls back to the ordinary token.
         */
        private static Object exact(Object value) throws Exception {
            if (value instanceof Number) {
                Number number = (Number) value;
                if (Math.abs(number.longValue()) <= 10_000L && number.doubleValue() == number.longValue()) {
                    return new JSONObject().put("n", number.longValue());
                }
            }
            return token(value);
        }

        /** One value as a typed token that keeps what the filters test and drops what identifies. */
        private static Object token(Object value) throws Exception {
            if (value == null) return JSONObject.NULL;
            JSONObject out = new JSONObject();
            if (value instanceof Boolean) {
                out.put("b", value);
            } else if (value instanceof Number) {
                Number number = (Number) value;
                out.put("num", "i" + (number.intValue() == 0 ? "0" : "1")
                        + "l" + (number.longValue() == 0L ? "0" : "1") + textShape(value));
            } else if (value instanceof CharSequence) {
                out.put("txt", textShape(value));
            } else if (value instanceof Collection) {
                out.put("c", Math.min(((Collection<?>) value).size(), 10_000));
            } else if (value instanceof Map) {
                out.put("m", Math.min(((Map<?, ?>) value).size(), 10_000));
            } else {
                out.put("obj", textShape(value));
            }
            return out;
        }

        /** What Reflect.string and SeriesFilter.isEpisode can learn without retaining the text. */
        private static String textShape(Object value) {
            String text = value.toString().trim();
            if (text.isEmpty()) return "b";
            try {
                return Long.parseLong(text) > 0L ? "p" : "z";
            } catch (NumberFormatException notALong) {
                return "x";
            }
        }

        /** Finds the fixed disclosure label and reports only its native view structure. */
        private List<String> commissionViewEvidence() throws Exception {
            android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                    .getMethod("getActivity").invoke(null);
            if (activity == null) return Collections.singletonList("activity=null");
            android.view.View root = activity.getWindow().getDecorView();
            List<android.view.View> queue = new ArrayList<>();
            queue.add(root);
            List<android.view.View> found = new ArrayList<>();
            for (int index = 0; index < queue.size(); index++) {
                android.view.View view = queue.get(index);
                if (view instanceof android.widget.TextView) {
                    CharSequence value = ((android.widget.TextView) view).getText();
                    if (value != null && "Creator earns commission".contentEquals(value)) {
                        found.add(view);
                    }
                }
                if (view instanceof android.view.ViewGroup) {
                    android.view.ViewGroup group = (android.view.ViewGroup) view;
                    for (int child = 0; child < group.getChildCount(); child++) {
                        queue.add(group.getChildAt(child));
                    }
                }
            }

            List<String> out = new ArrayList<>();
            out.add("activity=" + activity.getClass().getName()
                    + ",visited=" + queue.size() + ",matches=" + found.size());
            for (int match = 0; match < found.size(); match++) {
                android.view.View current = found.get(match);
                for (int depth = 0; current != null && depth < 10; depth++) {
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    current.getGlobalVisibleRect(bounds);
                    Object tag = current.getTag();
                    out.add("match=" + match + ",depth=" + depth
                            + ",class=" + current.getClass().getName()
                            + ",id=" + safeResourceName(current)
                            + ",shown=" + current.isShown()
                            + ",attached=" + current.isAttachedToWindow()
                            + ",visibility=" + current.getVisibility()
                            + ",bounds=" + bounds.flattenToString()
                            + ",tag=" + (tag == null ? "null" : tag.getClass().getName()));
                    addViewObjectFields("match=" + match + ",depth=" + depth, current, out);
                    android.view.ViewParent parent = current.getParent();
                    current = parent instanceof android.view.View
                            ? (android.view.View) parent : null;
                }
            }
            return out;
        }

        /**
         * The video's renditions as a chosen-quality download sees them: each entry of the backing
         * bitRate field through Hushfeed's own QualitySelector.describe (gear name and height).
         */
        private static String gearList(ClassLoader loader, Object video) {
            if (video == null) return "none";
            try {
                // The way a download reads them: getRawBitRate, else the field under either name.
                Object rates = optional(video, "getRawBitRate");
                for (String name : new String[]{"bitRateList", "bitRate"}) {
                    if (rates != null) break;
                    for (Class<?> c = video.getClass(); c != null && rates == null; c = c.getSuperclass()) {
                        try {
                            java.lang.reflect.Field field = c.getDeclaredField(name);
                            field.setAccessible(true);
                            rates = field.get(video);
                        } catch (NoSuchFieldException next) { }
                    }
                }
                if (!(rates instanceof java.util.List)) return "none";
                Method describe = loader.loadClass("app.morphe.extension.tiktok.download.QualitySelector")
                        .getMethod("describe", Object.class);
                StringBuilder out = new StringBuilder("[");
                for (Object gear : (java.util.List<?>) rates) {
                    if (out.length() > 1) out.append(", ");
                    // With TikTok's codec code (is_bytevc1) and its format string.
                    out.append(describe.invoke(null, gear)).append(" codec ").append(optional(gear, "isBytevc1"))
                            .append(' ').append(optional(gear, "getFormat"));
                }
                return out.append(']').toString().replace(' ', '_');
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return "?(" + failure.getClass().getSimpleName() + ")";
            }
        }

        /**
         * The video's caption entries (language/format, from captionModel.captionList) and how many
         * subtitle tracks Hushfeed's own SubtitleDownloads.tracks makes of them for "all".
         */
        private static String captionList(ClassLoader loader, Object video) {
            try {
                Object model = optional(video, "getCaptionModel");
                if (model == null) return "none";
                java.lang.reflect.Field field = model.getClass().getDeclaredField("captionList");
                field.setAccessible(true);
                Object list = field.get(model);
                if (!(list instanceof java.util.List)) return "empty";
                StringBuilder out = new StringBuilder("[");
                for (Object caption : (java.util.List<?>) list) {
                    if (out.length() > 1) out.append(',');
                    out.append(optional(caption, "getLanguageCode")).append('/').append(optional(caption, "getFormat"));
                }
                Method tracks = loader.loadClass("app.morphe.extension.tiktok.download.SubtitleDownloads")
                        .getDeclaredMethod("tracks", Object.class, String.class, java.util.Locale.class);
                tracks.setAccessible(true);
                Object found = tracks.invoke(null, video, "all", java.util.Locale.getDefault());
                return out.append("]tracks").append(found instanceof java.util.List ? ((java.util.List<?>) found).size() : -1).toString();
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return "?(" + failure.getClass().getSimpleName() + ")";
            }
        }

        /** A no-argument getter's value, or null when the target or the getter is missing. */
        private static Object optional(Object target, String getter) {
            if (target == null) return null;
            try {
                return target.getClass().getMethod(getter).invoke(target);
            } catch (ReflectiveOperationException | RuntimeException missing) {
                return null;
            }
        }

        private static boolean blank(Object value) {
            return value == null || String.valueOf(value).trim().isEmpty();
        }

        /**
         * A video count as a bucket. Below 10,000 TikTok writes a count out whole (2,447), so a
         * check on abbreviated counts needs the top bucket. It still reads as over1000 to a
         * pattern looking for that.
         */
        private static String countBucket(Object count) {
            if (!(count instanceof Number)) return "unknown";
            long value = ((Number) count).longValue();
            return value <= 100 ? "100orFewer" : value <= 1000 ? "1000orFewer" : value < 10000 ? "over1000" : "over10000";
        }

        /** TikTok's caption strip shown, with the view that draws its cue visible at a height. */
        private static boolean captionDrawn(int strip, int drawn) throws Exception {
            if (strip == 0 || drawn == 0) return false;
            java.util.ArrayDeque<android.view.View> pending = new java.util.ArrayDeque<>(windowRoots());
            while (!pending.isEmpty()) {
                android.view.View view = pending.removeFirst();
                if (!(view instanceof android.view.ViewGroup)) continue;
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                boolean isStrip = view.getId() == strip && view.isShown();
                for (int i = 0; i < group.getChildCount(); i++) {
                    android.view.View child = group.getChildAt(i);
                    if (isStrip && child.getId() == drawn && child.getVisibility() == android.view.View.VISIBLE
                            && child.getHeight() > 0) return true;
                    pending.add(child);
                }
            }
            return false;
        }

        /** A view's own text Layout, when it keeps one in a field (TikTok's caption view does). */
        private static android.text.Layout layoutOf(android.view.View view) {
            for (Class<?> type = view.getClass(); type != null && type != android.view.View.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!android.text.Layout.class.isAssignableFrom(field.getType())) continue;
                    try {
                        field.setAccessible(true);
                        Object value = field.get(view);
                        if (value instanceof android.text.Layout) return (android.text.Layout) value;
                    } catch (ReflectiveOperationException | RuntimeException unreadable) {
                        // The next field, or none.
                    }
                }
            }
            return null;
        }

        /** An address's frame as WxH from its getWidth and getHeight, or none. */
        /** An id by its last six characters, enough to match two reads without logging it whole. */
        private static String tail(Object id) {
            if (id == null) return "null";
            String text = String.valueOf(id);
            return text.isEmpty() ? "empty" : text.length() <= 6 ? text : text.substring(text.length() - 6);
        }

        /** The idreset action: ids before, the reset and how it answered, ids after. */
        private static void idReset(Context context, ClassLoader loader, long timeout, boolean readOnly) {
            try {
                Class<?> appLog = loader.loadClass("com.bytedance.applog.AppLog");
                Class<?> register = loader.loadClass("com.ss.android.deviceregister.DeviceRegisterManager");
                Log.i(TAG, "ok idreset before" + registeredIds(context, appLog, register));
                if (readOnly) return;
                // 47.4.3's reset callback: LIZ() when the timeout runs out, LIZIZ(installInfo)
                // when new install info arrives.
                Class<?> callbackType = loader.loadClass("X.0Ncl");
                final java.util.concurrent.CountDownLatch answered = new java.util.concurrent.CountDownLatch(1);
                final String[] outcome = {"no answer"};
                Object callback = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{callbackType},
                        (self, method, args) -> {
                            switch (method.getName()) {
                                case "LIZ":
                                    outcome[0] = "timeout";
                                    answered.countDown();
                                    return null;
                                case "LIZIZ":
                                    outcome[0] = "new install info";
                                    answered.countDown();
                                    return null;
                                case "hashCode":
                                    return System.identityHashCode(self);
                                case "equals":
                                    return self == args[0];
                                case "toString":
                                    return "HushfeedProbeIdReset";
                                default:
                                    return null;
                            }
                        });
                long start = android.os.SystemClock.elapsedRealtime();
                appLog.getMethod("resetAndReInstall", long.class, callbackType).invoke(null, timeout, callback);
                long returned = android.os.SystemClock.elapsedRealtime() - start;
                boolean came = answered.await(timeout + 10_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                Log.i(TAG, "ok idreset returned after " + returned + " ms, "
                        + (came ? outcome[0] : "callback never came") + " after "
                        + (android.os.SystemClock.elapsedRealtime() - start) + " ms");
                Log.i(TAG, "ok idreset after" + registeredIds(context, appLog, register));
            } catch (Throwable error) {
                Log.e(TAG, "idreset failed", error);
            }
        }

        /** The idreset where step: each file under TikTok's data folder holding either id. */
        private static void idFiles(Context context, ClassLoader loader) {
            try {
                Class<?> appLog = loader.loadClass("com.bytedance.applog.AppLog");
                String did = String.valueOf(appLog.getMethod("getDid").invoke(null));
                String iid = String.valueOf(appLog.getMethod("getIid").invoke(null));
                if (did.length() < 8 || iid.length() < 8) {
                    Log.i(TAG, "ok idreset where: no ids registered yet");
                    return;
                }
                java.io.File root = context.getDataDir();
                java.util.ArrayDeque<java.io.File> todo = new java.util.ArrayDeque<>();
                todo.add(root);
                int scanned = 0;
                while (!todo.isEmpty()) {
                    java.io.File file = todo.poll();
                    java.io.File[] children = file.listFiles();
                    if (children != null) {
                        Collections.addAll(todo, children);
                        continue;
                    }
                    if (!file.isFile() || file.length() > 8L * 1024 * 1024) continue;
                    scanned++;
                    byte[] bytes;
                    try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                        bytes = new byte[(int) file.length()];
                        int read = 0;
                        while (read < bytes.length) {
                            int n = in.read(bytes, read, bytes.length - read);
                            if (n < 0) break;
                            read += n;
                        }
                    } catch (java.io.IOException unreadable) {
                        continue;
                    }
                    String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                    boolean hasDid = text.contains(did), hasIid = text.contains(iid);
                    if (hasDid || hasIid) {
                        Log.i(TAG, "ok idreset holds" + (hasDid ? " did" : "") + (hasIid ? " iid" : "") + ": "
                                + root.toURI().relativize(file.toURI()).getPath());
                    }
                }
                Log.i(TAG, "ok idreset where done, " + scanned + " files read");
            } catch (Throwable error) {
                Log.e(TAG, "idreset where failed", error);
            }
        }

        /** Each id TikTok registers, as a short hash ("-" when empty). */
        private static String registeredIds(Context context, Class<?> appLog, Class<?> register) {
            StringBuilder out = new StringBuilder();
            String[][] getters = {
                    {"did", "getDid"}, {"iid", "getIid"}, {"openudid", "getOpenUdid"},
                    {"clientudid", "getClientUdid"},
            };
            for (String[] getter : getters) out.append(' ').append(getter[0]).append('=').append(idHash(appLog, getter[1]));
            String[][] backups = {{"didBackup", "getDeviceIdWithBackup"}, {"iidBackup", "getInstallIdWithBackup"}};
            for (String[] getter : backups) out.append(' ').append(getter[0]).append('=').append(idHash(register, getter[1]));
            String cdid;
            try {
                cdid = idText(register.getMethod("getCdid", Context.class).invoke(null, context));
            } catch (Throwable error) {
                cdid = "<" + error.getClass().getSimpleName() + ">";
            }
            return out.append(" cdid=").append(cdid).toString();
        }

        private static String idHash(Class<?> owner, String getter) {
            try {
                return idText(owner.getMethod(getter).invoke(null));
            } catch (Throwable error) {
                return "<" + error.getClass().getSimpleName() + ">";
            }
        }

        private static String idText(Object id) {
            String text = id == null ? "" : String.valueOf(id);
            return text.isEmpty() ? "-" : Integer.toHexString(text.hashCode() & 0xffff) + "/" + text.length();
        }

        /** A Kotlin two-argument callback's call, logged with what TikTok passed it. */
        private static Object answer(java.lang.reflect.Method method, Object[] args, Object unit, String which) {
            switch (method.getName()) {
                case "invoke":
                    StringBuilder out = new StringBuilder("collect ").append(which);
                    if (args != null) {
                        for (Object arg : args) {
                            out.append(" | ").append(arg == null ? "null" : arg.getClass().getName())
                                    .append(' ').append(arg == null ? "" : String.valueOf(arg));
                        }
                    }
                    Log.i(TAG, out.toString());
                    return unit;
                case "hashCode":
                    return System.identityHashCode(method);
                case "equals":
                    return args != null && args.length == 1 && args[0] == null;
                default:
                    return "collect " + which + " callback";
            }
        }

        /** How many URLs an address holds, the frame it claims, and the first one's host and path. */
        private static String addressReport(Object address) {
            if (address == null) return "none";
            Object urls = optional(address, "getUrlList");
            int count = urls instanceof java.util.List ? ((java.util.List<?>) urls).size() : -1;
            String first = count > 0
                    ? hostAndPath(android.net.Uri.parse(String.valueOf(((java.util.List<?>) urls).get(0)))) : "-";
            return count + "urls," + frame(address) + "," + first;
        }

        private static String frame(Object address) {
            if (address == null) return "none";
            return optional(address, "getWidth") + "x" + optional(address, "getHeight");
        }

        /** Its lines, width and how many lines end inside a word, never the text itself. */
        private static String layoutReport(android.text.Layout layout) {
            CharSequence text = layout.getText();
            int inWords = 0;
            for (int i = 0; i < layout.getLineCount() - 1; i++) {
                int end = layout.getLineEnd(i);
                if (end > 0 && end < text.length() && Character.isLetterOrDigit(text.charAt(end - 1))
                        && Character.isLetterOrDigit(text.charAt(end))) inWords++;
            }
            return "lines" + layout.getLineCount() + "/w" + layout.getWidth() + "/inWords" + inWords;
        }

        private static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        }

        private static String safeResourceName(android.view.View view) {
            if (view.getId() == android.view.View.NO_ID) return "none";
            try {
                return view.getResources().getResourceName(view.getId());
            } catch (Throwable ignored) {
                return "numeric";
            }
        }

        private static void addViewObjectFields(
                String prefix,
                android.view.View view,
                List<String> out
        ) {
            int emitted = 0;
            for (Class<?> current = view.getClass(); current != null
                    && current != android.view.View.class && current != Object.class;
                 current = current.getSuperclass()) {
                String owner = current.getName();
                if (owner.startsWith("android.") || owner.startsWith("java.")) continue;
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || ++emitted > 64) continue;
                    try {
                        field.setAccessible(true);
                        Object value = field.get(view);
                        if (value == null || value instanceof CharSequence
                                || value instanceof Number || value instanceof Boolean) {
                            continue;
                        }
                        out.add(prefix + ",owner=" + owner + ",field=" + field.getName()
                                + ",declared=" + field.getType().getName()
                                + ",value=" + value.getClass().getName());
                    } catch (Throwable ignored) {
                        // A hidden framework field is irrelevant to the custom renderer shape.
                    }
                }
            }
        }

        private static void addRelevantMembers(
                String prefix,
                Object target,
                List<String> out,
                boolean includeMethods
        ) {
            if (target == null) return;
            int before = out.size();
            Class<?> type = target.getClass();
            for (Class<?> current = type; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !isCommerceMember(field.getName())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        addSafeValue(prefix + ".field." + field.getName(), field.get(target), out);
                    } catch (Throwable error) {
                        out.add(prefix + ".field." + field.getName() + "=unreadable");
                    }
                }
            }

            if (includeMethods) {
                for (Method method : type.getMethods()) {
                    String name = method.getName();
                    if (method.getParameterTypes().length != 0
                            || method.getReturnType() == void.class
                            || !(name.startsWith("get") || name.startsWith("is"))
                            || !isCommerceMember(name)) {
                        continue;
                    }
                    try {
                        method.setAccessible(true);
                        addSafeValue(prefix + ".method." + name, method.invoke(target), out);
                    } catch (Throwable error) {
                        out.add(prefix + ".method." + name + "=unreadable");
                    }
                }
            }

            if (out.size() == before) out.add(prefix + "=no-relevant-members");
        }

        private static boolean isCommerceMember(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.contains("anchor")
                    || lower.contains("commerce")
                    || lower.contains("paid")
                    || lower.contains("product")
                    || lower.contains("affiliate")
                    || lower.contains("commission")
                    || lower.contains("disclosure")
                    || lower.contains("promotion")
                    || lower.contains("sponsor")
                    || lower.contains("shop")
                    || lower.contains("ecom");
        }

        private static void addSafeValue(String name, Object value, List<String> out) {
            addSafeValue(name, value, out, true);
        }

        private static void addSafeValue(
                String name,
                Object value,
                List<String> out,
                boolean descend
        ) {
            if (value == null) {
                out.add(name + "=null");
                return;
            }
            if (value instanceof CharSequence) {
                String text = value.toString();
                String lower = text.toLowerCase(Locale.ROOT);
                out.add(name + "=string"
                        + ",len=" + text.length()
                        + ",hash=" + Integer.toHexString(text.hashCode())
                        + ",commission=" + lower.contains("commission")
                        + ",affiliate=" + lower.contains("affiliate")
                        + ",disclosure=" + lower.contains("disclosure")
                        + ",paid=" + lower.contains("paid")
                        + ",sponsored=" + lower.contains("sponsor")
                        + ",product=" + lower.contains("product"));
                if (name.endsWith(".field.extra")) addJsonShape(name, text, out);
                return;
            }
            if (value instanceof Boolean) {
                out.add(name + "=boolean," + value);
                return;
            }
            if (value instanceof Number) {
                out.add(name + "=number," + (((Number) value).doubleValue() == 0d
                        ? "zero" : "nonzero"));
                return;
            }
            if (value.getClass().isEnum()) {
                out.add(name + "=enum," + value.getClass().getName());
                return;
            }

            int size = -1;
            Object first = null;
            if (value instanceof Collection) {
                Collection<?> collection = (Collection<?>) value;
                size = collection.size();
                if (!collection.isEmpty()) first = collection.iterator().next();
            } else if (value instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) value;
                size = map.size();
                if (!map.isEmpty()) first = map.values().iterator().next();
            } else if (value.getClass().isArray()) {
                size = Array.getLength(value);
                if (size > 0) first = Array.get(value, 0);
            }
            if (size >= 0) {
                out.add(name + "=container," + value.getClass().getName() + ",size=" + size
                        + (first == null ? "" : ",first=" + first.getClass().getName()));
                if (descend && first != null) addAllFields(name + "[0]", first, out);
                return;
            }

            out.add(name + "=object," + value.getClass().getName());
            if (descend) addAllFields(name, value, out);
        }

        /** JSON keys and value shapes only. String contents never leave the process. */
        private static void addJsonShape(String name, String text, List<String> out) {
            String trimmed = text.trim();
            if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return;
            try {
                Object root = trimmed.startsWith("{")
                        ? new JSONObject(trimmed) : new JSONArray(trimmed);
                int[] remaining = {96};
                addJsonShape(name + ".json", root, out, 0, remaining);
            } catch (Throwable error) {
                out.add(name + ".json=invalid");
            }
        }

        private static void addJsonShape(
                String path,
                Object value,
                List<String> out,
                int depth,
                int[] remaining
        ) {
            if (remaining[0]-- <= 0) {
                out.add(path + "=node-limit");
                return;
            }
            if (depth > 5) {
                out.add(path + "=depth-limit");
                return;
            }
            if (value == null || value == JSONObject.NULL) {
                out.add(path + "=null");
                return;
            }
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                List<String> keys = new ArrayList<>();
                java.util.Iterator<String> iterator = object.keys();
                while (iterator.hasNext()) keys.add(iterator.next());
                Collections.sort(keys);
                out.add(path + "=object,size=" + keys.size());
                for (String key : keys) {
                    addJsonShape(path + "." + key, object.opt(key), out, depth + 1, remaining);
                }
                return;
            }
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                out.add(path + "=array,size=" + array.length());
                if (array.length() > 0) {
                    addJsonShape(path + "[0]", array.opt(0), out, depth + 1, remaining);
                }
                return;
            }
            addSafeValue(path, value, out, false);
        }

        /** One non-recursive field pass over a nested commerce model. */
        private static void addAllFields(String prefix, Object target, List<String> out) {
            int emitted = 0;
            for (Class<?> current = target.getClass(); current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (++emitted > 96) {
                        out.add(prefix + "=field-limit");
                        return;
                    }
                    try {
                        field.setAccessible(true);
                        addSafeValue(prefix + ".field." + field.getName(), field.get(target), out, false);
                    } catch (Throwable error) {
                        out.add(prefix + ".field." + field.getName() + "=unreadable");
                    }
                }
            }
        }

        /** Reads only a copied public TikTok video URL, never arbitrary clipboard content. */
        private String copiedTikTokVideoLink() {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "missing";
            CharSequence value = clip.getItemAt(0).coerceToText(app);
            String text = value == null ? "" : value.toString().trim();
            if (text.startsWith("https://www.tiktok.com/") && text.contains("/video/")) return text;
            return "not-a-tiktok-video-url";
        }

        /**
         * The copied text's host, whether it has a query and the kind of page its path names, or
         * what it is when it isn't a link.
         */
        private String copiedLinkShape() {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "clip=empty";
            CharSequence value = clip.getItemAt(0).coerceToText(app);
            String text = value == null ? "" : value.toString().trim();
            if (!text.startsWith("https://") && !text.startsWith("http://")) return "clip=not-a-link";
            android.net.Uri uri = android.net.Uri.parse(text);
            return "host=" + uri.getHost() + " query=" + (uri.getQuery() != null)
                    + " path=" + linkPathShape(uri.getPath());
        }

        /** The kind of page a path names, by its shape alone: never the name or the code in it. */
        private String linkPathShape(String path) {
            if (path == null || path.isEmpty() || path.equals("/")) return "root";
            if (path.matches("/@[^/]+/video/\\d+/?")) return "video";
            if (path.matches("/@[^/]+/photo/\\d+/?")) return "photo";
            if (path.matches("/t/[A-Za-z0-9]+/?")) return "t-short";
            if (path.matches("/[A-Za-z0-9]{4,20}/?")) return "short";
            return "other-" + (path.split("/", -1).length - 1) + "-segments";
        }

        /**
         * What FeedVisibility sees right now: the current activity, its answers, the Home tab's
         * flags and whether any of it has pixels on screen, and the detail-page registry. The
         * registry and its entries are read by field type rather than name, since the release
         * extension is minified and only the hooked entry points keep their names.
         */
        private String feedReport() throws Exception {
            Class<?> visibility = loader.loadClass("app.morphe.extension.tiktok.blockauthor.FeedVisibility");
            android.app.Activity activity = (android.app.Activity) loader.loadClass(UTILS)
                    .getMethod("getActivity").invoke(null);
            StringBuilder out = new StringBuilder();
            out.append("activity=").append(activity == null ? "null" : activity.getClass().getName());
            if (activity != null) {
                out.append("\nisOnFeed=").append(visibility.getMethod("isOnFeed", android.app.Activity.class)
                        .invoke(null, activity));
                out.append("\nonRecommendationFeed=").append(visibility
                        .getMethod("onRecommendationFeed", android.app.Activity.class).invoke(null, activity));
                out.append("\ncommentSheet=").append(visibility
                        .getMethod("isCommentSheetVisible", android.app.Activity.class).invoke(null, activity));
                android.view.View tab = (android.view.View) visibility
                        .getMethod("homeTabView", android.app.Activity.class).invoke(null, activity);
                if (tab == null) {
                    out.append("\nhomeTab=null");
                } else {
                    android.graphics.Rect rect = new android.graphics.Rect();
                    boolean onScreen = tab.getGlobalVisibleRect(rect);
                    int[] where = new int[2];
                    tab.getLocationOnScreen(where);
                    out.append("\nhomeTab shown=").append(tab.isShown())
                            .append(" selected=").append(tab.isSelected())
                            .append(" attached=").append(tab.isAttachedToWindow())
                            .append(" globalVisibleRect=").append(onScreen).append(' ').append(rect.toShortString())
                            .append(" locationOnScreen=").append(where[0]).append(',').append(where[1]);
                    android.view.ViewParent parent = tab.getParent();
                    int depth = 0;
                    while (parent instanceof android.view.View) {
                        android.view.View view = (android.view.View) parent;
                        depth++;
                        if (view.getScrollX() != 0 || view.getTranslationX() != 0f) {
                            out.append("\n  ancestor ").append(view.getClass().getName())
                                    .append(" scrollX=").append(view.getScrollX())
                                    .append(" translationX=").append(view.getTranslationX())
                                    .append(" left=").append(view.getLeft());
                        }
                        // What hides the tab when isShown says no: an ancestor that is not
                        // VISIBLE, or one faded out, with its depth above the tab and its id.
                        if (view.getVisibility() != android.view.View.VISIBLE || view.getAlpha() < 1f) {
                            out.append("\n  hidden ancestor ").append(depth).append(' ')
                                    .append(view.getClass().getName()).append('/')
                                    .append(idName(view, activity.getResources()))
                                    .append(" vis=").append(view.getVisibility())
                                    .append(" alpha=").append(view.getAlpha())
                                    .append(" translationY=").append(view.getTranslationY())
                                    .append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
                                    .append(view.getId() == android.R.id.content ? " (content)" : "");
                        }
                        if (view.getId() == android.R.id.content) {
                            out.append("\n  content at depth ").append(depth);
                        }
                        parent = view.getParent();
                    }
                }
            }
            for (java.lang.reflect.Method method : visibility.getDeclaredMethods()) {
                if (method.getParameterTypes().length == 0 && method.getReturnType() == boolean.class
                        && java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    method.setAccessible(true);
                    out.append('\n').append(method.getName()).append("()=").append(method.invoke(null));
                }
            }
            for (java.lang.reflect.Field field : visibility.getDeclaredFields()) {
                if (!java.util.Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                java.util.Map<?, ?> pages = (java.util.Map<?, ?>) field.get(null);
                out.append("\nregistry ").append(field.getName()).append(" size=").append(pages.size());
                for (java.util.Map.Entry<?, ?> entry : pages.entrySet()) {
                    out.append("\n  page ").append(entry.getKey().getClass().getName());
                    Object state = entry.getValue();
                    for (java.lang.reflect.Field part : state.getClass().getDeclaredFields()) {
                        part.setAccessible(true);
                        Object value = part.get(state);
                        if (value instanceof java.lang.ref.Reference) {
                            Object view = ((java.lang.ref.Reference<?>) value).get();
                            value = view == null ? "null" : view.getClass().getName() + " shown="
                                    + ((android.view.View) view).isShown();
                        }
                        out.append(' ').append(part.getName()).append('=').append(value);
                    }
                }
            }
            return out.toString();
        }

        /**
         * Every setting Hushfeed has created, with its value.
         *
         * <p>Loading the tiktok Settings class is what creates them: each is a static field on it,
         * so until the class is initialised the registry is empty and a lookup finds nothing.
         */
        private String dump() throws Exception {
            List<?> all = (List<?>) registry().getMethod("allLoadedSettings").invoke(null);
            List<String> lines = new ArrayList<>();
            for (Object each : all) lines.add(keyOf(each) + "=" + valueOf(each));
            Collections.sort(lines);
            // String.join arrived in API 26, and this runs on the same floor as the bundle.
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                if (out.length() != 0) out.append('\n');
                out.append(line);
            }
            return out.toString();
        }

        private void write(String key, String value) throws Exception {
            Object setting = find(key);
            Object current = setting.getClass().getMethod("get").invoke(setting);
            // save is declared final on Setting and generic, so it erases to save(Object).
            loader.loadClass(SETTING).getMethod("save", Object.class)
                    .invoke(setting, coerce(current, value));
        }

        /**
         * The string turned into whatever the setting already holds.
         *
         * <p>Booleans are parsed strictly. {@code Boolean.parseBoolean} answers false for "1",
         * for "yes" and for every typo, so a mistyped value would be written as false and logged
         * as a successful write. This exists to check what settings do on a device; a silent
         * wrong value is the one failure it must not have.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object coerce(Object current, String value) {
            if (current instanceof Boolean) {
                if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
                if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
                throw new IllegalArgumentException("a boolean setting takes true or false, not " + value);
            }
            if (current instanceof Integer) return Integer.parseInt(value);
            if (current instanceof Long) return Long.parseLong(value);
            if (current instanceof Float) return Float.parseFloat(value);
            // getDeclaringClass, not getClass: an enum constant with a body of its own is an
            // anonymous subclass, and valueOf on that finds nothing.
            if (current instanceof Enum) {
                return Enum.valueOf((Class<Enum>) ((Enum<?>) current).getDeclaringClass(), value);
            }
            return value;
        }

        private Object find(String key) throws Exception {
            Object found = registry().getMethod("getSettingFromPath", String.class)
                    .invoke(null, key);
            if (found == null) throw new IllegalArgumentException("no setting called " + key);
            return found;
        }

        private Class<?> registry() throws Exception {
            Class.forName(SETTINGS, true, loader);
            return loader.loadClass(SETTING);
        }

        private String keyOf(Object setting) throws Exception {
            return String.valueOf(setting.getClass().getField("key").get(setting));
        }

        private String valueOf(Object setting) throws Exception {
            return String.valueOf(setting.getClass().getMethod("get").invoke(setting));
        }
    }
}

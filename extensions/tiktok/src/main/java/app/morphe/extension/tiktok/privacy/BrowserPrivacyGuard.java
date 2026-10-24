/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Keeps TikTok's JavaScript bridges on its own pages and off external browser pages.
 *
 * <p>The page is not known when TikTok registers a bridge: {@link WebView#getUrl()} is still null
 * then. Every registration is therefore kept, and the bridges are applied or withheld before the
 * next main-frame document is created. Android only changes a page's bridges when a document
 * loads, and {@code onPageStarted} arrives after the new document has committed, so the decisions
 * that count are taken before the navigation: at TikTok's own load, reload and history calls,
 * when TikTok's WebViewClient is asked whether a link may load, and when a main-frame request is
 * about to leave the WebView. That last one sees form posts and history steps too, which nothing
 * else does, but only for a client that overrides {@code shouldInterceptRequest}; sixteen of
 * TikTok's do on 47.0.3. {@code onPageStarted} stays as a last correction for the page after.
 *
 * <p>A state object is owned by the WebView through an attach-state listener. The static index
 * holds weak references on both sides, so a bridge that points back to its WebView cannot turn
 * the index into a process-lifetime retention cycle.
 */
@SuppressWarnings("unused")
public final class BrowserPrivacyGuard {
    private static final Map<WebView, WeakReference<BridgeState>> STATES = new WeakHashMap<>();

    /**
     * TikTok's own registrable domains. Every app page the 47.0.3 APK opens is under one of them:
     * Activity center and Watch history on inapp.tiktokv.com, LIVE appeals on inapp-va.tiktokv.com,
     * Shop orders on oec-api.tiktokv.com, Report a problem on feedback.tiktokv.com, and account
     * status, appeals, surveys and verification under app paths on www.tiktok.com. A list of hosts
     * went stale the day it was written, and it failed TikTok's pages, not external ones.
     * tiktoklinksafety is not here: it is the interstitial that sends a tapped link outside.
     */
    private static final String[] FIRST_PARTY_DOMAINS = {
            "tiktok.com", "tiktokv.com", "tiktokv.us", "tiktokv.eu",
            // PIPO, TikTok's payment processor, for checkout and card entry.
            "pipopayment.com", "pipopayment.us",
    };

    /**
     * TikTok's own CDNs. They serve uploads and every ByteDance app's static pages too, so only
     * the puzzle CAPTCHA's folder there is trusted: that's what LIVEs and the Shop open
     * (lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx/oec-captcha-ttweb/..., read off a 47.1.4 LIVE
     * on 2026-10-11). The puzzle answers TikTok through its bridge, so withholding it there left
     * a check that could never pass (#87).
     */
    private static final String[] STATIC_CDN_DOMAINS = {"tiktokcdn.com", "tiktokcdn-us.com", "tiktokcdn-eu.com"};
    private static final String STATIC_FRONTEND = "/obj/static-tx/oec-captcha-ttweb/";

    /** How long a request thread waits for the decision to reach the WebView's own thread. */
    private static final long DECISION_WAIT_MS = 250;

    public static void filterJsInterface(WebView webView, Object object, String name) {
        if (object == null || name == null) {
            webView.addJavascriptInterface(object, name);
            return;
        }

        BridgeState state = stateFor(webView);
        boolean enabled = isEnabled();
        String currentUrl = webView.getUrl();
        boolean allowed = !enabled || (currentUrl == null
                ? state.bridgesAllowed()
                : isTrustedAppPage(currentUrl));
        state.setBridgesAllowed(webView, allowed);
        state.bind(webView, name, object);
    }

    public static void loadUrl(WebView webView, String url) {
        prepareForPage(webView, url);
        webView.loadUrl(url);
    }

    public static void loadUrl(WebView webView, String url, Map<String, String> headers) {
        prepareForPage(webView, url);
        webView.loadUrl(url, headers);
    }

    public static void postUrl(WebView webView, String url, byte[] postData) {
        prepareForPage(webView, url);
        webView.postUrl(url, postData);
    }

    public static void loadData(
            WebView webView, String data, String mimeType, String encoding) {
        prepareForPage(webView, null);
        webView.loadData(data, mimeType, encoding);
    }

    public static void loadDataWithBaseURL(
            WebView webView,
            String baseUrl,
            String data,
            String mimeType,
            String encoding,
            String historyUrl) {
        prepareForPage(webView, baseUrl);
        webView.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl);
    }

    public static void reload(WebView webView) {
        prepareForPage(webView, webView.getUrl());
        webView.reload();
    }

    /**
     * Before a reload through an interface one of TikTok's WebViews implements (its hybrid view
     * interface, which Lynx views implement too). The interface call itself follows unchanged.
     */
    public static void beforeInterfaceReload(Object target) {
        if (target instanceof WebView) {
            WebView webView = (WebView) target;
            prepareForPage(webView, webView.getUrl());
        }
    }

    /** A step back loads the page it returns to, which may be TikTok's or an external one. */
    public static void goBack(WebView webView) {
        prepareForHistoryStep(webView, -1);
        webView.goBack();
    }

    public static void goForward(WebView webView) {
        prepareForHistoryStep(webView, 1);
        webView.goForward();
    }

    public static void goBackOrForward(WebView webView, int steps) {
        prepareForHistoryStep(webView, steps);
        webView.goBackOrForward(steps);
    }

    /** A last correction: it arrives after the page has committed, so it decides the page after. */
    public static void onPageStarted(WebView webView, String url) {
        prepareForPage(webView, url);
    }

    /**
     * Runs before a string-form WebViewClient navigation continues. A link to another app's
     * scheme (aweme://, snssdk1233://, intent:) never loads in the WebView, so it leaves the page
     * where it is and changes nothing.
     */
    public static void onPageRequested(WebView webView, String url) {
        if (!isWebUrl(url)) return;
        prepareForPage(webView, url);
    }

    /** Runs before a request-form WebViewClient navigation continues. */
    public static void onPageRequested(WebView webView, WebResourceRequest request) {
        if (request == null || !request.isForMainFrame()) return;
        String url = request.getUrl() == null ? null : request.getUrl().toString();
        if (!isWebUrl(url)) return;
        prepareForPage(webView, url);
    }

    /**
     * Runs when a main-frame request is about to leave the WebView, on one of the WebView's
     * background threads. A navigation that fetches its page passes through here first, form
     * posts and history steps included, which {@code shouldOverrideUrlLoading} never sees, and the
     * address is Chromium's own reading of the URL. The decision is handed to the WebView's
     * thread, and the request waits for it, briefly.
     */
    public static void onRequestIntercepted(WebView webView, WebResourceRequest request) {
        if (webView == null || request == null || !request.isForMainFrame()) return;
        if (!isEnabled()) return;
        String url = request.getUrl() == null ? null : request.getUrl().toString();
        if (!isWebUrl(url)) return;

        Looper looper = Build.VERSION.SDK_INT >= 28 ? webView.getWebViewLooper() : null;
        if (looper == null) looper = Looper.getMainLooper();
        if (looper == Looper.myLooper()) {
            prepareForPage(webView, url);
            return;
        }
        CountDownLatch decided = new CountDownLatch(1);
        new Handler(looper).post(() -> {
            try {
                prepareForPage(webView, url);
            } finally {
                decided.countDown();
            }
        });
        try {
            decided.await(DECISION_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void prepareForHistoryStep(WebView webView, int steps) {
        if (steps == 0) return;
        WebBackForwardList history = webView.copyBackForwardList();
        int target = history.getCurrentIndex() + steps;
        // Out of range, WebView does nothing, and nothing loads.
        if (target < 0 || target >= history.getSize()) return;
        WebHistoryItem item = history.getItemAtIndex(target);
        prepareForPage(webView, item == null ? null : item.getUrl());
    }

    private static void prepareForPage(WebView webView, String url) {
        if (isScriptUrl(url)) return;

        boolean enabled = isEnabled();
        boolean allowed = !enabled || isTrustedAppPage(url);
        boolean changed = stateFor(webView).setBridgesAllowed(webView, allowed);
        if (enabled && changed) {
            String destination = originLabel(url);
            Logger.printInfo(() -> "Browser privacy guard: JavaScript bridge "
                    + (allowed ? "restored for " : "withheld from ") + destination);
        }
    }

    private static boolean isEnabled() {
        return Utils.getContext() != null && Settings.BLOCK_WEBVIEW_JS_INTERFACES.get();
    }

    static boolean isTrustedAppPage(String url) {
        if (url == null) return false;
        final Uri parsed;
        try {
            parsed = Uri.parse(url);
        } catch (RuntimeException malformed) {
            return false;
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme())) return false;
        // Chromium and android.net.Uri disagree at the edges: Chromium drops tabs and line breaks,
        // and Uri decodes an escape inside the host, so %40 turns into an @ that splits nothing.
        // They agree on a host of plain letters, digits, dots and hyphens with no user info in
        // front of it, and only that is trusted.
        if (parsed.getEncodedUserInfo() != null) return false;
        String host = parsed.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || !host.matches("[a-z0-9.-]+")) return false;

        if (host.equals("eu.pipopay.com")) return true;
        for (String domain : FIRST_PARTY_DOMAINS) {
            if (sameOrSubdomain(host, domain)) return true;
        }
        for (String domain : STATIC_CDN_DOMAINS) {
            if (sameOrSubdomain(host, domain)) return isStaticFrontend(parsed.getEncodedPath());
        }
        return false;
    }

    /**
     * Chromium resolves dot segments, escaped dots included, and drops tabs and line breaks
     * before it asks for a path, so a path is only read as the frontend's when it has none of
     * those to resolve: plain characters, no escapes, no empty or dot segment.
     */
    private static boolean isStaticFrontend(String path) {
        if (path == null || !path.startsWith(STATIC_FRONTEND) || path.contains("//")) return false;
        if (!path.matches("[A-Za-z0-9._~/-]+")) return false;
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private static boolean sameOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith('.' + domain);
    }

    private static boolean isWebUrl(String url) {
        if (url == null) return false;
        int colon = url.indexOf(':');
        if (colon <= 0) return false;
        String scheme = url.substring(0, colon);
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }

    private static boolean isScriptUrl(String url) {
        if (url == null) return false;
        int colon = url.indexOf(':');
        return colon > 0 && "javascript".equalsIgnoreCase(url.substring(0, colon));
    }

    private static String originLabel(String url) {
        if (url == null) return "an untrusted local page";
        try {
            Uri parsed = Uri.parse(url);
            String host = parsed.getHost();
            return host == null ? "an untrusted local page" : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException malformed) {
            return "an invalid page address";
        }
    }

    private static BridgeState stateFor(WebView webView) {
        synchronized (STATES) {
            WeakReference<BridgeState> reference = STATES.get(webView);
            BridgeState state = reference == null ? null : reference.get();
            if (state != null) return state;
            state = new BridgeState();
            webView.addOnAttachStateChangeListener(state);
            STATES.put(webView, new WeakReference<>(state));
            return state;
        }
    }

    static void resetForTests() {
        synchronized (STATES) {
            STATES.clear();
        }
    }

    private static final class BridgeState implements View.OnAttachStateChangeListener {
        private final Map<String, Object> interfaces = new LinkedHashMap<>();
        private boolean bridgesAllowed = true;

        synchronized boolean bridgesAllowed() {
            return bridgesAllowed;
        }

        synchronized void bind(WebView webView, String name, Object object) {
            interfaces.put(name, object);
            if (bridgesAllowed) webView.addJavascriptInterface(object, name);
            else webView.removeJavascriptInterface(name);
        }

        synchronized boolean setBridgesAllowed(WebView webView, boolean allowed) {
            if (bridgesAllowed == allowed) return false;
            bridgesAllowed = allowed;
            for (Map.Entry<String, Object> binding : interfaces.entrySet()) {
                if (allowed) {
                    webView.addJavascriptInterface(binding.getValue(), binding.getKey());
                } else {
                    webView.removeJavascriptInterface(binding.getKey());
                }
            }
            return true;
        }

        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) {}
    }

    private BrowserPrivacyGuard() {}
}

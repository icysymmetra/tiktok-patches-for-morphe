/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWebView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class BrowserPrivacyGuardTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(false);
        BrowserPrivacyGuard.resetForTests();
    }

    @After public void tearDown() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(false);
        BrowserPrivacyGuard.resetForTests();
    }

    @Test public void tiktokOwnDomainsKeepTheBridgeAndLookalikesDoNot() {
        for (String page : new String[] {
                "https://inapp.tiktokv.com/tpp/inapp/pns_product_activity_center/ac/watch_history",
                "https://verify-sg.tiktokv.com/captcha/verify",
                "https://checkout.pipopayment.com/pay",
                "https://fp.pipopayment.us/device",
                "https://pipo-wallet.tiktokv.com/wallet",
                "https://eu.pipopay.com/checkout",
                "https://shop-sg.tiktok.com/order",
                "https://www.tiktok.com/verifycenter/authentication",
                "https://www.tiktok.com/verifycenter/ttcaptcha/",
                // TikTok pages the 47.0.3 APK opens that a list of hosts had missed.
                "https://inapp-va.tiktokv.com/falcon/webcast_mt/page/appeal/index.html",
                "https://oec-api.tiktokv.com/view/fe_tiktok_ecommerce_order_detail",
                "https://www.tiktok.com/tns-inapp/pages/account_status",
                "https://www.tiktok.com/falcon/communitysafety/page/violation-appeal",
                "https://www.tiktok.com/inapp/filtered_comments/posts",
                "https://www.tiktok.com/ucenter_web/account_protect",
                "https://feedback.tiktokv.com/feedback/report",
                "https://webcast16-normal-useast5.tiktokv.us/falcon/page",
                "https://WWW.TIKTOK.COM./tns-inapp/pages/account_status",
                // #87: the puzzle CAPTCHA a 47.1.4 LIVE opens, on TikTok's static frontend.
                "https://lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx/oec-captcha-ttweb/captcha/ttp/3.0.75/2.0.0.886/index.html?verify_host=https%3A%2F%2Foec-api.tiktokv.com%2F&aid=1233",
                "https://sf16-website-login.tiktokcdn.com/obj/static-tx/oec-captcha-ttweb/captcha/",
        }) {
            assertTrue(page, BrowserPrivacyGuard.isTrustedAppPage(page));
        }

        for (String page : new String[] {
                "https://www.tiktoklinksafety.us/link/?target=example.com",
                "https://www.tiktoklinksafety.com/link/",
                "https://example.com",
                "https://inapp.tiktokv.com.example.com/watch_history",
                "https://shop-sg.tiktok.com.example.com/order",
                "https://eviltiktok.com/",
                "https://tiktok-minis.com/app",
                "https://us.pipopay.com/checkout",
                "http://inapp.tiktokv.com/watch_history",
                // Addresses Chromium and android.net.Uri would read as different hosts. Uri
                // decodes the escaped @ into the host; Chromium refuses the address.
                "https://evil.com%40a.tiktok.com/",
                "https://example.com\\@inapp.tiktokv.com/",
                "https://example.com\\.tiktok.com/",
                "https://inapp.tiktokv.com\t.example.com/",
                "https://inapp.tiktokv.com@example.com/",
                "https://anyone@inapp.tiktokv.com/",
                "https://tіktok.com/",
                "javascript:alert(1)",
                "aweme://detail/123",
                // The same CDNs outside the static frontend, or paths Chromium would resolve
                // somewhere else than they read.
                "https://lf16-cdn-tos.tiktokcdn-us.com/obj/tiktok-obj/upload.html",
                "https://lf16-cdn-tos.tiktokcdn-us.com/",
                // Another app's page in the same static bucket.
                "https://lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx/some-other-app/index.html?verify_host=x",
                "https://lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx/oec-captcha-ttweb",
                "https://lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx/oec-captcha-ttweb-evil/index.html",
                "https://lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx/oec-captcha-ttweb/../other/index.html",
                "https://lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx/../tiktok-obj/upload.html",
                "https://lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx/%2e%2e/tiktok-obj/upload.html",
                "https://lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx/.\t./tiktok-obj/upload.html",
                "https://lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx//upload.html",
                "https://lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx/a\\..\\..\\tiktok-obj/upload.html",
                "http://lf16-cdn-tos.tiktokcdn-us.com/obj/static-tx/captcha/index.html",
                "https://tiktokcdn-us.com.example.com/obj/static-tx/captcha/index.html",
        }) {
            assertFalse(page, BrowserPrivacyGuard.isTrustedAppPage(page));
        }
        assertFalse(BrowserPrivacyGuard.isTrustedAppPage(null));
    }

    @Test public void aLinkToAnotherAppLeavesThePageItsBridge() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");
        BrowserPrivacyGuard.onPageStarted(webView,
                "https://inapp.tiktokv.com/tpp/inapp/pns_product_activity_center/ac/watch_history");

        // Watch history opens a video through TikTok's own scheme; the page never leaves.
        BrowserPrivacyGuard.onPageRequested(webView, "aweme://detail/7228647995380862254");
        BrowserPrivacyGuard.onPageRequested(webView,
                request("snssdk1233://webview?url=https%3A%2F%2Fexample.com", true));
        BrowserPrivacyGuard.onPageRequested(webView, "intent://scan/#Intent;scheme=zxing;end");
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));

        BrowserPrivacyGuard.onPageRequested(webView, "https://example.com/");
        assertNull(shadow.getJavascriptInterface("bridge"));
    }

    @Test public void historyStepsDecideByThePageTheyReturnTo() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");
        shadow.pushEntryToHistory("https://inapp.tiktokv.com/activity");
        shadow.pushEntryToHistory("https://example.com/landing");
        BrowserPrivacyGuard.onPageStarted(webView, "https://example.com/landing");
        assertNull(shadow.getJavascriptInterface("bridge"));

        // Back from an external page to Activity center: Chromium asks no client about it.
        BrowserPrivacyGuard.goBack(webView);
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
        assertEquals(1, shadow.getGoBackInvocations());

        BrowserPrivacyGuard.goForward(webView);
        assertNull(shadow.getJavascriptInterface("bridge"));
        assertEquals(1, shadow.getGoForwardInvocations());

        BrowserPrivacyGuard.goBackOrForward(webView, -1);
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));

        // A step past either end loads nothing, so it changes nothing.
        BrowserPrivacyGuard.goBackOrForward(webView, -5);
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
    }

    @Test public void aReloadThroughTikTokInterfaceDecidesByTheCurrentPage() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");
        BrowserPrivacyGuard.loadUrl(webView, "https://inapp.tiktokv.com/activity");
        // Something left the bridge withheld while the trusted page stayed on screen.
        BrowserPrivacyGuard.onPageRequested(webView, "https://example.com/");
        assertNull(shadow.getJavascriptInterface("bridge"));

        BrowserPrivacyGuard.beforeInterfaceReload(webView);
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));

        // The same interface is implemented by Lynx views, which are not WebViews.
        BrowserPrivacyGuard.beforeInterfaceReload(new Object());
        BrowserPrivacyGuard.beforeInterfaceReload(null);
    }

    @Test public void aMainFrameRequestDecidesBeforeItLeavesEvenFromAnotherThread() throws Exception {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");
        BrowserPrivacyGuard.loadUrl(webView, "https://inapp.tiktokv.com/checkout");

        // Subresources, other apps' schemes and the WebView's own thread.
        BrowserPrivacyGuard.onRequestIntercepted(webView, request("https://example.com/pixel.gif", false));
        BrowserPrivacyGuard.onRequestIntercepted(webView, request("aweme://detail/1", true));
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
        BrowserPrivacyGuard.onRequestIntercepted(webView, request("https://bank.example/3ds", true));
        assertNull(shadow.getJavascriptInterface("bridge"));

        // A form post back to TikTok arrives on one of the WebView's background threads, and the
        // request has to wait until the bridge is back on the WebView's own thread.
        Thread requestThread = new Thread(() -> BrowserPrivacyGuard.onRequestIntercepted(webView,
                request("https://oec-api.tiktokv.com/view/fe_tiktok_ecommerce_order_detail", true)));
        requestThread.start();
        while (requestThread.isAlive()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            requestThread.join(5);
        }
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
    }

    @Test public void externalLoadsRemoveEveryBridgeAndFirstPartyLoadsRestoreThem() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object first = new Object();
        Object second = new Object();

        BrowserPrivacyGuard.filterJsInterface(webView, first, "first");
        BrowserPrivacyGuard.filterJsInterface(webView, second, "second");
        assertSame(first, shadow.getJavascriptInterface("first"));
        assertSame(second, shadow.getJavascriptInterface("second"));

        BrowserPrivacyGuard.loadUrl(webView, "https://www.tiktoklinksafety.us/link/");
        assertNull(shadow.getJavascriptInterface("first"));
        assertNull(shadow.getJavascriptInterface("second"));
        assertEquals("https://www.tiktoklinksafety.us/link/", shadow.getLastLoadedUrl());

        BrowserPrivacyGuard.loadUrl(webView,
                "https://inapp.tiktokv.com/tpp/inapp/pns_product_activity_center/ac/watch_history");
        assertSame(first, shadow.getJavascriptInterface("first"));
        assertSame(second, shadow.getJavascriptInterface("second"));
    }

    @Test public void redirectCallbacksChangeTheNextPagePolicy() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");

        BrowserPrivacyGuard.onPageStarted(webView, "https://example.com/landing");
        assertNull(shadow.getJavascriptInterface("bridge"));
        BrowserPrivacyGuard.onPageStarted(webView, "https://verify-va.tiktokv.com/captcha");
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
    }

    @Test public void subframeRequestsCannotChangeTheMainPagePolicy() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");

        BrowserPrivacyGuard.onPageStarted(webView, "https://example.com/landing");
        assertNull(shadow.getJavascriptInterface("bridge"));
        BrowserPrivacyGuard.onPageRequested(webView,
                request("https://inapp.tiktokv.com/embedded", false));
        assertNull(shadow.getJavascriptInterface("bridge"));

        BrowserPrivacyGuard.onPageRequested(webView,
                request("https://inapp.tiktokv.com/activity", true));
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
    }

    @Test public void scriptLoadsKeepTheCurrentPagePolicy() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");

        BrowserPrivacyGuard.onPageStarted(webView, "https://example.com/landing");
        BrowserPrivacyGuard.loadUrl(webView, "javascript:document.title='still external'");
        assertNull(shadow.getJavascriptInterface("bridge"));

        BrowserPrivacyGuard.onPageStarted(webView, "https://inapp.tiktokv.com/activity");
        BrowserPrivacyGuard.loadUrl(webView, "JAVASCRIPT:document.title='still trusted'");
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
    }

    @Test public void turningTheSwitchOffLeavesNativeRegistrationAndNavigationAlone() {
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");

        BrowserPrivacyGuard.loadUrl(webView, "https://example.com", Map.of("X-Test", "yes"));
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
        assertEquals("https://example.com", shadow.getLastLoadedUrl());
        assertEquals("yes", shadow.getLastAdditionalHttpHeaders().get("X-Test"));
    }

    @Test public void dataLoadsUseTheirBaseOriginAndPlainDataFailsClosed() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");

        BrowserPrivacyGuard.loadDataWithBaseURL(webView, "https://inapp.tiktokv.com/app/",
                "<p>inside</p>", "text/html", "UTF-8", null);
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));

        BrowserPrivacyGuard.loadData(webView, "<p>unknown origin</p>", "text/html", "UTF-8");
        assertNull(shadow.getJavascriptInterface("bridge"));
    }

    @Test public void theStaticRegistryCannotOwnAWebViewThroughItsBridge() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WeakReference<WebView> discarded = abandonExternalWebView();
        for (int attempt = 0; attempt < 20 && discarded.get() != null; attempt++) {
            System.gc();
            System.runFinalization();
        }
        assertNull("the bridge registry retained a discarded WebView", discarded.get());
    }

    private WeakReference<WebView> abandonExternalWebView() {
        WebView webView = new WebView(context);
        BrowserPrivacyGuard.filterJsInterface(webView, new BridgeThatOwnsItsView(webView), "bridge");
        BrowserPrivacyGuard.onPageStarted(webView, "https://example.com");
        return new WeakReference<>(webView);
    }

    private static WebResourceRequest request(String url, boolean mainFrame) {
        return new WebResourceRequest() {
            @Override public Uri getUrl() {
                return Uri.parse(url);
            }

            @Override public boolean isForMainFrame() {
                return mainFrame;
            }

            @Override public boolean isRedirect() {
                return false;
            }

            @Override public boolean hasGesture() {
                return false;
            }

            @Override public String getMethod() {
                return "GET";
            }

            @Override public Map<String, String> getRequestHeaders() {
                return Collections.emptyMap();
            }
        };
    }

    private static final class BridgeThatOwnsItsView {
        @SuppressWarnings("unused")
        final WebView webView;

        BridgeThatOwnsItsView(WebView webView) {
            this.webView = webView;
        }
    }
}

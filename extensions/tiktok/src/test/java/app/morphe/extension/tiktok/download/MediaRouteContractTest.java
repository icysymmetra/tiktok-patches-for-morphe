package app.morphe.extension.tiktok.download;

import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * The route contract behind the proxy settings: Hushfeed's saves and its short-link lookups
 * open direct, never through the JVM's proxy selector, and keep their address, redirect and TLS
 * checks. With a proxy installed the selector would otherwise choose the peer after the check.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MediaRouteContractTest {
    private ProxySelector original;
    private final List<URI> selected = new ArrayList<>();

    @Before public void installAProxySelectorThatWouldCatchAnyUse() {
        original = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                selected.add(uri);
                return Collections.singletonList(new Proxy(Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved("proxy.example", 8080)));
            }

            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        });
    }

    @After public void restoreTheSelector() {
        ProxySelector.setDefault(original);
    }

    @Test public void aSaveOpensDirectAndKeepsItsTlsIdentity() throws Exception {
        AtomicInteger viaSelector = new AtomicInteger();
        withHttpsHandler(viaSelector, url -> new Fake(url, HTTP_OK, null), () -> {
            URL media = new URL("https://cdn.example/video.mp4?sig=1");
            javax.net.ssl.HttpsURLConnection connection =
                    PinnedMediaConnection.open(media, InetAddress.getByName("8.8.8.8"));
            assertTrue(connection.getSSLSocketFactory() instanceof PinnedMediaConnection.PinnedFactory);
            assertEquals("cdn.example", connection.getRequestProperty("Host"));
        });
        assertEquals("the default selector was used", 0, viaSelector.get());
        assertTrue("the proxy selector was asked: " + selected, selected.isEmpty());
    }

    @Test public void aShortLinkLookupOpensDirectAndStillStopsAtAPrivateRedirect() throws Exception {
        AtomicInteger viaSelector = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        withHttpsHandler(viaSelector, url -> {
            opened.incrementAndGet();
            return new Fake(url, HTTP_MOVED_TEMP, "https://10.0.0.1/internal");
        }, () -> {
            // A literal public address needs no DNS, so the real opener runs end to end.
            assertThrows(IOException.class, () -> LinkResolver.finalUrl("https://8.8.8.8/short"));
            assertThrows(IOException.class, () -> LinkResolver.finalUrl("https://127.0.0.1/short"));
            assertThrows(IOException.class, () -> LinkResolver.finalUrl("http://8.8.8.8/short"));
        });
        assertEquals("only the public hop was opened", 1, opened.get());
        assertEquals("the default selector was used", 0, viaSelector.get());
        assertTrue("the proxy selector was asked: " + selected, selected.isEmpty());
    }

    @Test public void aShortLinkLookupFollowsAPublicRedirectDirect() throws Exception {
        AtomicInteger viaSelector = new AtomicInteger();
        List<String> hops = new ArrayList<>();
        withHttpsHandler(viaSelector, url -> {
            hops.add(url.getHost());
            return new Fake(url, hops.size() == 1 ? HTTP_MOVED_TEMP : HTTP_OK,
                    hops.size() == 1 ? "https://1.1.1.1/video/1" : null);
        }, () -> assertEquals("https://1.1.1.1/video/1", LinkResolver.finalUrl("https://8.8.8.8/short")));
        assertEquals(List.of("8.8.8.8", "1.1.1.1"), hops);
        assertEquals(0, viaSelector.get());
        assertTrue(selected.isEmpty());
    }

    private interface Opener {
        URLConnection open(URL url) throws IOException;
    }

    private interface Action {
        void run() throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static void withHttpsHandler(AtomicInteger viaSelector, Opener opener, Action action)
            throws Exception {
        new URL("https://8.8.8.8/");
        Hashtable<String, URLStreamHandler> handlers =
                ReflectionHelpers.getStaticField(URL.class, "handlers");
        URLStreamHandler previous = handlers.get("https");
        handlers.put("https", new URLStreamHandler() {
            // The one-argument form is the one that consults the default selector.
            @Override protected URLConnection openConnection(URL url) throws IOException {
                viaSelector.incrementAndGet();
                return opener.open(url);
            }

            @Override protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
                assertEquals(Proxy.NO_PROXY, proxy);
                return opener.open(url);
            }
        });
        try {
            action.run();
        } finally {
            handlers.put("https", previous);
        }
    }

    private static final class Fake extends FakeHttpsConnection {
        private final int code;
        private final String location;

        Fake(URL url, int code, String location) {
            super(url);
            this.code = code;
            this.location = location;
        }

        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String name) {
            return "Location".equalsIgnoreCase(name) ? location : null;
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
    }
}

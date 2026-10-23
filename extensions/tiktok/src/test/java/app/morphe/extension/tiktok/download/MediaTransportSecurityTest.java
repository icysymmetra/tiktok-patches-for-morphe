package app.morphe.extension.tiktok.download;

import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.ss.android.ugc.aweme.base.model.UrlModel;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HttpsURLConnection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Network destinations that extension-owned media downloads are allowed to reach. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MediaTransportSecurityTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=");

    /**
     * Previous media tests used a local HTTP server and therefore proved only byte copying.
     * They never made the platform expose that it was following a redirect outside our code.
     */
    @Test public void anAutomaticRedirectCannotDowngradeToCleartext() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        withHttpsHandler(url -> {
            opened.incrementAndGet();
            return response(url, true);
        }, () -> {
            File target = File.createTempFile("redirect-downgrade", ".tmp");
            Files.write(target.toPath(), new byte[]{9, 8, 7});
            try {
                assertThrows(IOException.class, () -> RemoteMedia.fetch(
                        List.of("https://8.8.8.8/start"), target, RemoteMedia.Kind.IMAGE));
                assertFalse("a refused redirect left its old or partial target", target.exists());
                assertEquals("the HTTPS hop should be inspected exactly once", 1, opened.get());
            } finally {
                target.delete();
            }
        });
    }

    /** A URL that spells HTTPS is still local when its resolved address is loopback. */
    @Test public void anHttpsLoopbackLiteralIsRejectedBeforeOpeningAConnection() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        withHttpsHandler(url -> {
            opened.incrementAndGet();
            return response(url, false);
        }, () -> {
            File target = File.createTempFile("loopback-media", ".tmp");
            try {
                assertThrows(IOException.class, () -> RemoteMedia.fetch(
                        List.of("https://127.0.0.1/private"), target, RemoteMedia.Kind.IMAGE));
                assertEquals("a local endpoint was opened before it was refused", 0, opened.get());
                assertFalse("a refused local URL left a target", target.exists());
            } finally {
                target.delete();
            }
        });
    }

    /** Download quality must not prefer a gear that the downloader will later refuse. */
    @Test public void qualitySelectionDoesNotTreatCleartextAsUsableMedia() {
        assertNull(QualitySelector.choose(
                List.of(new Gear("http://cdn.example/video.mp4")), "highest"));
        assertNull(QualitySelector.choose(
                List.of(new Gear("https://reader:secret@cdn.example/video.mp4")), "highest"));
    }

    @Test public void urlShapeRequiresHttpsAHostAndNoUserInformation() {
        assertTrue(MediaTransport.hasAllowedShape("https://v16-webapp-prime.tiktokcdn.com/video"));
        assertFalse(MediaTransport.hasAllowedShape("http://v16-webapp-prime.tiktokcdn.com/video"));
        assertFalse(MediaTransport.hasAllowedShape("https://name@v16-webapp-prime.tiktokcdn.com/video"));
        assertFalse(MediaTransport.hasAllowedShape("https:///video"));
        assertFalse(MediaTransport.hasAllowedShape("not a URL"));
        assertFalse(MediaTransport.hasAllowedShape(null));
    }

    @Test public void localPrivateWildcardAndMulticastAddressesAreNotMediaDestinations()
            throws Exception {
        for (String address : new String[]{
                "0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.4.2",
                "172.16.1.2", "192.168.1.2", "224.0.0.1", "255.255.255.255",
                "::", "::1", "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1"
        }) {
            assertFalse(address, MediaTransport.isPublicAddress(InetAddress.getByName(address)));
        }
        assertTrue(MediaTransport.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(MediaTransport.isPublicAddress(
                InetAddress.getByName("2606:4700:4700::1111")));
    }

    /**
     * IANA's special-purpose blocks that aren't globally reachable, each beside a neighbour just
     * outside it that is, so a block drawn one bit too wide fails as well as one left out.
     */
    @Test public void specialPurposeBlocksAreNotMediaDestinationsAndTheirNeighboursAre()
            throws Exception {
        for (String address : new String[]{
                "198.18.0.1", "198.19.255.255", "192.0.2.5", "198.51.100.7", "203.0.113.9",
                "192.0.0.8", "192.88.99.1", "100.64.0.1", "100.127.255.254", "240.0.0.1",
                "2001:db8::1", "2001::1", "2001:1ff:ffff::1", "2002:c000:204::1", "3fff::1",
                "3fff:fff::1", "5f00::1", "100::1", "64:ff9b:1::1", "64:ff9b::a00:1", "64:ff9b::c612:1"
        }) {
            assertFalse(address, MediaTransport.isPublicAddress(InetAddress.getByName(address)));
        }
        for (String address : new String[]{
                "198.17.255.255", "198.20.0.1", "100.63.255.255", "100.128.0.1", "192.0.1.1",
                "192.0.3.1", "223.255.255.254", "2001:200::1", "2001:4860:4860::8888", "2001:db9::1",
                "2003::1", "3fff:1000::1", "2620:4f:8000::1", "64:ff9b::808:808"
        }) {
            assertTrue(address, MediaTransport.isPublicAddress(InetAddress.getByName(address)));
        }
        // An IPv4-mapped address that stayed IPv6 is judged as the IPv4 address it names.
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = (byte) 198;
        mapped[13] = 18;
        mapped[15] = 1;
        assertFalse(MediaTransport.isPublicAddress(Inet6Address.getByAddress(null, mapped, -1)));
        mapped[12] = 8;
        mapped[13] = 8;
        mapped[14] = 8;
        mapped[15] = 8;
        assertTrue(MediaTransport.isPublicAddress(Inet6Address.getByAddress(null, mapped, -1)));
    }

    @Test public void aBenchmarkingAnswerBesideAPublicOneRefusesTheHostname() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaTransport.Client client = new MediaTransport.Client(host -> new InetAddress[]{
                InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8}),
                InetAddress.getByAddress(host, new byte[]{(byte) 198, 18, 0, 1})
        }, (url, address) -> {
            opened.incrementAndGet();
            return new FakeConnection(url, HTTP_OK, null, PNG);
        });

        assertThrows(IOException.class, () -> client.open(
                "https://v16.tiktokcdn.com/media", MediaBudget.deadline(),
                1000, 1000, null, false));
        assertEquals(0, opened.get());
    }

    @Test public void aRedirectIntoADocumentationRangeStopsBeforeOpeningThatHop() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaTransport.Client client = new MediaTransport.Client(host -> new InetAddress[]{
                "docs.example".equals(host)
                        ? InetAddress.getByName("2001:db8::7")
                        : InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})
        }, (url, address) -> {
            opened.incrementAndGet();
            return new FakeConnection(url, HTTP_MOVED_TEMP, "https://docs.example/media", new byte[0]);
        });

        assertThrows(IOException.class, () -> client.open(
                "https://v16.tiktokcdn.com/start", MediaBudget.deadline(),
                1000, 1000, null, false));
        assertEquals(1, opened.get());
    }

    @Test public void aRebindingResolverCannotChangeTheCheckedPeerAtConnect() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        FakeConnection response = new FakeConnection(
                new URL("https://cdn.example/video"), HTTP_OK, null, PNG);
        MediaTransport.Client client = new MediaTransport.Client(host -> {
            byte[] address = resolutions.incrementAndGet() == 1
                    ? new byte[]{8, 8, 8, 8}
                    : new byte[]{127, 0, 0, 1};
            return new InetAddress[]{InetAddress.getByAddress(host, address)};
        }, (url, address) -> {
            opened.incrementAndGet();
            assertEquals("8.8.8.8", address.getHostAddress());
            assertEquals("cdn.example", url.getHost());
            return response;
        });

        try (MediaTransport.Response result = client.open(
                "https://cdn.example/video", MediaBudget.deadline(),
                1000, 1000, null, false)) {
            assertEquals(HTTP_OK, result.statusCode);
        }
        assertEquals("connecting must not resolve the hostname again", 1, resolutions.get());
        assertEquals(1, opened.get());
        assertEquals(1, response.responseReads);
        assertTrue("the checked connection was not released", response.disconnected);
    }

    @Test public void onePrivateAnswerRefusesTheWholeHostname() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaTransport.Client client = new MediaTransport.Client(host -> new InetAddress[]{
                InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8}),
                InetAddress.getByAddress(host, new byte[]{10, 0, 0, 8})
        }, (url, address) -> {
            opened.incrementAndGet();
            return new FakeConnection(url, HTTP_OK, null, PNG);
        });

        assertThrows(IOException.class, () -> client.open(
                "https://v16.tiktokcdn.com/media", MediaBudget.deadline(),
                1000, 1000, null, false));
        assertEquals(0, opened.get());
    }

    @Test public void fallbackUsesTheCheckedSnapshotEvenIfTheResolverArrayChanges() throws Exception {
        InetAddress first = InetAddress.getByName("2606:4700:4700::1111");
        InetAddress second = InetAddress.getByName("8.8.8.8");
        InetAddress[] answers = {first, second};
        AtomicInteger opened = new AtomicInteger();
        MediaTransport.Client client = new MediaTransport.Client(host -> answers, (url, address) -> {
            if (opened.incrementAndGet() == 1) {
                assertEquals(first, address);
                answers[1] = InetAddress.getLoopbackAddress();
                throw new java.net.ConnectException("IPv6 unavailable");
            }
            assertEquals(second, address);
            return new FakeConnection(url, HTTP_OK, null, PNG);
        });
        try (MediaTransport.Response result = client.open("https://cdn.example/video",
                MediaBudget.deadline(), 1000, 1000, null, false)) {
            assertEquals(HTTP_OK, result.statusCode);
        }
        assertEquals(2, opened.get());
    }

    @Test public void failedPeersAreReleasedAndTheRetryableFailureIsPreserved() throws Exception {
        AtomicInteger disconnected = new AtomicInteger();
        MediaTransport.Client client = new MediaTransport.Client(host -> new InetAddress[]{
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1")
        }, (url, address) -> new HttpURLConnection(url) {
            @Override public int getResponseCode() throws IOException {
                throw new java.net.SocketTimeoutException("unreachable " + address.getHostAddress());
            }
            @Override public void disconnect() { disconnected.incrementAndGet(); }
            @Override public void connect() { }
            @Override public boolean usingProxy() { return false; }
        });
        IOException error = assertThrows(IOException.class, () -> client.open(
                "https://cdn.example/video", MediaBudget.deadline(), 1000, 1000, null, false));
        assertTrue(MediaBudget.isRetryableTransport(error));
        assertEquals(1, error.getSuppressed().length);
        assertEquals(2, disconnected.get());
    }

    @Test public void cancellationDoesNotTryAnotherPeer() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaTransport.Client client = new MediaTransport.Client(host -> new InetAddress[]{
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1")
        }, (url, address) -> {
            opened.incrementAndGet();
            throw new MediaBudget.StopException("deadline", false);
        });
        assertThrows(MediaBudget.StopException.class, () -> client.open("https://cdn.example/video",
                MediaBudget.deadline(), 1000, 1000, null, false));
        assertEquals(1, opened.get());
    }

    @Test public void eachRedirectGetsItsOwnCheckedPeerAndOriginalIdentity() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaTransport.Client client = new MediaTransport.Client(host -> new InetAddress[]{
                InetAddress.getByName(host.equals("first.example") ? "8.8.8.8" : "1.1.1.1")
        }, (url, address) -> {
            opened.incrementAndGet();
            boolean first = url.getHost().equals("first.example");
            assertEquals(first ? "8.8.8.8" : "1.1.1.1", address.getHostAddress());
            return new FakeConnection(url, first ? HTTP_MOVED_TEMP : HTTP_OK,
                    first ? "https://second.example/media" : null, PNG);
        });
        try (MediaTransport.Response result = client.open("https://first.example/start",
                MediaBudget.deadline(), 1000, 1000, null, false)) {
            assertEquals("second.example", result.url.getHost());
            assertEquals(HTTP_OK, result.statusCode);
        }
        assertEquals(2, opened.get());
    }

    @Test public void pinnedUrlsKeepTheSignedPathAndAuthenticateTheOriginalHost() throws Exception {
        withHttpsHandler(url -> {
            HttpsURLConnection connection = (HttpsURLConnection) response(url, false);
            connection.setHostnameVerifier((host, session) -> "cdn.example".equals(host));
            return connection;
        }, () -> {
            for (String address : new String[]{"8.8.8.8", "2606:4700:4700::1111"}) {
                URL original = new URL("https://cdn.example:8443/a%2Fb.jpg?signature=x%2By&n=2");
                HttpsURLConnection connection = PinnedMediaConnection.open(
                        original, InetAddress.getByName(address));
                assertEquals(InetAddress.getByName(address),
                        InetAddress.getByName(connection.getURL().getHost()));
                assertEquals(original.getFile(), connection.getURL().getFile());
                assertEquals(8443, connection.getURL().getPort());
                assertEquals("cdn.example:8443", connection.getRequestProperty("Host"));
                assertTrue(connection.getHostnameVerifier().verify(address, null));
                assertTrue(connection.getSSLSocketFactory() instanceof PinnedMediaConnection.PinnedFactory);
            }
        });
    }

    @Test public void aPublicTikTokCdnRedirectStillDownloads() throws Exception {
        Map<String, FakeConnection> connections = new LinkedHashMap<>();
        MediaTransport.Client client = publicClient(url -> {
            FakeConnection connection;
            if ("/start".equals(url.getPath())) {
                connection = new FakeConnection(url, HTTP_MOVED_TEMP,
                        "https://v16-webapp-prime.tiktokcdn.com/media", new byte[0]);
            } else {
                connection = new FakeConnection(url, HTTP_OK, null, PNG);
            }
            connections.put(url.toString(), connection);
            return connection;
        });

        File target = File.createTempFile("public-cdn-redirect", ".tmp");
        try {
            assertEquals("png", RemoteMedia.fetch(
                    List.of("https://v16.tiktokcdn.com/start"), target,
                    RemoteMedia.Kind.IMAGE, client));
            assertArrayEquals(PNG, Files.readAllBytes(target.toPath()));
            assertEquals(2, connections.size());
            for (FakeConnection connection : connections.values()) {
                assertFalse("a redirect was delegated to the platform",
                        connection.getInstanceFollowRedirects());
                assertTrue("a completed hop was not released", connection.disconnected);
            }
        } finally {
            target.delete();
        }
    }

    @Test public void redirectLoopsStopBeforeTheSameEndpointOpensAgain() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaTransport.Client client = publicClient(url -> {
            opened.incrementAndGet();
            String location = "/a".equals(url.getPath()) ? "/b" : "/a";
            return new FakeConnection(url, HTTP_MOVED_TEMP, location, new byte[0]);
        });

        assertThrows(IOException.class, () -> client.open(
                "https://v16.tiktokcdn.com/a", MediaBudget.deadline(),
                1000, 1000, null, false));
        assertEquals("the loop reopened an endpoint already visited", 2, opened.get());
    }

    @Test public void redirectChainsHaveOneSharedFiveHopLimit() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaTransport.Client client = publicClient(url -> {
            opened.incrementAndGet();
            int step = Integer.parseInt(url.getPath().substring(1));
            return new FakeConnection(url, HTTP_MOVED_TEMP,
                    "/" + (step + 1), new byte[0]);
        });

        assertThrows(IOException.class, () -> client.open(
                "https://v16.tiktokcdn.com/0", MediaBudget.deadline(),
                1000, 1000, null, false));
        assertEquals(MediaTransport.MAX_REDIRECTS + 1, opened.get());
    }

    @Test public void aRedirectToLoopbackStopsBeforeOpeningThatHop() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaTransport.Client client = new MediaTransport.Client(host -> {
            byte[] address = "private.example".equals(host)
                    ? new byte[]{127, 0, 0, 1}
                    : new byte[]{8, 8, 8, 8};
            return new InetAddress[]{InetAddress.getByAddress(host, address)};
        }, (url, checkedAddress) -> {
            opened.incrementAndGet();
            return new FakeConnection(url, HTTP_MOVED_TEMP,
                    "https://private.example/metadata", new byte[0]);
        });

        assertThrows(IOException.class, () -> client.open(
                "https://v16.tiktokcdn.com/start", MediaBudget.deadline(),
                1000, 1000, null, false));
        assertEquals(1, opened.get());
    }

    @Test public void aRedirectCannotIntroduceUserInformation() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaTransport.Client client = publicClient(url -> {
            opened.incrementAndGet();
            return new FakeConnection(url, HTTP_MOVED_TEMP,
                    "https://reader:secret@v16.tiktokcdn.com/media", new byte[0]);
        });

        assertThrows(IOException.class, () -> client.open(
                "https://v16.tiktokcdn.com/start", MediaBudget.deadline(),
                1000, 1000, null, false));
        assertEquals(1, opened.get());
    }

    @Test public void aBrokenMediaBodyLeavesNoPartialFile() throws Exception {
        byte[] prefix = java.util.Arrays.copyOf(PNG, 24);
        MediaTransport.Client client = publicClient(url -> new FakeConnection(
                url, HTTP_OK, null, failingAfter(prefix)));
        File target = File.createTempFile("partial-media", ".tmp");
        try {
            assertThrows(IOException.class, () -> RemoteMedia.fetch(
                    List.of("https://v16.tiktokcdn.com/media"), target,
                    RemoteMedia.Kind.IMAGE, client));
            assertFalse("a broken response left media bytes on disk", target.exists());
        } finally {
            target.delete();
        }
    }

    @Test public void aChunkedMediaBodyStopsBeforeUsingTheFreeSpaceFloor() throws Exception {
        assertUnknownLengthStopsBeforeUsingTheFreeSpaceFloor(null);
    }

    @Test public void aMalformedMediaLengthStopsBeforeUsingTheFreeSpaceFloor() throws Exception {
        assertUnknownLengthStopsBeforeUsingTheFreeSpaceFloor("not-a-length");
    }

    private static void assertUnknownLengthStopsBeforeUsingTheFreeSpaceFloor(String lengthHeader)
            throws Exception {
        byte[] body = new byte[2 * 1024 * 1024];
        System.arraycopy(PNG, 0, body, 0, PNG.length);
        MediaTransport.Client client = publicClient(url -> new FakeConnection(
                url, HTTP_OK, null, lengthHeader, body));
        File realTarget = File.createTempFile("shrinking-media", ".tmp");
        AtomicInteger spaceReads = new AtomicInteger();
        AtomicLong freeAtRejection = new AtomicLong(Long.MAX_VALUE);
        long floor = MediaBudget.MIN_FREE_BYTES + MediaBudget.PUBLISH_OVERHEAD_BYTES;
        File shrinkingDirectory = new File(realTarget.getParentFile(), "shrinking-volume") {
            @Override public boolean exists() {
                return true;
            }

            @Override public long getUsableSpace() {
                int read = spaceReads.getAndIncrement();
                if (read == 0) {
                    return MediaBudget.UNKNOWN_TRANSFER_RESERVATION_BYTES + floor;
                }
                long remaining = floor + MediaBudget.STREAM_SPACE_CHECK_BYTES
                        - (read == 1 ? 0 : 1);
                freeAtRejection.set(remaining);
                return remaining;
            }
        };
        File target = new File(realTarget.getPath()) {
            @Override public File getParentFile() {
                return shrinkingDirectory;
            }
        };
        try {
            assertThrows(IOException.class, () -> RemoteMedia.fetch(
                    List.of("https://v16.tiktokcdn.com/chunked"), target,
                    RemoteMedia.Kind.IMAGE, client));
            assertTrue("the copy never rechecked free space while streaming",
                    spaceReads.get() >= 3);
            assertTrue("the copy crossed the 32 MB plus publish reserve",
                    freeAtRejection.get() >= floor);
            assertFalse("a refused chunked download left partial media", target.exists());
        } finally {
            realTarget.delete();
        }
    }

    @Test public void aBrokenStickerBodyLeavesNoPartialFile() throws Exception {
        byte[] prefix = java.util.Arrays.copyOf(PNG, 24);
        MediaTransport.Client client = publicClient(url -> new FakeConnection(
                url, HTTP_OK, null, failingAfter(prefix)));
        File target = File.createTempFile(
                "partial-sticker", ".tmp", RuntimeEnvironment.getApplication().getCacheDir());
        try {
            assertThrows(IOException.class, () -> StickerGallerySaver.downloadSticker(
                    "https://v16.tiktokcdn.com/sticker", target,
                    RuntimeEnvironment.getApplication(), MediaBudget.deadline(), client));
            assertFalse("a broken sticker response left bytes on disk", target.exists());
        } finally {
            target.delete();
        }
    }

    private static HttpURLConnection response(URL url, boolean automaticRedirect) {
        return new HttpsURLConnection(url) {
            @Override public String getCipherSuite() { return "fixture"; }
            @Override public java.security.cert.Certificate[] getLocalCertificates() { return null; }
            @Override public java.security.cert.Certificate[] getServerCertificates() { return null; }
            @Override public int getResponseCode() {
                return automaticRedirect && !getInstanceFollowRedirects()
                        ? HTTP_MOVED_TEMP : HTTP_OK;
            }

            @Override public String getHeaderField(String name) {
                if (automaticRedirect && "Location".equalsIgnoreCase(name)) {
                    return "http://127.0.0.1/private";
                }
                return "Content-Length".equalsIgnoreCase(name)
                        ? String.valueOf(PNG.length) : null;
            }

            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(PNG);
            }

            @Override public void connect() { }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
        };
    }

    private interface ConnectionFactory {
        URLConnection open(URL url) throws IOException;
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static MediaTransport.Client publicClient(ConnectionFactory opener) {
        return new MediaTransport.Client(host -> new InetAddress[]{
                InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})
        }, (url, address) -> opener.open(url));
    }

    private static InputStream failingAfter(byte[] prefix) {
        return new InputStream() {
            private int offset;

            @Override public int read() throws IOException {
                if (offset >= prefix.length) throw new IOException("fixture stream broke");
                return prefix[offset++] & 255;
            }
        };
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int responseCode;
        private final String location;
        private final String contentLength;
        private final InputStream input;
        int responseReads;
        boolean disconnected;

        FakeConnection(URL url, int responseCode, String location, byte[] body) {
            this(url, responseCode, location, null, new ByteArrayInputStream(body));
        }

        FakeConnection(URL url, int responseCode, String location, String contentLength,
                byte[] body) {
            this(url, responseCode, location, contentLength, new ByteArrayInputStream(body));
        }

        FakeConnection(URL url, int responseCode, String location, InputStream input) {
            this(url, responseCode, location, null, input);
        }

        FakeConnection(URL url, int responseCode, String location, String contentLength,
                InputStream input) {
            super(url);
            this.responseCode = responseCode;
            this.location = location;
            this.contentLength = contentLength;
            this.input = input;
        }

        @Override public int getResponseCode() {
            responseReads++;
            return responseCode;
        }

        @Override public String getHeaderField(String name) {
            if ("Location".equalsIgnoreCase(name)) return location;
            if ("Content-Length".equalsIgnoreCase(name)) return contentLength;
            return null;
        }

        @Override public String getContentType() {
            return "image/png";
        }

        @Override public InputStream getInputStream() {
            return input;
        }

        @Override public void connect() { }

        @Override public void disconnect() {
            disconnected = true;
        }

        @Override public boolean usingProxy() {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void withHttpsHandler(ConnectionFactory factory, CheckedRunnable action)
            throws Exception {
        // Make the JDK install its ordinary HTTPS handler before replacing the cache entry.
        new URL("https://8.8.8.8/");
        Hashtable<String, URLStreamHandler> handlers =
                ReflectionHelpers.getStaticField(URL.class, "handlers");
        URLStreamHandler previous = handlers.get("https");
        handlers.put("https", new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) throws IOException {
                return factory.open(url);
            }
            @Override protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
                assertEquals(Proxy.NO_PROXY, proxy);
                return factory.open(url);
            }
        });
        try {
            action.run();
        } finally {
            handlers.put("https", previous);
        }
    }

    private static final class Address extends UrlModel {
        private final List<String> urls;

        Address(String url) {
            urls = List.of(url);
        }

        @Override public List<String> getUrlList() {
            return urls;
        }
    }

    private static final class Gear {
        public final String gearName = "1080p";
        public final int bitRate = 1;
        public final UrlModel playAddr;

        Gear(String url) {
            playAddr = new Address(url);
        }
    }
}

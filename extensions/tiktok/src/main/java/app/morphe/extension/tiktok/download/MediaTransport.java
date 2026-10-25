/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** HTTPS-only media transport with destination validation at every redirect hop. */
final class MediaTransport {
    /** Five redirects covers TikTok's ordinary CDN handoff without permitting an endless chain. */
    static final int MAX_REDIRECTS = 5;
    static final Client DEFAULT = new Client(InetAddress::getAllByName, PinnedMediaConnection::open);

    private MediaTransport() { }

    static boolean hasAllowedShape(String value) {
        try {
            target(value);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    static Response open(
            String value,
            MediaBudget.Deadline deadline,
            int connectTimeoutMs,
            int readTimeoutMs,
            String userAgent,
            boolean identityEncoding
    ) throws IOException {
        return DEFAULT.open(value, deadline, connectTimeoutMs, readTimeoutMs,
                userAgent, identityEncoding);
    }

    interface Resolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    interface ConnectionOpener {
        URLConnection open(URL url, InetAddress address) throws IOException;
    }

    static final class Client {
        private final Resolver resolver;
        private final ConnectionOpener opener;

        Client(Resolver resolver, ConnectionOpener opener) {
            this.resolver = resolver;
            this.opener = opener;
        }

        Response open(
                String value,
                MediaBudget.Deadline deadline,
                int connectTimeoutMs,
                int readTimeoutMs,
                String userAgent,
                boolean identityEncoding
        ) throws IOException {
            if (deadline == null) deadline = MediaBudget.deadline();
            MediaBudget.awaitRetry(deadline);
            Target current = target(value);
            Set<String> visited = new HashSet<>();
            int redirects = 0;
            while (true) {
                MediaBudget.awaitRetry(deadline);
                if (!visited.add(current.identity)) {
                    throw new IOException("Media redirect loop refused");
                }

                InetAddress[] addresses = publicAddresses(current.host, resolver);
                Response response = connect(current, addresses, deadline,
                        connectTimeoutMs, readTimeoutMs, userAgent, identityEncoding);
                HttpURLConnection connection = response.connection;
                boolean returned = false;
                try {
                    if (!isRedirect(response.statusCode)) {
                        returned = true;
                        return response;
                    }

                    if (redirects >= MAX_REDIRECTS) {
                        throw new IOException("Media redirect limit exceeded");
                    }
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("Media redirect has no destination");
                    }
                    current = redirect(current.uri, location);
                    redirects++;
                } finally {
                    if (!returned) response.close();
                }
            }
        }

        /** Try only the checked snapshot, including IPv4 after an unreachable IPv6 answer. */
        private Response connect(Target target, InetAddress[] addresses,
                MediaBudget.Deadline deadline, int connectTimeoutMs, int readTimeoutMs,
                String userAgent, boolean identityEncoding) throws IOException {
            IOException failed = null;
            for (InetAddress address : addresses) {
                MediaBudget.check(deadline);
                HttpURLConnection connection = null;
                boolean connected = false;
                try {
                    URLConnection opened = opener.open(target.url, address);
                    if (!(opened instanceof HttpURLConnection)) {
                        throw new IOException("Media transport is not HTTP");
                    }
                    connection = (HttpURLConnection) opened;
                    connection.setInstanceFollowRedirects(false);
                    connection.setUseCaches(false);
                    connection.setConnectTimeout(MediaBudget.timeoutMillis(deadline, connectTimeoutMs));
                    connection.setReadTimeout(MediaBudget.timeoutMillis(deadline, readTimeoutMs));
                    if (identityEncoding) connection.setRequestProperty("Accept-Encoding", "identity");
                    if (userAgent != null && !userAgent.isEmpty()) {
                        connection.setRequestProperty("User-Agent", userAgent);
                    }
                    MediaBudget.check(deadline);
                    int statusCode = connection.getResponseCode();
                    MediaBudget.recordRetryAfter(connection.getHeaderField("Retry-After"), deadline);
                    connected = true;
                    return new Response(connection, statusCode, target.url);
                } catch (MediaBudget.StopException stop) {
                    throw stop;
                } catch (IOException error) {
                    if (failed == null) failed = error;
                    else if (failed != error) failed.addSuppressed(error);
                } finally {
                    if (!connected && connection != null) connection.disconnect();
                }
            }
            if (failed != null) throw failed;
            throw new IOException("No checked media address is available");
        }
    }

    static final class Response implements AutoCloseable {
        private final HttpURLConnection connection;
        private boolean closed;
        final int statusCode;
        final URL url;

        private Response(HttpURLConnection connection, int statusCode, URL url) {
            this.connection = connection;
            this.statusCode = statusCode;
            this.url = url;
        }

        String header(String name) {
            return connection.getHeaderField(name);
        }

        InputStream inputStream() throws IOException {
            return connection.getInputStream();
        }

        String contentType() {
            return connection.getContentType();
        }

        @Override public void close() {
            if (!closed) {
                closed = true;
                connection.disconnect();
            }
        }
    }

    /**
     * The IPv4 blocks IANA's special-purpose registry marks as not globally reachable, as
     * {first address, prefix length}, with the deprecated 6to4 relay block and everything from
     * 224.0.0.0 up (multicast, reserved, broadcast). None can be a public CDN endpoint, and each
     * one accepted widens the local-network boundary. Checked 2026-09-27 against
     * https://www.iana.org/assignments/iana-ipv4-special-registry.
     */
    private static final int[][] NOT_GLOBAL_V4 = {
            {0x00000000, 8},    // this network
            {0x0A000000, 8},    // private
            {0x64400000, 10},   // shared address space, carrier NAT
            {0x7F000000, 8},    // loopback
            {0xA9FE0000, 16},   // link-local
            {0xAC100000, 12},   // private
            {0xC0000000, 24},   // IETF protocol assignments
            {0xC0000200, 24},   // documentation, TEST-NET-1
            {0xC0586300, 24},   // deprecated 6to4 relay anycast
            {0xC0A80000, 16},   // private
            {0xC6120000, 15},   // benchmarking
            {0xC6336400, 24},   // documentation, TEST-NET-2
            {0xCB007100, 24},   // documentation, TEST-NET-3
            {0xE0000000, 3},    // multicast, reserved and broadcast
    };

    static boolean isPublicAddress(InetAddress address) {
        if (address == null) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isGlobalV4(ipv4At(bytes, 0));
        if (bytes.length == 16) return isGlobalV6(bytes);
        return false;
    }

    private static boolean isGlobalV4(int address) {
        for (int[] block : NOT_GLOBAL_V4) {
            int mask = -1 << (32 - block[1]);
            if ((address & mask) == (block[0] & mask)) return false;
        }
        return true;
    }

    /**
     * Global unicast (2000::/3) only, which leaves out the unspecified and loopback addresses, the
     * discard-only, SRv6, unique-local, link-local and multicast blocks at once, and then the
     * special blocks inside it. An IPv4 address carried in IPv6 is judged as that IPv4 address.
     * Checked 2026-09-27 against https://www.iana.org/assignments/iana-ipv6-special-registry.
     */
    private static boolean isGlobalV6(byte[] bytes) {
        boolean zeroTo80 = true;
        for (int i = 0; i < 10; i++) zeroTo80 &= bytes[i] == 0;
        // IPv4-mapped, ::ffff:0:0/96.
        if (zeroTo80 && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
            return isGlobalV4(ipv4At(bytes, 12));
        }
        // The NAT64 well-known prefix, 64:ff9b::/96, which DNS64 answers with on IPv6-only mobile
        // networks for a host that has only an IPv4 address: as global as the address inside it.
        if (bytes[0] == 0 && bytes[1] == 0x64 && bytes[2] == (byte) 0xff && bytes[3] == (byte) 0x9b) {
            boolean zeroTo96 = true;
            for (int i = 4; i < 12; i++) zeroTo96 &= bytes[i] == 0;
            if (zeroTo96) return isGlobalV4(ipv4At(bytes, 12));
        }
        if ((bytes[0] & 0xe0) != 0x20) return false;
        int first = ((bytes[0] & 255) << 8) | (bytes[1] & 255);
        int second = ((bytes[2] & 255) << 8) | (bytes[3] & 255);
        if (first == 0x2001 && (second & 0xfe00) == 0) return false;   // IETF protocol assignments, Teredo among them
        if (first == 0x2001 && second == 0x0db8) return false;         // documentation
        if (first == 0x2002) return false;                             // 6to4
        if (first == 0x3fff && (second & 0xf000) == 0) return false;   // documentation, 3fff::/20
        return true;
    }

    private static int ipv4At(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }

    private static InetAddress[] publicAddresses(String host, Resolver resolver) throws IOException {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (RuntimeException error) {
            throw new IOException("Media hostname could not be resolved safely", error);
        }
        if (addresses == null || addresses.length == 0) {
            throw new IOException("Media hostname has no address");
        }
        // Validate the same snapshot that connection attempts will consume.
        addresses = addresses.clone();
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new IOException("Media URL resolves to a non-public address");
            }
        }
        return addresses;
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 300 || statusCode == 301 || statusCode == 302
                || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private static Target redirect(URI base, String location) throws IOException {
        try {
            return target(base.resolve(new URI(location.trim())).toString());
        } catch (IllegalArgumentException | URISyntaxException error) {
            throw new IOException("Media redirect destination is invalid", error);
        }
    }

    private static Target target(String value) throws IOException {
        if (value == null) throw new IOException("Media URL is missing");
        try {
            URI uri = new URI(value.trim()).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Media URL must use HTTPS");
            }
            if (uri.getRawUserInfo() != null) {
                throw new IOException("Media URL cannot contain user information");
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw new IOException("Media URL has no hostname");
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort() < 0 ? 443 : uri.getPort();
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            String identity = host + ":" + port + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            return new Target(uri, uri.toURL(), host, identity);
        } catch (URISyntaxException | IllegalArgumentException error) {
            throw new IOException("Media URL is invalid", error);
        }
    }

    private static final class Target {
        final URI uri;
        final URL url;
        final String host;
        final String identity;

        Target(URI uri, URL url, String host, String identity) {
            this.uri = uri;
            this.url = url;
            this.host = host;
            this.identity = identity;
        }
    }
}

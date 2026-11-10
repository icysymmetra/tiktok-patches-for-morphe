package app.morphe.extension.tiktok.download;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;

/** Deterministic public-network stand-ins shared by media download tests. */
final class MediaTransportFixtures {
    private MediaTransportFixtures() { }

    /** Each test owns its thread's clock and restores the enclosing job on exit. */
    static final class RetryClock implements MediaBudget.Clock, AutoCloseable {
        private final ThreadLocal<MediaBudget.Deadline> slot;
        private final MediaBudget.Deadline previous;
        final MediaBudget.Deadline deadline;
        long nanos;
        long wall = 784111777000L; // 1994-11-06 08:49:37 UTC, the RFC HTTP-date example.
        int sleeps;
        Runnable onSleep = () -> { };

        @SuppressWarnings("unchecked") RetryClock(long budgetMillis) throws Exception {
            var field = MediaBudget.class.getDeclaredField("CURRENT_DEADLINE");
            field.setAccessible(true);
            slot = (ThreadLocal<MediaBudget.Deadline>) field.get(null);
            previous = slot.get();
            deadline = new MediaBudget.Deadline(budgetMillis * 1_000_000L, this);
            slot.set(deadline);
        }

        public long nanoTime() { return nanos; }
        public long wallMillis() { return wall; }
        public void sleep(long millis) {
            sleeps++;
            nanos += millis * 1_000_000L;
            onSleep.run();
        }
        public void close() {
            if (previous == null) slot.remove(); else slot.set(previous);
        }
    }

    interface ConnectionFactory {
        URLConnection open(URL url) throws IOException;
    }

    static MediaTransport.Client publicClient(ConnectionFactory opener) {
        return new MediaTransport.Client(host -> new InetAddress[]{
                InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})
        }, (url, address) -> opener.open(url));
    }

    static HttpURLConnection response(URL url, int status, byte[] body) {
        return response(url, status, body, null, Map.of());
    }

    static HttpURLConnection response(
            URL url,
            int status,
            byte[] body,
            String contentType,
            Map<String, String> headers
    ) {
        return new HttpURLConnection(url) {
            @Override public int getResponseCode() {
                return status;
            }

            @Override public String getHeaderField(String name) {
                if ("Content-Length".equalsIgnoreCase(name)) {
                    return String.valueOf(body.length);
                }
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey().equalsIgnoreCase(name)) return header.getValue();
                }
                return null;
            }

            @Override public String getContentType() {
                return contentType;
            }

            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(body);
            }

            @Override public void connect() { }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
        };
    }
}

package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AdSkipMediaFetchTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private static final List<String> URLS = List.of("https://8.8.8.8/first", "https://8.8.8.8/second");
    private static final byte[] MP4 = new byte[32768];
    static { MP4[4] = 'f'; MP4[5] = 't'; MP4[6] = 'y'; MP4[7] = 'p'; }

    private static HttpURLConnection response(URL url, InputStream input, Runnable closed) {
        return new HttpURLConnection(url) {
            @Override public int getResponseCode() { return 200; }
            @Override public InputStream getInputStream() { return input; }
            @Override public String getHeaderField(String name) {
                return "Content-Length".equals(name) ? String.valueOf(MP4.length) : null;
            }
            @Override public void disconnect() { closed.run(); }
            @Override public void connect() { }
            @Override public boolean usingProxy() { return false; }
        };
    }

    @Test public void preCancelledDoesNotOpenAnyMirror() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean(true);
        AtomicInteger opens = new AtomicInteger();
        File target = new File(files.getRoot(), "never.media");
        var stop = assertThrows(MediaBudget.StopException.class, () -> AdSkipMediaFetch.fetch(URLS, target, cancel,
                (url, address) -> { opens.incrementAndGet(); throw new IOException("unexpected"); }));
        assertEquals(MediaBudget.StopException.Reason.CANCELLED, stop.reason);
        assertEquals(0, opens.get());
        assertFalse(target.exists());
    }

    @Test public void cancellingFinalReadDeletesPartialAndDoesNotTryAnotherMirror() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicInteger opens = new AtomicInteger();
        File target = new File(files.getRoot(), "partial.media");
        InputStream input = new ByteArrayInputStream(MP4) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                if (available() == 0) cancel.set(true);
                return count;
            }
        };
        var stop = assertThrows(MediaBudget.StopException.class, () -> AdSkipMediaFetch.fetch(URLS, target, cancel,
                (url, address) -> { opens.incrementAndGet(); return response(url, input, () -> { }); }));
        assertEquals(MediaBudget.StopException.Reason.CANCELLED, stop.reason);
        assertEquals(1, opens.get());
        assertFalse(target.exists());
        // A new scan must not inherit the previous job's cancellation.
        File fresh = new File(files.getRoot(), "fresh.media");
        AdSkipMediaFetch.fetch(URLS, fresh, new AtomicBoolean(),
                (url, address) -> response(url, new ByteArrayInputStream(MP4), () -> { }));
        assertEquals(MP4.length, fresh.length());
    }

    @Test public void cancellationDisconnectsAStalledReadAndReleasesWorker() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean();
        CountDownLatch reading = new CountDownLatch(1), disconnected = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File target = new File(files.getRoot(), "stalled.media");
        InputStream input = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("use bulk read"); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                reading.countDown();
                try {
                    if (!disconnected.await(5, TimeUnit.SECONDS)) throw new IOException("not disconnected");
                } catch (InterruptedException interrupted) { throw new IOException(interrupted); }
                throw new IOException("socket closed");
            }
        };
        Thread worker = new Thread(() -> {
            try {
                AdSkipMediaFetch.fetch(URLS, target, cancel, (url, address) -> {
                    opens.incrementAndGet();
                    return response(url, input, disconnected::countDown);
                });
            } catch (Throwable thrown) { failure.set(thrown); }
        });
        worker.start();
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            cancel.set(true);
            worker.join(2000);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertFalse("cancelled download still occupies worker", worker.isAlive());
            assertTrue(failure.get() instanceof MediaBudget.StopException);
            assertEquals(1, opens.get());
            assertFalse(target.exists());
            System.out.println("Adskip simulated stalled-read cancellation: " + elapsedMs + " ms; requests=1; fallback=0");
        } finally {
            cancel.set(true);
            disconnected.countDown();
            worker.join(6000);
        }
    }
}

package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Environment;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Exercises the production retry loops with elapsed time, without real network traffic or sleeps. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MediaRetryAfterTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    @Rule public final TemporaryFolder files = new TemporaryFolder();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10,
            0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] SRT = "1\n00:00:00,000 --> 00:00:01,000\nCaption\n".getBytes(StandardCharsets.UTF_8);
    private static final List<String> MIRRORS = List.of("https://8.8.8.8/first", "https://8.8.8.8/second");

    private void fetch(int kind, File target, MediaTransport.Client transport) throws IOException {
        if (kind == 0) RemoteMedia.fetch(MIRRORS, target, RemoteMedia.Kind.IMAGE, transport);
        else if (kind == 1) SubtitleDownloads.fetch(MIRRORS, "srt", transport);
        else StickerGallerySaver.downloadSticker(MIRRORS.get(0), target,
                RuntimeEnvironment.getApplication(), MediaBudget.deadline(), transport);
    }

    private static HttpURLConnection response(URL url, int status, String delay, byte[] body,
                                               AtomicInteger closed) {
        return new HttpURLConnection(url) {
            @Override public int getResponseCode() { return status; }
            @Override public String getHeaderField(String name) {
                if ("Location".equalsIgnoreCase(name) && status >= 300 && status < 400) return "/redirected";
                if ("Retry-After".equalsIgnoreCase(name)) return delay;
                if ("Content-Length".equalsIgnoreCase(name)) return String.valueOf(body.length);
                return null;
            }
            @Override public String getContentType() { return "image/png"; }
            @Override public ByteArrayInputStream getInputStream() { return new ByteArrayInputStream(body); }
            @Override public void connect() { }
            @Override public void disconnect() { closed.incrementAndGet(); }
            @Override public boolean usingProxy() { return false; }
        };
    }

    @Test public void allFetchersCloseBeforeWaitingTheWholeMinuteOrHttpDate() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            for (String delay : List.of("60", "Sun, 06 Nov 1994 08:50:37 GMT",
                    "Sunday, 06-Nov-94 08:50:37 GMT", "Sun Nov  6 08:50:37 1994")) {
                try (var clock = new MediaTransportFixtures.RetryClock(120_000)) {
                    AtomicInteger requests = new AtomicInteger(), closed = new AtomicInteger();
                    List<Long> times = new ArrayList<>();
                    byte[] body = kind == 1 ? SRT : PNG;
                    MediaTransport.Client transport = MediaTransportFixtures.publicClient(url -> {
                        times.add(clock.nanos);
                        return response(url, requests.incrementAndGet() == 1 ? 429 : 200, delay, body, closed);
                    });
                    clock.onSleep = () -> assertEquals("response held during server wait", 1, closed.get());
                    fetch(kind, files.newFile(), transport);
                    assertEquals(List.of(0L, 60_000_000_000L), times);
                    assertEquals("each connection is released exactly once", 2, closed.get());
                }
            }
        }
    }

    @Test public void theLastAttemptWaitsBeforeAnAlternateMirror() throws Exception {
        for (int kind = 0; kind < 2; kind++) {
            try (var clock = new MediaTransportFixtures.RetryClock(120_000)) {
                List<String> urls = new ArrayList<>();
                List<Long> times = new ArrayList<>();
                AtomicInteger closed = new AtomicInteger();
                byte[] body = kind == 1 ? SRT : PNG;
                var transport = MediaTransportFixtures.publicClient(url -> {
                    urls.add(url.toString());
                    times.add(clock.nanos);
                    return response(url, urls.size() < 3 ? 503 : 200, urls.size() == 1 ? "0" : "60", body, closed);
                });
                clock.onSleep = () -> assertEquals(2, closed.get());
                fetch(kind, files.newFile(), transport);
                assertEquals(List.of(MIRRORS.get(0), MIRRORS.get(0), MIRRORS.get(1)), urls);
                assertEquals(List.of(0L, 0L, 60_000_000_000L), times);
            }
        }
    }

    @Test public void redirectsAndPermanentErrorsCannotBypassTheDelay() throws Exception {
        for (int status : List.of(302, 307, 400)) {
            try (var clock = new MediaTransportFixtures.RetryClock(120_000)) {
                List<String> urls = new ArrayList<>();
                List<Long> times = new ArrayList<>();
                AtomicInteger closed = new AtomicInteger();
                var transport = MediaTransportFixtures.publicClient(url -> {
                    urls.add(url.toString()); times.add(clock.nanos);
                    return response(url, urls.size() == 1 ? status : 200, urls.size() == 1 ? "60" : null, PNG, closed);
                });
                clock.onSleep = () -> assertEquals("redirect/error response held during wait", 1, closed.get());
                fetch(0, files.newFile(), transport);
                assertEquals(List.of(0L, 60_000_000_000L), times);
                assertEquals(List.of(MIRRORS.get(0), status == 400 ? MIRRORS.get(1) : "https://8.8.8.8/redirected"), urls);
                assertEquals(2, closed.get());
            }
        }
    }

    @Test public void aSuccessfulResponseDelaysTheNextRequestFromReceiptNotFromBodyCompletion() throws Exception {
        try (var clock = new MediaTransportFixtures.RetryClock(120_000)) {
            AtomicInteger requests = new AtomicInteger(), closed = new AtomicInteger();
            List<Long> times = new ArrayList<>();
            var transport = MediaTransportFixtures.publicClient(url -> {
                times.add(clock.nanos);
                return response(url, 200, requests.incrementAndGet() == 1
                        ? "Sun, 06 Nov 1994 08:50:37 GMT" : null, PNG, closed);
            });
            try (var first = transport.open(MIRRORS.get(0), clock.deadline, 1000, 1000, null, false)) {
                assertEquals(200, first.statusCode);
                clock.nanos += 30_000_000_000L; // Time spent consuming the first response's body.
                clock.wall += 86_400_000L;
            }
            clock.onSleep = () -> assertEquals(1, closed.get());
            try (var second = transport.open(MIRRORS.get(1), clock.deadline, 1000, 1000, null, false)) {
                assertEquals(200, second.statusCode);
            }
            assertEquals(List.of(0L, 60_000_000_000L), times);
            assertEquals(300, clock.sleeps);
        }
    }

    @Test public void anUnfinishableRedirectDelayStopsBeforeTheRedirectOrMirror() throws Exception {
        try (var clock = new MediaTransportFixtures.RetryClock(50_000)) {
            AtomicInteger requests = new AtomicInteger(), closed = new AtomicInteger();
            var transport = MediaTransportFixtures.publicClient(url -> {
                requests.incrementAndGet(); return response(url, 302, "60", PNG, closed);
            });
            File target = files.newFile();
            var refused = assertThrows(MediaBudget.StopException.class, () -> fetch(0, target, transport));
            assertEquals(MediaBudget.StopException.Reason.SERVER_WAIT, refused.reason);
            assertEquals(1, requests.get()); assertEquals(1, closed.get());
            assertEquals(0, clock.sleeps);
        }
    }

    @Test public void everyFinalAttemptRefusesAnUnfinishableWaitWithoutTryingElsewhere() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            try (var clock = new MediaTransportFixtures.RetryClock(50_000)) {
                AtomicInteger requests = new AtomicInteger(), closed = new AtomicInteger();
                var transport = MediaTransportFixtures.publicClient(url -> response(url, 503,
                        requests.incrementAndGet() == 1 ? "0" : "60", PNG, closed));
                File target = files.newFile();
                int fetcher = kind;
                var refused = assertThrows(MediaBudget.StopException.class, () -> fetch(fetcher, target, transport));
                assertEquals(MediaBudget.StopException.Reason.SERVER_WAIT, refused.reason);
                assertEquals(2, requests.get());
                assertEquals(2, closed.get());
                assertEquals(0, clock.sleeps);
                assertSame(refused, assertThrows(MediaBudget.StopException.class, () ->
                        transport.open("https://later.example/file", clock.deadline, 1000, 1000, null, false)));
                assertEquals("a later request bypassed the refusal", 2, requests.get());
                MediaBudget.check(clock.deadline); // Local publication of an already-downloaded file remains valid.
            }
        }
    }

    @Test public void cancelDuringServerWaitPreservesPriorFilesAndPreventsAnotherRequest() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            Utils.setActivity(null);
            try (var clock = new MediaTransportFixtures.RetryClock(120_000)) {
                SaveProgress progress = SaveProgress.begin(3);
                AtomicInteger requests = new AtomicInteger(), closed = new AtomicInteger();
                var transport = MediaTransportFixtures.publicClient(url -> {
                    requests.incrementAndGet();
                    return response(url, 429, "60", PNG, closed);
                });
                clock.onSleep = () -> { assertEquals(1, closed.get()); progress.cancel(); };
                File retained = files.newFile(), target = files.newFile();
                int fetcher = kind;
                SaveProgress.Outcome outcome = progress.run(index -> {
                    if (index == 0) Files.write(retained.toPath(), PNG);
                    else fetch(fetcher, target, transport);
                });
                assertArrayEquals(PNG, Files.readAllBytes(retained.toPath()));
                assertEquals(1, outcome.saved);
                assertEquals(0, outcome.skipped);
                assertEquals(2, outcome.cancelled);
                assertEquals(SaveProgress.Stop.NONE, outcome.stop);
                assertEquals("Saved 1 of 3, the rest cancelled", SaveProgress.message(outcome, "complete"));
                assertEquals(1, requests.get());
                assertEquals(100_000_000L, clock.nanos);
            }
        }
    }

    @Test public void theCountedSaveReportsServerWaitAndKeepsItsPublishedFile() throws Exception {
        Utils.setActivity(null);
        try (var clock = new MediaTransportFixtures.RetryClock(50_000)) {
            AtomicInteger requests = new AtomicInteger(), closed = new AtomicInteger();
            var transport = MediaTransportFixtures.publicClient(url -> {
                requests.incrementAndGet();
                return response(url, 429, "60", PNG, closed);
            });
            File retained = files.newFile(), target = files.newFile();
            SaveProgress.Outcome outcome = SaveProgress.begin(3).run(index -> {
                if (index == 0) Files.write(retained.toPath(), PNG);
                else fetch(0, target, transport);
            });
            assertArrayEquals(PNG, Files.readAllBytes(retained.toPath()));
            assertEquals(1, outcome.saved);
            assertEquals(0, outcome.skipped);
            assertEquals(2, outcome.cancelled);
            assertEquals(SaveProgress.Stop.SERVER_WAIT, outcome.stop);
            assertEquals("Saved 1 of 3. The server asked us to wait. Try again later.",
                    SaveProgress.message(outcome, "complete"));
            assertEquals(2, requests.get());
            assertEquals(2, closed.get());
            assertEquals(600, clock.sleeps);
            assertEquals(60_000_000_000L, clock.nanos);
        }
    }

    @Test public void subtitleBatchStopsBeforeLaterTracksAndKeepsTheFirstPublication() throws Exception {
        String folder = "Movies/retry-after-" + System.nanoTime();
        File output = new File(Environment.getExternalStorageDirectory(), folder);
        try (var clock = new MediaTransportFixtures.RetryClock(50_000)) {
            AtomicInteger requests = new AtomicInteger(), closed = new AtomicInteger();
            var transport = MediaTransportFixtures.publicClient(url -> {
                boolean first = requests.incrementAndGet() == 1;
                return response(url, first ? 200 : 429, first ? null : "60", SRT, closed);
            });
            List<SubtitleDownloads.Track> tracks = new ArrayList<>();
            for (String language : List.of("en", "es", "de"))
                tracks.add(new SubtitleDownloads.Track(language, "srt", MIRRORS, true));
            var refused = assertThrows(MediaBudget.StopException.class, () -> SubtitleDownloads.save(
                    RuntimeEnvironment.getApplication(), tracks, "video.mp4", folder, transport));
            assertEquals(MediaBudget.StopException.Reason.SERVER_WAIT, refused.reason);
            assertEquals(2, requests.get());
            assertEquals(2, closed.get());
            assertEquals(List.of("video.en.srt"), List.of(output.list()));
            assertTrue(new String(Files.readAllBytes(new File(output, "video.en.srt").toPath()),
                    StandardCharsets.UTF_8).contains("Caption"));
        } finally {
            if (output.exists()) try (var paths = Files.walk(output.toPath())) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test public void stickerWorkerDoesNotSwallowTheStopAndTryItsNextMirror() throws Exception {
        new URL(MIRRORS.get(0));
        Hashtable<String, URLStreamHandler> handlers = ReflectionHelpers.getStaticField(URL.class, "handlers");
        AtomicInteger requests = new AtomicInteger(), closed = new AtomicInteger();
        URLStreamHandler previous = handlers.put("https", new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url, java.net.Proxy proxy) { return openConnection(url); }
            @Override protected URLConnection openConnection(URL url) {
                requests.incrementAndGet();
                return new FakeHttpsConnection(url) {
                    @Override public int getResponseCode() { return 429; }
                    @Override public String getHeaderField(String name) { return "Retry-After".equals(name) ? "60" : null; }
                    @Override public void connect() { }
                    @Override public void disconnect() { closed.incrementAndGet(); }
                    @Override public boolean usingProxy() { return false; }
                };
            }
        });
        try (var clock = new MediaTransportFixtures.RetryClock(50_000)) {
            var worker = StickerGallerySaver.class.getDeclaredMethod("saveSticker", Context.class,
                    StickerGallerySaver.StickerAsset.class);
            worker.setAccessible(true);
            Object result = worker.invoke(null, RuntimeEnvironment.getApplication(),
                    new StickerGallerySaver.StickerAsset(MIRRORS, false));
            assertFalse(ReflectionHelpers.getField(result, "success"));
            assertEquals("The server asked us to wait. Try again later.", ReflectionHelpers.getField(result, "message"));
            assertEquals(1, requests.get());
            assertEquals(1, closed.get());
        } finally {
            if (previous == null) handlers.remove("https"); else handlers.put("https", previous);
        }
    }
}

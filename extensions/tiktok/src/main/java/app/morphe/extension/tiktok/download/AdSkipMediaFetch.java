/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Cancellation belongs only to adskip; ordinary gallery downloads keep their transport. */
final class AdSkipMediaFetch {
    private static final ScheduledExecutorService WATCHER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "metra-adskip-cancel");
        thread.setDaemon(true);
        return thread;
    });

    private AdSkipMediaFetch() { }

    static void check(AtomicBoolean cancel) throws IOException {
        if (cancel != null && cancel.get()) {
            throw new MediaBudget.StopException("Ad skip download cancelled", MediaBudget.StopException.Reason.CANCELLED);
        }
    }

    static void fetch(List<String> urls, File target, AtomicBoolean cancel) throws IOException {
        fetch(urls, target, cancel, PinnedMediaConnection::open);
    }

    static void fetch(List<String> urls, File target, AtomicBoolean cancel,
            MediaTransport.ConnectionOpener opener) throws IOException {
        check(cancel);
        AtomicReference<HttpURLConnection> active = new AtomicReference<>();
        MediaTransport.Client client = new MediaTransport.Client(InetAddress::getAllByName, (url, address) -> {
            check(cancel);
            HttpURLConnection connection = (HttpURLConnection) opener.open(url, address);
            active.set(connection);
            try {
                check(cancel);
                return connection;
            } catch (IOException stopped) {
                connection.disconnect();
                throw stopped;
            }
        });
        // Disconnect unblocks a stalled body/header read too, not just the next copied buffer.
        var watch = WATCHER.scheduleWithFixedDelay(() -> {
            if (cancel != null && cancel.get()) {
                HttpURLConnection connection = active.getAndSet(null);
                if (connection != null) connection.disconnect();
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        MediaBudget.Deadline deadline = MediaBudget.deadline();
        AtomicBoolean previousCancellation = deadline.cancellation;
        deadline.cancellation = cancel;
        try {
            RemoteMedia.fetch(urls, target, RemoteMedia.Kind.VIDEO, client, null, deadline, cancel);
        } finally {
            deadline.cancellation = previousCancellation;
            watch.cancel(false);
            active.set(null);
        }
    }
}

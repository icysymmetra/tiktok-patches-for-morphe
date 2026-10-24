package app.morphe.extension.tiktok.settings.preference;

import android.os.ParcelFileDescriptor;

import app.morphe.extension.tiktok.DocumentExportProvider;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A cloud file app's slow transfer: the descriptor comes back at once and the data comes late.
 * Each read or write through it waits until the test lets it go or until its descriptor is
 * closed, which on a phone wakes a read or write blocked on it. Robolectric's descriptors are
 * plain files that never block, so this goes in through {@link DocumentOperation#streams}.
 */
final class SlowTransfers implements DocumentOperation.Streams {
    private final DocumentExportProvider provider;
    private final CountDownLatch release;
    private final CountDownLatch started = new CountDownLatch(1);

    private SlowTransfers(DocumentExportProvider provider, CountDownLatch release) {
        this.provider = provider;
        this.release = release;
    }

    /** Holds every read and write until {@code release} is counted down. The test's tear-down clears it. */
    static SlowTransfers install(DocumentExportProvider provider, CountDownLatch release) {
        SlowTransfers transfers = new SlowTransfers(provider, release);
        DocumentOperation.streams = transfers;
        return transfers;
    }

    boolean awaitStarted() throws InterruptedException {
        return started.await(5, TimeUnit.SECONDS);
    }

    @Override public InputStream reading(InputStream input) {
        return new FilterInputStream(input) {
            @Override public int read() throws IOException {
                waitForData();
                return super.read();
            }

            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                waitForData();
                return super.read(buffer, offset, length);
            }
        };
    }

    @Override public OutputStream writing(OutputStream output) {
        return new FilterOutputStream(output) {
            @Override public void write(int value) throws IOException {
                waitForData();
                out.write(value);
            }

            @Override public void write(byte[] buffer, int offset, int length) throws IOException {
                waitForData();
                out.write(buffer, offset, length);
            }
        };
    }

    private void waitForData() throws IOException {
        started.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (!release.await(10, TimeUnit.MILLISECONDS)) {
                if (descriptorClosed()) return;
                if (System.nanoTime() > deadline) throw new IllegalStateException("the test never let the transfer go");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private boolean descriptorClosed() {
        for (ParcelFileDescriptor descriptor : provider.handedOut) {
            if (!descriptor.getFileDescriptor().valid()) return true;
        }
        return false;
    }
}

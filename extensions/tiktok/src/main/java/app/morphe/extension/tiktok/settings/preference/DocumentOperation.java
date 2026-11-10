/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One document a settings row reads or writes through a file app, from asking for it to having
 * the answer.
 *
 * <p>A file app can take as long as it likes to hand a document over: a cloud one downloads it
 * first, and one that has hung never answers. Backup, restore and the watch-history import used
 * to do this on the shared worker pool behind a busy flag nothing else could clear, so a stalled
 * file app left their rows out of reach until TikTok was closed. Each read or write now runs on a
 * worker of its own, the file app is passed a cancellation it can honour, and after
 * {@link #stallMillis} the row offers to stop waiting.
 *
 * <p>Stopping can't interrupt code running inside the file app. It cancels the request, closes
 * whatever the file app handed over, and shuts the two gates the work passes: an import stopped
 * before {@link #commit()} changes nothing, and a backup stopped before {@link #publish()} is
 * never handed over. A worker the file app is still holding keeps its kind's slot until it
 * returns, so stopping and trying again can't pile up workers or open files behind a file app
 * that ignores the cancellation.
 *
 * <p>Public for the Feature Gate Lab, whose loaded-value files go the same way.
 */
public final class DocumentOperation {
    public enum Kind { SETTINGS_FILE, WATCH_HISTORY_FILE, LAB_FILE, COMMENT_FILE, SEEN_HISTORY_FILE, FONT_FILE }

    public enum Stage {
        /** Opening or reading the file, or getting a backup ready. Stopping here changes nothing. */
        PREPARING,
        /** The change the file asked for is being made, and can't be stopped any more. */
        COMMITTING,
        /** The file app is being handed the backup. */
        PUBLISHING,
        /** Stopped before anything changed or was handed over. */
        STOPPED,
        /** Stopped while the file app had the backup, so only the worker's return says how it went. */
        STOPPED_WHILE_PUBLISHING,
        /** The file app has taken the whole backup, or the worker has returned without being stopped. */
        DONE
    }

    /** The read or write itself, run on the operation's own worker. */
    public interface Work {
        void run(DocumentOperation operation) throws Exception;
    }

    /** What the work is thrown once it has been stopped, so it unwinds without a result. */
    public static final class Stopped extends IOException {
        Stopped(Throwable cause) {
            super("Stopped waiting for the file app", cause);
        }
    }

    /** How long a file app may take before the row offers to stop waiting. Tests shorten it. */
    static volatile long stallMillis = 4_000;

    /**
     * Wraps the streams the work reads and writes. Only tests set it, to hold a read or a write
     * the way a cloud file app does when the pipe comes back at once and the data comes late.
     */
    interface Streams {
        InputStream reading(InputStream input);
        OutputStream writing(OutputStream output);
    }

    /** Null outside tests. */
    static volatile Streams streams;

    public static long stallMillisForTests() {
        return stallMillis;
    }

    private static final ConcurrentHashMap<Kind, DocumentOperation> WORKERS = new ConcurrentHashMap<>();

    private final Kind kind;
    private final Runnable onChange;
    private final CancellationSignal signal = new CancellationSignal();
    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.PREPARING);
    /** The provider client and descriptors the work was handed, closed once it stops or returns. */
    private final List<Closeable> held = new ArrayList<>();
    private volatile boolean stalled;

    private DocumentOperation(Kind kind, Runnable onChange) {
        this.kind = kind;
        this.onChange = onChange;
    }

    /**
     * Starts the work on a worker of its own, or returns null while a worker of the same kind is
     * still out, stopped or not. onChange runs on the main thread when the stage or the stall
     * changes and once the worker has returned.
     */
    public static DocumentOperation start(Kind kind, Work work, Runnable onChange) {
        DocumentOperation operation = new DocumentOperation(kind, onChange);
        if (WORKERS.putIfAbsent(kind, operation) != null) return null;
        if (!Utils.runOnOwnThread("Metra-" + kind, () -> operation.runWork(work))) {
            WORKERS.remove(kind, operation);
            return null;
        }
        Utils.runOnMainThreadDelayed(operation::checkStall, stallMillis);
        return operation;
    }

    /** Whether a worker of this kind is still out. A stopped one counts until its file app lets go. */
    public static boolean busy(Kind kind) {
        return WORKERS.containsKey(kind);
    }

    /**
     * Whether a stopped worker of this kind is still out because its file app hasn't let go. A
     * worker that wasn't stopped stays out for a moment after its run has ended, which isn't
     * worth showing.
     */
    public static boolean heldAfterStop(Kind kind) {
        DocumentOperation out = WORKERS.get(kind);
        return out != null && out.isStopped();
    }

    public Stage stage() {
        return stage.get();
    }

    public boolean isStopped() {
        Stage now = stage.get();
        return now == Stage.STOPPED || now == Stage.STOPPED_WHILE_PUBLISHING;
    }

    /** Whether the row should offer to stop: the file app has kept it waiting, and stopping still can. */
    public boolean offersStop() {
        Stage now = stage.get();
        return stalled && (now == Stage.PREPARING || now == Stage.PUBLISHING);
    }

    /** Shuts the gate an import passes before it changes anything. False once it has been stopped. */
    public boolean commit() {
        return advance(Stage.COMMITTING);
    }

    /** Shuts the gate a backup passes before the file app is handed it. False once it has been stopped. */
    public boolean publish() {
        return advance(Stage.PUBLISHING);
    }

    /**
     * Marks a handed-over backup as kept once the file app has taken all of it, so a stop after
     * this can't call the outcome open. A stop that came first is answered by the outcome the
     * worker reports next.
     */
    public void finish() {
        if (stage.compareAndSet(Stage.PUBLISHING, Stage.DONE)) changed();
    }

    private boolean advance(Stage next) {
        if (!stage.compareAndSet(Stage.PREPARING, next)) return false;
        changed();
        return true;
    }

    /**
     * Stops waiting. Before the gates nothing has changed or been handed over; once the file app
     * has the backup, how it went stays open until the worker returns. Returns the stage the
     * operation is left in, which is the one it was already in once stopping can't change it.
     */
    public Stage stop() {
        while (true) {
            Stage now = stage.get();
            Stage stopped = now == Stage.PREPARING ? Stage.STOPPED
                    : now == Stage.PUBLISHING ? Stage.STOPPED_WHILE_PUBLISHING : null;
            if (stopped == null) return now;
            if (stage.compareAndSet(now, stopped)) {
                signal.cancel();
                closeHeld();
                changed();
                return stopped;
            }
        }
    }

    /** The document, read through the descriptor the file app hands over. */
    public InputStream openForRead(ContentResolver resolver, Uri uri) throws IOException {
        InputStream input = open(resolver, uri, "r").createInputStream();
        Streams wrap = streams;
        return wrap == null ? input : wrap.reading(input);
    }

    /** The document, written through the descriptor the file app hands over. */
    public OutputStream openForWrite(ContentResolver resolver, Uri uri, String mode) throws IOException {
        OutputStream output = open(resolver, uri, mode).createOutputStream();
        Streams wrap = streams;
        return wrap == null ? output : wrap.writing(output);
    }

    /**
     * Removes the document a write that didn't happen was going to fill. The picker can hand back
     * a file the user chose to replace, and until the write opens it, it still holds what it held,
     * so it goes only once the write has had it or when the file app says it's empty. False only
     * when it should have gone and couldn't.
     */
    public static boolean removeUnsaved(ContentResolver resolver, Uri uri, boolean opened) {
        if (resolver == null || uri == null) return false;
        if (!opened && !reportsEmpty(resolver, uri)) return true;
        try {
            return DocumentsContract.deleteDocument(resolver, uri);
        } catch (Exception error) {
            Logger.printException(() -> "Could not remove an unsaved file", error);
            return false;
        }
    }

    /**
     * Logs why file work ended without a result. Once stopped, the work ends on {@link Stopped} or
     * on whatever the closed file throws at the write in progress. Either way that's the user's own
     * stop, and the row has already said what it meant, so it goes in at info, never as a patch
     * error with its debug toast. An Error is never a stop's doing.
     */
    public static void logFailure(DocumentOperation operation, Logger.LogMessage message, Throwable failure) {
        if (operation != null && operation.isStopped() && failure instanceof Exception) {
            Logger.printInfo(message, (Exception) failure);
        } else {
            Logger.printException(message, failure);
        }
    }

    /** Whether the file app says the document holds nothing. A size it doesn't give counts as something. */
    private static boolean reportsEmpty(ContentResolver resolver, Uri uri) {
        String[] size = {OpenableColumns.SIZE};
        // From Android 8 a DocumentsProvider implements only the Bundle form of query, and the
        // older form reaches it only through the platform resolver's conversion.
        try (android.database.Cursor cursor = android.os.Build.VERSION.SDK_INT >= 26
                ? resolver.query(uri, size, null, null)
                : resolver.query(uri, size, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            // By name, since a file app may answer with columns of its own.
            int column = cursor.getColumnIndex(OpenableColumns.SIZE);
            return column >= 0 && !cursor.isNull(column) && cursor.getLong(column) == 0;
        } catch (RuntimeException error) {
            Logger.printInfo(() -> "Could not read the size of a file the file app made: " + error);
            return false;
        }
    }

    /**
     * Asks the file app for the document and passes the cancellation along. ContentResolver
     * sends a read through openTypedAssetFile, where DocumentsProvider drops the signal, so the
     * provider client is asked directly: DocumentsProvider.openAssetFile hands the signal to
     * openDocument for reads and writes alike. The descriptor is used as it comes, start offset
     * and length included, which is what the streams it creates keep to.
     */
    @SuppressWarnings("deprecation") // ContentProviderClient.close() needs API 24; release() is the same call.
    private AssetFileDescriptor open(ContentResolver resolver, Uri uri, String mode) throws IOException {
        if (isStopped()) throw new Stopped(null);
        ContentProviderClient client = resolver.acquireUnstableContentProviderClient(uri);
        if (client == null) throw new FileNotFoundException("No file app answers for " + uri);
        hold(client::release);
        AssetFileDescriptor descriptor;
        try {
            descriptor = client.openAssetFile(uri, mode, signal);
        } catch (OperationCanceledException canceled) {
            throw new Stopped(canceled);
        } catch (RemoteException gone) {
            throw new IOException("The file app stopped answering", gone);
        }
        if (descriptor == null) throw new FileNotFoundException("The file app handed over nothing for " + uri);
        hold(descriptor);
        return descriptor;
    }

    /** Keeps what the file app handed over to close later, or closes it now if stopping came first. */
    private void hold(Closeable resource) throws Stopped {
        synchronized (held) {
            if (!isStopped()) {
                held.add(resource);
                return;
            }
        }
        closeQuietly(resource);
        throw new Stopped(null);
    }

    private void closeHeld() {
        List<Closeable> closing;
        synchronized (held) {
            closing = new ArrayList<>(held);
            held.clear();
        }
        // The descriptor before the client that handed it over.
        for (int index = closing.size() - 1; index >= 0; index--) closeQuietly(closing.get(index));
    }

    private static void closeQuietly(Closeable resource) {
        try {
            resource.close();
        } catch (IOException | RuntimeException failure) {
            Logger.printDebug(() -> "Could not close what a file app handed over: " + failure);
        }
    }

    private void runWork(Work work) {
        try {
            work.run(this);
        } catch (Exception failure) {
            // The work reports its own outcome. This is only what it let through.
            logFailure(this, () -> "File work for " + kind + " failed", failure);
        } finally {
            // A stopped worker stays stopped. A CAS loop rather than updateAndGet, which is API 24.
            while (true) {
                Stage now = stage.get();
                if (now == Stage.STOPPED || now == Stage.STOPPED_WHILE_PUBLISHING
                        || stage.compareAndSet(now, Stage.DONE)) break;
            }
            closeHeld();
            WORKERS.remove(kind, this);
            changed();
        }
    }

    private void checkStall() {
        Stage now = stage.get();
        if (now != Stage.PREPARING && now != Stage.PUBLISHING) return;
        stalled = true;
        changed();
    }

    private void changed() {
        if (onChange != null) Utils.runOnMainThread(onChange);
    }
}

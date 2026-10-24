package app.morphe.extension.tiktok;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.robolectric.shadows.ShadowContentResolver;

/**
 * A SAF document whose real DocumentsProvider dispatch distinguishes deleteDocument from delete,
 * and which can stand in for a slow file app: an open can be held until the test lets it go,
 * and the provider either gives up when the caller cancels or, like a file app that has hung,
 * ignores it. With {@link #contents} it is a file the user chose to replace; without, the empty
 * one the picker made.
 */
public final class DocumentExportProvider extends DocumentsProvider {
    public static final String AUTHORITY = "app.morphe.test.export.documents";
    public final Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, "backup");
    public final CountDownLatch deletion = new CountDownLatch(1);
    /** Counted down once a held open has been entered. */
    public final CountDownLatch opening = new CountDownLatch(1);
    public final AtomicInteger openCalls = new AtomicInteger();
    /** Cancellations that reached the provider, which is the signal crossing the client. */
    public final AtomicInteger cancels = new AtomicInteger();
    /** Every descriptor handed over, so a test can see that each one was closed. */
    public final List<ParcelFileDescriptor> handedOut = new CopyOnWriteArrayList<>();
    public volatile boolean exists = true;
    public volatile boolean failOpen;
    public volatile boolean refuseDeletion;
    /** Hands a write a read-only descriptor, so the write itself fails. */
    public volatile boolean readOnlyWrites;
    /** Whether a held open gives up when the caller cancels, as a cooperative file app does. */
    public volatile boolean honorCancel;
    /** Leaves the size out of the document's row, as some file apps do. */
    public volatile boolean sizeUnknown;
    public volatile int deleteCalls;
    public File file;
    private volatile CountDownLatch hold;

    public static DocumentExportProvider register(Context context) {
        DocumentExportProvider provider = new DocumentExportProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = AUTHORITY;
        info.exported = true;
        info.grantUriPermissions = true;
        info.readPermission = "android.permission.MANAGE_DOCUMENTS";
        info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
        return provider;
    }

    /** What a read of the document returns. */
    public DocumentExportProvider contents(byte[] bytes) throws IOException {
        Files.write(file.toPath(), bytes);
        return this;
    }

    /** Holds every open until the returned latch is counted down. */
    public CountDownLatch holdOpens() {
        CountDownLatch release = new CountDownLatch(1);
        hold = release;
        return release;
    }

    public boolean awaitOpening() throws InterruptedException {
        return opening.await(5, TimeUnit.SECONDS);
    }

    @Override public boolean onCreate() {
        try {
            file = File.createTempFile("created-export", ".json", getContext().getCacheDir());
            return true;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection == null
                ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID} : projection);
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE});
        if (exists) cursor.addRow(new Object[]{"backup", "backup.json", "application/json",
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                        | DocumentsContract.Document.FLAG_SUPPORTS_DELETE,
                sizeUnknown ? null : file.length()});
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) {
        return queryDocument("backup", projection);
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        openCalls.incrementAndGet();
        if (signal != null) signal.setOnCancelListener(cancels::incrementAndGet);
        CountDownLatch release = hold;
        if (release != null) {
            opening.countDown();
            awaitRelease(release, signal);
        }
        if (failOpen || !exists) throw new FileNotFoundException("injected destination refusal");
        int flags = readOnlyWrites ? ParcelFileDescriptor.MODE_READ_ONLY : ParcelFileDescriptor.parseMode(mode);
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, flags);
        handedOut.add(descriptor);
        return descriptor;
    }

    private void awaitRelease(CountDownLatch release, CancellationSignal signal) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (!release.await(10, TimeUnit.MILLISECONDS)) {
                if (honorCancel && signal != null && signal.isCanceled()) throw new OperationCanceledException();
                if (System.nanoTime() > deadline) throw new IllegalStateException("the test never released the open");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        deleteCalls++;
        try {
            if (refuseDeletion) throw new FileNotFoundException("injected cleanup refusal");
            if (file.exists() && !file.delete()) throw new FileNotFoundException("could not remove fixture");
            exists = false;
        } finally {
            deletion.countDown();
        }
    }

    public boolean awaitDeletion() throws InterruptedException {
        return deletion.await(5, TimeUnit.SECONDS);
    }
}

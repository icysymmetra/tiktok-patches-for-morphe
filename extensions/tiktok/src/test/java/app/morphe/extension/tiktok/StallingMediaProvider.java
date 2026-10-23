package app.morphe.extension.tiktok;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.robolectric.shadows.ShadowContentResolver;

/**
 * The media store a report export writes through, able to stall the way a busy one does: an insert
 * can be held until the test lets it go, so a second tap can arrive while the first save is out.
 */
public final class StallingMediaProvider extends ContentProvider {
    public final File file;
    public final AtomicInteger inserts = new AtomicInteger();
    /** Counted down once the first insert has been entered. */
    public final CountDownLatch inserting = new CountDownLatch(1);
    public volatile ContentValues created;
    public volatile boolean published;
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean holding;

    private StallingMediaProvider(File file) {
        this.file = file;
    }

    public static StallingMediaProvider register(Context context, File file) {
        StallingMediaProvider provider = new StallingMediaProvider(file);
        ProviderInfo info = new ProviderInfo();
        info.authority = MediaStore.AUTHORITY;
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider);
        return provider;
    }

    /** Holds every insert until {@link #release()}. */
    public StallingMediaProvider hold() {
        holding = true;
        return this;
    }

    public void release() {
        release.countDown();
    }

    public boolean awaitInserting() throws InterruptedException {
        return inserting.await(5, TimeUnit.SECONDS);
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        int id = inserts.incrementAndGet();
        created = new ContentValues(values);
        inserting.countDown();
        if (holding) {
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the insert");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id));
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public Cursor query(Uri uri, String[] fields, String where, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[]{MediaStore.MediaColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{created.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)});
        return cursor;
    }

    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        published = Integer.valueOf(0).equals(values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        return 1;
    }

    @Override public int delete(Uri uri, String where, String[] args) {
        return 1;
    }

    @Override public String getType(Uri uri) {
        return null;
    }
}

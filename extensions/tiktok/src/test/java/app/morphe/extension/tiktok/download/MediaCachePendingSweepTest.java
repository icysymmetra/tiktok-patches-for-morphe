/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Base64;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The day-old sweep against a MediaStore that hides pending rows, which is what the real one
 * does: from API 29 a query sees no pending row, not even the caller's own, unless it asks
 * (MediaStore.setIncludePending on API 29, QUERY_ARG_MATCH_PENDING = MATCH_INCLUDE from API 30).
 * A sweep that didn't ask read every orphan as gone, dropped its journal entry and left the
 * row behind for good.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {29, 30})
public class MediaCachePendingSweepTest {
    private static final Uri COLLECTION = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    private static final String FOLDER = "Pictures/Hushfeed";

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        HidingMediaProvider.reset();
        Robolectric.setupContentProvider(HidingMediaProvider.class, "media");
    }

    @After public void tearDown() {
        HidingMediaProvider.reset();
        File journal = journal();
        if (journal.exists()) assertTrue(journal.delete());
    }

    @Test public void aDayOldPendingRowGoesWithItsEntry() throws Exception {
        HidingMediaProvider.add(7L, "hushfeed-pending-1-1.jpg", 1);
        writeStale(ContentUris.withAppendedId(COLLECTION, 7L).toString());

        MediaCache.reconcile(context);

        assertEquals("the orphan row stayed", List.of(), HidingMediaProvider.ids());
        assertFalse("the entry outlived its row", journalText().contains("content:"));
    }

    @Test public void aDayOldPendingRowUnderAnInterruptedInsertGoesWithItsToken() throws Exception {
        HidingMediaProvider.add(7L, "hushfeed-pending-1-1.jpg", 1);
        writeStale(token("hushfeed-pending-1-1.jpg"));

        MediaCache.reconcile(context);

        assertEquals("the orphan row stayed", List.of(), HidingMediaProvider.ids());
        assertFalse("the token outlived its row", journalText().contains("intent:"));
    }

    /** Without this the cases above would pass against a sweep that deleted every row it saw. */
    @Test public void aPublishedRowStaysAndOnlyItsEntryGoes() throws Exception {
        HidingMediaProvider.add(7L, "photo.jpg", 0);
        writeStale(ContentUris.withAppendedId(COLLECTION, 7L).toString());

        MediaCache.reconcile(context);

        assertEquals(List.of(7L), HidingMediaProvider.ids());
        assertFalse(journalText().contains("content:"));
    }

    private static String token(String name) {
        return "intent:0:" + base64(COLLECTION.toString()) + ":" + base64(name) + ":" + base64(FOLDER);
    }

    private static String base64(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP);
    }

    private File journal() {
        return new File(new File(context.getNoBackupFilesDir(), MediaCache.DIRECTORY_NAME), "pending-uris.tsv");
    }

    private void writeStale(String key) throws Exception {
        File file = journal();
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        long stale = System.currentTimeMillis() - MediaCache.STALE_AFTER_MS - 60_000L;
        Files.write(file.toPath(), (stale + "\t" + key + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private String journalText() throws Exception {
        File file = journal();
        return file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
    }

    /**
     * MediaStore's visibility rule for pending rows, and nothing else: a query sees them only when
     * it asks the way its API level does. A write by id reaches them, as the owner's does.
     */
    public static final class HidingMediaProvider extends ContentProvider {
        private static final List<Object[]> ROWS = new ArrayList<>();

        static synchronized void reset() {
            ROWS.clear();
        }

        static synchronized void add(long id, String name, int pending) {
            ROWS.add(new Object[]{id, name, pending, FOLDER});
        }

        static synchronized List<Long> ids() {
            List<Long> ids = new ArrayList<>();
            for (Object[] row : ROWS) ids.add((Long) row[0]);
            return ids;
        }

        @Override public boolean onCreate() {
            return true;
        }

        /** API 29's way to ask: a query parameter MediaStore.setIncludePending puts on the URI. */
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            return rows(uri, projection, args, "1".equals(uri.getQueryParameter("includePending")));
        }

        /** API 30's way: an argument in the query Bundle. */
        @Override public Cursor query(Uri uri, String[] projection, Bundle queryArgs, CancellationSignal signal) {
            if (queryArgs == null) return query(uri, projection, null, null, null);
            String[] args = queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
            boolean include = queryArgs.getInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_DEFAULT)
                    == MediaStore.MATCH_INCLUDE || "1".equals(uri.getQueryParameter("includePending"));
            return rows(uri, projection, args, include);
        }

        private static synchronized Cursor rows(Uri uri, String[] projection, String[] args, boolean includePending) {
            Long only = null;
            try {
                only = ContentUris.parseId(uri);
            } catch (NumberFormatException | UnsupportedOperationException collection) {
                // A collection: every row.
            }
            String wanted = args == null || args.length == 0 ? null : args[0];
            MatrixCursor cursor = new MatrixCursor(projection);
            for (Object[] row : ROWS) {
                if (only != null && !only.equals(row[0])) continue;
                if (wanted != null && !wanted.equals(row[1])) continue;
                if ((Integer) row[2] != 0 && !includePending) continue;
                Object[] values = new Object[projection.length];
                for (int column = 0; column < projection.length; column++) {
                    switch (projection[column]) {
                        case MediaStore.MediaColumns._ID: values[column] = row[0]; break;
                        case MediaStore.MediaColumns.DISPLAY_NAME: values[column] = row[1]; break;
                        case MediaStore.MediaColumns.IS_PENDING: values[column] = row[2]; break;
                        case MediaStore.MediaColumns.RELATIVE_PATH: values[column] = row[3]; break;
                        default: values[column] = null; break;
                    }
                }
                cursor.addRow(values);
            }
            return cursor;
        }

        @Override public int delete(Uri uri, String selection, String[] args) {
            long id = ContentUris.parseId(uri);
            synchronized (HidingMediaProvider.class) {
                for (int at = 0; at < ROWS.size(); at++) {
                    if ((Long) ROWS.get(at)[0] == id) {
                        ROWS.remove(at);
                        return 1;
                    }
                }
            }
            return 0;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException();
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }

        @Override public String getType(Uri uri) {
            return null;
        }
    }
}

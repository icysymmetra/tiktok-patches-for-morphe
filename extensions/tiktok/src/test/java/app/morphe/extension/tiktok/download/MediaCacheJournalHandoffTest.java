/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

/** Legacy ownership, unreadable records and real process overlap at the journal boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class MediaCacheJournalHandoffTest {
    private static final Uri COLLECTION = Uri.parse("content://media/external_primary/downloads");
    private static final String FOLDER = "Download/Hushfeed";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Context context;
    private File cache, durable;

    @Before public void setUp() throws Exception {
        cache = temporary.newFolder("cache");
        durable = temporary.newFolder("no-backup");
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getCacheDir() { return cache; }
            @Override public File getNoBackupFilesDir() { return durable; }
            @Override public Context getApplicationContext() { return this; }
        };
        MediaCacheRecoveryTest.FakeMediaProvider.reset();
        Robolectric.setupContentProvider(MediaCacheRecoveryTest.FakeMediaProvider.class, "media");
    }

    @After public void tearDown() {
        MediaCacheRecoveryTest.FakeMediaProvider.reset();
    }

    @Test public void anInsertIntentSurvivesCacheLossAndKeepsItsExactFolderScope() throws Exception {
        String name = MediaCache.TEMPORARY_NAME_PREFIX + "interrupted.jpg";
        MediaCacheRecoveryTest.FakeMediaProvider.add(7L, name, 0, FOLDER);
        MediaCacheRecoveryTest.FakeMediaProvider.add(8L, "native-pending.jpg", 1, FOLDER);
        MediaCacheRecoveryTest.FakeMediaProvider.add(9L, name, 1, FOLDER);
        MediaCacheRecoveryTest.FakeMediaProvider.add(10L, name, 1, "Movies/Hushfeed");
        String intent = MediaCache.beginPending(context, COLLECTION, name, FOLDER);
        write(journal(durable), stale() + "\t" + intent + "\n");
        remove(cache);

        MediaCache.reconcile(context);

        assertEquals(List.of(7L, 8L, 10L), MediaCacheRecoveryTest.FakeMediaProvider.ids());
        assertEquals(List.of(9L), MediaCacheRecoveryTest.FakeMediaProvider.deletedIds());
    }

    @Test public void handoffMergesLegacyRecordsWithoutAgingAnActivePublication() throws Exception {
        Uri active = uri(9);
        MediaCacheRecoveryTest.FakeMediaProvider.add(7L, "completed.mp4", 0, FOLDER);
        MediaCacheRecoveryTest.FakeMediaProvider.add(8L, "native-pending.mp4", 1, FOLDER);
        MediaCacheRecoveryTest.FakeMediaProvider.add(9L, "being-written.mp4", 1, FOLDER);
        String name = MediaCache.TEMPORARY_NAME_PREFIX + "old.mp4";
        MediaCacheRecoveryTest.FakeMediaProvider.add(10L, name, 1, FOLDER);
        MediaCache.markPending(context, active);
        write(journal(cache), stale() + "\t" + active + "\n"
                + stale() + "\t" + intent(name) + "\n");

        MediaCache.reconcile(context);

        assertEquals(List.of(7L, 8L, 9L), MediaCacheRecoveryTest.FakeMediaProvider.ids());
        assertEquals(List.of(10L), MediaCacheRecoveryTest.FakeMediaProvider.deletedIds());
        assertTrue(text(journal(durable)).contains(active.toString()));
        assertFalse("legacy records were not retired after durable handoff", journal(cache).exists());
    }

    @Test public void aLegacyAtomicBackupIsRecoveredBeforeCacheRemoval() throws Exception {
        MediaCacheRecoveryTest.FakeMediaProvider.add(9L, "interrupted.mp4", 1, FOLDER);
        File backup = new File(journal(cache).getPath() + ".bak");
        write(journal(cache), stale() + "\t" + uri(11) + "\n");
        write(backup, stale() + "\t" + uri(9) + "\n");
        write(new File(journal(cache).getPath() + ".new"), stale() + "\t" + uri(12) + "\n");

        MediaCache.markPending(context, uri(10));
        assertTrue(text(journal(durable)).contains(uri(9).toString()));
        assertFalse("uncommitted base ownership was merged", text(journal(durable)).contains(uri(11).toString()));
        assertFalse("uncommitted .new ownership was merged", text(journal(durable)).contains(uri(12).toString()));
        assertFalse(backup.exists());
        remove(cache);
        MediaCache.reconcile(context);

        assertEquals(List.of(), MediaCacheRecoveryTest.FakeMediaProvider.ids());
        assertEquals(List.of(9L), MediaCacheRecoveryTest.FakeMediaProvider.deletedIds());
        assertTrue("fresh row's record was lost", text(journal(durable)).contains(uri(10).toString()));
    }

    @Test public void unreadableLegacyRecordsPreventReplacementOrPartialHandoff() throws Exception {
        File legacy = journal(cache);
        assertTrue(legacy.mkdirs());
        File evidence = new File(legacy, "unreadable-record");
        write(evidence, "preserve");

        assertThrows(IOException.class, () -> MediaCache.markPending(context, uri(9)));

        assertEquals("preserve", text(evidence));
        assertFalse("a partial handoff was written", journal(durable).exists());
    }

    @Test public void unreadableDurableRecordsDoNotRetireTheLegacyJournal() throws Exception {
        File current = journal(durable);
        assertTrue(current.mkdirs());
        File evidence = new File(current, "unreadable-record");
        write(evidence, "preserve");
        String legacy = stale() + "\t" + uri(7) + "\n";
        write(journal(cache), legacy);

        assertThrows(IOException.class, () -> MediaCache.markPending(context, uri(9)));

        assertEquals("preserve", text(evidence));
        assertEquals(legacy, text(journal(cache)));
    }

    @Test public void aMalformedLegacyRecordIsPreservedInsteadOfMigratingAnEmptyJournal() throws Exception {
        String unreadable = "not-a-timestamp\t" + uri(7) + "\n";
        write(journal(cache), unreadable);

        assertThrows(IOException.class, () -> MediaCache.markPending(context, uri(9)));

        assertEquals(unreadable, text(journal(cache)));
        assertFalse(journal(durable).exists());
    }

    @Test public void anUnreadablePendingRowKeepsItsDurableOwnershipAfterCacheLoss() throws Exception {
        MediaCacheRecoveryTest.FakeMediaProvider.add(9L, "interrupted.mp4", 1, FOLDER);
        MediaCache.markPending(context, uri(9));
        write(journal(durable), stale() + "\t" + uri(9) + "\n");
        Robolectric.setupContentProvider(UnavailableMediaProvider.class, "media");
        remove(cache);

        MediaCache.reconcile(context);

        assertEquals(List.of(9L), MediaCacheRecoveryTest.FakeMediaProvider.ids());
        assertTrue(text(journal(durable)).contains(uri(9).toString()));
        assertEquals("unknown rows must not be deleted", 0, UnavailableMediaProvider.deletes);
    }

    @Test public void invalidUtf8InLegacyKeysIsNotRewrittenOrRetired() throws Exception {
        assertInvalidBytesStay(journal(cache));
        assertFalse(journal(durable).exists());
    }

    @Test public void invalidUtf8InDurableKeysIsNotReplacedByANewPublication() throws Exception {
        assertInvalidBytesStay(journal(durable));
    }

    @Test @Config(sdk = 33)
    public void anUnreadableLegacyBackupPreservesAllAtomicFileBytes() throws Exception {
        assertUnreadableAtomicFilesStay(cache, true);
    }

    @Test @Config(sdk = 33)
    public void anUnreadableDurableBackupPreservesAllAtomicFileBytes() throws Exception {
        assertUnreadableAtomicFilesStay(durable, true);
    }

    @Test @Config(sdk = 33)
    public void aMalformedLegacyBasePreservesItsUncommittedWrite() throws Exception {
        assertUnreadableAtomicFilesStay(cache, false);
    }

    @Test @Config(sdk = 33)
    public void aMalformedDurableBasePreservesItsUncommittedWrite() throws Exception {
        assertUnreadableAtomicFilesStay(durable, false);
    }

    @Test @Config(sdk = 33, shadows = FinishWithoutPublication.class)
    public void aSilentAtomicFinishFailureDoesNotRetireLegacyOwnership() throws Exception {
        String legacy = stale() + "\t" + uri(7) + "\n";
        write(journal(cache), legacy);

        assertThrows(IOException.class, () -> MediaCache.markPending(context, uri(9)));

        assertEquals(legacy, text(journal(cache)));
        assertFalse("failed new journal was treated as published", journal(durable).exists());
    }

    @Test @Config(sdk = {23, 33}, shadows = FinishWithWrongRecords.class)
    public void aCommittedReadBackMismatchDoesNotRetireLegacyOwnership() throws Exception {
        String legacy = stale() + "\t" + uri(7) + "\n";
        write(journal(cache), legacy);

        assertThrows(IOException.class, () -> MediaCache.markPending(context, uri(9)));

        assertEquals(legacy, text(journal(cache)));
        assertTrue("committed mismatch evidence was removed", journal(durable).isFile());
        assertTrue(text(journal(durable)).endsWith("\tcontent://media/external_primary/downloads/777\n"));
    }

    @Test @Config(sdk = 33, shadows = WriteFailure.class)
    public void aWriteFailureAfterReadingLegacyRecordsDoesNotRetireThem() throws Exception {
        String legacy = stale() + "\t" + uri(7) + "\n";
        write(journal(cache), legacy);

        assertThrows(IOException.class, () -> MediaCache.markPending(context, uri(9)));

        assertEquals(legacy, text(journal(cache)));
        assertFalse(journal(durable).exists());
    }

    @Test public void aSeparateProcessesPublicationCannotBeLostDuringLegacyHandoff() throws Exception {
        write(journal(cache), stale() + "\t" + uri(7) + "\n");
        File directory = journal(durable).getParentFile();
        assertTrue(directory.mkdirs());
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        File java = new File(System.getProperty("java.home"), "bin/" + binary);
        // Copy the uninstrumented fixture bytes. A sandbox class's protection domain need
        // not have a code source, and the test worker's java.class.path isn't the test graph.
        File classes = temporary.newFolder("journal-process-classes");
        File fixture = new File(classes,
                "app/morphe/extension/tiktok/download/MediaCacheJournalProcess.class");
        assertTrue(fixture.getParentFile().mkdirs());
        try (InputStream bytes = MediaCacheJournalProcess.class.getResourceAsStream("MediaCacheJournalProcess.class")) {
            if (bytes == null) throw new IOException("Process fixture bytecode is unavailable");
            Files.copy(bytes, fixture.toPath());
        }
        ProcessBuilder builder = new ProcessBuilder(java.getPath(), "-Xmx32m", "-cp", classes.getPath(),
                MediaCacheJournalProcess.class.getName(), new File(directory, "pending-uris.lock").getPath(),
                journal(durable).getPath(), uri(10).toString()).redirectErrorStream(true);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        Process other = builder.start();
        ExecutorService writer = Executors.newSingleThreadExecutor();
        Future<?> publication = null;
        try (BufferedReader output = new BufferedReader(new InputStreamReader(
                other.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("other JVM never held the journal lock", "JOURNAL_LOCKED", output.readLine());
            CountDownLatch started = new CountDownLatch(1);
            publication = writer.submit(() -> {
                started.countDown();
                MediaCache.markPending(context, uri(9));
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertFalse("publisher bypassed the other process's transaction lock", publication.isDone());
            other.getOutputStream().write('x');
            other.getOutputStream().flush();
            assertTrue(other.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, other.exitValue());
            publication.get(10, TimeUnit.SECONDS);
            String records = text(journal(durable));
            assertTrue("legacy ownership was lost", records.contains(uri(7).toString()));
            assertTrue("this process's publication was lost", records.contains(uri(9).toString()));
            assertTrue("the other process's publication was lost", records.contains(uri(10).toString()));
            assertFalse(journal(cache).exists());
        } finally {
            if (publication != null) publication.cancel(true);
            writer.shutdownNow();
            other.destroyForcibly();
            assertTrue(other.waitFor(10, TimeUnit.SECONDS));
            assertTrue(writer.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static Uri uri(int id) { return Uri.withAppendedPath(COLLECTION, Integer.toString(id)); }
    private static long stale() { return System.currentTimeMillis() - MediaCache.STALE_AFTER_MS - 60_000L; }
    private File journal(File root) { return new File(new File(root, MediaCache.DIRECTORY_NAME), "pending-uris.tsv"); }
    private static String text(File file) throws Exception {
        return file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
    }
    private static void write(File file, String value) throws Exception {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
    private static String intent(String name) {
        return "intent:0:" + encode(COLLECTION.toString()) + ":" + encode(name) + ":" + encode(FOLDER);
    }
    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP);
    }
    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        assertTrue(file.delete());
    }

    private void assertInvalidBytesStay(File file) throws Exception {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        byte[] bytes = invalidUtf8Record(7);
        Files.write(file.toPath(), bytes);
        assertThrows(IOException.class, () -> MediaCache.markPending(context, uri(9)));
        assertArrayEquals("the unreadable source bytes changed", bytes, Files.readAllBytes(file.toPath()));
    }

    private void assertUnreadableAtomicFilesStay(File root, boolean backupSelected) throws Exception {
        File base = journal(root);
        File backup = new File(base.getPath() + ".bak");
        File uncommitted = new File(base.getPath() + ".new");
        assertTrue(base.getParentFile().mkdirs());
        byte[] baseBytes = ((backupSelected ? Long.toString(stale()) : "not-a-timestamp")
                + "\t" + uri(7) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] backupBytes = invalidUtf8Record(8);
        byte[] uncommittedBytes = (stale() + "\t" + uri(10) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(base.toPath(), baseBytes);
        if (backupSelected) Files.write(backup.toPath(), backupBytes);
        Files.write(uncommitted.toPath(), uncommittedBytes);

        assertThrows(IOException.class, () -> MediaCache.markPending(context, uri(9)));

        assertArrayEquals("original base bytes changed", baseBytes, Files.readAllBytes(base.toPath()));
        if (backupSelected) {
            assertArrayEquals("original backup bytes changed", backupBytes, Files.readAllBytes(backup.toPath()));
        }
        assertArrayEquals("uncommitted bytes were removed", uncommittedBytes,
                Files.readAllBytes(uncommitted.toPath()));
        if (root.equals(cache)) assertFalse("a durable replacement was written", journal(durable).exists());
    }

    private static byte[] invalidUtf8Record(int id) {
        byte[] bytes = (stale() + "\t" + uri(id) + "\n").getBytes(StandardCharsets.UTF_8);
        bytes[bytes.length - 2] = (byte) 0xC3; // Missing its UTF8 continuation before the newline.
        return bytes;
    }

    /** Models Android AtomicFile's normal return after a failed rename. */
    @Implements(AtomicFile.class)
    public static final class FinishWithoutPublication {
        @Implementation protected void finishWrite(FileOutputStream output) throws IOException { output.close(); }
    }

    /** The base is published, but it does not contain the map the writer committed. */
    @Implements(AtomicFile.class)
    public static final class FinishWithWrongRecords {
        @RealObject private AtomicFile actual;
        @Implementation protected void finishWrite(FileOutputStream output) throws IOException {
            output.close();
            File base = actual.getBaseFile();
            Files.write(base.toPath(), (System.currentTimeMillis()
                    + "\tcontent://media/external_primary/downloads/777\n").getBytes(StandardCharsets.UTF_8));
            // API23 writes the base directly; newer AtomicFile commits from .new.
            File uncommitted = new File(base.getPath() + ".new");
            if (uncommitted.exists()) assertTrue(uncommitted.delete());
        }
    }

    @Implements(AtomicFile.class)
    public static final class WriteFailure {
        @RealObject private AtomicFile actual;
        @Implementation protected FileOutputStream startWrite() throws IOException {
            FileOutputStream output = new FileOutputStream(actual.getBaseFile().getPath() + ".new");
            output.close();
            return output;
        }
    }

    public static final class UnavailableMediaProvider extends ContentProvider {
        static int deletes;
        @Override public boolean onCreate() { deletes = 0; return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            return null;
        }
        @Override public int delete(Uri uri, String selection, String[] args) {
            deletes++;
            throw new UnsupportedOperationException();
        }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
        @Override public String getType(Uri uri) { return null; }
    }
}

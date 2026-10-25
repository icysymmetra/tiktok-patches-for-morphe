/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

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

/** Recovery ownership must outlive deletion of the temporary cache, including process death. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class MediaCacheDurableJournalTest {
    private static final Uri COLLECTION = Uri.parse("content://media/external_primary/downloads");
    private static final String FOLDER = "Download/Hushfeed";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Context context;
    private File cache;
    private File durable;

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

    @Test public void clearingCacheDoesNotLoseTheInterruptedRowsRecoveryOwnership() throws Exception {
        MediaCacheRecoveryTest.FakeMediaProvider.add(7L, "completed.mp4", 0, FOLDER);
        MediaCacheRecoveryTest.FakeMediaProvider.add(8L, "native-pending.mp4", 1, FOLDER);
        MediaCacheRecoveryTest.FakeMediaProvider.add(9L, "interrupted.mp4", 1, FOLDER);
        Uri pending = Uri.withAppendedPath(COLLECTION, "9");
        MediaCache.markPending(context, pending);
        ageCurrentJournal();
        File scratch = new File(cache, "temporary-download.mp4");
        assertTrue(scratch.createNewFile());

        remove(cache);
        assertFalse("the simulated cache clear did not remove cache", cache.exists());
        MediaCache.reconcile(context);

        assertEquals("cache clearing lost the exact pending row's ownership",
                List.of(7L, 8L), MediaCacheRecoveryTest.FakeMediaProvider.ids());
        assertEquals(List.of(9L), MediaCacheRecoveryTest.FakeMediaProvider.deletedIds());
    }

    private File journal(File root) {
        return new File(new File(root, MediaCache.DIRECTORY_NAME), "pending-uris.tsv");
    }

    private void ageCurrentJournal() throws Exception {
        File journal = journal(durable).exists() ? journal(durable) : journal(cache);
        String text = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8);
        long stale = System.currentTimeMillis() - MediaCache.STALE_AFTER_MS - 60_000L;
        StringBuilder aged = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.isEmpty()) aged.append(stale).append(line.substring(line.indexOf('\t'))).append('\n');
        }
        Files.write(journal.toPath(), aged.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        assertTrue("could not remove owned test path " + file, file.delete());
    }
}

package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MediaCacheTest {
    @Test public void reconciliationOnlyRemovesStaleFilesInTheOwnedNamespace() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File owned = new File(context.getCacheDir(), MediaCache.DIRECTORY_NAME);
        if (!owned.isDirectory()) assertTrue(owned.mkdirs());
        File stale = new File(owned, "selected-video-stale.mp4");
        File recent = new File(owned, "selected-video-recent.mp4");
        File unrelated = new File(context.getCacheDir(), "tiktok-unrelated.tmp");
        assertTrue(stale.createNewFile());
        assertTrue(recent.createNewFile());
        assertTrue(unrelated.createNewFile());
        long old = System.currentTimeMillis() - MediaCache.STALE_AFTER_MS - 1_000L;
        assertTrue(stale.setLastModified(old));
        try {
            MediaCache.reconcile(context);
            assertFalse(stale.exists());
            assertTrue(recent.exists());
            assertTrue(unrelated.exists());
        } finally {
            stale.delete();
            recent.delete();
            unrelated.delete();
            owned.delete();
        }
    }

    @Test public void anUnreadableJournalIsNotReplacedByTheNextPendingRow() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File owned = new File(context.getCacheDir(), MediaCache.DIRECTORY_NAME);
        assertTrue(owned.isDirectory() || owned.mkdirs());
        File journal = new File(owned, "pending-uris.tsv");
        assertTrue(journal.mkdir());
        try {
            org.junit.Assert.assertThrows(IOException.class, () -> MediaCache.markPending(
                    context, Uri.parse("content://media/existing")));
            assertTrue("the unreadable journal must remain for recovery", journal.isDirectory());
        } finally {
            journal.delete();
            owned.delete();
        }
    }
}

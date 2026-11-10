/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;

import app.morphe.extension.shared.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

/**
 * The start's sweep runs from TikTok's application attachBaseContext, where the context it gets
 * has no application yet. It took getApplicationContext() and quietly did nothing with the null.
 * Now that it runs, the 24-hour guard is what keeps it off a file a save is still using, and the
 * file this process created is kept at any age.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class MediaCacheOwnershipTest {
    private Context context;
    private File owned;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        SaveRecordsFixtures.reset(context);
        owned = new File(context.getCacheDir(), MediaCache.DIRECTORY_NAME);
        assertTrue(owned.isDirectory() || owned.mkdirs());
    }

    @After public void tearDown() throws Exception {
        File[] files = owned.listFiles();
        if (files != null) for (File file : files) file.delete();
        SaveRecordsFixtures.reset(context);
    }

    private static long stale() {
        return System.currentTimeMillis() - MediaCache.STALE_AFTER_MS - 60_000L;
    }

    /** The context attachBaseContext hands over: everything works but the application behind it. */
    private Context beforeAttach() {
        return new ContextWrapper(context) {
            @Override public Context getApplicationContext() {
                return null;
            }
        };
    }

    @Test public void theSweepRunsFromAContextWithNoApplicationYet() throws Exception {
        File leftover = new File(owned, "selected-video-leftover.mp4");
        assertTrue(leftover.createNewFile());
        assertTrue(leftover.setLastModified(stale()));

        MediaCache.reconcileAsync(beforeAttach());

        for (int wait = 0; wait < 250 && leftover.exists(); wait++) Thread.sleep(20);
        assertFalse("the start's sweep never ran", leftover.exists());
    }

    /**
     * A temporary file this process made is its own until it lets go of it, however old the
     * file looks: the guard reads ownership before age. Let go of, the same age takes it.
     */
    @Test public void aFileThisProcessStillOwnsSurvivesTheSweepAtAnyAge() throws Exception {
        File inUse = MediaCache.createTempFile(context, "selected-video-", ".mp4");
        assertTrue(inUse.setLastModified(stale()));
        File orphan = new File(owned, "selected-video-orphan.mp4");
        assertTrue(orphan.createNewFile());
        assertTrue(orphan.setLastModified(stale()));

        MediaCache.reconcile(beforeAttach());
        assertTrue("the sweep took a file this process is using", inUse.exists());
        assertFalse(orphan.exists());

        // Let go of the way a save lets go of it, then left under the same name by something
        // that isn't this process's save: ownership ended with the let-go.
        assertTrue(MediaCache.delete(inUse));
        assertTrue(inUse.createNewFile());
        assertTrue(inUse.setLastModified(stale()));
        MediaCache.reconcile(beforeAttach());
        assertFalse("a file nobody owns survived past the guard", inUse.exists());
    }

    @Test public void aFailedFinalDeletionLetsTheSweepReclaimTheAbandonedFile() throws Exception {
        File abandoned = MediaCache.createTempFile(context, "selected-video-", ".mp4");
        File inUse = MediaCache.createTempFile(context, "selected-video-", ".mp4");
        byte[] bytes = {1, 2, 3};
        try {
            Files.write(abandoned.toPath(), bytes);
            Files.write(inUse.toPath(), bytes);
            assertFalse("the final deletion should be refused once", MediaCache.delete(refuseDeletion(abandoned)));
            assertArrayEquals(bytes, Files.readAllBytes(abandoned.toPath()));
            assertTrue(abandoned.setLastModified(stale()));
            assertTrue(inUse.setLastModified(stale()));

            MediaCache.reconcile(beforeAttach());

            assertFalse("a finished job's failed cleanup kept permanent ownership", abandoned.exists());
            assertArrayEquals("a still-active job lost its bytes", bytes, Files.readAllBytes(inUse.toPath()));
        } finally {
            MediaCache.delete(abandoned);
            MediaCache.delete(inUse);
        }
    }

    @Test public void anIntermediateDeletionKeepsOwnershipThroughRefusalAndRecreation() throws Exception {
        File inUse = MediaCache.createTempFile(context, "selected-video-", ".mp4");
        byte[] bytes = {4, 5, 6};
        try {
            Files.write(inUse.toPath(), bytes);
            assertFalse(MediaCache.deletePartial(refuseDeletion(inUse)));
            assertTrue(inUse.setLastModified(stale()));
            MediaCache.reconcile(beforeAttach());
            assertArrayEquals(bytes, Files.readAllBytes(inUse.toPath()));

            assertTrue(MediaCache.deletePartial(inUse));
            Files.write(inUse.toPath(), bytes);
            assertTrue(inUse.setLastModified(stale()));
            MediaCache.reconcile(beforeAttach());
            assertArrayEquals("a retry's recreated file lost ownership", bytes, Files.readAllBytes(inUse.toPath()));
        } finally {
            MediaCache.delete(inUse);
        }
    }

    @Test public void aMirrorFallbackKeepsItsRecreatedFileOwnedUntilFinalCleanup() throws Exception {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=");
        MediaTransport.Client transport = MediaTransportFixtures.publicClient(url ->
                MediaTransportFixtures.response(url, url.getPath().endsWith("bad") ? 403 : 200, png));
        File inUse = MediaCache.createTempFile(context, "original-photo-", ".tmp");
        try {
            assertEquals("png", RemoteMedia.fetch(List.of(
                    "https://first.tiktokcdn.com/bad", "https://second.tiktokcdn.com/photo"),
                    inUse, RemoteMedia.Kind.IMAGE, transport));
            assertTrue(inUse.setLastModified(stale()));
            MediaCache.reconcile(beforeAttach());
            assertArrayEquals("the fallback released a file its caller still needs", png,
                    Files.readAllBytes(inUse.toPath()));
        } finally {
            MediaCache.delete(inUse);
        }
    }

    /** Only this cleanup attempt refuses deletion; the sweep sees the ordinary, deletable file. */
    private static File refuseDeletion(File file) {
        return new File(file.getAbsolutePath()) {
            @Override public boolean delete() { return false; }
        };
    }
}

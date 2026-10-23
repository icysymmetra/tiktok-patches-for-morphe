/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Forgetting the saved-video record, and putting it back, around the saves still coming in. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SavedVideoForgetTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(SavedVideoArchive.DATABASE_NAME);
        SavedVideoArchive.resetForTests();
    }

    @After public void tearDown() {
        SavedVideoArchive.resetForTests();
        context.deleteDatabase(SavedVideoArchive.DATABASE_NAME);
    }

    @Test public void forgettingEmptiesTheRecordAndLeavesTheFiles() throws Exception {
        MediaFileWriter.Saved one = saved("one");
        SavedVideoArchive.remember(context, "1", one, SavedVideoArchive.generation());
        assertNotNull(SavedVideoArchive.find(context, "1"));

        long[] forgotten = new long[1];
        assertEquals(SavedVideoArchive.ForgetResult.FORGOTTEN, SavedVideoArchive.forget(context, forgotten));
        assertNull("the record still knew the video", SavedVideoArchive.find(context, "1"));
        assertTrue("the saved file went with the record", one.file.isFile());
        assertTrue(SavedVideoArchive.canUndo(forgotten[0]));

        assertEquals(SavedVideoArchive.UndoResult.RESTORED, SavedVideoArchive.undo(context, forgotten[0]));
        assertNotNull("Undo did not put the video back", SavedVideoArchive.find(context, "1"));
        assertFalse("the Undo could run twice", SavedVideoArchive.canUndo(forgotten[0]));
    }

    @Test public void aSaveAlreadyRunningWhenTheReaderForgotRecordsNothing() throws Exception {
        long startedIn = SavedVideoArchive.generation();
        SavedVideoArchive.forget(context, new long[1]);
        SavedVideoArchive.remember(context, "2", saved("two"), startedIn);
        assertNull("an earlier save refilled the forgotten record", SavedVideoArchive.find(context, "2"));

        // A save that starts afterwards records as before.
        SavedVideoArchive.remember(context, "3", saved("three"), SavedVideoArchive.generation());
        assertNotNull(SavedVideoArchive.find(context, "3"));
    }

    @Test public void aNewerForgetRetiresTheOlderUndo() throws Exception {
        SavedVideoArchive.remember(context, "1", saved("one"), SavedVideoArchive.generation());
        long[] first = new long[1];
        SavedVideoArchive.forget(context, first);

        SavedVideoArchive.remember(context, "2", saved("two"), SavedVideoArchive.generation());
        long[] second = new long[1];
        SavedVideoArchive.forget(context, second);

        assertEquals("the first forget's Undo brought back a record forgotten since",
                SavedVideoArchive.UndoResult.EXPIRED, SavedVideoArchive.undo(context, first[0]));
        assertNull(SavedVideoArchive.find(context, "1"));
        assertNull(SavedVideoArchive.find(context, "2"));

        assertEquals(SavedVideoArchive.UndoResult.RESTORED, SavedVideoArchive.undo(context, second[0]));
        assertNotNull("the newer Undo lost its own video", SavedVideoArchive.find(context, "2"));
        assertNull("the newer Undo put back what the older forget took", SavedVideoArchive.find(context, "1"));
    }

    @Test public void undoKeepsASaveOfTheSameVideoMadeSince() throws Exception {
        SavedVideoArchive.remember(context, "1", saved("old-name"), SavedVideoArchive.generation());
        long[] forgotten = new long[1];
        SavedVideoArchive.forget(context, forgotten);

        // The same video saved again after the forget: the newer row wins over the restored one.
        MediaFileWriter.Saved again = saved("new-name");
        SavedVideoArchive.remember(context, "1", again, SavedVideoArchive.generation());
        SavedVideoArchive.undo(context, forgotten[0]);
        assertEquals("new-name.mp4", SavedVideoArchive.find(context, "1").name);
    }

    @Test public void undoNeverTakesTheRecordPastItsLimit() throws Exception {
        SavedVideoArchive.remember(context, "forgotten", saved("forgotten"), SavedVideoArchive.generation());
        long[] forgotten = new long[1];
        SavedVideoArchive.forget(context, forgotten);
        // A full record of newer saves since the forget.
        try (var db = context.openOrCreateDatabase(SavedVideoArchive.DATABASE_NAME, 0, null)) {
            db.beginTransaction();
            try {
                long newer = System.currentTimeMillis() + 60_000L;
                for (int i = 0; i < SavedVideoArchive.LIMIT; i++) db.execSQL(
                        "INSERT INTO saved_videos(aid,name,uri,path,saved_at) VALUES(?,?,?,?,?)",
                        new Object[]{"newer-" + i, "newer.mp4", "", "", newer + i});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        assertEquals(SavedVideoArchive.UndoResult.RESTORED, SavedVideoArchive.undo(context, forgotten[0]));
        try (var db = context.openOrCreateDatabase(SavedVideoArchive.DATABASE_NAME, 0, null);
             var count = db.rawQuery("SELECT COUNT(*) FROM saved_videos", null);
             var oldest = db.rawQuery("SELECT COUNT(*) FROM saved_videos WHERE aid='forgotten'", null)) {
            assertTrue(count.moveToFirst());
            assertEquals(SavedVideoArchive.LIMIT, count.getInt(0));
            assertTrue(oldest.moveToFirst());
            assertEquals("the oldest row, the restored one, should be the one to go", 0, oldest.getInt(0));
        }
    }

    @Test public void theRecordIsForgottenOnDiskAndTheUndoOnlyInMemory() throws Exception {
        SavedVideoArchive.remember(context, "1", saved("one"), SavedVideoArchive.generation());
        long[] forgotten = new long[1];
        SavedVideoArchive.forget(context, forgotten);

        // A new process: the database says what it said, and the Undo is gone with the old one.
        SavedVideoArchive.resetForTests();
        assertNull(SavedVideoArchive.find(context, "1"));
        assertEquals(SavedVideoArchive.UndoResult.EXPIRED, SavedVideoArchive.undo(context, forgotten[0]));
    }

    @Test public void anExpiredUndoIsDropped() throws Exception {
        SavedVideoArchive.remember(context, "1", saved("one"), SavedVideoArchive.generation());
        long[] forgotten = new long[1];
        SavedVideoArchive.forget(context, forgotten);
        SavedVideoArchive.discardUndo(forgotten[0]);
        assertFalse(SavedVideoArchive.canUndo(forgotten[0]));
        assertEquals(SavedVideoArchive.UndoResult.EXPIRED, SavedVideoArchive.undo(context, forgotten[0]));
        assertNull(SavedVideoArchive.find(context, "1"));
    }

    @Test public void forgettingNothingSaysSoAndOffersNoUndo() {
        long[] forgotten = new long[1];
        assertEquals(SavedVideoArchive.ForgetResult.NOTHING_SAVED, SavedVideoArchive.forget(context, forgotten));
        assertFalse(SavedVideoArchive.canUndo(forgotten[0]));
    }

    @Test public void aDatabaseThatCannotBeOpenedChangesNothing() throws Exception {
        SavedVideoArchive.remember(context, "1", saved("one"), SavedVideoArchive.generation());
        long[] earlier = new long[1];
        SavedVideoArchive.forget(context, earlier);
        long generation = SavedVideoArchive.generation();

        // Where the database should be, a directory SQLite cannot open.
        File database = context.getDatabasePath(SavedVideoArchive.DATABASE_NAME);
        context.deleteDatabase(SavedVideoArchive.DATABASE_NAME);
        assertTrue(database.mkdirs());
        try {
            assertEquals(SavedVideoArchive.ForgetResult.FAILED, SavedVideoArchive.forget(context, new long[1]));
            assertEquals("a failed forget moved the generation", generation, SavedVideoArchive.generation());
            assertTrue("a failed forget took the earlier Undo away", SavedVideoArchive.canUndo(earlier[0]));
            assertEquals(SavedVideoArchive.UndoResult.FAILED, SavedVideoArchive.undo(context, earlier[0]));
            assertTrue("a failed Undo gave up its rows", SavedVideoArchive.canUndo(earlier[0]));
        } finally {
            assertTrue(database.delete());
        }
    }

    private MediaFileWriter.Saved saved(String name) throws IOException {
        File file = new File(context.getCacheDir(), name + ".mp4");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        return new MediaFileWriter.Saved(name + ".mp4", null, file);
    }
}

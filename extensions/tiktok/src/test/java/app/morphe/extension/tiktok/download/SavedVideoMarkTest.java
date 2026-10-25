/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.feed.ProfileGridCount;
import app.morphe.extension.tiktok.publishdate.AlwaysShowPublishDatePatch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** The check mark profile grids put on videos saved here, and the ids in memory it reads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SavedVideoMarkTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;

    private static final String MARKED = SavedVideoMark.MARK + " 12.3K";

    /** A grid cell's item as the bind hands it over. */
    public static final class Cell extends Aweme {
        final String aid;

        Cell(String aid) {
            this.aid = aid;
        }

        @Override public String getAid() { return aid; }
    }

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Utils.awaitBackgroundTasksForTests();
        context.deleteDatabase(SavedVideoArchive.DATABASE_NAME);
        SavedVideoArchive.resetForTests();
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.CHECK_SAVED_VIDEOS.save(true);
        Settings.MARK_SAVED_VIDEOS.save(true);
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        PausedProcess.set(false);
        SettingsStatus.advancedDownloadsEnabled = false;
        Settings.CHECK_SAVED_VIDEOS.save(false);
        Settings.MARK_SAVED_VIDEOS.save(false);
        SavedVideoArchive.resetForTests();
        File database = context.getDatabasePath(SavedVideoArchive.DATABASE_NAME);
        if (database.isDirectory()) database.delete();
        context.deleteDatabase(SavedVideoArchive.DATABASE_NAME);
    }

    @Test public void theSwitchStartsOffAndWaitsOnTheCheck() {
        assertFalse(Settings.MARK_SAVED_VIDEOS.defaultValue);
        assertTrue(Settings.MARK_SAVED_VIDEOS.getParentSettings().contains(Settings.CHECK_SAVED_VIDEOS));
        assertTrue(Settings.MARK_SAVED_VIDEOS.isAvailable());
        Settings.CHECK_SAVED_VIDEOS.save(false);
        assertFalse("the row stayed open with the check off", Settings.MARK_SAVED_VIDEOS.isAvailable());
    }

    @Test public void theFirstCellAsksWithoutWaitingAndLaterCellsAreMarked() throws Exception {
        SavedVideoArchive.remember(context, "7", saved("seven"), SavedVideoArchive.generation());
        // A new process: the row is on disk and nothing is in memory.
        SavedVideoArchive.resetForTests();

        assertEquals("the first cell waited on the database", "12.3K",
                SavedVideoMark.gridText("12.3K", new Cell("7")));
        Utils.awaitBackgroundTasksForTests();
        assertTrue("the ids were never read", SavedVideoArchive.idsReadForTests());

        assertEquals(MARKED, SavedVideoMark.gridText("12.3K", new Cell("7")));
        assertEquals("a video that isn't saved got the mark", "4.1M",
                SavedVideoMark.gridText("4.1M", new Cell("8")));
    }

    @Test public void theGridCountCarriesTheMark() throws Exception {
        SavedVideoArchive.remember(context, "7", saved("seven"), SavedVideoArchive.generation());
        readIds();
        assertEquals(MARKED, ProfileGridCount.text("12.3K", new Cell("7")));
        assertEquals("12.3K", ProfileGridCount.text("12.3K", new Cell("8")));
        assertNull(ProfileGridCount.text(null, new Cell("7")));
        assertEquals("an item that isn't a video got the mark", "12.3K",
                SavedVideoMark.gridText("12.3K", "7"));
        assertEquals("12.3K", SavedVideoMark.gridText("12.3K", new Cell(null)));
    }

    @Test public void aFeedVideosCreatorRowCarriesTheMarkBeforeItsTime() throws Exception {
        SavedVideoArchive.remember(context, "7", saved("seven"), SavedVideoArchive.generation());
        readIds();
        String mark = SavedVideoMark.MARK + " ";
        assertEquals(mark + "2d ago", AlwaysShowPublishDatePatch.postTime("2d ago", new Cell("7")));
        assertEquals("2d ago", AlwaysShowPublishDatePatch.postTime("2d ago", new Cell("8")));
        Settings.MARK_SAVED_VIDEOS.save(false);
        assertEquals("2d ago", AlwaysShowPublishDatePatch.postTime("2d ago", new Cell("7")));
    }

    @Test public void theMarkNeedsBothSwitchesAndAdvancedDownloadsAndStopsWhilePaused() throws Exception {
        SavedVideoArchive.remember(context, "7", saved("seven"), SavedVideoArchive.generation());
        readIds();
        Cell cell = new Cell("7");
        assertEquals(MARKED, SavedVideoMark.gridText("12.3K", cell));

        Settings.MARK_SAVED_VIDEOS.save(false);
        assertEquals("12.3K", SavedVideoMark.gridText("12.3K", cell));
        Settings.MARK_SAVED_VIDEOS.save(true);

        Settings.CHECK_SAVED_VIDEOS.save(false);
        assertEquals("the mark showed while its record isn't kept", "12.3K",
                SavedVideoMark.gridText("12.3K", cell));
        Settings.CHECK_SAVED_VIDEOS.save(true);

        SettingsStatus.advancedDownloadsEnabled = false;
        assertEquals("12.3K", SavedVideoMark.gridText("12.3K", cell));
        SettingsStatus.advancedDownloadsEnabled = true;

        PausedProcess.set(true);
        assertEquals("Pause Metra left the mark on", "12.3K", SavedVideoMark.gridText("12.3K", cell));
        PausedProcess.set(false);
        assertEquals(MARKED, SavedVideoMark.gridText("12.3K", cell));
    }

    @Test public void nothingIsReadWhileTheMarkIsOff() throws Exception {
        Settings.MARK_SAVED_VIDEOS.save(false);
        SavedVideoMark.gridText("12.3K", new Cell("7"));
        SavedVideoMark.warm();
        Utils.awaitBackgroundTasksForTests();
        assertFalse("the ids were read for a switch that's off", SavedVideoArchive.idsReadForTests());

        Settings.MARK_SAVED_VIDEOS.save(true);
        SavedVideoMark.warm();
        Utils.awaitBackgroundTasksForTests();
        assertTrue("the start didn't get the marks ready", SavedVideoArchive.idsReadForTests());
    }

    @Test public void savesForgetsAndUndoKeepTheMarksInStep() throws Exception {
        readIds();
        Cell cell = new Cell("7");
        assertEquals("12.3K", SavedVideoMark.gridText("12.3K", cell));

        SavedVideoArchive.remember(context, "7", saved("seven"), SavedVideoArchive.generation());
        assertEquals("a save that landed left its cell unmarked", MARKED, SavedVideoMark.gridText("12.3K", cell));

        long[] forgotten = new long[1];
        assertEquals(SavedVideoArchive.ForgetResult.FORGOTTEN, SavedVideoArchive.forget(context, forgotten));
        assertEquals("a forgotten video kept its mark", "12.3K", SavedVideoMark.gridText("12.3K", cell));

        assertEquals(SavedVideoArchive.UndoResult.RESTORED, SavedVideoArchive.undo(context, forgotten[0]));
        assertEquals("Undo didn't bring the mark back", MARKED, SavedVideoMark.gridText("12.3K", cell));
        assertTrue(SavedVideoArchive.idsReadForTests());
    }

    @Test public void aSaveTheReaderForgotWhileItRanLeavesNoMark() throws Exception {
        readIds();
        long startedIn = SavedVideoArchive.generation();
        SavedVideoArchive.forget(context, new long[1]);
        SavedVideoArchive.remember(context, "7", saved("seven"), startedIn);
        assertEquals("12.3K", SavedVideoMark.gridText("12.3K", new Cell("7")));
    }

    @Test public void aVideoTheLimitPushesOffLosesItsMark() throws Exception {
        // The table comes with the first save. Then a full record, older than that save.
        SavedVideoArchive.remember(context, "first", saved("first"), SavedVideoArchive.generation());
        try (var db = context.openOrCreateDatabase(SavedVideoArchive.DATABASE_NAME, 0, null)) {
            db.beginTransaction();
            try {
                for (int i = 0; i < SavedVideoArchive.LIMIT - 1; i++) db.execSQL(
                        "INSERT INTO saved_videos(aid,name,uri,path,saved_at) VALUES(?,?,?,?,?)",
                        new Object[]{"old-" + i, "old.mp4", "", "", 1_000L + i});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        readIds();
        assertEquals(MARKED, SavedVideoMark.gridText("12.3K", new Cell("old-0")));

        SavedVideoArchive.remember(context, "newest", saved("newest"), SavedVideoArchive.generation());
        assertNull(SavedVideoArchive.find(context, "old-0"));
        assertEquals("the oldest save left the record and kept its mark", "12.3K",
                SavedVideoMark.gridText("12.3K", new Cell("old-0")));
        assertEquals(MARKED, SavedVideoMark.gridText("12.3K", new Cell("old-1")));
        assertEquals(MARKED, SavedVideoMark.gridText("12.3K", new Cell("newest")));
        assertEquals(MARKED, SavedVideoMark.gridText("12.3K", new Cell("first")));
    }

    @Test public void aRecordThatCannotBeReadIsTriedAgainLaterNotForEveryCell() throws Exception {
        // Where the database should be, a directory SQLite cannot open.
        File database = context.getDatabasePath(SavedVideoArchive.DATABASE_NAME);
        assertTrue(database.mkdirs());
        assertEquals("12.3K", SavedVideoMark.gridText("12.3K", new Cell("7")));
        Utils.awaitBackgroundTasksForTests();
        assertFalse(SavedVideoArchive.idsReadForTests());

        assertTrue(database.delete());
        SavedVideoMark.gridText("12.3K", new Cell("7"));
        Utils.awaitBackgroundTasksForTests();
        assertFalse("a failed read was tried again at once", SavedVideoArchive.idsReadForTests());

        ShadowSystemClock.advanceBy(SavedVideoArchive.IDS_RETRY_MS, TimeUnit.MILLISECONDS);
        SavedVideoMark.gridText("12.3K", new Cell("7"));
        Utils.awaitBackgroundTasksForTests();
        assertTrue("the read was never tried again", SavedVideoArchive.idsReadForTests());
    }

    private void readIds() throws Exception {
        SavedVideoArchive.readIdsLater();
        Utils.awaitBackgroundTasksForTests();
        assertTrue("the ids were never read", SavedVideoArchive.idsReadForTests());
    }

    private MediaFileWriter.Saved saved(String name) throws IOException {
        File file = new File(context.getCacheDir(), name + ".mp4");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        return new MediaFileWriter.Saved(name + ".mp4", null, file);
    }
}

package app.morphe.extension.tiktok.seen;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.feedfilter.SeenVideoFilter;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SeenVideoHistoryTest {
    /** The account most tests are signed in as. */
    private static final String ME = "me";

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SignedInUser.idForTests = ME;
        Settings.HIDE_SEEN_VIDEOS.save(true);
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(30);
        Settings.SEEN_VIDEO_MARK_PERCENT.save(0);
        SeenVideoHistory.clear();
        drain();
        // Other accounts' rows outlive a clear on purpose, so the table is emptied by hand.
        database().execSQL("DELETE FROM seen_videos");
        SeenVideoHistory.size();
        drain();
    }

    @After public void tearDown() {
        SeenVideoHistory.watchClock = SeenVideoHistory.SYSTEM_CLOCK;
        SignedInUser.idForTests = null;
        SignedInUser.handleForTests = null;
    }

    @Test public void aChosenShareDecidesWhenALongVideoCountsAsSeen() throws Exception {
        // Five seconds used to hide a ten-minute video for good. At half, it takes five minutes.
        Settings.SEEN_VIDEO_MARK_PERCENT.save(50);
        SeenVideoHistory.onPlayProgressChange("long", 5_000, 600_000);
        SeenVideoHistory.onPlayProgressChange("long", 299_999, 600_000);
        drain();
        assertFalse(SeenVideoHistory.shouldHide("long"));
        SeenVideoHistory.onPlayProgressChange("long", 300_000, 600_000);
        drain();
        assertTrue(SeenVideoHistory.shouldHide("long"));
    }

    @Test public void withNoShareChosenTheFewSecondsRuleStays() throws Exception {
        SeenVideoHistory.onPlayProgressChange("long", 5_000, 600_000);
        drain();
        assertTrue(SeenVideoHistory.shouldHide("long"));
        assertFalse(SeenVideoHistory.hasReachedSeenThreshold(999, 5_000));
        assertTrue(SeenVideoHistory.hasReachedSeenThreshold(1_000, 5_000));
        assertFalse(SeenVideoHistory.hasReachedSeenThreshold(4_999, 600_000));
    }

    /** A clip under a second never plays to the one-second mark, so it looped back unseen. */
    @Test public void aClipShorterThanTheFloorIsSeenAtHalfItsLength() {
        assertFalse(SeenVideoHistory.hasReachedSeenThreshold(399, 800));
        assertTrue(SeenVideoHistory.hasReachedSeenThreshold(400, 800));
        // A clip that does reach a second keeps the second, as before.
        Settings.SEEN_VIDEO_MARK_PERCENT.save(50);
        assertFalse(SeenVideoHistory.hasReachedSeenThreshold(999, 1_500));
        assertTrue(SeenVideoHistory.hasReachedSeenThreshold(1_000, 1_500));
        Settings.SEEN_VIDEO_MARK_PERCENT.resetToDefault();
    }

    @Test public void aShareIsHeldInsideTheClipAndUnknownLengthsKeepTwoSeconds() {
        Settings.SEEN_VIDEO_MARK_PERCENT.save(90);
        // 90% of three seconds is 2.7 s, past the last second, where a report may never land.
        assertFalse(SeenVideoHistory.hasReachedSeenThreshold(1_999, 3_000));
        assertTrue(SeenVideoHistory.hasReachedSeenThreshold(2_000, 3_000));
        Settings.SEEN_VIDEO_MARK_PERCENT.save(10);
        // A tenth of a four-second clip is under the one-second floor.
        assertFalse(SeenVideoHistory.hasReachedSeenThreshold(999, 4_000));
        assertTrue(SeenVideoHistory.hasReachedSeenThreshold(1_000, 4_000));
        // No length, no share of it: two seconds, as before.
        assertFalse(SeenVideoHistory.hasReachedSeenThreshold(1_999, 0));
        assertTrue(SeenVideoHistory.hasReachedSeenThreshold(2_000, -1));
    }

    @Test public void clearingKeepsAWayBackUntilTheNextClear() throws Exception {
        SeenVideoHistory.onPlayProgressChange("42", 5000, 10000);
        SeenVideoHistory.onPlayProgressChange("43", 5000, 10000);
        drain();
        assertEquals(2, SeenVideoHistory.size());

        SeenVideoHistory.clear();
        drain();
        assertEquals(0, SeenVideoHistory.size());
        assertTrue(SeenVideoHistory.canUndo());
        assertEquals(2, SeenVideoHistory.undoSize());
        assertFalse(SeenVideoHistory.shouldHide("42"));

        assertTrue(SeenVideoHistory.undoClear());
        drain();
        assertEquals(2, SeenVideoHistory.size());
        assertTrue(SeenVideoHistory.shouldHide("42"));
        assertTrue(SeenVideoHistory.shouldHide("43"));
        // The way back is used up once it has been taken.
        assertFalse(SeenVideoHistory.canUndo());
        assertEquals(0, SeenVideoHistory.undoSize());
        assertFalse(SeenVideoHistory.undoClear());
    }

    @Test public void whatWasPutBackSurvivesAReload() throws Exception {
        SeenVideoHistory.onPlayProgressChange("77", 5000, 10000);
        drain();
        SeenVideoHistory.clear();
        drain();
        assertTrue(SeenVideoHistory.undoClear());
        drain();

        // The rows go back to the database, not just to memory: a reload finds them again.
        Field started = SeenVideoHistory.class.getDeclaredField("LOAD_STARTED");
        started.setAccessible(true);
        ((AtomicBoolean) started.get(null)).set(false);
        Field seen = SeenVideoHistory.class.getDeclaredField("SEEN");
        seen.setAccessible(true);
        ((java.util.Map<?, ?>) seen.get(null)).clear();

        // The reload runs on the same worker, so the first read only starts it.
        SeenVideoHistory.size();
        drain();

        assertEquals(1, SeenVideoHistory.size());
        assertTrue(SeenVideoHistory.shouldHide("77"));
    }

    @Test public void nothingToClearMeansNothingToPutBack() throws Exception {
        assertEquals(0, SeenVideoHistory.size());
        SeenVideoHistory.clear();
        drain();
        assertEquals(0, SeenVideoHistory.undoSize());
        assertFalse(SeenVideoHistory.undoClear());
    }

    @Test public void aClearBeforeTheHistoryLoadsStillKeepsAWayBack() throws Exception {
        // The rows are on disk but memory has never been loaded, which is what happens when
        // the feature is off. Clearing has to read the database, not the empty map.
        SeenVideoHistory.onPlayProgressChange("91", 5000, 10000);
        SeenVideoHistory.onPlayProgressChange("92", 5000, 10000);
        drain();

        Field started = SeenVideoHistory.class.getDeclaredField("LOAD_STARTED");
        started.setAccessible(true);
        ((AtomicBoolean) started.get(null)).set(false);
        Field seen = SeenVideoHistory.class.getDeclaredField("SEEN");
        seen.setAccessible(true);
        ((java.util.Map<?, ?>) seen.get(null)).clear();

        SeenVideoHistory.clear();
        drain();
        assertEquals("the copy comes off the database", 2, SeenVideoHistory.undoSize());

        assertTrue(SeenVideoHistory.undoClear());
        drain();
        assertTrue(SeenVideoHistory.shouldHide("91"));
        assertTrue(SeenVideoHistory.shouldHide("92"));
    }

    @Test public void refreshedPageRejectsTheVideoJustWatched() throws Exception {
        SeenVideoHistory.onPlayProgressChange("42", 5000, 10000);
        drain();
        assertTrue(SeenVideoHistory.shouldHide("42"));
    }

    /** The feed activity built again for a new window width, issue #26's second report. */
    @Test public void theVideoOnScreenIsKeptThroughARebuildUntilPlaybackMovesOn() throws Exception {
        SeenVideoHistory.onPlayProgressChange("41", 5000, 10000);
        SeenVideoHistory.onPlayProgressChange("42", 5000, 10000);
        drain();
        assertTrue(SeenVideoHistory.shouldHide("42"));

        SeenVideoHistory.keepThroughRebuild();
        assertFalse("the video that was playing", SeenVideoHistory.shouldHide("42"));
        assertTrue("an earlier video", SeenVideoHistory.shouldHide("41"));
        // TikTok restores the kept video and plays it on: still kept.
        SeenVideoHistory.onPlayProgressChange("42", 6000, 10000);
        assertFalse(SeenVideoHistory.shouldHide("42"));

        SeenVideoHistory.onPlayProgressChange("43", 0, 10000);
        assertTrue("once the user swiped on, a refreshed page drops it again", SeenVideoHistory.shouldHide("42"));
    }

    @Test public void aRebuildWithNothingPlayingKeepsNothing() throws Exception {
        SeenVideoHistory.onPlayProgressChange("42", 5000, 10000);
        drain();
        SeenVideoHistory.clear();
        drain();
        SeenVideoHistory.keepThroughRebuild();
        SeenVideoHistory.onPlayProgressChange("42", 5000, 10000);
        drain();
        assertTrue(SeenVideoHistory.shouldHide("42"));
    }

    @Test public void disabledFilterPreservesWatchedVideos() throws Exception {
        SeenVideoHistory.onPlayProgressChange("42", 5000, 10000);
        drain();
        Settings.HIDE_SEEN_VIDEOS.save(false);
        assertFalse(SeenVideoHistory.shouldHide("42"));
    }

    @Test public void initialFeedFilteringLoadsRetainedHistoryAndPrunesExpiredSqlRows() throws Exception {
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(7);
        long now = System.currentTimeMillis();
        insert("recent", now - TimeUnit.DAYS.toMillis(2));
        insert("expired", now - TimeUnit.DAYS.toMillis(8));
        resetLoadedMemory();
        SeenVideoFilter filter = new SeenVideoFilter();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        io().execute(() -> {
            ready.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(filter.getEnabled());
            assertFalse("the first feed read must not wait for SQLite", filter.getFiltered(video("recent")));
            assertEquals(Set.of("recent", "expired"), persistedIds());
        } finally {
            release.countDown();
        }
        drain();

        assertEquals("initial loading must remove expired rows from SQLite",
                Set.of("recent"), persistedIds());
        assertTrue("the loaded record never reached the feed filter", filter.getFiltered(video("recent")));
        assertFalse(filter.getFiltered(video("expired")));
        assertFalse(filter.getFiltered(video("unwatched")));
        assertEquals(1, SeenVideoHistory.size());
    }

    @Test public void filteringAnExpiredLoadedVideoRemovesOnlyItsPersistedRecord() throws Exception {
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(0);
        long now = System.currentTimeMillis();
        insert("expired", now - TimeUnit.DAYS.toMillis(2));
        insert("recent", now);
        resetLoadedMemory();
        SeenVideoFilter filter = new SeenVideoFilter();
        filter.getFiltered(video("expired"));
        drain();
        assertTrue("zero retention must preserve the older record", filter.getFiltered(video("expired")));
        assertTrue(filter.getFiltered(video("recent")));
        assertEquals(Set.of("expired", "recent"), persistedIds());

        Settings.SEEN_VIDEO_RETENTION_DAYS.save(1);
        assertFalse(filter.getFiltered(video("expired")));
        assertTrue(filter.getFiltered(video("recent")));
        drain();
        assertEquals("filtering expiry must also delete its SQLite row",
                Set.of("recent"), persistedIds());
        assertEquals(1, SeenVideoHistory.size());
    }

    @Test public void theTwoHundredthRecordedProgressPrunesSqlAndMemoryWithoutPruningEarly() throws Exception {
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(0);
        long now = System.currentTimeMillis();
        insert("expired", now - TimeUnit.DAYS.toMillis(2));
        insert("recent", now);
        resetLoadedMemory();
        SeenVideoFilter filter = new SeenVideoFilter();
        filter.getFiltered(video("recent"));
        drain();
        assertTrue(filter.getFiltered(video("expired")));
        ((AtomicInteger) field("writesSincePrune")).set(0);
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(1);

        for (int number = 1; number <= 199; number++) {
            SeenVideoHistory.onPlayProgressChange("watched-" + number, 1000, 10000);
        }
        // Later progress for the same video and a new video below the threshold are not writes.
        SeenVideoHistory.onPlayProgressChange("watched-199", 9000, 10000);
        SeenVideoHistory.onPlayProgressChange("watched-200", 999, 10000);
        drain();
        Set<String> before = persistedIds();
        assertEquals(201, before.size());
        assertTrue("the sweep ran before 200 recorded videos", before.contains("expired"));
        assertFalse(before.contains("watched-200"));
        assertEquals(201, SeenVideoHistory.size());

        SeenVideoHistory.onPlayProgressChange("watched-200", 1000, 10000);
        drain();
        Set<String> after = persistedIds();
        assertFalse("the 200th progress write never pruned expired SQLite history", after.contains("expired"));
        assertEquals(201, after.size());
        assertEquals("the same sweep must discard expired memory entries", 201, SeenVideoHistory.size());
        assertTrue(filter.getFiltered(video("recent")));
        assertTrue(filter.getFiltered(video("watched-1")));
        assertTrue(filter.getFiltered(video("watched-200")));
        assertFalse(filter.getFiltered(video("expired")));
    }

    private static Aweme video(String aid) {
        return new Aweme() { @Override public String getAid() { return aid; } };
    }

    private static void insert(String aid, long seenAt) throws Exception {
        database().execSQL("INSERT INTO seen_videos (account, aid, last_seen_ms) VALUES (?, ?, ?)", new Object[]{ME, aid, seenAt});
    }

    private static void resetLoadedMemory() throws Exception {
        ((AtomicBoolean) field("LOAD_STARTED")).set(false);
        ((java.util.Map<?, ?>) field("SEEN")).clear();
    }

    private static Set<String> persistedIds() throws Exception {
        Set<String> result = new HashSet<>();
        try (android.database.Cursor cursor = database().rawQuery(
                "SELECT aid FROM seen_videos WHERE account = ?", new String[]{ME})) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }

    /** Every row on disk as "account|aid", unowned rows with an empty account. */
    private static Set<String> rows() throws Exception {
        Set<String> result = new HashSet<>();
        try (android.database.Cursor cursor = database().rawQuery("SELECT account, aid FROM seen_videos", null)) {
            while (cursor.moveToNext()) result.add(cursor.getString(0) + "|" + cursor.getString(1));
        }
        return result;
    }

    @Test public void pendingLoadCannotRestoreClearedHistory() throws Exception {
        database().execSQL("INSERT INTO seen_videos (account, aid, last_seen_ms) VALUES ('me', 'old', ?)",
                new Object[]{System.currentTimeMillis()});
        ((AtomicBoolean) field("LOAD_STARTED")).set(false);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        io().execute(() -> {
            ready.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        try {
            SeenVideoHistory.size();
            SeenVideoHistory.clear();
        } finally { release.countDown(); }
        drain();
        assertEquals(0, SeenVideoHistory.size());
        assertFalse(SeenVideoHistory.shouldHide("old"));
    }

    @Test public void failedInitialOpenCanRetryAndLoadHistory() throws Exception {
        Field databaseField = SeenVideoHistory.class.getDeclaredField("database");
        databaseField.setAccessible(true);
        Object previousDatabase = databaseField.get(null);
        databaseField.set(null, null);
        ((AtomicBoolean) field("LOAD_STARTED")).set(false);

        Field factoryField = SeenVideoHistory.class.getDeclaredField("databaseFactory");
        factoryField.setAccessible(true);
        Object previousFactory = factoryField.get(null);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        factoryField.set(null, (SeenVideoHistory.DatabaseFactory) context ->
                new SeenVideoHistory.Database(context) {
                    @Override public SQLiteDatabase getReadableDatabase() {
                        if (failOnce.compareAndSet(true, false)) {
                            throw new IllegalStateException("injected open failure");
                        }
                        return super.getReadableDatabase();
                    }
                });
        try {
            SeenVideoHistory.size();
            drain();
            assertFalse("a failed open must allow a later retry",
                    ((AtomicBoolean) field("LOAD_STARTED")).get());

            // The failed helper remains usable once the transient open error is gone.
            database().execSQL("INSERT OR REPLACE INTO seen_videos (account, aid, last_seen_ms) VALUES ('me', 'retry', ?)",
                    new Object[]{System.currentTimeMillis()});
            SeenVideoHistory.size();
            drain();
            try (android.database.Cursor cursor = database().rawQuery(
                    "SELECT aid FROM seen_videos WHERE aid = 'retry'", null)) {
                assertEquals("the retried database should still contain the row", 1, cursor.getCount());
            }
            assertEquals("the retried load should see the persisted row", 1, SeenVideoHistory.size());
            assertTrue(SeenVideoHistory.shouldHide("retry"));
        } finally {
            factoryField.set(null, previousFactory);
            databaseField.set(null, previousDatabase);
            failOnce.set(false);
        }
    }

    @Test public void failedInitialOpenAfterClearStillAllowsALaterRetry() throws Exception {
        Field databaseField = SeenVideoHistory.class.getDeclaredField("database");
        databaseField.setAccessible(true);
        Object previousDatabase = databaseField.get(null);
        databaseField.set(null, null);
        Field factoryField = SeenVideoHistory.class.getDeclaredField("databaseFactory");
        factoryField.setAccessible(true);
        Object previousFactory = factoryField.get(null);
        CountDownLatch openStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        factoryField.set(null, (SeenVideoHistory.DatabaseFactory) context ->
                new SeenVideoHistory.Database(context) {
                    @Override public SQLiteDatabase getReadableDatabase() {
                        if (failOnce.compareAndSet(true, false)) {
                            openStarted.countDown();
                            try { release.await(5, TimeUnit.SECONDS); }
                            catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                            throw new IllegalStateException("injected open failure after clear");
                        }
                        return super.getReadableDatabase();
                    }
                });
        ((AtomicBoolean) field("LOAD_STARTED")).set(false);
        try {
            SeenVideoHistory.size();
            assertTrue(openStarted.await(5, TimeUnit.SECONDS));
            SeenVideoHistory.clear(); // Advances the generation while the first open waits.
            release.countDown();
            drain();
            assertFalse("a failed open after clear must release the retry gate",
                    ((AtomicBoolean) field("LOAD_STARTED")).get());
        } finally {
            release.countDown();
            factoryField.set(null, previousFactory);
            databaseField.set(null, previousDatabase);
            failOnce.set(false);
        }
    }

    @Test public void failedUndoCommitRetainsRecoveryAndReportsFailure() throws Exception {
        SeenVideoHistory.onPlayProgressChange("failed-undo", 5000, 10000);
        drain();
        SeenVideoHistory.clear();
        drain();
        assertEquals(1, SeenVideoHistory.undoSize());

        Field writerField = SeenVideoHistory.class.getDeclaredField("rowWriter");
        writerField.setAccessible(true);
        Object previousWriter = writerField.get(null);
        writerField.set(null, (SeenVideoHistory.RowWriter) (database, values) -> -1L);
        AtomicReference<SeenVideoHistory.UndoResult> result = new AtomicReference<>();
        CountDownLatch callback = new CountDownLatch(1);
        try {
            assertTrue(SeenVideoHistory.undoClear(undoResult -> {
                result.set(undoResult);
                callback.countDown();
            }));
            drain();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(callback.await(5, TimeUnit.SECONDS));
            assertEquals(SeenVideoHistory.UndoResult.FAILED, result.get());
            assertTrue("a rejected insert must keep the recovery copy", SeenVideoHistory.canUndo());
            assertEquals(1, SeenVideoHistory.undoSize());
        } finally {
            writerField.set(null, previousWriter);
        }

        assertTrue(SeenVideoHistory.undoClear());
        drain();
        assertFalse(SeenVideoHistory.canUndo());
        assertTrue(SeenVideoHistory.shouldHide("failed-undo"));
    }

    @Test public void staleExpiryCannotDeleteARefreshedRecord() throws Exception {
        long now = System.currentTimeMillis();
        database().execSQL("INSERT INTO seen_videos (account, aid, last_seen_ms) VALUES ('me', '42', ?)", new Object[]{now});
        Method delete = SeenVideoHistory.class.getDeclaredMethod("deleteAsync", String.class, String.class, long.class);
        delete.setAccessible(true);
        delete.invoke(null, ME, "42", now - 1000);
        drain();
        try (android.database.Cursor c = database().rawQuery("SELECT aid FROM seen_videos", null)) {
            assertEquals(1, c.getCount());
        }
    }

    @Test public void historyHasAHardLimitEvenWithoutAgeRetention() throws Exception {
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(0);
        SQLiteDatabase db = database();
        db.beginTransaction();
        try {
            for (int i = 0; i < 10005; i++) {
                db.execSQL("INSERT INTO seen_videos (account, aid, last_seen_ms) VALUES (?, ?, ?)", new Object[]{ME, "id" + i, (long) i});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        ((AtomicBoolean) field("LOAD_STARTED")).set(false);
        SeenVideoHistory.size();
        drain();
        assertEquals(10000, SeenVideoHistory.size());
        assertFalse(SeenVideoHistory.shouldHide("id0"));
        assertTrue(SeenVideoHistory.shouldHide("id10004"));
        try (android.database.Cursor c = db.rawQuery("SELECT aid FROM seen_videos", null)) {
            assertEquals(10000, c.getCount());
        }
    }

    @Test public void aTapBeforeTheCopyIsReadKeepsTheWayBack() throws Exception {
        SeenVideoHistory.onPlayProgressChange("91", 5000, 10000);
        SeenVideoHistory.onPlayProgressChange("92", 5000, 10000);
        drain();

        // Hold the worker so the clear queues its read behind the latch, which is the window
        // the toast invites a second tap into.
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        io().execute(() -> {
            ready.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        try {
            SeenVideoHistory.clear();
            assertTrue(SeenVideoHistory.canUndo());
            // The copy is not there yet, so there is nothing to put back this instant, but the
            // way back must survive the attempt.
            assertFalse(SeenVideoHistory.undoClear());
            assertTrue("the offer is not spent by a tap that was too early",
                    SeenVideoHistory.canUndo());
        } finally { release.countDown(); }
        drain();

        assertTrue(SeenVideoHistory.canUndo());
        assertEquals(2, SeenVideoHistory.undoSize());
        assertTrue(SeenVideoHistory.undoClear());
        drain();
        assertTrue(SeenVideoHistory.shouldHide("91"));
        assertTrue(SeenVideoHistory.shouldHide("92"));
    }

    /**
     * An undo that a newer clear overtakes used to return from its worker without telling anyone,
     * so a caller waiting on the callback waited forever and its row stayed offering an undo that
     * would never run.
     */
    @Test public void anUndoOvertakenByANewerClearStillReportsBack() throws Exception {
        SeenVideoHistory.onPlayProgressChange("81", 5000, 10000);
        drain();
        SeenVideoHistory.clear();
        drain();
        assertTrue(SeenVideoHistory.canUndo());

        // Hold the worker so the undo queues, then let a newer clear take the generation.
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        io().execute(() -> {
            ready.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));

        AtomicReference<SeenVideoHistory.UndoResult> result = new AtomicReference<>();
        CountDownLatch callback = new CountDownLatch(1);
        try {
            assertTrue(SeenVideoHistory.undoClear(undoResult -> {
                result.set(undoResult);
                callback.countDown();
            }));
            SeenVideoHistory.clear();
        } finally {
            release.countDown();
        }
        drain();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        assertTrue("the overtaken undo never reported back", callback.await(5, TimeUnit.SECONDS));
        assertEquals(SeenVideoHistory.UndoResult.SUPERSEDED, result.get());
    }

    @Test public void puttingTheRecordBackKeepsTheNewerSighting() throws Exception {
        SeenVideoHistory.onPlayProgressChange("55", 5000, 10000);
        drain();
        SeenVideoHistory.clear();
        drain();

        // Watched again after the clear, so memory holds a newer time than the copy does.
        SeenVideoHistory.onPlayProgressChange("55", 5000, 10000);
        drain();
        long watchedAgain = seenAt("55");

        assertTrue(SeenVideoHistory.undoClear());
        drain();
        assertEquals("the older copy must not overwrite it", watchedAgain, seenAt("55"));

        // And what went back to the database is the newer time too, or the next load would
        // quietly undo the merge.
        try (android.database.Cursor c = database().rawQuery(
                "SELECT last_seen_ms FROM seen_videos WHERE account = 'me' AND aid = '55'", null)) {
            assertTrue(c.moveToFirst());
            assertEquals("the row on disk must match memory", watchedAgain, c.getLong(0));
        }
    }

    private static long seenAt(String aid) throws Exception {
        Field f = SeenVideoHistory.class.getDeclaredField("SEEN");
        f.setAccessible(true);
        Object value = ((java.util.Map<?, ?>) f.get(null)).get(aid);
        assertNotNull("nothing recorded for " + aid, value);
        return (Long) value;
    }

    @Test public void aSchemaChangeKeepsWhatWasAlreadyWatched() throws Exception {
        long watched = System.currentTimeMillis();
        insert("kept", watched);

        // What SQLiteOpenHelper calls when the version moves in either direction. Neither is
        // allowed to take the record with it, or the account it belongs to.
        SQLiteOpenHelper helper = (SQLiteOpenHelper) field("database");
        helper.onUpgrade(database(), 1, 2);
        helper.onDowngrade(database(), 3, 2);

        try (android.database.Cursor c = database().rawQuery(
                "SELECT last_seen_ms FROM seen_videos WHERE account = 'me' AND aid = 'kept'", null)) {
            assertTrue("the row survived the migration", c.moveToFirst());
            assertEquals(watched, c.getLong(0));
        }
    }

    /** One account's watching hides nothing from another, from the first read after a switch. */
    @Test public void eachAccountKeepsARecordOfItsOwn() throws Exception {
        SignedInUser.idForTests = "111";
        SeenVideoHistory.onPlayProgressChange("a1", 5000, 10000);
        drain();
        assertTrue(SeenVideoHistory.shouldHide("a1"));

        SignedInUser.idForTests = "222";
        assertFalse("the first account's record reached the feed after the switch",
                SeenVideoHistory.shouldHide("a1"));
        assertEquals(0, SeenVideoHistory.size());
        SeenVideoHistory.onPlayProgressChange("b1", 5000, 10000);
        drain();
        assertTrue(SeenVideoHistory.shouldHide("b1"));

        SignedInUser.idForTests = "111";
        SeenVideoHistory.size();
        drain();
        assertTrue(SeenVideoHistory.shouldHide("a1"));
        assertFalse(SeenVideoHistory.shouldHide("b1"));
        assertEquals(Set.of("111|a1", "222|b1"), rows());

        // Signed out is a record of its own too.
        SignedInUser.idForTests = "";
        assertFalse(SeenVideoHistory.shouldHide("a1"));
        SeenVideoHistory.onPlayProgressChange("g1", 5000, 10000);
        drain();
        assertTrue(rows().contains(SeenVideoHistory.SIGNED_OUT + "|g1"));
    }

    @Test public void clearingForgetsOnlyTheSignedInAccountAndItsWayBackStaysWithIt() throws Exception {
        SignedInUser.idForTests = "111";
        SeenVideoHistory.onPlayProgressChange("a1", 5000, 10000);
        drain();
        SignedInUser.idForTests = "222";
        SeenVideoHistory.onPlayProgressChange("b1", 5000, 10000);
        drain();

        SeenVideoHistory.clear();
        drain();
        assertTrue(SeenVideoHistory.canUndo());
        assertEquals(1, SeenVideoHistory.undoSize());
        assertEquals(Set.of("111|a1"), rows());

        SignedInUser.idForTests = "111";
        assertFalse("the other account was offered a way back to a record that isn't its own",
                SeenVideoHistory.canUndo());
        SeenVideoHistory.size();
        drain();
        assertTrue(SeenVideoHistory.shouldHide("a1"));
    }

    /**
     * A progress callback reads the account, then a switch wins the race before it marks. The
     * watch stays the first account's on disk, the second account's record doesn't take it in,
     * and the second account playing the same video still marks it for itself.
     */
    @Test public void aWatchThatLosesTheRaceToAnAccountSwitchStaysWithTheAccountThatWatched() throws Exception {
        SignedInUser.idForTests = "111";
        SeenVideoHistory.size();
        drain();
        CountDownLatch readAccount = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread player = new Thread(() -> SeenVideoHistory.onPlayProgressChange("a1", 5000, 10000), "player");
        SeenVideoHistory.watchClock = () -> {
            if (Thread.currentThread() == player) {
                readAccount.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            return System.currentTimeMillis();
        };
        try {
            player.start();
            assertTrue(readAccount.await(5, TimeUnit.SECONDS));
            SignedInUser.idForTests = "222";
            SeenVideoHistory.size();
            drain();
        } finally {
            release.countDown();
        }
        player.join(5000);
        assertFalse(player.isAlive());
        SeenVideoHistory.watchClock = SeenVideoHistory.SYSTEM_CLOCK;
        drain();

        assertFalse("the first account's watch reached the second account's feed", SeenVideoHistory.shouldHide("a1"));
        assertEquals(0, SeenVideoHistory.size());
        assertEquals("the watch left the account that watched it", Set.of("111|a1"), rows());

        SeenVideoHistory.onPlayProgressChange("a1", 5000, 10000);
        drain();
        assertEquals("the first account's mark kept the second from recording the same video",
                Set.of("111|a1", "222|a1"), rows());
        assertTrue(SeenVideoHistory.shouldHide("a1"));

        // The second account's clear and Undo only ever hold its own row.
        SeenVideoHistory.clear();
        drain();
        assertEquals(1, SeenVideoHistory.undoSize());
        assertTrue(SeenVideoHistory.undoClear());
        drain();
        assertEquals(Set.of("111|a1", "222|a1"), rows());

        SignedInUser.idForTests = "111";
        SeenVideoHistory.size();
        drain();
        assertTrue("the first account lost its watch", SeenVideoHistory.shouldHide("a1"));
    }

    /**
     * Version 1 recorded no account. Its rows hide nothing, for anyone, until they're added to
     * an account on purpose, and then the newer sighting of a video both hold wins.
     */
    @Test public void versionOneRowsHideNothingUntilTheyAreAddedToAnAccount() throws Exception {
        long now = System.currentTimeMillis();
        replaceWithVersionOne(Map.of("old1", now - 5000, "both", now - 1000, "stale", now - 9000));
        SignedInUser.idForTests = "111";
        SeenVideoHistory.size();
        drain();
        assertFalse("an unowned row hid a video", SeenVideoHistory.shouldHide("old1"));
        assertEquals(3, SeenVideoHistory.unownedCount());
        assertEquals(Set.of("|old1", "|both", "|stale"), rows());

        // The account already holds two of them: "both" at an older time, "stale" at a newer one.
        database().execSQL("INSERT INTO seen_videos (account, aid, last_seen_ms) VALUES ('111', 'both', ?)",
                new Object[]{now - 3000});
        database().execSQL("INSERT INTO seen_videos (account, aid, last_seen_ms) VALUES ('111', 'stale', ?)",
                new Object[]{now - 2000});

        AtomicInteger added = new AtomicInteger(-2);
        CountDownLatch done = new CountDownLatch(1);
        SeenVideoHistory.adoptUnowned(count -> {
            added.set(count);
            done.countDown();
        });
        drain();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(3, added.get());
        assertEquals(0, SeenVideoHistory.unownedCount());
        assertEquals(Set.of("111|old1", "111|both", "111|stale"), rows());
        assertTrue(SeenVideoHistory.shouldHide("old1"));
        assertEquals("the newer unowned sighting wins", now - 1000, lastSeen("111", "both"));
        assertEquals("the newer account sighting stays", now - 2000, lastSeen("111", "stale"));

        // Another account never sees them.
        SignedInUser.idForTests = "222";
        SeenVideoHistory.size();
        drain();
        assertFalse(SeenVideoHistory.shouldHide("old1"));
    }

    /**
     * An older bundle opens a version 2 database as version 1: it writes rows without an account
     * and sets the version back. Coming back to this bundle must not take the accounts off the
     * rows it wrote before.
     */
    @Test public void anOlderBundleRunningInBetweenLeavesEveryAccountOnItsRows() throws Exception {
        SignedInUser.idForTests = "111";
        SeenVideoHistory.onPlayProgressChange("mine", 5000, 10000);
        drain();
        SQLiteDatabase db = database();
        db.execSQL("INSERT OR REPLACE INTO seen_videos (aid, last_seen_ms) VALUES ('legacy', ?)",
                new Object[]{System.currentTimeMillis()});
        db.setVersion(1);
        ((SQLiteOpenHelper) field("database")).onUpgrade(db, 1, 2);
        assertEquals(Set.of("111|mine", "|legacy"), rows());
    }

    private static long lastSeen(String account, String aid) throws Exception {
        try (android.database.Cursor c = database().rawQuery(
                "SELECT last_seen_ms FROM seen_videos WHERE account = ? AND aid = ?", new String[]{account, aid})) {
            assertTrue("no row for " + account + "|" + aid, c.moveToFirst());
            return c.getLong(0);
        }
    }

    /** Swaps the database for one version 1 wrote, holding these rows, and forgets memory. */
    private static void replaceWithVersionOne(Map<String, Long> watched) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Field databaseField = SeenVideoHistory.class.getDeclaredField("database");
        databaseField.setAccessible(true);
        ((SQLiteOpenHelper) databaseField.get(null)).close();
        databaseField.set(null, null);
        context.deleteDatabase("seen_videos.db");
        SQLiteDatabase v1 = context.openOrCreateDatabase("seen_videos.db", Context.MODE_PRIVATE, null);
        try {
            v1.execSQL("CREATE TABLE seen_videos (aid TEXT PRIMARY KEY NOT NULL, last_seen_ms INTEGER NOT NULL)");
            v1.execSQL("CREATE INDEX seen_videos_last_seen ON seen_videos (last_seen_ms)");
            for (Map.Entry<String, Long> row : watched.entrySet()) {
                v1.execSQL("INSERT INTO seen_videos VALUES (?, ?)", new Object[]{row.getKey(), row.getValue()});
            }
            v1.setVersion(1);
        } finally {
            v1.close();
        }
        resetLoadedMemory();
        Field partition = SeenVideoHistory.class.getDeclaredField("partition");
        partition.setAccessible(true);
        partition.set(null, null);
    }

    private static Object field(String name) throws Exception {
        Field f = SeenVideoHistory.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    @Test public void theClearRowWaitsForStorageBeforeOfferingUndo() throws Exception {
        var row = clearRowWithSeenVideo();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        io().execute(() -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        org.robolectric.shadows.ShadowToast.reset();
        try {
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse("the row announced a clear that SQLite had not started",
                    String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                            .contains("Seen videos cleared"));
            assertFalse("the pending clear accepts another tap", row.isEnabled());
            assertNotEquals("Undo clearing seen videos", row.getTitle().toString());
        } finally {
            release.countDown();
            drain();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        assertTrue(persistedIds().isEmpty());
        assertTrue(row.isEnabled());
        assertEquals("Undo clearing seen videos", row.getTitle().toString());
        assertEquals("Seen videos cleared. You can undo until TikTok closes.",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    @Test public void aClearReadFailureKeepsHistoryAndOffersAWorkingRetry() throws Exception {
        assertClearFailureCanRetry(true);
    }

    @Test public void aClearDeleteFailureKeepsHistoryAndOffersAWorkingRetry() throws Exception {
        assertClearFailureCanRetry(false);
    }

    private void assertClearFailureCanRetry(boolean failRead) throws Exception {
        var row = clearRowWithSeenVideo();
        Field held = SeenVideoHistory.class.getDeclaredField("database");
        held.setAccessible(true);
        SeenVideoHistory.Database original = (SeenVideoHistory.Database) held.get(null);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        held.set(null, new SeenVideoHistory.Database(RuntimeEnvironment.getApplication()) {
            @Override public SQLiteDatabase getReadableDatabase() {
                if (failRead && failOnce.getAndSet(false)) {
                    throw new android.database.sqlite.SQLiteException("injected clear read failure");
                }
                return original.getReadableDatabase();
            }
            @Override public SQLiteDatabase getWritableDatabase() {
                if (!failRead && failOnce.getAndSet(false)) {
                    throw new android.database.sqlite.SQLiteException("injected clear delete failure");
                }
                return original.getWritableDatabase();
            }
        });
        try {
            org.robolectric.shadows.ShadowToast.reset();
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            drain();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(Set.of("clear-me"), persistedIds());
            assertTrue("the failed clear erased the live filter's record",
                    SeenVideoHistory.shouldHide("clear-me"));
            assertFalse("a failed clear offered an undo instead of a retry", SeenVideoHistory.canUndo());
            assertTrue(row.isEnabled());
            assertEquals("Clear seen videos", row.getTitle().toString());
            assertTrue("the database failure was not disclosed",
                    String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                            .contains("Couldn't clear"));
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            drain();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("the clear could not be retried", persistedIds().isEmpty());
            assertTrue(SeenVideoHistory.canUndo());
            assertEquals("Undo clearing seen videos", row.getTitle().toString());
        } finally {
            drain();
            held.set(null, original);
        }
    }

    @Test public void aClearFinishingAfterAnAccountChangeDoesNotOfferTheOtherAccountsUndo() throws Exception {
        var row = clearRowWithSeenVideo();
        SignedInUser.idForTests = "other";
        SeenVideoHistory.onPlayProgressChange("other-video", 5000, 10000);
        drain();
        SignedInUser.idForTests = ME;
        SeenVideoHistory.size();
        drain();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        io().execute(() -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            SignedInUser.idForTests = "other";
            SeenVideoHistory.size();
            org.robolectric.shadows.ShadowToast.reset();
        } finally {
            release.countDown();
            drain();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        assertEquals(Set.of("other|other-video"), rows());
        assertTrue(SeenVideoHistory.shouldHide("other-video"));
        assertFalse(SeenVideoHistory.canUndo());
        assertTrue("the completed old operation left the row disabled", row.isEnabled());
        assertEquals("the old account's operation repainted the new account as cleared",
                "Clear seen videos", row.getTitle().toString());
        assertFalse(String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                .contains("Seen videos cleared"));
    }

    @Test public void anOldUndoCannotLeaveTheNewAccountsRowOfferingUndo() throws Exception {
        var row = clearRowWithSeenVideo();
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        drain();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("Undo clearing seen videos", row.getTitle().toString());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        io().execute(() -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            SignedInUser.idForTests = "other";
            SeenVideoHistory.size();
        } finally {
            release.countDown();
            drain();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        assertFalse(SeenVideoHistory.canUndo());
        assertEquals("an overtaken undo offered another account's recovery",
                "Clear seen videos", row.getTitle().toString());
    }

    @Test public void aThousandParsedWatchesFilterTheNextFeedAndKeepTheirOriginalDates() throws Exception {
        long watched = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        SimpleDateFormat dates = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        TimeZone zone = TimeZone.getTimeZone("America/New_York");
        dates.setTimeZone(zone);
        String date = dates.format(watched);
        long expected = dates.parse(date).getTime();
        org.json.JSONArray list = new org.json.JSONArray();
        for (int index = 0; index < 1000; index++) {
            list.put(new org.json.JSONObject().put("Date", date).put("Link",
                    "https://www.tiktokv.com/share/video/" + (7000000000000000000L + index) + "/"));
        }
        String file = new org.json.JSONObject().put("Your Activity", new org.json.JSONObject()
                .put("Watch History", new org.json.JSONObject().put("VideoList", list))).toString();
        WatchHistoryImport.Records records = WatchHistoryImport.read(new ByteArrayInputStream(
                file.getBytes(StandardCharsets.UTF_8)), zone, System.currentTimeMillis());
        SeenVideoHistory.ImportResult result = importAndWait(SeenVideoHistory.captureImportTarget(), records);
        assertEquals(SeenVideoHistory.ImportStatus.IMPORTED, result.status);
        assertEquals(1000, result.imported);
        assertEquals(0, result.skipped);
        assertEquals(1000, persistedIds().size());
        assertEquals(expected, lastSeen(ME, "7000000000000000000"));
        assertEquals(expected, seenAt("7000000000000000000"));
        SeenVideoFilter filter = new SeenVideoFilter();
        assertTrue(filter.getFiltered(video("7000000000000000000")));
        assertTrue(filter.getFiltered(video("7000000000000000999")));
        assertFalse(filter.getFiltered(video("7000000000000001000")));
        resetLoadedMemory();
        SeenVideoHistory.size();
        drain();
        assertTrue(filter.getFiltered(video("7000000000000000000")));

        SeenVideoHistory.ImportResult repeated = importAndWait(SeenVideoHistory.captureImportTarget(), records);
        assertEquals(0, repeated.imported);
        assertEquals(1000, repeated.skipped);
        assertEquals(expected, lastSeen(ME, "7000000000000000000"));
    }

    @Test public void theRealReviewedExportImportsOnlyItsFifteenUniqueVideos() throws Exception {
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(0);
        WatchHistoryImport.Records records = WatchHistoryImport.read(getClass()
                .getResourceAsStream("/seen/reviewed-watch-history.json"),
                TimeZone.getTimeZone("America/New_York"), System.currentTimeMillis());
        SeenVideoHistory.ImportResult result = importAndWait(SeenVideoHistory.captureImportTarget(), records);
        assertEquals(15, result.imported);
        assertEquals(3, result.skipped);
        assertTrue(new SeenVideoFilter().getFiltered(video("7420104946231577888")));
        assertEquals(records.videos.get("7200255970008861998").longValue(),
                lastSeen(ME, "7200255970008861998"));
        result = importAndWait(SeenVideoHistory.captureImportTarget(), records);
        assertEquals(0, result.imported);
        assertEquals(18, result.skipped);
    }

    @Test public void retentionAndExistingNewerDatesAreAppliedWithoutTouchingAnotherAccount() throws Exception {
        long now = System.currentTimeMillis();
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(1);
        insert("newer", now - 1000);
        insert("expired-on-disk", now - TimeUnit.DAYS.toMillis(2));
        database().execSQL("INSERT INTO seen_videos VALUES (?, ?, ?)",
                new Object[]{"other", "expired-on-disk", now - TimeUnit.DAYS.toMillis(2)});
        SeenVideoHistory.ImportResult result = importAndWait(SeenVideoHistory.captureImportTarget(),
                records(Map.of("newer", now - 2000, "valid", now - 3000,
                        "expired-import", now - TimeUnit.DAYS.toMillis(2),
                        "future-import", now + TimeUnit.DAYS.toMillis(1))));
        assertEquals(SeenVideoHistory.ImportStatus.IMPORTED, result.status);
        assertEquals(1, result.imported);
        assertEquals(3, result.skipped);
        assertEquals(Set.of(ME + "|newer", ME + "|valid", "other|expired-on-disk"), rows());
        assertEquals(now - 1000, lastSeen(ME, "newer"));
        assertEquals(now - 3000, lastSeen(ME, "valid"));
        assertTrue(SeenVideoHistory.shouldHide("valid"));
        assertFalse(SeenVideoHistory.shouldHide("expired-import"));
    }

    @Test public void aRejectedInsertRollsBackEarlierRowsAndPublishesNoPhantomVideos() throws Exception {
        long now = System.currentTimeMillis();
        insert("existing", now - 1000);
        SeenVideoHistory.RowWriter original = SeenVideoHistory.rowWriter;
        AtomicInteger writes = new AtomicInteger();
        SeenVideoHistory.rowWriter = (db, values) -> writes.incrementAndGet() == 2
                ? -1 : original.insert(db, values);
        SeenVideoHistory.ImportResult result;
        try {
            result = importAndWait(SeenVideoHistory.captureImportTarget(),
                    records(Map.of("first", now - 2000, "second", now - 3000)));
        } finally {
            SeenVideoHistory.rowWriter = original;
        }
        assertEquals(SeenVideoHistory.ImportStatus.FAILED, result.status);
        assertEquals(0, result.imported);
        assertEquals(Set.of("existing"), persistedIds());
        assertFalse(SeenVideoHistory.shouldHide("first"));
        assertFalse(SeenVideoHistory.shouldHide("second"));
        result = importAndWait(SeenVideoHistory.captureImportTarget(),
                records(Map.of("first", now - 2000, "second", now - 3000)));
        assertEquals(2, result.imported);
    }

    @Test public void failedPruningRollsBackImportedRowsAndKeepsThePreviousDatabase() throws Exception {
        long now = System.currentTimeMillis();
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(1);
        insert("expired", now - TimeUnit.DAYS.toMillis(2));
        database().execSQL("CREATE TEMP TRIGGER reject_import_prune BEFORE DELETE ON seen_videos "
                + "BEGIN SELECT RAISE(ABORT, 'injected import prune failure'); END");
        SeenVideoHistory.ImportResult result;
        try {
            result = importAndWait(SeenVideoHistory.captureImportTarget(), records(Map.of("fresh", now - 1000)));
        } finally {
            database().execSQL("DROP TRIGGER reject_import_prune");
        }
        assertEquals(SeenVideoHistory.ImportStatus.FAILED, result.status);
        assertEquals(Set.of("expired"), persistedIds());
        assertFalse(SeenVideoHistory.shouldHide("fresh"));
    }

    @Test public void queuedImportCannotFollowAnAccountChangeOrANewerClear() throws Exception {
        SeenVideoHistory.ImportTarget target = SeenVideoHistory.captureImportTarget();
        SignedInUser.idForTests = "other";
        SeenVideoHistory.ImportResult result = importAndWait(target,
                records(Map.of("wrong-account", System.currentTimeMillis() - 1000)));
        assertEquals(SeenVideoHistory.ImportStatus.ACCOUNT_CHANGED, result.status);
        assertTrue(rows().isEmpty());
        SignedInUser.idForTests = ME;
        target = SeenVideoHistory.captureImportTarget();
        SeenVideoHistory.clear();
        result = importAndWait(target, records(Map.of("cleared", System.currentTimeMillis() - 1000)));
        assertEquals(SeenVideoHistory.ImportStatus.SUPERSEDED, result.status);
        assertTrue(rows().isEmpty());
        SignedInUser.idForTests = null;
        assertNull(SeenVideoHistory.captureImportTarget());
    }

    @Test public void anAccountSwitchDuringWritingRollsBackTheCapturedAccountsTransaction() throws Exception {
        long now = System.currentTimeMillis();
        insert("existing", now - 1000);
        SeenVideoHistory.RowWriter original = SeenVideoHistory.rowWriter;
        SeenVideoHistory.rowWriter = (db, values) -> {
            long inserted = original.insert(db, values);
            SignedInUser.idForTests = "other";
            return inserted;
        };
        SeenVideoHistory.ImportResult result;
        try {
            result = importAndWait(SeenVideoHistory.captureImportTarget(),
                    records(Map.of("wrong-account", now - 2000)));
        } finally {
            SeenVideoHistory.rowWriter = original;
        }
        assertEquals(SeenVideoHistory.ImportStatus.ACCOUNT_CHANGED, result.status);
        assertEquals(Set.of(ME + "|existing"), rows());
        assertFalse(SeenVideoHistory.shouldHide("wrong-account"));
        SignedInUser.idForTests = ME;
        SeenVideoHistory.size();
        drain();
        assertTrue(SeenVideoHistory.shouldHide("existing"));
        assertFalse(SeenVideoHistory.shouldHide("wrong-account"));
    }

    @Test public void aClearDuringImportKeepsOnlyThePreviousHistoryInItsUndoCopy() throws Exception {
        long now = System.currentTimeMillis();
        insert("existing", now - 1000);
        SeenVideoHistory.RowWriter original = SeenVideoHistory.rowWriter;
        AtomicBoolean clearOnce = new AtomicBoolean(true);
        SeenVideoHistory.rowWriter = (db, values) -> {
            long inserted = original.insert(db, values);
            if (clearOnce.getAndSet(false)) SeenVideoHistory.clear();
            return inserted;
        };
        SeenVideoHistory.ImportResult result;
        try {
            result = importAndWait(SeenVideoHistory.captureImportTarget(),
                    records(Map.of("must-roll-back", now - 2000)));
            drain();
        } finally {
            SeenVideoHistory.rowWriter = original;
        }
        assertEquals(SeenVideoHistory.ImportStatus.SUPERSEDED, result.status);
        assertTrue(rows().isEmpty());
        assertEquals(1, SeenVideoHistory.undoSize());
        assertTrue(SeenVideoHistory.undoClear());
        drain();
        assertEquals(Set.of("existing"), persistedIds());
    }

    @Test public void theAccountCapCountsOnlyImportedRowsThatSurviveAndLeavesOtherAccountsAlone() throws Exception {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = database();
        db.beginTransaction();
        try {
            for (int index = 0; index < SeenVideoHistory.MAX_RECORDS; index++) {
                db.execSQL("INSERT INTO seen_videos VALUES (?, ?, ?)",
                        new Object[]{ME, "old-" + index, now - 5000});
            }
            db.execSQL("INSERT INTO seen_videos VALUES (?, ?, ?)",
                    new Object[]{"other", "private", now - 10000});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WatchHistoryImport.Records records = new WatchHistoryImport.Records(
                Map.of("old-500", now - 1000, "fresh", now - 2000, "too-old", now - 10000), 2, 5);
        SeenVideoHistory.ImportResult result = importAndWait(SeenVideoHistory.captureImportTarget(), records);
        assertEquals(2, result.imported);
        assertEquals(3, result.skipped);
        assertEquals(SeenVideoHistory.MAX_RECORDS, persistedIds().size());
        assertTrue(rows().contains("other|private"));
        assertEquals(now - 1000, lastSeen(ME, "old-500"));
        assertTrue(SeenVideoHistory.shouldHide("fresh"));
        assertFalse(SeenVideoHistory.shouldHide("too-old"));
        assertEquals(SeenVideoHistory.MAX_RECORDS, SeenVideoHistory.size());
    }

    private static WatchHistoryImport.Records records(Map<String, Long> values) {
        return new WatchHistoryImport.Records(values, 0, values.size());
    }

    @Test public void importingAFullNewHistoryRetiresTheOldClearsUndoInsteadOfReportingZeroRestored() throws Exception {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = database();
        db.beginTransaction();
        try {
            for (int index = 0; index < SeenVideoHistory.MAX_RECORDS; index++) {
                db.execSQL("INSERT INTO seen_videos VALUES (?, ?, ?)",
                        new Object[]{ME, "old-" + index, now - 5000});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        SeenVideoHistory.clear();
        drain();
        assertEquals(SeenVideoHistory.MAX_RECORDS, SeenVideoHistory.undoSize());
        assertTrue(SeenVideoHistory.canUndo());
        Map<String, Long> newer = new java.util.HashMap<>();
        for (int index = 0; index < SeenVideoHistory.MAX_RECORDS; index++) {
            newer.put("new-" + index, now - 1000);
        }
        SeenVideoHistory.ImportResult imported = importAndWait(SeenVideoHistory.captureImportTarget(), records(newer));
        assertEquals(SeenVideoHistory.MAX_RECORDS, imported.imported);
        assertTrue(imported.undoRetired);
        assertFalse(SeenVideoHistory.canUndo());
        assertEquals(0, SeenVideoHistory.undoSize());
        AtomicReference<SeenVideoHistory.UndoResult> undo = new AtomicReference<>();
        assertFalse(SeenVideoHistory.undoClear(undo::set));
        drain();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(SeenVideoHistory.UndoResult.NOT_READY, undo.get());
        assertEquals(newer.keySet(), persistedIds());
        assertEquals(SeenVideoHistory.MAX_RECORDS, SeenVideoHistory.size());
    }

    @Test public void ordinarySightingsAtCapacityCannotReportClearedRowsRestored() throws Exception {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = database();
        db.beginTransaction();
        try {
            for (int index = 0; index < SeenVideoHistory.MAX_RECORDS; index++) {
                db.execSQL("INSERT INTO seen_videos VALUES (?, ?, ?)",
                        new Object[]{ME, "cleared-" + index, now - 60_000});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        SeenVideoHistory.clear();
        drain();
        assertEquals(SeenVideoHistory.MAX_RECORDS, SeenVideoHistory.undoSize());
        for (int index = 0; index < SeenVideoHistory.MAX_RECORDS; index++) {
            SeenVideoHistory.onPlayProgressChange("watched-" + index, 5000, 10000);
        }
        drain();
        assertEquals(SeenVideoHistory.MAX_RECORDS, SeenVideoHistory.size());
        assertTrue(SeenVideoHistory.canUndo());
        AtomicReference<SeenVideoHistory.UndoResult> result = new AtomicReference<>();
        assertTrue(SeenVideoHistory.undoClear(result::set));
        drain();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNotEquals("no cleared row survived the cap", SeenVideoHistory.UndoResult.RESTORED,
                result.get());
        Set<String> retained = persistedIds();
        assertEquals(SeenVideoHistory.MAX_RECORDS, retained.size());
        assertFalse(retained.stream().anyMatch(id -> id.startsWith("cleared-")));
    }

    @Test public void undoReportsTheRetainedSubsetAtTheCap() throws Exception {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = database();
        db.beginTransaction();
        try {
            for (int index = 0; index < SeenVideoHistory.MAX_RECORDS; index++) {
                db.execSQL("INSERT INTO seen_videos VALUES (?, ?, ?)",
                        new Object[]{ME, "cleared-" + index, now - 60_000});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        SeenVideoHistory.clear();
        drain();
        SeenVideoHistory.onPlayProgressChange("new-a", 5000, 10000);
        SeenVideoHistory.onPlayProgressChange("new-b", 5000, 10000);
        drain();
        AtomicReference<SeenVideoHistory.UndoResult> result = new AtomicReference<>();
        AtomicInteger retained = new AtomicInteger();
        AtomicInteger requested = new AtomicInteger();
        assertTrue(SeenVideoHistory.undoClear((outcome, kept, total) -> {
            result.set(outcome);
            retained.set(kept);
            requested.set(total);
        }));
        drain();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(SeenVideoHistory.UndoResult.PARTIAL, result.get());
        assertEquals(SeenVideoHistory.MAX_RECORDS - 2, retained.get());
        assertEquals(SeenVideoHistory.MAX_RECORDS, requested.get());
        Set<String> durable = persistedIds();
        assertEquals(SeenVideoHistory.MAX_RECORDS, durable.size());
        assertTrue(durable.contains("new-a"));
        assertTrue(durable.contains("new-b"));
        assertEquals(retained.get(), durable.stream().filter(id -> id.startsWith("cleared-")).count());
        assertEquals(durable, ((Map<?, ?>) field("SEEN")).keySet());
    }

    @Test public void undoCountsSightingsThatArriveDuringItsTransaction() throws Exception {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = database();
        db.beginTransaction();
        try {
            for (int index = 0; index < SeenVideoHistory.MAX_RECORDS; index++) {
                db.execSQL("INSERT INTO seen_videos VALUES (?, ?, ?)",
                        new Object[]{ME, "cleared-" + index, now - 60_000});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        SeenVideoHistory.clear();
        drain();
        SeenVideoHistory.RowWriter original = SeenVideoHistory.rowWriter;
        AtomicBoolean first = new AtomicBoolean(true);
        CountDownLatch queuedWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseQueuedWrites = new CountDownLatch(1);
        ExecutorService worker = io();
        SeenVideoHistory.rowWriter = (database, values) -> {
            if (first.getAndSet(false)) {
                worker.execute(() -> {
                    queuedWriteEntered.countDown();
                    try { releaseQueuedWrites.await(15, TimeUnit.SECONDS); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                });
                SeenVideoHistory.onPlayProgressChange("during-a", 5000, 10000);
                SeenVideoHistory.onPlayProgressChange("during-b", 5000, 10000);
            }
            return original.insert(database, values);
        };
        AtomicInteger retained = new AtomicInteger(-1);
        AtomicReference<SeenVideoHistory.UndoResult> result = new AtomicReference<>();
        try {
            assertTrue(SeenVideoHistory.undoClear((outcome, kept, total) -> {
                result.set(outcome);
                retained.set(kept);
            }));
            assertTrue(queuedWriteEntered.await(15, TimeUnit.SECONDS));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (result.get() != null) {
                assertEquals("Undo reported rows before concurrent writes became durable",
                        persistedIds().stream().filter(id -> id.startsWith("cleared-")).count(),
                        retained.get());
            }
            releaseQueuedWrites.countDown();
            drain();
            // Writes enqueued inside the transaction land after the first drain marker.
            drain();
        } finally {
            releaseQueuedWrites.countDown();
            SeenVideoHistory.rowWriter = original;
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(SeenVideoHistory.UndoResult.PARTIAL, result.get());
        assertEquals(SeenVideoHistory.MAX_RECORDS - 2, retained.get());
        assertTrue(SeenVideoHistory.shouldHide("during-a"));
        assertTrue(SeenVideoHistory.shouldHide("during-b"));
        resetLoadedMemory();
        SeenVideoHistory.size();
        drain();
        Set<String> durable = persistedIds();
        assertEquals(retained.get(), durable.stream().filter(id -> id.startsWith("cleared-")).count());
        assertEquals(durable, ((Map<?, ?>) field("SEEN")).keySet());
    }

    @Test public void aFailedConcurrentUndoRowRollsBackAndKeepsRecovery() throws Exception {
        insert("cleared", System.currentTimeMillis() - 60_000);
        SeenVideoHistory.clear();
        drain();
        SeenVideoHistory.RowWriter original = SeenVideoHistory.rowWriter;
        AtomicBoolean first = new AtomicBoolean(true);
        SeenVideoHistory.rowWriter = (db, values) -> {
            if (first.getAndSet(false)) {
                SeenVideoHistory.onPlayProgressChange("during", 5000, 10000);
            }
            return "during".equals(values.getAsString("aid")) ? -1L : original.insert(db, values);
        };
        AtomicReference<SeenVideoHistory.UndoResult> result = new AtomicReference<>();
        try {
            assertTrue(SeenVideoHistory.undoClear(result::set));
            drain();
            drain();
        } finally {
            SeenVideoHistory.rowWriter = original;
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(SeenVideoHistory.UndoResult.FAILED, result.get());
        assertEquals(Set.of("during"), persistedIds());
        assertTrue(SeenVideoHistory.canUndo());
        assertFalse(SeenVideoHistory.isRestoring());
        assertTrue(SeenVideoHistory.undoClear());
        drain();
        assertEquals(Set.of("cleared", "during"), persistedIds());
    }

    @Test public void queuedSightingCannotRegressTimestampCommittedByUndo() throws Exception {
        long now = System.currentTimeMillis();
        insert("cleared", now - 60_000);
        SeenVideoHistory.clear();
        drain();
        SeenVideoHistory.RowWriter original = SeenVideoHistory.rowWriter;
        AtomicBoolean first = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = io();
        Method mark = SeenVideoHistory.class.getDeclaredMethod("markSeen", String.class, String.class, long.class);
        mark.setAccessible(true);
        SeenVideoHistory.rowWriter = (db, values) -> {
            if (first.getAndSet(false)) {
                try {
                    mark.invoke(null, ME, "during", now - 2000);
                    worker.execute(() -> {
                        entered.countDown();
                        try { release.await(15, TimeUnit.SECONDS); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                    });
                    mark.invoke(null, ME, "during", now - 1000);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }
            return original.insert(db, values);
        };
        try {
            assertTrue(SeenVideoHistory.undoClear());
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            assertEquals("an older queued write replaced Undo's newer committed sighting",
                    now - 1000, lastSeen(ME, "during"));
        } finally {
            release.countDown();
            drain();
            SeenVideoHistory.rowWriter = original;
        }
    }

    @Test public void undoDoesNotPublishOrMergeOnTheCallerAndRejectsASecondPendingUndo() throws Exception {
        SeenVideoHistory.onPlayProgressChange("before-clear", 5000, 10000);
        drain();
        SeenVideoHistory.clear();
        drain();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        io().execute(() -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            assertTrue(SeenVideoHistory.undoClear());
            assertTrue(SeenVideoHistory.isRestoring());
            assertFalse("the caller published history before the worker could commit",
                    SeenVideoHistory.shouldHide("before-clear"));
            assertFalse("a repeated tap must not supersede its own in-flight merge",
                    SeenVideoHistory.undoClear());
            SeenVideoHistory.onPlayProgressChange("during-undo", 5000, 10000);
        } finally {
            release.countDown();
        }
        drain();
        assertFalse(SeenVideoHistory.isRestoring());
        assertTrue(SeenVideoHistory.shouldHide("before-clear"));
        assertTrue(SeenVideoHistory.shouldHide("during-undo"));
        assertEquals(Set.of("before-clear", "during-undo"), persistedIds());
    }

    @Test public void expiredClearSnapshotReportsNothingRetained() throws Exception {
        insert("expired-clear", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2));
        SeenVideoHistory.clear();
        drain();
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(1);
        AtomicReference<SeenVideoHistory.UndoResult> result = new AtomicReference<>();
        AtomicInteger retained = new AtomicInteger(-1);
        assertTrue(SeenVideoHistory.undoClear((outcome, kept, total) -> {
            result.set(outcome);
            retained.set(kept);
            assertEquals(1, total);
        }));
        drain();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(SeenVideoHistory.UndoResult.NONE_RETAINED, result.get());
        assertEquals(0, retained.get());
        assertTrue(persistedIds().isEmpty());
        assertFalse(SeenVideoHistory.canUndo());
    }

    @Test public void aFailedOrFullySkippedImportKeepsTheExistingClearRecovery() throws Exception {
        long now = System.currentTimeMillis();
        insert("cleared", now - 1000);
        SeenVideoHistory.clear();
        drain();
        SeenVideoHistory.RowWriter original = SeenVideoHistory.rowWriter;
        SeenVideoHistory.rowWriter = (db, values) -> -1L;
        SeenVideoHistory.ImportResult failed;
        try {
            failed = importAndWait(SeenVideoHistory.captureImportTarget(), records(Map.of("new", now - 500)));
        } finally {
            SeenVideoHistory.rowWriter = original;
        }
        assertEquals(SeenVideoHistory.ImportStatus.FAILED, failed.status);
        assertFalse(failed.undoRetired);
        assertTrue(SeenVideoHistory.canUndo());
        assertEquals(1, SeenVideoHistory.undoSize());
        SeenVideoHistory.ImportResult skipped = importAndWait(SeenVideoHistory.captureImportTarget(),
                records(Map.of("future", now + TimeUnit.DAYS.toMillis(1))));
        assertEquals(0, skipped.imported);
        assertFalse(skipped.undoRetired);
        assertTrue(SeenVideoHistory.canUndo());
        assertEquals(1, SeenVideoHistory.undoSize());
        assertTrue(SeenVideoHistory.undoClear());
        drain();
        assertEquals(Set.of("cleared"), persistedIds());
    }

    private static final long DAY = 24L * 60L * 60L * 1000L;

    @Test public void aSnapshotHoldsOnlyTheSignedInAccountsVideosInsideRetention() throws Exception {
        long now = System.currentTimeMillis();
        insert("7555000000000000001", now - 60_000);
        insert("7555000000000000002", now - 40 * DAY);
        database().execSQL("INSERT INTO seen_videos (account, aid, last_seen_ms) VALUES (?, ?, ?)",
                new Object[]{"someone-else", "7555000000000000003", now - 60_000});
        SeenVideoHistory.Snapshot snapshot = SeenVideoHistory.snapshot(SeenVideoHistory.captureImportTarget());
        assertEquals(Map.of("7555000000000000001", now - 60_000), snapshot.videos);
        assertTrue(snapshot.takenAt >= now);
    }

    @Test public void aSnapshotIsRefusedOnceAnotherAccountIsSignedIn() throws Exception {
        insert("7555000000000000001", System.currentTimeMillis());
        SeenVideoHistory.ImportTarget target = SeenVideoHistory.captureImportTarget();
        SignedInUser.idForTests = "someone-else";
        assertThrows(SeenVideoHistory.AccountChanged.class, () -> SeenVideoHistory.snapshot(target));
        assertThrows(SeenVideoHistory.AccountChanged.class, () -> SeenVideoHistory.snapshot(null));
    }

    /**
     * Save on one account, restore on another: every video comes back at its own time, the new
     * account still hides them after a restart, the first account's record is untouched, and a
     * settings backup carries none of it.
     */
    @Test public void aSavedFileRestoresIntoAnotherAccountAndOutlivesARestart() throws Exception {
        long now = System.currentTimeMillis();
        insert("7555000000000000011", now - 120_000);
        insert("7555000000000000012", now - 60_000);
        SeenVideoHistory.Snapshot snapshot = SeenVideoHistory.snapshot(SeenVideoHistory.captureImportTarget());
        SeenHistoryFile.Encoded file = SeenHistoryFile.encode(snapshot.videos, snapshot.takenAt);
        assertEquals(2, file.rows);
        SeenHistoryFile.Contents contents = SeenHistoryFile.read(new ByteArrayInputStream(file.bytes));

        SignedInUser.idForTests = "restorer";
        SeenVideoHistory.ImportResult result = importAndWait(SeenVideoHistory.captureImportTarget(),
                contents.records());
        assertEquals(SeenVideoHistory.ImportStatus.IMPORTED, result.status);
        assertEquals(2, result.imported);
        assertEquals(0, result.skipped);
        assertEquals(now - 120_000, lastSeen("restorer", "7555000000000000011"));
        assertEquals(now - 60_000, lastSeen("restorer", "7555000000000000012"));

        resetLoadedMemory();
        SeenVideoHistory.size();
        drain();
        assertTrue(SeenVideoHistory.shouldHide("7555000000000000011"));
        assertTrue(SeenVideoHistory.shouldHide("7555000000000000012"));
        assertEquals(now - 120_000, lastSeen(ME, "7555000000000000011"));
        assertEquals(now - 60_000, lastSeen(ME, "7555000000000000012"));
        String backup = app.morphe.extension.tiktok.settings.SettingsBackup.create(false);
        assertFalse("a settings backup carried a seen video", backup.contains("7555000000000000011"));
    }

    @Test public void aRestoreSaysWhyEachVideoItLeftOutWasLeftOut() throws Exception {
        long now = System.currentTimeMillis();
        insert("21", now - 60_000);
        insert("22", now - 180_000);
        Map<String, Long> file = new java.util.HashMap<>();
        file.put("21", now - 120_000);
        file.put("22", now - 60_000);
        file.put("23", now - 40 * DAY);
        file.put("24", now + DAY);
        file.put("25", now - 30_000);
        SeenVideoHistory.ImportResult result = importAndWait(SeenVideoHistory.captureImportTarget(),
                new WatchHistoryImport.Records(file, 1, 6));
        assertEquals(2, result.imported);
        assertEquals("22 was there already and the file moved its date later", 1, result.refreshed);
        assertEquals("21 was recorded later than the file has it", 1, result.alreadyRecorded);
        assertEquals("23 is older than retention keeps", 1, result.expired);
        assertEquals("24 is dated ahead of the clock", 1, result.future);
        assertEquals(0, result.capped);
        assertEquals(0, result.displaced);
        assertEquals(1 + 5 - 2, result.skipped);
        assertEquals(now - 60_000, lastSeen(ME, "21"));
        assertEquals(now - 60_000, lastSeen(ME, "22"));
        assertEquals(now - 30_000, lastSeen(ME, "25"));
        assertFalse(persistedIds().contains("23"));
        assertFalse(persistedIds().contains("24"));
    }

    @Test public void aRestorePastTheCapCountsWhatDidntFitAndWhatItPushedOut() throws Exception {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = database();
        db.beginTransaction();
        try {
            for (int index = 0; index < SeenVideoHistory.MAX_RECORDS; index++) {
                db.execSQL("INSERT INTO seen_videos VALUES (?, ?, ?)",
                        new Object[]{ME, "kept-" + index, now - 10_000 - index});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Map<String, Long> file = new java.util.HashMap<>();
        file.put("fresh-1", now - 1_000);
        file.put("fresh-2", now - 2_000);
        file.put("stale", now - 10_000 - SeenVideoHistory.MAX_RECORDS - 50);
        SeenVideoHistory.ImportResult result = importAndWait(SeenVideoHistory.captureImportTarget(),
                new WatchHistoryImport.Records(file, 0, 3));
        assertEquals(2, result.imported);
        assertEquals("the file's oldest video didn't fit", 1, result.capped);
        assertEquals("two of the oldest recorded videos made room", 2, result.displaced);
        assertEquals(0, result.alreadyRecorded);
        assertEquals(0, result.expired);
        assertEquals(0, result.future);
        assertEquals(1, result.skipped);
        Set<String> ids = persistedIds();
        assertEquals(SeenVideoHistory.MAX_RECORDS, ids.size());
        assertFalse(ids.contains("stale"));
        assertFalse(ids.contains("kept-" + (SeenVideoHistory.MAX_RECORDS - 1)));
        assertFalse(ids.contains("kept-" + (SeenVideoHistory.MAX_RECORDS - 2)));
        assertTrue(ids.contains("kept-" + (SeenVideoHistory.MAX_RECORDS - 3)));
        assertTrue(ids.contains("fresh-2"));
    }

    private static SeenVideoHistory.ImportResult importAndWait(SeenVideoHistory.ImportTarget target,
                                                               WatchHistoryImport.Records records) throws Exception {
        AtomicReference<SeenVideoHistory.ImportResult> result = new AtomicReference<>();
        SeenVideoHistory.importHistory(target, records, result::set);
        drain();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNotNull("the import never answered", result.get());
        return result.get();
    }

    private static app.morphe.extension.tiktok.settings.preference.ClearSeenVideoHistoryPreference
            clearRowWithSeenVideo() throws Exception {
        // Consume an empty offer left by setup without restoring an earlier test's rows.
        SeenVideoHistory.clear();
        drain();
        SeenVideoHistory.undoClear();
        drain();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(SeenVideoHistory.canUndo());
        SeenVideoHistory.onPlayProgressChange("clear-me", 5000, 10000);
        drain();
        assertTrue(SeenVideoHistory.shouldHide("clear-me"));
        return new app.morphe.extension.tiktok.settings.preference.ClearSeenVideoHistoryPreference(
                RuntimeEnvironment.getApplication());
    }
    private static ExecutorService io() throws Exception { return (ExecutorService) field("IO"); }
    private static void drain() throws Exception { io().submit(() -> {}).get(15, TimeUnit.SECONDS); }
    private static SQLiteDatabase database() throws Exception {
        return ((SQLiteOpenHelper) field("database")).getWritableDatabase();
    }
}

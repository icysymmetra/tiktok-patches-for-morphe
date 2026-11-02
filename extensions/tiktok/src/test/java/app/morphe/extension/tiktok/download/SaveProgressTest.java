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

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/**
 * The running count over a save of several files: the row from three files up and only after
 * the share sheet's settle, its Cancel that stops after the file under way and says so once, a
 * failed file skipped rather than ending the save, a refusal from the disk or the clock ending
 * it with a reason, and the one message that says what came of it all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class SaveProgressTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<String> said = new ArrayList<>();
    private final List<CountDownLatch> holds = new ArrayList<>();

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // No other window: the row falls to the activity's content root.
        SaveNotice.windowRootsForTests = List.of();
        SaveProgress.announcementsForTests = said;
        // The media pool is process-wide, so an earlier class's jobs would sit in this one's line.
        drainPool();
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        for (CountDownLatch hold : holds) hold.countDown();
        holds.clear();
        drainPool();
        idle();
        Utils.setActivity(null);
        SaveNotice.windowRootsForTests = null;
        SaveProgress.announcementsForTests = null;
    }

    private static void drainPool() throws Exception {
        for (int wait = 0; wait < 500; wait++) {
            if (MediaJobScheduler.idle()) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("the media pool never emptied");
    }

    /** Holds all three media workers until the latch opens, so every save after this waits. */
    private CountDownLatch holdWorkers() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        holds.add(hold);
        CountDownLatch started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        for (int index = 0; index < MediaJobScheduler.MAX_RUNNING_JOBS; index++) {
            assertTrue(MediaJobScheduler.submit("progress test hold", () -> {
                started.countDown();
                await(hold);
            }));
        }
        assertTrue("the media workers never started", started.await(5, TimeUnit.SECONDS));
        return hold;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitFor(AtomicInteger counter, int value) throws Exception {
        for (int wait = 0; wait < 500 && counter.get() < value; wait++) Thread.sleep(10);
        assertEquals(value, counter.get());
    }

    /** Lets the row's wait for the share sheet pass, and runs everything posted since. */
    private static void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(SaveNotice.SHEET_SETTLE_MS + 1, TimeUnit.MILLISECONDS);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static TextView find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = find(group.getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    @Test public void twoFilesAreOverBeforeARowWouldHelp() {
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SaveProgress progress = SaveProgress.begin(2);
            settle();
            ViewGroup root = activity.findViewById(android.R.id.content);
            assertNull("no row for two files", find(root, "Cancel"));
            SaveProgress.Outcome outcome = progress.run(index -> { });
            assertTrue(outcome.complete());
            assertEquals("all", SaveProgress.message(outcome, "all"));
            assertTrue("nothing said for a save with no row", said.isEmpty());
        }
    }

    @Test public void threeFilesAreEnoughForARow() {
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            SaveProgress progress = SaveProgress.begin(3);
            settle();
            assertNotNull("the row names the first of three", find(root, "Saving 1 of 3"));
            progress.run(index -> { });
            idle();
            assertNull("the row came down with the save", find(root, "Cancel"));
        }
    }

    /**
     * A sheet with no background of its own has its content at the window's root, which needn't
     * be a FrameLayout. Rows on it take that layout's params, and stacking them or taking one
     * down cast them back to a FrameLayout's and threw.
     */
    @Test public void rowsOnAWindowWhoseRootIsNotAFrameLayoutStackAndGo() {
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            android.widget.LinearLayout sheet = new android.widget.LinearLayout(activity);
            sheet.setOrientation(android.widget.LinearLayout.VERTICAL);
            activity.getWindowManager().addView(sheet, new android.view.WindowManager.LayoutParams());
            idle();
            SaveNotice.windowRootsForTests = List.of(activity.getWindow().getDecorView(), sheet);
            try {
                SaveProgress first = SaveProgress.begin(3);
                SaveProgress second = SaveProgress.begin(4);
                settle();
                View lower = (View) find(sheet, "Saving 1 of 3").getParent();
                View upper = (View) find(sheet, "Saving 1 of 4").getParent();
                assertTrue("the second row stands above the first",
                        ((ViewGroup.MarginLayoutParams) upper.getLayoutParams()).bottomMargin
                                > ((ViewGroup.MarginLayoutParams) lower.getLayoutParams()).bottomMargin);
                first.run(index -> { });
                idle();
                second.run(index -> { });
                idle();
                assertNull("the rows came down with their saves", find(sheet, "Cancel"));
            } finally {
                activity.getWindowManager().removeView(sheet);
            }
        }
    }

    /**
     * Three saves started on a creator's video keep running after it closes, their rows left on
     * its dead views. Those rows took the three places, so a save started back on the feed got
     * no row, and the line counting it went onto the closed screen.
     */
    @Test public void rowsLeftOnAClosedScreenDontHideTheNextSavesRow() {
        var closed = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible();
        Utils.setActivity(closed.get());
        List<SaveProgress> left = new ArrayList<>();
        for (int index = 0; index < SaveProgress.MAX_ROWS; index++) left.add(SaveProgress.begin(3));
        settle();
        closed.get().finish();
        closed.pause().stop().destroy();
        idle();
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity feed = owner.get();
            Utils.setActivity(feed);
            ViewGroup root = feed.findViewById(android.R.id.content);
            SaveProgress next = SaveProgress.begin(4);
            settle();
            View row = root.findViewWithTag("hushfeed_save_progress");
            assertNotNull("the feed's save has a row", row);
            assertEquals("and it can be seen", View.VISIBLE, row.getVisibility());
            assertNull("no line counts rows nobody can see", root.findViewWithTag("hushfeed_save_waiting"));
            next.run(index -> { });
            for (SaveProgress progress : left) progress.run(index -> { });
            idle();
            assertNull(root.findViewWithTag("hushfeed_save_progress"));
        }
    }

    @Test public void aSaveOverInsideTheSheetWaitNeverShowsARow() {
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SaveProgress progress = SaveProgress.begin(5);
            // Five small files, done before TikTok's sheet has even settled.
            progress.run(index -> { });
            settle();
            ViewGroup root = activity.findViewById(android.R.id.content);
            assertNull("a row for a save already over", find(root, "Cancel"));
            assertTrue(said.isEmpty());
        }
    }

    @Test public void theRowSitsAboveTheBannerAndSpeaksOnceForTheWholeSave() {
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            SaveProgress progress = SaveProgress.begin(4);
            settle();
            View row = (View) find(root, "Cancel").getParent();
            int banner = BlockAuthorOverlay.bannerParams(activity, root).bottomMargin;
            assertTrue("the row clears the banner's place",
                    ((FrameLayout.LayoutParams) row.getLayoutParams()).bottomMargin > banner);
            assertEquals(List.of("Saving 1 of 4"), said);
            progress.run(index -> idle());
            idle();
            assertEquals("nothing more is said per file", List.of("Saving 1 of 4"), said);
        }
    }

    @Test public void fiveFilesCountAndCancelStopsAfterTheFileUnderWay() {
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            SaveProgress progress = SaveProgress.begin(5);
            settle();
            assertNotNull("the row names the first file", find(root, "Saving 1 of 5"));
            assertNotNull("the row offers Cancel", find(root, "Cancel"));

            List<Integer> ran = new ArrayList<>();
            SaveProgress.Outcome outcome = progress.run(index -> {
                ran.add(index);
                idle();
                assertNotNull("the count follows the file", find(root, "Saving " + (index + 1) + " of 5"));
                if (index == 1) {
                    // Cancel pressed while the second file is under way: it finishes, the rest don't start.
                    find(root, "Cancel").performClick();
                    idle();
                    assertNotNull("the row says it is stopping", find(root, "Stopping after this file"));
                }
            });
            idle();
            assertEquals(List.of(0, 1), ran);
            assertEquals(2, outcome.saved);
            assertEquals(0, outcome.skipped);
            assertEquals(3, outcome.cancelled);
            assertNull("the row came down with the save", find(root, "Cancel"));
            assertEquals("Saved 2 of 5, the rest cancelled", SaveProgress.message(outcome, "all"));
            assertEquals("said once at the start and once for Cancel",
                    List.of("Saving 1 of 5", "Stopping after this file"), said);
        }
    }

    @Test public void aFileThatFailsIsSkippedAndTheRestStillLand() {
        List<Integer> ran = new ArrayList<>();
        SaveProgress.Outcome outcome = SaveProgress.begin(4).run(index -> {
            ran.add(index);
            if (index == 1) throw new IOException("the mirror refused this one");
        });
        assertEquals(List.of(0, 1, 2, 3), ran);
        assertEquals(3, outcome.saved);
        assertEquals(1, outcome.skipped);
        assertEquals(0, outcome.cancelled);
        assertEquals("Saved 3 of 4, 1 skipped", SaveProgress.message(outcome, "all"));
    }

    @Test public void aRefusalFromTheDiskOrTheClockEndsTheSaveAndSaysWhy() {
        List<Integer> ran = new ArrayList<>();
        SaveProgress.Outcome outcome = SaveProgress.begin(5).run(index -> {
            ran.add(index);
            if (index == 2) throw new MediaBudget.StopException("no room", true);
        });
        assertEquals("the files after the refusal are not tried", List.of(0, 1, 2), ran);
        assertEquals(2, outcome.saved);
        assertEquals(0, outcome.skipped);
        assertEquals(3, outcome.cancelled);
        assertEquals(SaveProgress.Stop.NO_SPACE, outcome.stop);
        assertEquals("Saved 2 of 5, the rest need more free space", SaveProgress.message(outcome, "all"));
        assertEquals("Saved 1 of 4, the rest ran out of time",
                SaveProgress.message(new SaveProgress.Outcome(4, 1, 0, 3, SaveProgress.Stop.NO_TIME), "all"));
    }

    /** #27: a long save is timed file by file, so 50 picks don't run out of time a few files in. */
    @Test public void eachFileGetsTheJobsFullTimeAndAStalledOneStillRunsOut() throws Exception {
        try (var clock = new MediaTransportFixtures.RetryClock(MediaBudget.JOB_DEADLINE_MS)) {
            List<Integer> ran = new ArrayList<>();
            SaveProgress.Outcome outcome = SaveProgress.begin(5).run(index -> {
                ran.add(index);
                clock.nanos += 50_000L * 1_000_000L;
                MediaBudget.check(null);
            });
            assertEquals("five files of 50 s each under a two-minute budget", List.of(0, 1, 2, 3, 4), ran);
            assertEquals(5, outcome.saved);
            assertEquals(SaveProgress.Stop.NONE, outcome.stop);
        }
        try (var clock = new MediaTransportFixtures.RetryClock(MediaBudget.JOB_DEADLINE_MS)) {
            SaveProgress.Outcome outcome = SaveProgress.begin(3).run(index -> {
                clock.nanos += (index == 1 ? 130_000L : 10_000L) * 1_000_000L;
                MediaBudget.check(null);
            });
            assertEquals(1, outcome.saved);
            assertEquals(SaveProgress.Stop.NO_TIME, outcome.stop);
            assertEquals("Saved 1 of 3, the rest ran out of time", SaveProgress.message(outcome, "all"));
        }
    }

    /** A save that failed says so itself; the row must not announce a file that never comes. */
    @Test public void aFailedSaveStopsTheRestWithoutSayingItIsStopping() {
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            SaveProgress progress = SaveProgress.begin(3);
            settle();
            List<Integer> ran = new ArrayList<>();
            progress.run(index -> {
                ran.add(index);
                progress.stop();
                idle();
                assertNull("a failure was announced as a cancel", find(root, "Stopping after this file"));
            });
            idle();
            assertEquals(List.of(0), ran);
            assertEquals(List.of("Saving 1 of 3"), said);
        }
    }

    @Test public void theMessageNamesSkippedAndCancelledTogether() {
        assertEquals("Saved 2 of 6, 1 skipped and the rest cancelled",
                SaveProgress.message(new SaveProgress.Outcome(6, 2, 1, 3), "all"));
        assertEquals("all", SaveProgress.message(new SaveProgress.Outcome(6, 6, 0, 0), "all"));
    }

    /**
     * A save that has to wait shows its row as it is accepted, saying it waits and offering
     * Cancel, and turns into the count when a worker takes it, said once each time.
     */
    @Test public void aSaveThatWaitsShowsItsRowAtOnceAndCountsWhenItStarts() throws Exception {
        CountDownLatch hold = holdWorkers();
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            SaveProgress progress = SaveProgress.queued(3, false);
            CountDownLatch inFirst = new CountDownLatch(1);
            CountDownLatch finishFirst = new CountDownLatch(1);
            holds.add(finishFirst);
            AtomicInteger ended = new AtomicInteger();
            MediaJobScheduler.Job job = progress.submit("waiting photos", null, () -> progress.run(index -> {
                if (index == 0) {
                    inFirst.countDown();
                    await(finishFirst);
                }
            }), ended::incrementAndGet);
            assertNotNull(job);
            assertTrue(job.waiting());
            settle();
            assertNotNull("the row says the save waits", find(root, "Waiting to save 3 files"));
            assertNotNull("the waiting row offers Cancel", find(root, "Cancel"));
            assertEquals(List.of("Waiting to save 3 files"), said);

            hold.countDown();
            assertTrue("the save never started", inFirst.await(5, TimeUnit.SECONDS));
            idle();
            assertNotNull("the row counts once the save runs", find(root, "Saving 1 of 3"));
            assertEquals("the start is said once", List.of("Waiting to save 3 files", "Saving 1 of 3"), said);
            finishFirst.countDown();
            waitFor(ended, 1);
            idle();
            assertNull("the row stayed after the save", root.findViewWithTag("hushfeed_save_progress"));
            assertEquals(2, said.size());
        }
    }

    /** Cancel on a waiting row takes the save out of line: it never runs, and it lets go once. */
    @Test public void cancelOnAWaitingRowTakesTheSaveOutOfLineAndLetsGoOnce() throws Exception {
        CountDownLatch hold = holdWorkers();
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            SaveProgress progress = SaveProgress.queued(1, true);
            AtomicInteger ran = new AtomicInteger();
            AtomicInteger ended = new AtomicInteger();
            MediaJobScheduler.Job job = progress.submit("waiting video", null, ran::incrementAndGet, ended::incrementAndGet);
            settle();
            TextView cancel = find(root, "Cancel");
            assertNotNull("a single waiting video still offers Cancel", cancel);
            assertEquals(View.VISIBLE, cancel.getVisibility());
            assertNotNull(find(root, "Waiting to save video"));

            cancel.performClick();
            idle();
            assertEquals("Save cancelled. Nothing was saved.", ShadowToast.getTextOfLatestToast());
            assertNull("the cancelled save's row stayed", root.findViewWithTag("hushfeed_save_progress"));
            assertEquals(1, ended.get());
            assertFalse(job.waiting());
            assertEquals(0, MediaJobScheduler.queuedJobs());

            hold.countDown();
            drainPool();
            idle();
            assertEquals("a cancelled save ran", 0, ran.get());
            assertEquals("the cancelled save let go twice", 1, ended.get());
        }
    }

    /** Once a single video runs there is nothing after it to cancel, so its Cancel goes. */
    @Test public void aSingleVideoLosesCancelWhenItStarts() throws Exception {
        CountDownLatch hold = holdWorkers();
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            SaveProgress progress = SaveProgress.queued(1, true);
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            holds.add(finish);
            AtomicInteger ended = new AtomicInteger();
            progress.submit("single video", null, () -> progress.run(index -> {
                inside.countDown();
                await(finish);
            }), ended::incrementAndGet);
            settle();
            assertEquals(View.VISIBLE, find(root, "Cancel").getVisibility());
            hold.countDown();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            idle();
            assertNotNull(find(root, "Saving video"));
            assertEquals("a running single file still offers Cancel", View.GONE, find(root, "Cancel").getVisibility());
            finish.countDown();
            waitFor(ended, 1);
        }
    }

    /**
     * Three saves running and a fourth in line, as a phone makes it: the running rows fill the
     * places, so the waiting save's own row, and its Cancel, is out of sight behind the line.
     * The line carries a Cancel that takes the last save in line out of it.
     */
    @Test public void aSaveWaitingOutOfSightCanStillBeCancelled() throws Exception {
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            CountDownLatch finish = new CountDownLatch(1);
            CountDownLatch running = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
            AtomicInteger ended = new AtomicInteger();
            for (int index = 0; index < MediaJobScheduler.MAX_RUNNING_JOBS; index++) {
                SaveProgress.queued(3, false).submit("running " + index, null, () -> {
                    running.countDown();
                    await(finish);
                }, ended::incrementAndGet);
            }
            assertTrue(running.await(5, TimeUnit.SECONDS));
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            SaveProgress.queued(4, false).submit("waiting", null, () -> ran.set(true), ended::incrementAndGet);
            settle();
            try {
                assertEquals(3, visibleRows(root).size());
                assertEquals("One more save waiting",
                        ((TextView) root.findViewWithTag("hushfeed_save_waiting")).getText().toString());
                View line = root.findViewWithTag("hushfeed_save_waiting_row");
                assertNotNull("nothing in sight can cancel the save in line", line);
                TextView cancel = find(line, "Cancel");
                assertNotNull("the line has no Cancel", cancel);
                cancel.performClick();
                idle();
                assertEquals("Save cancelled. Nothing was saved.", ShadowToast.getTextOfLatestToast());
                assertNull("the line stayed after its save left", root.findViewWithTag("hushfeed_save_waiting"));
            } finally {
                finish.countDown();
            }
            waitFor(ended, MediaJobScheduler.MAX_RUNNING_JOBS + 1);
            assertFalse("the cancelled save ran anyway", ran.get());
        }
    }

    /**
     * Five saves waiting at once: three rows show, one line counts the other two, and as rows
     * go the ones out of sight come up in order and are said when they do.
     */
    @Test public void moreRowsThanTheCapWaitBehindOneLine() throws Exception {
        CountDownLatch hold = holdWorkers();
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            List<MediaJobScheduler.Job> jobs = new ArrayList<>();
            AtomicInteger ended = new AtomicInteger();
            for (int index = 0; index < 5; index++) {
                SaveProgress progress = SaveProgress.queued(3 + index, false);
                jobs.add(progress.submit("row " + index, null, () -> { }, ended::incrementAndGet));
            }
            settle();
            assertEquals(3, visibleRows(root).size());
            TextView line = root.findViewWithTag("hushfeed_save_waiting");
            assertNotNull("nothing counts the rows out of sight", line);
            assertEquals("2 more saves waiting", line.getText().toString());
            assertEquals("each row in sight and the line are said once",
                    List.of("Waiting to save 3 files", "Waiting to save 4 files", "Waiting to save 5 files",
                            "One more save waiting", "2 more saves waiting"), said);

            said.clear();
            clickCancelOf(root, "Waiting to save 3 files");
            idle();
            assertEquals(3, visibleRows(root).size());
            assertNotNull("the next row came into sight", visible(find(root, "Waiting to save 6 files")));
            assertEquals("One more save waiting", ((TextView) root.findViewWithTag("hushfeed_save_waiting")).getText().toString());
            assertEquals("the row that came up is said, the shorter count is not",
                    List.of("Waiting to save 6 files"), said);

            clickCancelOf(root, "Waiting to save 4 files");
            idle();
            assertNull("the line stayed with nothing out of sight", root.findViewWithTag("hushfeed_save_waiting"));
            assertEquals(3, visibleRows(root).size());
            for (MediaJobScheduler.Job job : jobs) job.cancel();
            idle();
            assertEquals(5, ended.get());
            assertTrue(visibleRows(root).isEmpty());
            hold.countDown();
        }
    }

    /** Presses the Cancel of the row saying {@code label}: its last child, in any language. */
    private static void clickCancelOf(ViewGroup root, String label) {
        ViewGroup row = (ViewGroup) rowOf(find(root, label));
        assertNotNull("no row says " + label, row);
        View cancel = row.getChildAt(row.getChildCount() - 1);
        assertEquals(View.VISIBLE, cancel.getVisibility());
        cancel.performClick();
    }

    private static View rowOf(View view) {
        View at = view;
        while (at != null && !"hushfeed_save_progress".equals(at.getTag())) at = (View) at.getParent();
        return at;
    }

    private static View visible(View view) {
        View row = rowOf(view);
        return row != null && row.getVisibility() == View.VISIBLE ? row : null;
    }

    private static List<View> visibleRows(ViewGroup root) {
        List<View> rows = new ArrayList<>();
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if ("hushfeed_save_progress".equals(child.getTag()) && child.getVisibility() == View.VISIBLE) rows.add(child);
        }
        return rows;
    }

    /** What the queue says in each language Hushfeed ships, with a count of one in each. */
    private static final String[][] LOCALES = {
            {"en", "Waiting to save 3 files", "One more save waiting",
                    "Waiting to save video\nStarts after one other save",
                    "The last one is still waiting to start", "Save cancelled. Nothing was saved."},
            {"de", "3 Dateien warten auf das Speichern", "Eine weitere Speicherung wartet",
                    "Video wartet auf das Speichern\nStartet nach einer anderen Speicherung",
                    "Das letzte wartet noch auf den Start", "Speichern abgebrochen. Es wurde nichts gespeichert."},
            {"es", "Esperando para guardar 3 archivos", "Un guardado más en espera",
                    "Esperando para guardar el vídeo\nEmpieza después de otro guardado",
                    "El anterior todavía está esperando para empezar", "Guardado cancelado. No se guardó nada."},
            {"pt-rBR", "Aguardando para salvar 3 arquivos", "Mais um salvamento aguardando",
                    "Aguardando para salvar o vídeo\nComeça depois de outro salvamento",
                    "O anterior ainda está esperando para começar", "Salvamento cancelado. Nada foi salvo."},
            {"tr", "3 dosya kaydedilmeyi bekliyor", "Bir kayıt daha bekliyor",
                    "Video kaydedilmeyi bekliyor\nBaşka bir kayıttan sonra başlar",
                    "Bir önceki hâlâ başlamayı bekliyor", "Kaydetme iptal edildi. Hiçbir şey kaydedilmedi."},
            // Indonesian has no singular, so one takes the other form, number and all.
            {"in-rID", "Menunggu untuk menyimpan 3 berkas", "1 penyimpanan lagi menunggu",
                    "Menunggu untuk menyimpan video\nDimulai setelah 1 penyimpanan lain",
                    "Yang sebelumnya masih menunggu untuk dimulai", "Penyimpanan dibatalkan. Tidak ada yang disimpan."},
    };

    /** The waiting row, the line past the cap, the brief word, a second request and Cancel, in six languages. */
    @Test public void theQueueSpeaksEveryShippedLanguage() throws Exception {
        CountDownLatch hold = holdWorkers();
        for (String[] locale : LOCALES) {
            RuntimeEnvironment.setQualifiers(locale[0]);
            ShadowToast.reset();
            try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
                Activity activity = owner.get();
                Utils.setActivity(activity);
                ViewGroup root = activity.findViewById(android.R.id.content);
                AtomicInteger ended = new AtomicInteger();
                MediaJobScheduler.Job brief = MediaJobScheduler.submit("brief", "brief " + locale[0], () -> { }, ended::incrementAndGet);
                MediaJobScheduler.acknowledge(brief, null, app.morphe.extension.tiktok.settings.L10n.t("Waiting to save video"));
                idle();
                assertEquals(locale[0], locale[3], ShadowToast.getTextOfLatestToast());
                assertEquals(locale[0], locale[4], MediaJobScheduler.busyMessage("brief " + locale[0]));

                List<MediaJobScheduler.Job> rows = new ArrayList<>();
                for (int index = 0; index < SaveProgress.MAX_ROWS + 1; index++) {
                    rows.add(SaveProgress.queued(3, false).submit("row", null, () -> { }, ended::incrementAndGet));
                }
                settle();
                assertNotNull(locale[0] + " row", find(root, locale[1]));
                TextView line = root.findViewWithTag("hushfeed_save_waiting");
                assertEquals(locale[0], locale[2], line.getText().toString());

                clickCancelOf(root, locale[1]);
                idle();
                assertEquals(locale[0], locale[5], ShadowToast.getTextOfLatestToast());
                brief.cancel();
                for (MediaJobScheduler.Job job : rows) job.cancel();
                idle();
                assertEquals(locale[0], rows.size() + 1, ended.get());
            } finally {
                Utils.setActivity(null);
            }
        }
        RuntimeEnvironment.setQualifiers("en");
        hold.countDown();
    }

    /**
     * Original photos from three up get a row while they wait. Cancel there lets go of the post
     * exactly once, so asking again is taken rather than told the last one is still going.
     */
    @Test public void waitingPhotosCanBeCancelledFromTheRowAndAskedForAgain() throws Exception {
        boolean before = Settings.DOWNLOAD_ORIGINAL_PHOTOS.get();
        Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        CountDownLatch hold = holdWorkers();
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            AdvancedDownloadsTest.PhotoPost post = new AdvancedDownloadsTest.PhotoPost("queued-post", List.of(
                    new AdvancedDownloadsTest.Photo("https://example.com/one"),
                    new AdvancedDownloadsTest.Photo("https://example.com/two"),
                    new AdvancedDownloadsTest.Photo("https://example.com/three")));
            Set<String> active = ReflectionHelpers.getStaticField(OriginalPhotos.class, "ACTIVE");

            assertTrue(OriginalPhotos.startPhotos(post, null, false));
            idle();
            assertEquals("Saving 3 original photos", ShadowToast.getTextOfLatestToast());
            assertTrue(OriginalPhotos.startPhotos(post, null, false));
            idle();
            assertEquals("The last one is still waiting to start", ShadowToast.getTextOfLatestToast());
            assertEquals("a second request queued a second save", 1, MediaJobScheduler.queuedJobs());
            settle();
            clickCancelOf(root, "Waiting to save 3 files");
            idle();
            assertFalse("cancelling kept the post held", active.contains("queued-post"));
            assertEquals(0, MediaJobScheduler.queuedJobs());

            ShadowToast.reset();
            assertTrue(OriginalPhotos.startPhotos(post, null, false));
            idle();
            assertEquals("the retry was not taken", "Saving 3 original photos", ShadowToast.getTextOfLatestToast());
            assertTrue(active.contains("queued-post"));
            MediaJobScheduler.job("photos queued-post").cancel();
            assertFalse(active.contains("queued-post"));
        } finally {
            hold.countDown();
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(before);
        }
    }

    @Test public void cancelBeforeTheFirstFileSavesNothingAndSaysSo() {
        SaveProgress progress = SaveProgress.begin(3);
        progress.cancel();
        List<Integer> ran = new ArrayList<>();
        SaveProgress.Outcome outcome = progress.run(ran::add);
        assertTrue(ran.isEmpty());
        assertEquals(0, outcome.saved);
        assertEquals(3, outcome.cancelled);
        assertEquals("Saved 0 of 3, the rest cancelled", SaveProgress.message(outcome, "all"));
    }

    /** Small photo jobs used the default threshold and had no count or Cancel at all. */
    @Test public void fileCountModeShowsRunningCountsAndCancelForOneTwoAndThreeFiles() throws Exception {
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            ViewGroup root = owner.get().findViewById(android.R.id.content);
            for (int files = 1; files <= 3; files++) {
                SaveProgress progress = SaveProgress.queuedFiles(files);
                CountDownLatch inFirst = new CountDownLatch(1);
                CountDownLatch finishFirst = new CountDownLatch(1);
                holds.add(finishFirst);
                List<Integer> ran = java.util.Collections.synchronizedList(new ArrayList<>());
                MediaJobScheduler.Job job = progress.submit("file-count test", null, () -> progress.run(index -> {
                    ran.add(index);
                    if (index == 0) {
                        inFirst.countDown();
                        await(finishFirst);
                    }
                }), null);
                assertNotNull(job);
                assertTrue("The file save never started", inFirst.await(5, TimeUnit.SECONDS));
                settle();
                assertNotNull(find(root, "Saving 1 of " + files));
                assertNull("A one-photo job must not be named video", find(root, "Saving video"));
                View row = root.findViewWithTag("hushfeed_save_progress");
                assertNotNull(row);
                assertFalse("File counts must not pretend to stream byte progress",
                        containsProgressBar(row));
                TextView cancel = find(root, "Cancel");
                assertNotNull(cancel);
                assertEquals(View.VISIBLE, cancel.getVisibility());
                cancel.performClick();
                idle();
                assertTrue(progress.isCancelled());
                assertNotNull(find(root, "Stopping after this file"));
                finishFirst.countDown();
                drainPool();
                idle();
                assertEquals("Only the file already under way may finish", List.of(0), ran);
                assertNull(root.findViewWithTag("hushfeed_save_progress"));
            }
        }
    }

    private static boolean containsProgressBar(View view) {
        if (view instanceof android.widget.ProgressBar) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsProgressBar(group.getChildAt(i))) return true;
            }
        }
        return false;
    }
}

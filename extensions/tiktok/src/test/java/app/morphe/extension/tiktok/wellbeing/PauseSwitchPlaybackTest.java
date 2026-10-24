package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorPatch;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.playback.PausePlayback;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.wellbeing.SessionPlaybackHoldTest.NativeController;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * The two pause switches on TikTok's own player. On 47.0.3 the video played on under the
 * comments and under the "Tap to start the feed" cover, because asking for the audio focus does
 * not stop that player; the switches now pause it through the bridge the daily hold uses. These
 * drive the switches through their own entry points with the hold's fake player, and read the
 * player's position the way the device check does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class PauseSwitchPlaybackTest {
    private final AtomicLong now = new AtomicLong(1_788_800_400_000L);

    public static final class HostActivity extends Activity { }

    /** The feed as TikTok builds it: its content set in onCreate, so the window is there by resume. */
    public static final class FeedActivity extends Activity {
        @Override protected void onCreate(android.os.Bundle state) {
            super.onCreate(state);
            setContentView(new FrameLayout(this));
        }
    }

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // The switches this suite drives have their rows with Comment tools and Block author.
        SettingsStatus.commentToolsEnabled = true;
        SettingsStatus.blockAuthorEnabled = true;
        SessionPlaybackHoldTest.installNativeControls();
        SessionBudget.awaitWritesForTests();
        resetSettings();
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "resetForTests");
        ReflectionHelpers.callStaticMethod(PausePlayback.class, "resetForTests");
    }

    @After public void tearDown() throws Exception {
        SettingsStatus.commentToolsEnabled = false;
        SettingsStatus.blockAuthorEnabled = false;
        SessionPlaybackHold.releaseForLock(false);
        ReflectionHelpers.callStaticMethod(PausePlayback.class, "resetForTests");
        Utils.setActivity(null);
        SessionBudget.awaitWritesForTests();
        resetSettings();
        SessionBudget.resetForTests();
        SessionLockOverlay.sync();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        SessionBudget.setClockForTests(null);
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "resetForTests");
        SessionPlaybackHold.nativeForTests = null;
        String pkg = RuntimeEnvironment.getApplication().getPackageName();
        FeedVisibility.resolveForTests(pkg, "47.4.3:pwm", 0);
        FeedVisibility.resolveForTests(pkg, "47.4.3:wla", 0);
    }

    private static void resetSettings() {
        Settings.PAUSE_ON_COMMENTS.resetToDefault();
        Settings.NO_RESUME_ON_FOREGROUND.resetToDefault();
        Settings.PAUSE_FIRST_VIDEO.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_PASSES_PER_DAY.resetToDefault();
        Settings.SESSION_BUDGET_NOTICE_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
    }

    @Test public void openCommentsStopTheVideoAndClosingThemPlaysItOnFromThere() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");

            Dialog sheet = openSheet(activity);
            PausePlayback.onCommentCellBound(cellIn(sheet));
            idle();
            assertEquals("opening the comments did not pause the video", 1, player.manager.pauses);
            long stoppedAt = player.manager.position;
            player.manager.advance(3_000);
            assertEquals("the video played on under the comments", stoppedAt, player.manager.position);

            sheet.dismiss();
            idle();
            assertEquals("closing the comments did not give the video back", 1, player.manager.resumes);
            player.manager.advance(1_000);
            assertEquals(stoppedAt + 1_000, player.manager.position);
        }
    }

    @Test public void underTheReturnCoverAVideoTikTokStartsAgainStaysStoppedUntilTheTap() {
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");

            ReflectionHelpers.callStaticMethod(PausePlayback.class, "onBackground");
            idle();
            // TikTok stops its own player as the app goes away.
            player.manager.playing = false;
            player.manager.paused = true;
            ReflectionHelpers.callStaticMethod(PausePlayback.class, "onForeground",
                    ClassParameter.from(Activity.class, activity));
            idle();
            View catcher = ReflectionHelpers.callStaticMethod(PausePlayback.class, "catcherForTests");
            assertNotNull("returning put up no cover", catcher);
            assertEquals("a video TikTok had stopped was paused again", 0, player.manager.pauses);

            // Then TikTok starts it again by itself, under the cover.
            player.manager.nativeResume();
            player.reportProgress();
            idle();
            assertEquals("the video TikTok started again played on under the cover",
                    1, player.manager.pauses);
            long stoppedAt = player.manager.position;
            player.manager.advance(3_000);
            assertEquals(stoppedAt, player.manager.position);

            assertTrue(catcher.performClick());
            idle();
            assertEquals("the tap did not start the video", 1, player.manager.resumes);
            player.manager.advance(1_000);
            assertEquals(stoppedAt + 1_000, player.manager.position);
        }
    }

    /**
     * #83: opened from its icon with "Don't start the first video" on, the first video played for
     * a second or two, because the hold only heard of its player at the first progress report.
     * The first frame stops it now, and the tap still finds it to play.
     */
    @Test public void aStartsFirstVideoStopsAtItsFirstFrameAndTheTapPlaysIt() {
        Settings.PAUSE_FIRST_VIDEO.save(true);
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try (var owner = Robolectric.buildActivity(FeedActivity.class, launcher).create()) {
            Utils.setActivity(owner.get());
            PausePlayback.install(owner.get());
            // Bound and starting, with no progress reported yet.
            NativeController player = new NativeController();
            player.bind("cold");
            owner.start().resume().visible();
            View catcher = ReflectionHelpers.callStaticMethod(PausePlayback.class, "catcherForTests");
            assertNotNull("the start was not held", catcher);

            BlockAuthorPatch.onFirstFrame(player);
            idle();
            assertEquals("the first video played on past its first frame", 1, player.manager.pauses);
            long stoppedAt = player.manager.position;
            player.manager.advance(2_000);
            assertEquals(stoppedAt, player.manager.position);

            assertTrue(catcher.performClick());
            idle();
            assertEquals("the tap did not start the first video", 1, player.manager.resumes);
        }
    }

    /**
     * #83 on the S22: after the first-frame pause TikTok played the first video a second time
     * about 1.7 s later, and it played until the next progress report paused it again. That play,
     * through PlayerController's play method or the manager's own resume, is turned down now while
     * the cover waits. The tap plays the video once, through the same guarded resume, and after it
     * TikTok's plays go through as usual.
     */
    @Test public void aStartsSecondPlayIsTurnedDownAndTheTapPlaysTheVideoOnce() {
        Settings.PAUSE_FIRST_VIDEO.save(true);
        guardTheManagersResume();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try (var owner = Robolectric.buildActivity(FeedActivity.class, launcher).create()) {
            Utils.setActivity(owner.get());
            PausePlayback.install(owner.get());
            NativeController player = new NativeController();
            player.bind("cold");
            owner.start().resume().visible();
            View catcher = ReflectionHelpers.callStaticMethod(PausePlayback.class, "catcherForTests");
            assertNotNull("the start was not held", catcher);
            assertFalse("the play that starts the first video was turned down",
                    SessionPlaybackHold.refusePlay(player, player.currentClip()));

            BlockAuthorPatch.onFirstFrame(player);
            idle();
            assertEquals("the first video played on past its first frame", 1, player.manager.pauses);

            assertTrue("TikTok's second play started the video under the cover",
                    SessionPlaybackHold.refusePlay(player, player.currentClip()));
            assertTrue("TikTok's own resume started the video under the cover",
                    SessionPlaybackHold.refuseResume(player.manager));
            NativeController other = new NativeController();
            assertFalse("a play on another player was turned down",
                    SessionPlaybackHold.refusePlay(other, other.currentClip()));
            assertFalse("another player's resume was turned down",
                    SessionPlaybackHold.refuseResume(other.manager));

            assertTrue(catcher.performClick());
            idle();
            assertEquals("the tap did not start the first video exactly once", 1, player.manager.resumes);
            assertTrue(player.manager.playing);
            assertFalse("TikTok's play after the tap was turned down",
                    SessionPlaybackHold.refusePlay(player, player.currentClip()));
            assertFalse("TikTok's resume after the tap was turned down",
                    SessionPlaybackHold.refuseResume(player.manager));
        }
    }

    /** With the switch off, or on but with Hushfeed paused, nothing turns TikTok's plays down. */
    @Test public void withTheSwitchOffOrHushfeedPausedTikTokPlaysAsUsual() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (boolean paused : new boolean[] {false, true}) {
            Settings.PAUSE_FIRST_VIDEO.save(paused);
            ReflectionHelpers.callStaticMethod(PausePlayback.class, "resetForTests");
            ReflectionHelpers.callStaticMethod(app.morphe.extension.shared.settings.Setting.class,
                    "setPausedForProcess", ClassParameter.from(boolean.class, paused));
            try (var owner = Robolectric.buildActivity(FeedActivity.class, launcher).create()) {
                Utils.setActivity(owner.get());
                PausePlayback.install(owner.get());
                NativeController player = new NativeController();
                player.bind("free");
                owner.start().resume().visible();
                BlockAuthorPatch.onFirstFrame(player);
                idle();
                String state = paused ? "paused" : "off";
                assertEquals("a first frame was stopped with the switch " + state, 0, player.manager.pauses);
                assertFalse("TikTok's play was turned down with the switch " + state,
                        SessionPlaybackHold.refusePlay(player, player.currentClip()));
                assertFalse("TikTok's resume was turned down with the switch " + state,
                        SessionPlaybackHold.refuseResume(player.manager));
            } finally {
                ReflectionHelpers.callStaticMethod(app.morphe.extension.shared.settings.Setting.class,
                        "setPausedForProcess", ClassParameter.from(boolean.class, false));
            }
        }
    }

    /**
     * The fakes' resume asks the hold first, the way the patched manager implementation does, so
     * the hold's own resume is shown to pass its guard.
     */
    private static void guardTheManagersResume() {
        SessionPlaybackHold.nativeForTests = new SessionPlaybackHold.NativeControls() {
            @Override public Object currentAweme(Object controller) {
                return ((NativeController) controller).currentClip();
            }
            @Override public void pause(Object manager) { ((SessionPlaybackHoldTest.NativeManager) manager).pause(); }
            @Override public void resume(Object manager) {
                if (SessionPlaybackHold.refuseResume(manager)) return;
                ((SessionPlaybackHoldTest.NativeManager) manager).resume();
            }
        };
    }

    /** With nothing holding the feed, a first frame leaves the video alone. */
    @Test public void aFirstFrameWithNoSwitchHoldingTheFeedPlays() {
        NativeController player = new NativeController();
        player.bind("free");
        BlockAuthorPatch.onFirstFrame(player);
        idle();
        assertEquals(0, player.manager.pauses);
    }

    @Test public void goingAwayLeavesTheVideoToTikTokAndASheetStillOpenStopsItAgain() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            Dialog sheet = openSheet(activity);
            PausePlayback.onCommentCellBound(cellIn(sheet));
            idle();
            assertEquals(1, player.manager.pauses);

            ReflectionHelpers.callStaticMethod(PausePlayback.class, "onBackground");
            idle();
            assertEquals("going away started the video", 0, player.manager.resumes);

            ReflectionHelpers.callStaticMethod(PausePlayback.class, "onForeground",
                    ClassParameter.from(Activity.class, activity));
            player.manager.nativeResume();
            player.reportProgress();
            idle();
            assertEquals("the video played on behind the sheet still open", 2, player.manager.pauses);

            sheet.dismiss();
            idle();
            assertEquals(1, player.manager.resumes);
        }
    }

    /**
     * Turning the switch off while the comments still hold the video stops the feature, and that
     * must not start the sound behind the open sheet: the video is left as it is, and closing the
     * sheet afterwards changes nothing either, since the feature let go when it was switched off.
     */
    @Test public void switchingOffMidHoldLeavesTheVideoPausedBehindTheOpenComments() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            FrameLayout sheet = panelIn(activity, true);
            PausePlayback.onCommentCellBound(sheet.getChildAt(1));
            idle();
            assertEquals(1, player.manager.pauses);
            assertTrue(ReflectionHelpers.<Boolean>callStaticMethod(PausePlayback.class, "quietenedForTests"));

            Settings.PAUSE_ON_COMMENTS.save(false);
            idleFor(600);
            assertEquals("switching off started the video behind the open comments",
                    0, player.manager.resumes);
            assertFalse("the sound was kept after the switch went off",
                    ReflectionHelpers.<Boolean>callStaticMethod(PausePlayback.class, "quietenedForTests"));

            park(sheet);
            idleFor(600);
            assertEquals("the sheet closing resumed a video the feature had let go",
                    0, player.manager.resumes);
        }
    }

    @Test public void aVideoTheReaderPausedStaysPausedWhenTheCommentsClose() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            player.manager.playing = false;
            player.manager.paused = true;

            Dialog sheet = openSheet(activity);
            PausePlayback.onCommentCellBound(cellIn(sheet));
            idle();
            sheet.dismiss();
            idle();
            assertEquals(0, player.manager.pauses);
            assertEquals("closing the comments started a video the reader had paused",
                    0, player.manager.resumes);
        }
    }

    @Test public void closingTheCommentsOverTheReturnCoverKeepsTheVideoStopped() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            Dialog sheet = openSheet(activity);
            PausePlayback.onCommentCellBound(cellIn(sheet));
            idle();

            ReflectionHelpers.callStaticMethod(PausePlayback.class, "onBackground");
            idle();
            ReflectionHelpers.callStaticMethod(PausePlayback.class, "onForeground",
                    ClassParameter.from(Activity.class, activity));
            player.manager.nativeResume();
            player.reportProgress();
            idle();
            View catcher = ReflectionHelpers.callStaticMethod(PausePlayback.class, "catcherForTests");
            assertNotNull("returning put up no cover under the sheet", catcher);
            assertFalse(player.manager.isPlaying());

            sheet.dismiss();
            idle();
            assertEquals("closing the comments started the video under the cover",
                    0, player.manager.resumes);
            assertTrue("closing the comments handed the sound back under the cover",
                    ReflectionHelpers.<Boolean>callStaticMethod(PausePlayback.class, "quietenedForTests"));

            assertTrue(catcher.performClick());
            idle();
            assertEquals(1, player.manager.resumes);
        }
    }

    @Test public void aPanelUpWhenTheCommentsCloseKeepsTheVideoStopped() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            Dialog sheet = openSheet(activity);
            PausePlayback.onCommentCellBound(cellIn(sheet));
            idle();
            assertEquals(1, player.manager.pauses);

            Settings.SESSION_BUDGET_VIDEOS.save(2);
            Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
            SessionBudget.noteVideo("one");
            SessionBudget.noteVideo("two");
            assertTrue("the budget was not reached", SessionBudget.claimNotice());
            assertTrue("no hold was placed", SessionBudget.isLocked());

            sheet.dismiss();
            idle();
            assertEquals("the video played under the daily panel", 0, player.manager.resumes);
        }
    }

    /**
     * TikTok 47.0.3 draws the comments into the activity rather than a window of their own, and
     * parks the sheet below the screen when it closes, so nothing detaches. The switch did
     * nothing there at all, not even the sound, until it went by the sheet the feed's controls
     * already know by its id.
     */
    @Test public void aCommentSheetDrawnIntoTheActivityStopsTheVideoUntilItSlidesAway() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            FrameLayout sheet = panelIn(activity, true);

            PausePlayback.onCommentCellBound(sheet.getChildAt(1));
            idle();
            assertEquals("opening 47.0.3's comment sheet did not pause the video", 1, player.manager.pauses);
            long stoppedAt = player.manager.position;
            player.manager.advance(3_000);
            assertEquals(stoppedAt, player.manager.position);

            // Scrolling the comments binds more cells into the same sheet, and the sheet stays up
            // past the checks that follow a bind.
            PausePlayback.onCommentCellBound(sheet.getChildAt(1));
            idleFor(2_000);
            assertEquals(1, player.manager.pauses);
            assertEquals("the video came back with the comments still open", 0, player.manager.resumes);

            park(sheet);
            idleFor(300);
            assertEquals("the video stayed paused after the sheet slid away", 1, player.manager.resumes);
            player.manager.advance(1_000);
            assertEquals(stoppedAt + 1_000, player.manager.position);
        }
    }

    @Test public void cellsBoundBeforeTheSheetSlidesUpStillStopTheVideo() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            FrameLayout sheet = panelIn(activity, false);

            PausePlayback.onCommentCellBound(sheet.getChildAt(1));
            idle();
            assertEquals("a sheet still below the screen paused the video", 0, player.manager.pauses);

            // The slide up moves the sheet without a layout pass.
            sheet.setTranslationY(0f);
            idleFor(300);
            assertEquals("the sheet came up after its cells bound and the video played on",
                    1, player.manager.pauses);

            park(sheet);
            idleFor(300);
            assertEquals(1, player.manager.resumes);
        }
    }

    @Test public void aSheetFilledWhileParkedIsCaughtWhenItOpensLater() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            FrameLayout sheet = panelIn(activity, false);

            PausePlayback.onCommentCellBound(sheet.getChildAt(1));
            idleFor(2_000);
            assertEquals(0, player.manager.pauses);
            assertFalse("a parked sheet took the sound",
                    ReflectionHelpers.<Boolean>callStaticMethod(PausePlayback.class, "quietenedForTests"));

            // Opened later with nothing new to bind: the opening comes with a layout pass.
            sheet.setTranslationY(0f);
            sheet.getViewTreeObserver().dispatchOnGlobalLayout();
            idle();
            assertEquals("an opening that bound nothing new left the video playing",
                    1, player.manager.pauses);

            park(sheet);
            idleFor(300);
            assertEquals(1, player.manager.resumes);
        }
    }

    @Test public void theFirstSheetOfAStartStopsTheVideoBeforeAnyCommentBinds() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        // The first open of each start used to wait for a comment to bind, which comes only once
        // the comments have loaded, and a video with none binds nothing at all.
        try (var owner = Robolectric.buildActivity(FeedActivity.class).create()) {
            PausePlayback.install(owner.get());
            owner.start().resume().visible();
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            // TikTok inflates the sheet as it opens the first time: a layout pass, no bind.
            FrameLayout sheet = panelIn(activity, true);
            idle();
            assertEquals("a sheet that opened before any comment bound left the video playing",
                    1, player.manager.pauses);

            park(sheet);
            idleFor(300);
            assertEquals(1, player.manager.resumes);

            // Opened again on a video with no comments: nothing binds this time either.
            sheet.setTranslationY(0f);
            sheet.getViewTreeObserver().dispatchOnGlobalLayout();
            idle();
            assertEquals("a sheet with nothing to bind left the video playing", 2, player.manager.pauses);
        }
    }

    @Test public void watchingForTheSheetFromTheStartDoesNothingWithTheSwitchOff() {
        try (var owner = Robolectric.buildActivity(FeedActivity.class).create()) {
            PausePlayback.install(owner.get());
            owner.start().resume().visible();
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            panelIn(activity, true);
            idleFor(300);
            assertEquals("the switch is off and the comments paused the video", 0, player.manager.pauses);
        }
    }

    @Test public void aSheetThatSlidesUpAfterItsLayoutPassStillStopsTheVideo() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(FeedActivity.class).create()) {
            PausePlayback.install(owner.get());
            owner.start().resume().visible();
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            FrameLayout sheet = panelIn(activity, true);
            idle();
            park(sheet);
            idleFor(2_000);
            assertEquals(1, player.manager.resumes);

            // Opened on the next video, one with no comments: the pass that lays it out comes
            // while it's still below the screen, and the slide up after it makes no pass at all.
            sheet.getViewTreeObserver().dispatchOnGlobalLayout();
            sheet.setTranslationY(0f);
            idleFor(300);
            assertEquals("a sheet that slid up after its layout pass left the video playing",
                    2, player.manager.pauses);
        }
    }

    @Test public void aSavedCommentSwitchDoesNothingInABundleWithoutCommentTools() {
        // Saved on under a bundle with Comment tools, then patched again without it: no row
        // says the switch is on, so the comments must leave the video alone.
        SettingsStatus.commentToolsEnabled = false;
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(FeedActivity.class).create()) {
            PausePlayback.install(owner.get());
            owner.start().resume().visible();
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            FrameLayout sheet = panelIn(activity, true);
            PausePlayback.onCommentCellBound(sheet.getChildAt(1));
            idleFor(300);
            assertEquals("a switch with no row paused the video behind the comments", 0, player.manager.pauses);
        }
    }

    @Test public void comingBackToCommentsLeftOpenInTheActivityKeepsTheVideoStoppedUntilBothAreGone() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            FrameLayout sheet = panelIn(activity, true);
            PausePlayback.onCommentCellBound(sheet.getChildAt(1));
            idle();
            assertEquals(1, player.manager.pauses);

            ReflectionHelpers.callStaticMethod(PausePlayback.class, "onBackground");
            idle();
            assertEquals("going away started the video", 0, player.manager.resumes);
            ReflectionHelpers.callStaticMethod(PausePlayback.class, "onForeground",
                    ClassParameter.from(Activity.class, activity));
            player.manager.nativeResume();
            player.reportProgress();
            idle();
            assertEquals("the video played on behind the comments on the way back",
                    2, player.manager.pauses);
            View catcher = ReflectionHelpers.callStaticMethod(PausePlayback.class, "catcherForTests");
            assertNotNull(catcher);

            assertTrue(catcher.performClick());
            idle();
            assertEquals("the tap started the video with the comments still open",
                    0, player.manager.resumes);

            park(sheet);
            idleFor(300);
            assertEquals(1, player.manager.resumes);
        }
    }

    // ------------------------------------------------------------------------------- app lock

    @Test public void theAppLockStopsTheVideoUntilItLiftsAndThenPlaysItOn() {
        NativeController player = playing("first");
        SessionPlaybackHold.pauseForLock();
        idle();
        assertEquals("the lock did not stop the video under it", 1, player.manager.pauses);

        // TikTok plays it again by itself under the prompt.
        player.manager.nativeResume();
        player.reportProgress();
        idle();
        assertEquals("the video TikTok started again played on under the lock", 2, player.manager.pauses);

        SessionPlaybackHold.releaseForLock(true);
        idle();
        assertEquals("unlocking did not give the video back", 1, player.manager.resumes);
    }

    @Test public void aSwitchLettingGoUnderTheLockLeavesTheVideoStoppedUntilTheUnlock() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            Dialog sheet = openSheet(activity);
            PausePlayback.onCommentCellBound(cellIn(sheet));
            idle();
            assertEquals(1, player.manager.pauses);

            SessionPlaybackHold.pauseForLock();
            sheet.dismiss();
            idle();
            assertEquals("closing the comments started the video under the lock", 0, player.manager.resumes);

            SessionPlaybackHold.releaseForLock(true);
            idle();
            assertEquals(1, player.manager.resumes);
        }
    }

    @Test public void theReturnCoverKeepsTheVideoStoppedWhenTheLockLifts() {
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            NativeController player = playing("first");
            SessionPlaybackHold.pauseForLock();
            idle();
            ReflectionHelpers.callStaticMethod(PausePlayback.class, "onBackground");
            idle();
            ReflectionHelpers.callStaticMethod(PausePlayback.class, "onForeground",
                    ClassParameter.from(Activity.class, activity));
            idle();
            View catcher = ReflectionHelpers.callStaticMethod(PausePlayback.class, "catcherForTests");
            assertNotNull(catcher);

            SessionPlaybackHold.releaseForLock(true);
            idle();
            assertEquals("the unlock started the video under the tap cover", 0, player.manager.resumes);

            assertTrue(catcher.performClick());
            idle();
            assertEquals(1, player.manager.resumes);
        }
    }

    // ------------------------------------------------------------------------------- fixture

    /**
     * 47.0.3's comment sheet: drawn into the activity, known by its ids (pvp, and the wk7 title),
     * with a cell in it, and parked below the screen unless {@code up}.
     */
    private static FrameLayout panelIn(Activity activity, boolean up) {
        FrameLayout content = activity.findViewById(android.R.id.content);
        FrameLayout sheet = new FrameLayout(activity);
        sheet.setId(0x7f0a1001);
        TextView title = new TextView(activity);
        title.setId(0x7f0a1002);
        sheet.addView(title, new FrameLayout.LayoutParams(200, 80));
        sheet.addView(new View(activity), new FrameLayout.LayoutParams(200, 80));
        content.addView(sheet, new FrameLayout.LayoutParams(500, 700));
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.4.3:pwm", sheet.getId());
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.4.3:wla", title.getId());
        idle();
        if (!up) park(sheet);
        return sheet;
    }

    /** Where TikTok keeps the sheet once it closes: inflated, below the screen. */
    private static void park(View sheet) {
        sheet.setTranslationY(((View) sheet.getParent()).getHeight() + 100f);
    }

    private static void idleFor(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis));
    }

    /** A player on its video, which has reported progress, so the hold knows it is on screen. */
    private static NativeController playing(String aid) {
        NativeController player = new NativeController();
        player.bind(aid);
        player.reportProgress();
        idle();
        assertTrue(player.manager.isPlaying());
        return player;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** A comment sheet the way TikTok's is: a window of its own over the feed. */
    private static Dialog openSheet(Activity activity) {
        Dialog sheet = new Dialog(activity);
        FrameLayout content = new FrameLayout(activity);
        content.addView(new View(activity));
        sheet.setContentView(content);
        sheet.show();
        idle();
        return sheet;
    }

    private static View cellIn(Dialog sheet) {
        ViewGroup content = sheet.findViewById(android.R.id.content);
        assertNotNull("the sheet has no content", content);
        View cell = content;
        while (cell instanceof ViewGroup && ((ViewGroup) cell).getChildCount() > 0) {
            cell = ((ViewGroup) cell).getChildAt(0);
        }
        return cell;
    }
}

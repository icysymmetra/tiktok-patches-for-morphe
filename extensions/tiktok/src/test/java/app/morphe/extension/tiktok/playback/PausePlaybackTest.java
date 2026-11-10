package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * The feed keeps playing behind an open comment sheet, and it starts itself again every time
 * the app comes back. Neither is something the reader asked for, so both ask for the audio focus,
 * which is how one app tells another to stop. These cover the focus, the sheet and the catcher;
 * the pause on TikTok's own player is in wellbeing.PauseSwitchPlaybackTest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class PausePlaybackTest {
    public static class HostActivity extends Activity {
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // The switches this suite drives have their rows with Comment tools and Block author.
        SettingsStatus.commentToolsEnabled = true;
        SettingsStatus.blockAuthorEnabled = true;
        Settings.PAUSE_ON_COMMENTS.resetToDefault();
        Settings.NO_RESUME_ON_FOREGROUND.resetToDefault();
        Settings.PAUSE_FIRST_VIDEO.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        // Both switches stand down under a hold, and the budget's state is static and shared
        // with every other suite in this sandbox, so without this the class passes or fails on
        // whichever suite happened to run before it. Not clear(): that arms the undo, and the
        // Playback page then says something different, which is another suite's business.
        org.robolectric.util.ReflectionHelpers.callStaticMethod(
                app.morphe.extension.tiktok.wellbeing.SessionBudget.class, "resetForTests");
        PausePlayback.resetForTests();
    }

    @After public void tearDown() {
        SettingsStatus.commentToolsEnabled = false;
        SettingsStatus.blockAuthorEnabled = false;
        PausePlayback.resetForTests();
        Settings.PAUSE_ON_COMMENTS.resetToDefault();
        Settings.NO_RESUME_ON_FOREGROUND.resetToDefault();
        Settings.PAUSE_FIRST_VIDEO.resetToDefault();
    }

    /**
     * A comment sheet in a window of its own, which is what a bottom sheet dialog is. Its root
     * view detaches when it is dismissed, and that is the signal this feature is built on.
     */
    @Test public void aCommentSheetInItsOwnWindowTakesTheSoundAndGivesItBackOnClose() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            var shadow = Shadows.shadowOf(
                    (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE));
            assertNull("something already held the focus", shadow.getLastAudioFocusRequest());

            Dialog sheet = openSheet(activity);
            View cell = cellIn(sheet);
            PausePlayback.onCommentCellBound(cell);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("the sheet did not quieten the feed", PausePlayback.quietenedForTests());
            assertNotNull("the focus was never asked for", shadow.getLastAudioFocusRequest());
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                    shadow.getLastAudioFocusRequest().durationHint);

            // Another cell in the same sheet is the list scrolling, not a second sheet.
            PausePlayback.onCommentCellBound(cell);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull("scrolling the comments handed the focus back",
                    shadow.getLastAbandonedAudioFocusListener());

            sheet.dismiss();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("the feed stayed quiet after the sheet closed",
                    PausePlayback.quietenedForTests());
            assertNotNull("the focus was never handed back",
                    shadow.getLastAbandonedAudioFocusListener());
        }
    }

    /**
     * And a sheet drawn into the activity itself, which shares the activity's root view. There
     * is no signal here that says when it closes, so this does nothing rather than taking the
     * sound and never giving it back. The failure worth having is the one that leaves the app
     * as it was.
     */
    @Test public void aSheetDrawnIntoTheActivityIsLeftAlone() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            var shadow = Shadows.shadowOf(
                    (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE));

            FrameLayout inActivitySheet = new FrameLayout(activity);
            View cell = new View(activity);
            inActivitySheet.addView(cell);
            root.addView(inActivitySheet);

            PausePlayback.onCommentCellBound(cell);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertNull("the walk up found a sheet where there is no window of its own",
                    PausePlayback.sheetWindowOf(cell));
            assertFalse(PausePlayback.quietenedForTests());
            assertNull("the sound was taken with nothing to give it back",
                    shadow.getLastAudioFocusRequest());
        }
    }

    /** A sheet closing underneath a second one does not hand the sound back. */
    @Test public void aClosingSheetLeavesTheSoundWithTheOneStillOpen() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);

            Dialog first = openSheet(activity);
            PausePlayback.onCommentCellBound(cellIn(first));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Dialog second = openSheet(activity);
            PausePlayback.onCommentCellBound(cellIn(second));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(PausePlayback.quietenedForTests());

            first.dismiss();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the sheet underneath handed back the sound the top one is holding",
                    PausePlayback.quietenedForTests());

            second.dismiss();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(PausePlayback.quietenedForTests());
        }
    }

    /**
     * The walk up waits a post, because a cell has no parent during its bind. A sheet that
     * closes in that gap is a sheet nothing will ever see detach again.
     */
    @Test public void aSheetThatClosesBeforeThePostRunsTakesNothing() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);

            Dialog sheet = openSheet(activity);
            View cell = cellIn(sheet);
            PausePlayback.onCommentCellBound(cell);
            sheet.dismiss();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertFalse("the sound was taken for a sheet that had already gone",
                    PausePlayback.quietenedForTests());
        }
    }

    /** Off, which is the default, the sheet is none of this feature's business. */
    @Test public void withTheSwitchOffACommentSheetChangesNothing() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            var shadow = Shadows.shadowOf(
                    (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE));

            PausePlayback.onCommentCellBound(cellIn(openSheet(activity)));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertFalse(PausePlayback.quietenedForTests());
            assertNull("the switch is off and the focus was taken anyway",
                    shadow.getLastAudioFocusRequest());
        }
    }

    /**
     * Coming back to the app holds the feed until one tap. The tap is taken and does nothing but
     * hand the feed back: the sound, and the video if the cover paused it.
     */
    /**
     * Back from one of TikTok's own screens, the feed resumes before that screen stops. Taking
     * that stop for leaving the app froze the hold's countdown and gated the feed behind a tap.
     */
    @Test public void aScreenOfTikToksOwnClosingIsNotLeavingTheApp() {
        try (var feedOwner = Robolectric.buildActivity(HostActivity.class).create();
             var other = Robolectric.buildActivity(Activity.class)) {
            PausePlayback.install(feedOwner.get());
            feedOwner.start().resume().visible();

            feedOwner.pause();
            other.create().start().resume();
            feedOwner.stop();
            assertFalse("opening a screen over the feed counted as leaving", PausePlayback.wasAwayForTests());

            other.pause();
            feedOwner.restart().resume();
            other.stop().destroy();
            assertFalse("closing it after the feed came back counted as leaving", PausePlayback.wasAwayForTests());

            feedOwner.pause().stop();
            assertTrue("leaving the app was not noticed", PausePlayback.wasAwayForTests());
        }
    }

    /**
     * The feed going behind another of TikTok's screens, a hashtag opened from the comments,
     * still lets go of what it held for the sheet, without counting as leaving the app.
     */
    @Test public void theFeedGoingBehindAnotherScreenLetsGoOfTheCommentsHold() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var feedOwner = Robolectric.buildActivity(HostActivity.class).create();
             var other = Robolectric.buildActivity(Activity.class)) {
            PausePlayback.install(feedOwner.get());
            feedOwner.start().resume().visible();
            Activity feed = feedOwner.get();
            Utils.setActivity(feed);
            Dialog sheet = openSheet(feed);
            PausePlayback.onCommentCellBound(cellIn(sheet));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(PausePlayback.quietenedForTests());

            feedOwner.pause();
            other.create().start().resume();
            feedOwner.stop();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("the comments' hold followed the reader to another screen",
                    PausePlayback.quietenedForTests());
            assertFalse("another screen counted as leaving the app", PausePlayback.wasAwayForTests());
            sheet.dismiss();
        }
    }

    @Test public void comingBackHoldsTheFeedUntilATap() {
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            int before = root.getChildCount();

            PausePlayback.setWasAwayForTests(true);
            PausePlayback.onForeground(activity);

            assertTrue("returning did not hold the feed", PausePlayback.quietenedForTests());
            View catcher = PausePlayback.catcherForTests();
            assertNotNull("nothing was waiting for the tap", catcher);
            assertTrue("the catcher does not take a tap", catcher.isClickable());

            catcher.performClick();
            assertFalse("the tap did not start the feed", PausePlayback.quietenedForTests());
            assertNull("the catcher stayed on the feed", PausePlayback.catcherForTests());
            assertEquals("the catcher was left behind", before, root.getChildCount());
        }
    }

    /**
     * The stop says what it is and what ends it, and it is the only thing on the page.
     *
     * <p>The catcher was a transparent view, so coming back to the app looked like a frozen,
     * silent feed with nothing to say why. And a screen reader could swipe straight through it
     * to the like and comment buttons underneath, the way it cannot through the hold panel.
     */
    @Test public void comingBackSaysWhatToDoAndCurtainsTheFeed() {
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            View feed = new View(activity);
            root.addView(feed);
            PausePlayback.setWasAwayForTests(true);
            PausePlayback.onForeground(activity);

            View catcher = PausePlayback.catcherForTests();
            assertNotNull(catcher);
            assertTrue("the catcher has nothing drawn in it", catcher instanceof ViewGroup);
            android.widget.TextView hint = null;
            for (int i = 0; i < ((ViewGroup) catcher).getChildCount(); i++) {
                View child = ((ViewGroup) catcher).getChildAt(i);
                if (child instanceof android.widget.TextView) hint = (android.widget.TextView) child;
            }
            assertNotNull("the catcher shows no label", hint);
            assertEquals("Tap to start the feed", hint.getText().toString());
            assertEquals(View.VISIBLE, hint.getVisibility());
            assertEquals("the label is a second stop for a screen reader",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO, hint.getImportantForAccessibility());
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                assertEquals("the stop is not announced when it arrives",
                        "Tap to start the feed", String.valueOf(catcher.getAccessibilityPaneTitle()));
            }
            assertEquals("a screen reader can still reach the feed under the catcher",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    feed.getImportantForAccessibility());

            catcher.performClick();
            assertNull(PausePlayback.catcherForTests());
            assertEquals("the feed was left hidden from a screen reader after the tap",
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, feed.getImportantForAccessibility());
        }
    }

    /**
     * Leaving the feed takes the catcher with it and hands the sound back.
     *
     * <p>The tab bar is kept clear so Profile and Inbox are one tap away, but the page that
     * opens is drawn under the same content root, and the catcher stayed over it: the first
     * tap there was eaten and the sound was held for a feed nobody was looking at.
     */
    @Test public void leavingTheFeedTakesTheCatcherAndHandsBackTheSound() {
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            View homeTab = new View(activity);
            homeTab.setId(0x7f0a4b89);
            homeTab.setSelected(true);
            root.addView(homeTab, new FrameLayout.LayoutParams(60, 40));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FeedVisibility.resolveForTests(activity.getPackageName(), "47.4.3:onh", homeTab.getId());
            try {
                PausePlayback.setWasAwayForTests(true);
                PausePlayback.onForeground(activity);
                assertNotNull(PausePlayback.catcherForTests());
                assertTrue(PausePlayback.quietenedForTests());

                // A layout pass on the feed itself changes nothing.
                root.getViewTreeObserver().dispatchOnGlobalLayout();
                assertNotNull("a layout on the feed took the catcher down",
                        PausePlayback.catcherForTests());

                // The reader taps Profile: the Home tab is no longer the selected one.
                homeTab.setSelected(false);
                root.getViewTreeObserver().dispatchOnGlobalLayout();
                assertNull("the catcher stayed up over a page that is not the feed",
                        PausePlayback.catcherForTests());
                assertFalse("the sound was held for a feed nobody is looking at",
                        PausePlayback.quietenedForTests());
            } finally {
                FeedVisibility.resolveForTests(activity.getPackageName(), "47.4.3:onh", 0);
            }
        }
    }

    /**
     * Two curtains over one root: the later one lifting must not hand the feed back while the
     * earlier one is still up. The hold panel and the tap catcher share the curtain.
     */
    @Test public void aLaterCurtainLiftingLeavesTheEarlierOnesCover() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            View feed = new View(activity);
            View catcher = new View(activity);
            View panel = new View(activity);
            root.addView(feed);
            root.addView(catcher);
            root.addView(panel);

            app.morphe.extension.tiktok.wellbeing.SessionLockOverlay.hideBehind(root, catcher, true);
            app.morphe.extension.tiktok.wellbeing.SessionLockOverlay.hideBehind(root, panel, true);
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    catcher.getImportantForAccessibility());

            app.morphe.extension.tiktok.wellbeing.SessionLockOverlay.hideBehind(root, panel, false);
            assertEquals("the hold lifting handed the feed back under the catcher",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    feed.getImportantForAccessibility());
            assertEquals("the catcher itself was left curtained",
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, catcher.getImportantForAccessibility());

            app.morphe.extension.tiktok.wellbeing.SessionLockOverlay.hideBehind(root, catcher, false);
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, feed.getImportantForAccessibility());
        }
    }

    /** A resume that follows no stop is a dialog closing, not the reader coming back. */
    @Test public void aResumeWithoutHavingLeftIsNotAReturn() {
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);

            PausePlayback.onForeground(activity);

            assertFalse("a resume that never left held the feed",
                    PausePlayback.quietenedForTests());
            assertNull(PausePlayback.catcherForTests());
        }
    }

    /**
     * And the app going away hands everything back, whatever state it was in. A sheet dismissed
     * while nobody was looking, or a catcher on an activity that is being torn down, would
     * otherwise leave the feed quiet for the life of the process.
     */
    @Test public void theAppGoingAwayHandsBackTheSoundAndTheCatcher() {
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            PausePlayback.setWasAwayForTests(true);
            PausePlayback.onForeground(activity);
            assertTrue(PausePlayback.quietenedForTests());
            assertNotNull(PausePlayback.catcherForTests());

            PausePlayback.onBackground();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertFalse("the feed stayed quiet after the app went away",
                    PausePlayback.quietenedForTests());
            assertNull("the catcher outlived the screen it was on",
                    PausePlayback.catcherForTests());
        }
    }

    /** And with the switch off, returning is left exactly as it was. */
    @Test public void withTheSwitchOffReturningChangesNothing() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            PausePlayback.setWasAwayForTests(true);
            PausePlayback.onForeground(activity);
            assertFalse(PausePlayback.quietenedForTests());
            assertNull(PausePlayback.catcherForTests());
        }
    }

    /**
     * Comment tools, App lock and Picture-in-picture install this class too. Their bundles can
     * leave Block author out, and with it the Screen time page, so a return hold saved on under a
     * fuller bundle would gate the feed with no row left to turn it off.
     */
    @Test public void aSavedReturnHoldDoesNothingInABundleWithoutScreenTime() {
        SettingsStatus.blockAuthorEnabled = false;
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            PausePlayback.setWasAwayForTests(true);
            PausePlayback.onForeground(activity);
            assertFalse("a switch with no row held the feed", PausePlayback.quietenedForTests());
            assertNull("a switch with no row put the catcher up", PausePlayback.catcherForTests());
        }
    }

    /**
     * The tab bar keeps its own tap. A reader who comes back to open their messages should not
     * have to spend a tap on the feed first, which is the same courtesy the session hold owes.
     */
    @Test public void theCatcherLeavesTheTabBarAlone() {
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            PausePlayback.setWasAwayForTests(true);
            PausePlayback.onForeground(activity);

            View catcher = PausePlayback.catcherForTests();
            assertNotNull(catcher);
            var params = (FrameLayout.LayoutParams) catcher.getLayoutParams();
            assertEquals("the catcher does not ask the overlay where the tabs are",
                    app.morphe.extension.tiktok.wellbeing.SessionLockOverlay
                            .navigationHeight(activity, root),
                    params.bottomMargin);
        }
    }

    /** What a launcher sends for a tap on the icon. */
    private static Intent launcher() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    }

    /**
     * Opened from its icon, the feed's first video waits for one tap (#83), and only the first:
     * coming back from another of TikTok's own screens later in the same run isn't a start.
     */
    @Test public void aStartFromTheLauncherHoldsTheFirstVideoUntilATapOnce() {
        Settings.PAUSE_FIRST_VIDEO.save(true);
        try (var feedOwner = Robolectric.buildActivity(HostActivity.class, launcher()).create();
             var other = Robolectric.buildActivity(Activity.class)) {
            Utils.setActivity(feedOwner.get());
            PausePlayback.install(feedOwner.get());
            feedOwner.start().resume().visible();

            assertTrue("the first video played", PausePlayback.quietenedForTests());
            View catcher = PausePlayback.catcherForTests();
            assertNotNull("nothing was waiting for the tap", catcher);
            catcher.performClick();
            assertFalse("the tap did not start the feed", PausePlayback.quietenedForTests());
            assertNull(PausePlayback.catcherForTests());

            feedOwner.pause();
            other.create().start().resume();
            feedOwner.stop();
            other.pause();
            feedOwner.restart().resume();
            other.stop().destroy();
            assertNull("a later return to the feed was held as a start", PausePlayback.catcherForTests());
            assertFalse(PausePlayback.quietenedForTests());
        }
    }

    /** A link, a notification or a shortcut starts TikTok to open something, and that plays. */
    @Test public void aStartThatCameToOpenSomethingIsNotHeld() {
        Settings.PAUSE_FIRST_VIDEO.save(true);
        Intent link = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.tiktok.com/@creator_name/video/7312345678901234567"));
        try (var feedOwner = Robolectric.buildActivity(HostActivity.class, link).create()) {
            Utils.setActivity(feedOwner.get());
            PausePlayback.install(feedOwner.get());
            feedOwner.start().resume().visible();

            assertFalse("a link's video was held", PausePlayback.quietenedForTests());
            assertNull(PausePlayback.catcherForTests());
        }

        assertTrue(PausePlayback.startedFromTheLauncher(launcher()));
        assertFalse("a shortcut with a page to open",
                PausePlayback.startedFromTheLauncher(launcher().setData(Uri.parse("snssdk1233://search"))));
        assertFalse("a notification's own action", PausePlayback.startedFromTheLauncher(
                new Intent("com.ss.android.ugc.aweme.push").addCategory(Intent.CATEGORY_DEFAULT)));
        assertFalse("ACTION_MAIN alone, without the launcher's category",
                PausePlayback.startedFromTheLauncher(new Intent(Intent.ACTION_MAIN)));
        assertFalse(PausePlayback.startedFromTheLauncher(null));
        assertFalse("a notification sent as the launcher's intent, marked by TikTok's push extra",
                PausePlayback.startedFromTheLauncher(launcher().putExtra("from_notification", true)));
        assertFalse("a notification sent as the launcher's intent with a tab to open",
                PausePlayback.startedFromTheLauncher(launcher().putExtra(
                        "com.ss.android.ugc.aweme.intent.extra.EXTRA_AWEME_PUSH_TAB", "inbox")));
        assertFalse("a reopen from Recents after Android closed TikTok",
                PausePlayback.startedFromTheLauncher(launcher().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)));
    }

    /**
     * Android closed TikTok in the background and builds its screen again for a reopen from
     * Recents: the intent is the launcher's from the first start, without the history flag, and
     * the saved state is what says the reader was already watching.
     */
    @Test public void aScreenBuiltAgainFromSavedStateIsNotHeld() {
        Settings.PAUSE_FIRST_VIDEO.save(true);
        try (var feedOwner = Robolectric.buildActivity(HostActivity.class, launcher()).create(new Bundle())) {
            Utils.setActivity(feedOwner.get());
            PausePlayback.install(feedOwner.get(), new Bundle());
            feedOwner.start().resume().visible();

            assertFalse("a rebuilt screen was held as a start", PausePlayback.quietenedForTests());
            assertNull(PausePlayback.catcherForTests());
        }
    }

    @Test public void withTheFirstVideoSwitchOffAStartFromTheLauncherPlays() {
        try (var feedOwner = Robolectric.buildActivity(HostActivity.class, launcher()).create()) {
            Utils.setActivity(feedOwner.get());
            PausePlayback.install(feedOwner.get());
            feedOwner.start().resume().visible();

            assertFalse(PausePlayback.quietenedForTests());
            assertNull(PausePlayback.catcherForTests());
        }
    }

    /**
     * On a cold start the catcher goes up before TikTok has drawn its tab bar, so the room it
     * leaves for the tabs is measured again as they arrive. Measured once, it covered them.
     */
    @Test public void theCatcherMakesRoomForATabBarDrawnAfterIt() {
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            PausePlayback.setWasAwayForTests(true);
            PausePlayback.onForeground(activity);
            View catcher = PausePlayback.catcherForTests();
            assertNotNull(catcher);
            assertEquals("no tab bar yet, so nothing to keep clear", 0,
                    ((FrameLayout.LayoutParams) catcher.getLayoutParams()).bottomMargin);

            android.widget.LinearLayout bar = new android.widget.LinearLayout(activity);
            View home = new View(activity);
            home.setId(0x7f0a4b89);
            home.setSelected(true);
            bar.addView(home, new android.widget.LinearLayout.LayoutParams(80, 60));
            root.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 60, android.view.Gravity.BOTTOM));
            FeedVisibility.resolveForTests(activity.getPackageName(), "47.4.3:onh", home.getId());
            try {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                root.getViewTreeObserver().dispatchOnGlobalLayout();

                int tabs = app.morphe.extension.tiktok.wellbeing.SessionLockOverlay
                        .navigationHeight(activity, root);
                assertTrue("the fixture has no tab row to keep clear: " + tabs, tabs > 0);
                assertNotNull("the catcher left the feed", PausePlayback.catcherForTests());
                assertEquals("the catcher covers the tabs that arrived after it", tabs,
                        ((FrameLayout.LayoutParams) catcher.getLayoutParams()).bottomMargin);
            } finally {
                FeedVisibility.resolveForTests(activity.getPackageName(), "47.4.3:onh", 0);
            }
        }
    }

    /**
     * A sheet that was open when the reader switched apps is open when they come back. Nothing
     * binds a cell in a list that is already full, so handing the sound back on the way out and
     * never taking it again left the feed playing behind the comments for the rest of that
     * sheet's life.
     */
    @Test public void aSheetStillOpenOnTheWayBackTakesTheSoundAgain() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);

            Dialog sheet = openSheet(activity);
            PausePlayback.onCommentCellBound(cellIn(sheet));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(PausePlayback.quietenedForTests());

            PausePlayback.onBackground();
            assertFalse("the app went away still holding the sound",
                    PausePlayback.quietenedForTests());

            PausePlayback.onForeground(activity);
            assertTrue("the feed played on behind a sheet that was still open",
                    PausePlayback.quietenedForTests());

            sheet.dismiss();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(PausePlayback.quietenedForTests());
        }
    }

    /**
     * A sheet in a screen that is not the one the extension hooked. Only the main activity is
     * hooked, so asking the extension which activity is current compared this sheet against the
     * wrong window and answered with the other screen's own root: a view that detaches when the
     * whole screen goes, not when the comments close.
     */
    @Test public void aSheetInAnotherScreenIsMeasuredAgainstThatScreen() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var hooked = Robolectric.buildActivity(HostActivity.class).setup().visible();
             var other = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(hooked.get());
            Activity second = other.get();
            ViewGroup root = second.findViewById(android.R.id.content);
            FrameLayout inActivitySheet = new FrameLayout(second);
            View cell = new View(second);
            inActivitySheet.addView(cell);
            root.addView(inActivitySheet);

            assertNull("a panel drawn into another screen was taken for a window of its own",
                    PausePlayback.sheetWindowOf(cell));
        }
    }

    /**
     * And coming back onto some other screen does not. These callbacks are registered for every
     * activity in the process, and the settings are their own activity, so a sheet left open on
     * the feed would otherwise take the sound off whatever was playing there.
     */
    @Test public void comingBackOntoAnotherScreenLeavesTheSoundAlone() {
        Settings.PAUSE_ON_COMMENTS.save(true);
        try (var feed = Robolectric.buildActivity(HostActivity.class).setup().visible();
             var elsewhere = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(feed.get());
            Dialog sheet = openSheet(feed.get());
            PausePlayback.onCommentCellBound(cellIn(sheet));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(PausePlayback.quietenedForTests());

            PausePlayback.onBackground();
            assertFalse(PausePlayback.quietenedForTests());

            PausePlayback.onForeground(elsewhere.get());
            assertFalse("the sound was taken for a sheet on a screen nobody is looking at",
                    PausePlayback.quietenedForTests());

            PausePlayback.onForeground(feed.get());
            assertTrue("the screen the sheet is on did not take it back",
                    PausePlayback.quietenedForTests());
        }
    }

    // ------------------------------------------------------------------------------- fixture

    /** A comment sheet the way TikTok's is: a window of its own over the feed. */
    private static Dialog openSheet(Activity activity) {
        Dialog sheet = new Dialog(activity);
        FrameLayout content = new FrameLayout(activity);
        content.addView(new View(activity));
        sheet.setContentView(content);
        sheet.show();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return sheet;
    }

    private static View cellIn(Dialog sheet) {
        ViewGroup content = sheet.findViewById(android.R.id.content);
        assertNotNull("the sheet has no content", content);
        View cell = firstLeaf(content);
        assertNotNull("the sheet has no cell in it", cell);
        return cell;
    }

    private static View firstLeaf(View view) {
        if (!(view instanceof ViewGroup)) return view;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View found = firstLeaf(group.getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }
}

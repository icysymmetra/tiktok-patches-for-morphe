package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.atomic.AtomicInteger;

/** A long press on the Home tab opens Hushfeed's settings, and nothing else about the tab moves (#45). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HomeTabSettingsShortcutTest {
    private static final int HOME_ID = 0x7f0a4b89;

    private ActivityController<Activity> owner;
    private Activity activity;
    private View home;
    private final AtomicInteger taps = new AtomicInteger();

    @Before public void setUp() {
        HomeTabSettingsShortcut.resetForTests();
        ReflectionHelpers.setStaticField(SettingsStatus.class, "feedNavigationEnabled", true);
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = owner.get();
        Utils.setContext(activity);
        PausedProcess.set(false);
        home = addHomeTab(activity);
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.4.3:onh", HOME_ID);
    }

    private View addHomeTab(Activity into) {
        FrameLayout content = new FrameLayout(into);
        FrameLayout bar = new FrameLayout(into);
        View tab = new View(into);
        tab.setId(HOME_ID);
        tab.setOnClickListener(view -> taps.incrementAndGet());
        bar.addView(tab, new FrameLayout.LayoutParams(216, 138));
        content.addView(bar, new FrameLayout.LayoutParams(-1, 138, Gravity.BOTTOM));
        into.setContentView(content);
        return tab;
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        HomeTabSettingsShortcut.resetForTests();
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.4.3:onh", 0);
        ReflectionHelpers.setStaticField(SettingsStatus.class, "feedNavigationEnabled", false);
        Settings.HOME_TAB_OPENS_SETTINGS.resetToDefault();
        owner.pause().stop().destroy();
    }

    @Test public void aLongPressOnHomeOpensHushfeedSettings() {
        installAndResume();

        assertTrue("the long press was not handled", home.performLongClick());
        Intent opened = nextStarted();
        assertNotNull("nothing was opened", opened);
        assertEquals("morphe_settings", opened.getAction());
        assertEquals("it opened settings somewhere other than their home page", null,
                opened.getStringExtra("morphe_settings_section"));
        assertEquals("a long press also counted as a tap", 0, taps.get());
        assertTrue("a tap on Home stopped reaching TikTok", home.performClick());
        assertEquals(1, taps.get());
        assertNull("a tap opened the settings", nextStarted());
    }

    @Test public void switchedOffTheTabIsLeftAsItWasAndOnAgainItComesBack() {
        Settings.HOME_TAB_OPENS_SETTINGS.save(false);
        installAndResume();
        assertFalse(home.isLongClickable());

        Settings.HOME_TAB_OPENS_SETTINGS.save(true);
        layoutPass();
        assertTrue(home.performLongClick());
        assertNotNull(nextStarted());

        Settings.HOME_TAB_OPENS_SETTINGS.save(false);
        layoutPass();
        assertFalse("switched off, the tab kept a long press", home.isLongClickable());
        home.performLongClick();
        assertNull(nextStarted());
    }

    @Test public void pausedHomeReopensSettingsAfterReturningToTheFeed() {
        installAndResume();
        assertTrue(home.performLongClick());
        assertNotNull(nextStarted());

        PausedProcess.set(true);
        owner.pause().stop().start().resume();
        layoutPass();

        assertTrue("pausing removed the guest's recovery entry", home.performLongClick());
        assertNotNull(nextStarted());
        assertTrue(home.performClick());
        assertEquals(1, taps.get());
        assertNull("an ordinary tap opened settings", nextStarted());
    }

    @Test public void aPausedColdStartKeepsTheSavedShortcutChoice() {
        PausedProcess.set(true);
        installAndResume();
        assertTrue("a paused launch lost the guest's recovery entry", home.performLongClick());
        assertNotNull(nextStarted());

        Settings.HOME_TAB_OPENS_SETTINGS.save(false);
        layoutPass();
        assertFalse("an explicitly disabled shortcut remained attached", home.isLongClickable());
        assertFalse(home.performLongClick());
        assertNull(nextStarted());

        Settings.HOME_TAB_OPENS_SETTINGS.save(true);
        layoutPass();
        assertTrue(home.performLongClick());
        assertNotNull(nextStarted());
    }

    @Test public void aPausedRebuiltTabKeepsTheRecoveryEntry() {
        installAndResume();
        PausedProcess.set(true);
        FrameLayout bar = (FrameLayout) home.getParent();
        View rebuilt = new View(activity);
        rebuilt.setId(HOME_ID);
        rebuilt.setOnClickListener(view -> taps.incrementAndGet());
        bar.removeView(home);
        bar.addView(rebuilt, new FrameLayout.LayoutParams(216, 138));
        layoutPass();

        assertTrue(rebuilt.performLongClick());
        assertNotNull(nextStarted());
        assertFalse("the old tab kept the long press", home.isLongClickable());
        assertTrue(rebuilt.performClick());
        assertEquals(1, taps.get());
    }

    @Test public void aHomeTabWithALongPressOfItsOwnKeepsIt() {
        AtomicInteger own = new AtomicInteger();
        home.setOnLongClickListener(view -> {
            own.incrementAndGet();
            return true;
        });
        installAndResume();

        assertTrue(home.performLongClick());
        assertEquals("TikTok's own long press was replaced", 1, own.get());
        assertNull(nextStarted());

        PausedProcess.set(true);
        layoutPass();
        assertTrue(home.performLongClick());
        assertEquals("pausing replaced TikTok's own long press", 2, own.get());
        assertNull(nextStarted());
    }

    /** A tab view whose parent takes the tap would swallow that tap once it is long-clickable. */
    @Test public void aHomeViewThatDoesNotTakeTheTapIsLeftAlone() {
        home.setOnClickListener(null);
        home.setClickable(false);
        FrameLayout bar = (FrameLayout) home.getParent();
        bar.setOnClickListener(view -> taps.incrementAndGet());
        installAndResume();

        assertFalse(home.isLongClickable());
        assertFalse(home.performLongClick());
        assertNull(nextStarted());
    }

    @Test public void withoutFeedTabNavigationNothingIsAttached() {
        ReflectionHelpers.setStaticField(SettingsStatus.class, "feedNavigationEnabled", false);
        installAndResume();

        assertFalse(home.isLongClickable());
    }

    @Test public void aRebuiltTabGetsTheLongPressAndTheOldOneLetsItGo() {
        installAndResume();
        FrameLayout bar = (FrameLayout) home.getParent();
        View rebuilt = new View(activity);
        rebuilt.setId(HOME_ID);
        // A rebuilt tab takes its taps like the one it replaces.
        rebuilt.setOnClickListener(view -> taps.incrementAndGet());
        bar.removeView(home);
        bar.addView(rebuilt, new FrameLayout.LayoutParams(216, 138));
        layoutPass();

        assertTrue(rebuilt.performLongClick());
        assertNotNull(nextStarted());
        assertFalse("the old tab kept the long press", home.isLongClickable());
    }

    /**
     * TikTok's launcher entry is a second MainActivity under the feed's. Changing TikTok's
     * appearance recreates both, the buried one last, and the shortcut followed the newest one, so
     * Home lost its long press until TikTok restarted (S22, 47.1.4, Dark then Light).
     */
    @Test public void aThemeChangeLeavesTheLongPressOnTheFeedInFront() {
        installAndResume();
        // The feed's copy goes behind the settings page that changed the theme.
        owner.pause().stop();
        ActivityController<Activity> launcher = Robolectric.buildActivity(Activity.class).create();
        try {
            View buried = addHomeTab(launcher.get());
            HomeTabSettingsShortcut.install(launcher.get());
            // Recreated last, the launcher's copy is resumed for a moment and stopped again.
            launcher.start().resume().visible().pause().stop();

            owner.restart().start().resume();
            layoutPass();

            assertTrue("Home lost its long press after a theme change", home.performLongClick());
            assertNotNull(nextStarted());
            assertFalse("the buried copy kept the long press", buried.isLongClickable());
        } finally {
            launcher.destroy();
        }
    }

    /** The buried copy can also blink on while the feed stays resumed and gets no new resume. */
    @Test public void aBuriedMainActivityResumedForAMomentHandsTheLongPressBack() {
        installAndResume();
        ActivityController<Activity> launcher = Robolectric.buildActivity(Activity.class).create();
        try {
            View buried = addHomeTab(launcher.get());
            HomeTabSettingsShortcut.install(launcher.get());
            launcher.start().resume().visible().pause().stop();
            layoutPass();

            assertTrue("the feed in front lost its long press", home.performLongClick());
            assertNotNull(nextStarted());
            assertFalse(buried.isLongClickable());
        } finally {
            launcher.destroy();
        }
    }

    private void installAndResume() {
        HomeTabSettingsShortcut.install(activity);
        owner.pause().resume();
    }

    private void layoutPass() {
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
    }

    private static Intent nextStarted() {
        return Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
    }
}

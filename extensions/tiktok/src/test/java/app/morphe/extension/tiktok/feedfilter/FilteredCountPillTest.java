/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * Show how many were filtered, on a plain activity standing in for TikTok's main one, with a
 * selected Home tab seeded the way the budget cue's tests seed it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FilteredCountPillTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private boolean feedFilterWas;
    private ActivityController<Activity> controller;
    private View home;

    @Before
    public void setUp() {
        feedFilterWas = SettingsStatus.feedFilterEnabled;
        SettingsStatus.feedFilterEnabled = true;
        Settings.FILTERED_COUNT_PILL.save(false);
        FeedFilterCounters.resetSessionForTests();
        controller = Robolectric.buildActivity(Activity.class).create();
        home = standOnTheFeed(controller.get());
        FilteredCountPill.install(controller.get());
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        Settings.FILTERED_COUNT_PILL.resetToDefault();
        SettingsStatus.feedFilterEnabled = feedFilterWas;
        FeedFilterCounters.resetSessionForTests();
    }

    /** A shown, selected Home tab, as the lookup would find it in TikTok. */
    private static View standOnTheFeed(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        View home = new View(activity);
        root.addView(home, new FrameLayout.LayoutParams(96, 100, Gravity.BOTTOM));
        home.setSelected(true);
        ReflectionHelpers.setStaticField(FeedVisibility.class, "homeTabReference", new WeakReference<>(home));
        return home;
    }

    /** One layout pass of the window, which is what moves the label on a phone. */
    private void layout() {
        controller.get().getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
    }

    @Test
    public void theLabelCountsWhatTheFilterTookOutOnlyWithTheSwitchOn() {
        FeedFilterCounters.removedItems("FeedItemList", 3, "AdsFilter");
        controller.start().resume().visible();
        assertNull("the switch is off", FilteredCountPill.pillForTests());

        Settings.FILTERED_COUNT_PILL.save(true);
        controller.pause().resume();
        TextView pill = FilteredCountPill.pillForTests();
        assertNotNull(pill);
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals("3 filtered out", pill.getText().toString());
        assertFalse("a tap goes through to the video", pill.isClickable());

        FeedFilterCounters.removedItems("SearchAds", 1, "searchAd");
        layout();
        assertEquals("4 filtered out", pill.getText().toString());

        Settings.FILTERED_COUNT_PILL.save(false);
        layout();
        assertEquals(View.GONE, pill.getVisibility());
        controller.pause().resume();
        assertNull("off takes it off the feed at the next resume", FilteredCountPill.pillForTests());
        assertNull(pill.getParent());
    }

    @Test
    public void nothingShowsUntilTheFilterTakesSomethingOut() {
        Settings.FILTERED_COUNT_PILL.save(true);
        controller.start().resume().visible();
        TextView pill = FilteredCountPill.pillForTests();
        assertNotNull(pill);
        assertEquals(View.GONE, pill.getVisibility());

        FeedFilterCounters.removedItems("FeedItemList", 1, "AdsFilter");
        layout();
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals("1 filtered out", pill.getText().toString());
    }

    @Test
    public void offTheHomeFeedTheLabelHides() {
        Settings.FILTERED_COUNT_PILL.save(true);
        FeedFilterCounters.removedItems("FeedItemList", 2, "AdsFilter");
        controller.start().resume().visible();
        TextView pill = FilteredCountPill.pillForTests();
        assertNotNull(pill);
        // The resume ran before the window was up, when no tab could be on screen.
        layout();
        assertEquals(View.VISIBLE, pill.getVisibility());

        // Another tab, or a page opened over the feed.
        home.setSelected(false);
        layout();
        assertEquals(View.GONE, pill.getVisibility());
        home.setSelected(true);
        layout();
        assertEquals(View.VISIBLE, pill.getVisibility());

        // A Home tab that can't be found isn't taken for the feed.
        ((ViewGroup) home.getParent()).removeView(home);
        layout();
        assertEquals(View.GONE, pill.getVisibility());
    }

    @Test
    public void withoutTheFeedFilterInTheBundleNothingIsDrawn() {
        SettingsStatus.feedFilterEnabled = false;
        Settings.FILTERED_COUNT_PILL.save(true);
        FeedFilterCounters.removedItems("FeedItemList", 2, "AdsFilter");
        controller.start().resume().visible();
        assertNull(FilteredCountPill.pillForTests());
    }

    @Test
    public void closingTheActivityTakesTheLabelWithIt() {
        Settings.FILTERED_COUNT_PILL.save(true);
        FeedFilterCounters.removedItems("FeedItemList", 2, "AdsFilter");
        controller.start().resume().visible();
        TextView pill = FilteredCountPill.pillForTests();
        assertNotNull(pill);
        controller.pause().stop().destroy();
        assertNull(FilteredCountPill.pillForTests());
        assertNull(pill.getParent());
        controller = Robolectric.buildActivity(Activity.class).create().start().resume();
    }

    /**
     * TikTok's launcher entry is a second MainActivity under the feed's. Changing TikTok's
     * appearance recreates both, the buried one last, and the label went with the newest one and
     * stayed there, so the feed showed no count until TikTok restarted. The Home long press had
     * the same fault (S22, 47.1.4, Dark then Light).
     */
    @Test
    public void aThemeChangeLeavesTheLabelOnTheFeedInFront() {
        Settings.FILTERED_COUNT_PILL.save(true);
        FeedFilterCounters.removedItems("FeedItemList", 2, "AdsFilter");
        controller.start().resume().visible();
        // The feed's copy goes behind the settings page that changed the theme.
        controller.pause().stop();
        ActivityController<Activity> launcher = Robolectric.buildActivity(Activity.class).create();
        try {
            FilteredCountPill.install(launcher.get());
            // Recreated last, the launcher's copy is resumed for a moment and stopped again.
            launcher.start().resume().pause().stop();

            controller.restart().start().resume().visible();
            layout();

            TextView pill = FilteredCountPill.pillForTests();
            assertNotNull(pill);
            assertSame("the label stayed on the buried copy",
                    controller.get().findViewById(android.R.id.content), pill.getParent());
            assertEquals(View.VISIBLE, pill.getVisibility());
            assertEquals("2 filtered out", pill.getText().toString());
        } finally {
            launcher.destroy();
        }
    }

    /** The buried copy can also blink on while the feed stays resumed and gets no new resume. */
    @Test
    public void aBuriedMainActivityResumedForAMomentHandsTheLabelBack() {
        Settings.FILTERED_COUNT_PILL.save(true);
        FeedFilterCounters.removedItems("FeedItemList", 2, "AdsFilter");
        controller.start().resume().visible();
        ActivityController<Activity> launcher = Robolectric.buildActivity(Activity.class).create();
        try {
            FilteredCountPill.install(launcher.get());
            launcher.start().resume().pause().stop();
            layout();

            TextView pill = FilteredCountPill.pillForTests();
            assertNotNull(pill);
            assertSame("the feed in front lost its label",
                    controller.get().findViewById(android.R.id.content), pill.getParent());
            assertEquals(View.VISIBLE, pill.getVisibility());
        } finally {
            launcher.destroy();
        }
    }
}

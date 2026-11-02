package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;

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
import org.robolectric.shadows.ShadowLooper;

/**
 * A long press on For You at the top of the feed opens Hushfeed's settings. The settings patch hands
 * every top tab TikTok binds to {@link ForYouTabSettingsShortcut#onTopTab} with its tag. These build
 * the tab the way TikTok 47.1.4 does: a clickable LinearLayout that takes its tap in performClick,
 * with no listener of its own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ForYouTabSettingsShortcutTest {
    /** The tags TikTok's top tab models answer for For You and Following. */
    private static final String FOR_YOU = "homepage_hot";
    private static final String FOLLOWING = "homepage_follow";

    private ActivityController<Activity> owner;
    private Activity activity;

    /** TikTok's top tab: its tap is its own performClick (switch to the tab, or reload For You). */
    static final class TopTab extends LinearLayout {
        int taps;

        TopTab(Context context) {
            super(context);
            setClickable(true);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            taps++;
            return true;
        }
    }

    @Before public void setUp() {
        ForYouTabSettingsShortcut.resetForTests();
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = owner.get();
        Utils.setContext(activity);
        PausedProcess.set(false);
        nextStarted();
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        ForYouTabSettingsShortcut.resetForTests();
        owner.pause().stop().destroy();
    }

    private TopTab tab() {
        TopTab tab = new TopTab(activity);
        FrameLayout strip = new FrameLayout(activity);
        strip.addView(tab, new FrameLayout.LayoutParams(300, 120));
        activity.setContentView(strip);
        ShadowLooper.idleMainLooper();
        assertTrue("the tab isn't on screen", tab.isAttachedToWindow() && tab.getWidth() == 300);
        return tab;
    }

    private static MotionEvent event(int action, long downTime, float x, float y) {
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
    }

    /** A finger held on the tab for {@code heldMs}, then lifted. */
    private static void press(View tab, long heldMs) {
        long down = SystemClock.uptimeMillis();
        tab.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, 150, 60));
        ShadowLooper.idleMainLooper(heldMs, TimeUnit.MILLISECONDS);
        tab.dispatchTouchEvent(event(MotionEvent.ACTION_UP, down, 150, 60));
        ShadowLooper.idleMainLooper();
    }

    private static long longPress() {
        return ViewConfiguration.getLongPressTimeout() + 100;
    }

    private static Intent nextStarted() {
        return Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
    }

    private static void assertOpenedSettings() {
        Intent opened = nextStarted();
        assertNotNull("a long press on For You didn't open Metra settings", opened);
        assertEquals("morphe_settings", opened.getAction());
        assertNull("it opened settings somewhere other than their home page",
                opened.getStringExtra("morphe_settings_section"));
    }

    @Test public void aLongPressOnForYouOpensHushfeedSettings() {
        TopTab tab = tab();
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);

        press(tab, longPress());

        assertOpenedSettings();
        assertEquals("the long press gave no long-press vibration",
                HapticFeedbackConstants.LONG_PRESS, Shadows.shadowOf(tab).lastHapticFeedbackPerformed());
        assertEquals("letting go after the long press was also a tap that reloads the feed", 0, tab.taps);
    }

    @Test public void aTapOnForYouIsStillTikToks() {
        TopTab tab = tab();
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);

        press(tab, 80);

        assertEquals("the tap didn't reach TikTok's tab", 1, tab.taps);
        assertNull("a tap opened the settings", nextStarted());
    }

    @Test public void otherTopTabsAreLeftAsTheyWere() {
        TopTab tab = tab();
        ForYouTabSettingsShortcut.onTopTab(tab, FOLLOWING);

        press(tab, longPress());

        assertNull("a long press on Following opened the settings", nextStarted());
        assertEquals("a held Following stopped acting as TikTok's tap", 1, tab.taps);
    }

    /** TikTok reuses its top tab views, and binds the same one again as tabs come and go. */
    @Test public void aTabBoundAgainKeepsWorkingAndFollowsTheTabItShows() {
        TopTab tab = tab();
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);
        press(tab, longPress());
        assertOpenedSettings();

        ForYouTabSettingsShortcut.onTopTab(tab, FOLLOWING);
        press(tab, longPress());
        assertNull("the view showing Following still opened the settings", nextStarted());
        assertEquals(1, tab.taps);

        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);
        press(tab, longPress());
        assertOpenedSettings();
        assertEquals("the long press was also a tap", 1, tab.taps);
    }

    @Test public void aViewFirstBoundAsAnotherTabOpensTheSettingsOnceItShowsForYou() {
        TopTab tab = tab();
        ForYouTabSettingsShortcut.onTopTab(tab, FOLLOWING);
        ForYouTabSettingsShortcut.onTopTab(tab, "For You");

        press(tab, longPress());

        assertOpenedSettings();
        assertEquals(0, tab.taps);
    }

    @Test public void aLongPressTikTokGaveTheTabStaysTikToks() {
        TopTab tab = tab();
        AtomicInteger own = new AtomicInteger();
        tab.setOnLongClickListener(view -> {
            own.incrementAndGet();
            return true;
        });
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);

        press(tab, longPress());

        assertEquals("TikTok's own long press didn't run", 1, own.get());
        assertNull("the long press was taken from TikTok", nextStarted());
        assertNull("TalkBack was offered a second long press beside TikTok's",
                openAction(tab.createAccessibilityNodeInfo()));
    }

    /**
     * TalkBack can't time a press, so For You names the long press as an action TalkBack lists.
     * The tab keeps its click, the action doesn't tap the tab, and a view that shows another tab
     * now neither offers nor runs it.
     */
    @Test public void talkBackGetsANamedActionThatOpensTheSettings() {
        TopTab tab = tab();
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);

        AccessibilityNodeInfo info = tab.createAccessibilityNodeInfo();
        AccessibilityNodeInfo.AccessibilityAction action = openAction(info);
        assertNotNull("For You offers TalkBack no way to the settings", action);
        assertTrue("TikTok's tap went missing from the actions",
                info.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
        assertTrue(tab.performAccessibilityAction(action.getId(), null));
        assertOpenedSettings();
        assertEquals("the action tapped the tab as well", 0, tab.taps);

        ForYouTabSettingsShortcut.onTopTab(tab, FOLLOWING);
        assertNull("Following offers the For You action", openAction(tab.createAccessibilityNodeInfo()));
        assertFalse(tab.performAccessibilityAction(action.getId(), null));
        assertNull("the action opened the settings from Following", nextStarted());
    }

    /** A delegate TikTok gave the tab still answers for it under the action. */
    @Test public void tikToksOwnAccessibilityDelegateStillAnswers() {
        TopTab tab = tab();
        tab.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription("For You, selected");
            }
        });
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);

        AccessibilityNodeInfo info = tab.createAccessibilityNodeInfo();
        assertEquals("TikTok's own description was lost", "For You, selected", String.valueOf(info.getContentDescription()));
        assertNotNull(openAction(info));
    }

    /** TikTok can set a delegate after the first bind. The next bind wraps it, once. */
    @Test public void aDelegateTikTokSetsLaterIsWrappedOnTheNextBind() {
        TopTab tab = tab();
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);
        tab.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription("For You, selected");
            }
        });
        assertNull(openAction(tab.createAccessibilityNodeInfo()));

        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);

        AccessibilityNodeInfo info = tab.createAccessibilityNodeInfo();
        assertNotNull("the bind after TikTok's delegate didn't bring the action back", openAction(info));
        assertEquals("TikTok's later delegate was lost", "For You, selected", String.valueOf(info.getContentDescription()));
        long offered = info.getActionList().stream()
                .filter(action -> "Open Metra settings".equals(String.valueOf(action.getLabel()))).count();
        assertEquals("binding again stacked the action", 1, offered);
    }

    private static AccessibilityNodeInfo.AccessibilityAction openAction(AccessibilityNodeInfo info) {
        for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
            if ("Open Metra settings".equals(String.valueOf(action.getLabel()))) return action;
        }
        return null;
    }

    @Test public void slidingOffTheTabLetsGo() {
        TopTab tab = tab();
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);
        long down = SystemClock.uptimeMillis();

        tab.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, 150, 60));
        tab.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, down, 150, 900));
        ShadowLooper.idleMainLooper(longPress(), TimeUnit.MILLISECONDS);
        tab.dispatchTouchEvent(event(MotionEvent.ACTION_UP, down, 150, 900));
        ShadowLooper.idleMainLooper();

        assertNull("a finger that slid off For You still opened the settings", nextStarted());
        assertEquals("letting go off the tab was a tap", 0, tab.taps);
    }

    /** No switch reads it, so it's still the way back to the switch while Hushfeed is paused. */
    @Test public void pauseLeavesItWorking() {
        PausedProcess.set(true);
        TopTab tab = tab();
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);

        press(tab, longPress());

        assertOpenedSettings();
    }

    @Test public void aMissingTabOrTagChangesNothing() {
        TopTab tab = tab();
        ForYouTabSettingsShortcut.onTopTab(null, FOR_YOU);
        ForYouTabSettingsShortcut.onTopTab(tab, null);

        press(tab, longPress());

        assertNull(nextStarted());
        assertEquals("a tab without a tag lost TikTok's tap", 1, tab.taps);
    }

    /** A press the listener can't follow is left to the tab, and doesn't throw into TikTok. */
    @Test public void aPressItCantFollowIsTheTabs() {
        TopTab tab = tab();

        assertFalse("a touch it failed on wasn't left to the tab",
                new ForYouTabSettingsShortcut.Press().onTouch(tab, null));
    }
}

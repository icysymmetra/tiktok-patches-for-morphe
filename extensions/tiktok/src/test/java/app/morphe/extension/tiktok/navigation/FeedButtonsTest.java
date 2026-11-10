package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeedButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> owner;
    private View previous;
    private View next;
    private final int[] presses = new int[2];

    @Before public void setUp() {
        Settings.SHOW_FEED_BUTTONS.resetToDefault();
        HookStatus.clear();
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = owner.get();
        LinearLayout row = new LinearLayout(activity);
        previous = button(activity);
        next = button(activity);
        previous.setOnClickListener(v -> presses[0]++);
        next.setOnClickListener(v -> presses[1]++);
        row.addView(previous, 100, 100);
        row.addView(next, 100, 100);
        activity.setContentView(row);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After public void tearDown() {
        owner.close();
        Settings.SHOW_FEED_BUTTONS.resetToDefault();
        HookStatus.clear();
    }

    /** One of TikTok's feed buttons as its layout declares it: clickable and focusable in touch mode. */
    private static View button(Context context) {
        View button = new View(context);
        button.setClickable(true);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        return button;
    }

    /** A finger's tap: down, then up 80 ms later, with whatever the button posts run. */
    private static void tap(View view) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 50, 50, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, 50, 50, 0);
        view.dispatchTouchEvent(down);
        view.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void tikToksLayoutSpendsAFirstTapOnFocus() {
        tap(next);
        assertEquals("the first tap on next only took focus", 0, presses[1]);
        tap(next);
        assertEquals("the second one pressed it", 1, presses[1]);
        tap(previous);
        assertEquals("moving to previous spends another tap", 0, presses[0]);
    }

    @Test public void theRowAndItsButtonsStayTikToksByDefault() {
        assertFalse(Settings.SHOW_FEED_BUTTONS.get());
        assertFalse(FeedButtons.showWithoutScreenReader());
        FeedButtons.buttonShown(next);
        assertTrue("a screen reader's row keeps TikTok's focus", next.isFocusableInTouchMode());
        tap(next);
        assertEquals(0, presses[1]);
    }

    @Test public void theSwitchShowsTheRow() {
        Settings.SHOW_FEED_BUTTONS.save(true);
        assertTrue(FeedButtons.showWithoutScreenReader());
    }

    @Test public void theSwitchAppliesWithoutARestart() {
        assertFalse("TikTok runs the row's check again each time the feed resumes, so a change shows on the way back",
                Settings.SHOW_FEED_BUTTONS.rebootApp);
    }

    @Test public void withTheSwitchOnEveryFirstTapPresses() {
        Settings.SHOW_FEED_BUTTONS.save(true);
        FeedButtons.buttonShown(previous);
        FeedButtons.buttonShown(next);
        tap(next);
        assertEquals("next pressed on the first tap", 1, presses[1]);
        tap(previous);
        assertEquals("previous pressed on the first tap", 1, presses[0]);
        tap(next);
        assertEquals("and next again", 2, presses[1]);
    }

    @Test public void aButtonKeepsItsKeyboardFocusAndItsClick() {
        Settings.SHOW_FEED_BUTTONS.save(true);
        FeedButtons.buttonShown(next);
        assertFalse(next.isFocusableInTouchMode());
        assertTrue("a keyboard can still reach it", next.isFocusable());
        assertTrue(next.isClickable());
    }

    @Test public void aMissingButtonIsSkippedQuietly() {
        Settings.SHOW_FEED_BUTTONS.save(true);
        ShadowLog.clear();
        FeedButtons.buttonShown(null);
        assertEquals("a button TikTok never inflated is not an error", 0, errors());
    }

    @Test public void aFailingButtonIsLoggedAndLeftToTikTok() {
        Settings.SHOW_FEED_BUTTONS.save(true);
        View broken = new View(owner.get()) {
            @Override public void setFocusableInTouchMode(boolean focusableInTouchMode) {
                super.setFocusableInTouchMode(focusableInTouchMode);
                if (!focusableInTouchMode) throw new IllegalStateException("no focus change");
            }
        };
        broken.setFocusable(true);
        broken.setFocusableInTouchMode(true);
        ShadowLog.clear();
        FeedButtons.buttonShown(broken);
        assertTrue("the failure reaches the log", errors() > 0);
    }

    private static int errors() {
        int found = 0;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.type >= Log.ERROR) found++;
        }
        return found;
    }

    @Test public void theExportCountsTheCheckTheRowAndEachButton() {
        int[] found = new int[1];
        HookStatus.setLineWriter((family, count, missing, truncated, firstMiss) -> {
            if (FeedButtons.FAMILY.equals(family)) found[0] = count;
            return family;
        });
        try {
            FeedButtons.showWithoutScreenReader();
            HookStatus.report();
            assertEquals("the check alone", 1, found[0]);
            FeedButtons.buttonShown(next);
            HookStatus.report();
            assertEquals("the check and a button left as it was", 2, found[0]);
            Settings.SHOW_FEED_BUTTONS.save(true);
            FeedButtons.showWithoutScreenReader();
            View plain = new View(owner.get());
            plain.setClickable(true);
            FeedButtons.buttonShown(plain);
            HookStatus.report();
            assertEquals("the row shown, and a button that already pressed at once changes nothing", 3, found[0]);
            FeedButtons.buttonShown(next);
            HookStatus.report();
            assertEquals("the row shown and a first tap freed", 4, found[0]);
        } finally {
            HookStatus.setLineWriter(null);
        }
    }
}

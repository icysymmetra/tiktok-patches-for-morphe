package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-night-mdpi")
public class SettingsActionBannerTest {
    private Activity activity;
    private FrameLayout content;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setContext(activity);
        content = new FrameLayout(activity);
        content.setTag(SettingsActionBanner.CONTENT_ROOT_TAG);
        activity.setContentView(content);
    }

    @After public void tearDown() {
        SettingsActionBanner.dismissForTests();
        RestartPendingPreference.setRestarterForTests(null);
        activity.finish();
    }

    @Test public void undoIsReadableReachableAndRunsOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        SettingsActionBanner.showUndo(activity, "Today starts again", calls::incrementAndGet);

        View banner = content.findViewWithTag(SettingsActionBanner.BANNER_TAG);
        assertNotNull("the settings action was still sent to a toast", banner);
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                banner.getAccessibilityLiveRegion());
        TextView message = banner.findViewWithTag(SettingsActionBanner.MESSAGE_TAG);
        TextView action = banner.findViewWithTag(SettingsActionBanner.ACTION_TAG);
        assertEquals("Today starts again", message.getText().toString());
        assertEquals("Undo", action.getText().toString());
        assertTrue(action.getMinimumWidth() >= SettingsUi.dp(activity, 48));
        assertTrue(action.getMinimumHeight() >= SettingsUi.dp(activity, 48));

        AccessibilityNodeInfo node = action.createAccessibilityNodeInfo();
        assertEquals(android.widget.Button.class.getName(), node.getClassName());
        assertTrue(node.isClickable());
        node.recycle();

        assertTrue(action.performClick());
        action.performClick();
        assertEquals("one press ran Undo more than once", 1, calls.get());
        assertNull(content.findViewWithTag(SettingsActionBanner.BANNER_TAG));
    }

    /** A failed action names the action: a Restart now that threw used to say a clear couldn't be undone. */
    @Test public void aFailedActionSaysWhichActionFailed() {
        SettingsActionBanner.showUndo(activity, "Today starts again", () -> {
            throw new IllegalStateException("undo failed on purpose");
        });
        TextView undo = content.findViewWithTag(SettingsActionBanner.ACTION_TAG);
        assertTrue(undo.performClick());
        assertEquals("Couldn't undo that. Try again.", ShadowToast.getTextOfLatestToast());

        RestartPendingPreference.setRestarterForTests(context -> {
            throw new IllegalStateException("restart failed on purpose");
        });
        SettingsActionBanner.showRestart(activity, "Restart TikTok to apply this.");
        TextView restart = content.findViewWithTag(SettingsActionBanner.ACTION_TAG);
        assertEquals("Restart now", restart.getText().toString());
        assertTrue(restart.performClick());
        assertEquals("Couldn't restart TikTok. Close it and open it again.",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void aNewBannerGetsItsOwnFullLifetime() {
        var looper = Shadows.shadowOf(android.os.Looper.getMainLooper());
        SettingsActionBanner.showNotice(activity, "first");
        looper.idleFor(Duration.ofSeconds(4));
        SettingsActionBanner.showNotice(activity, "second");

        looper.idleFor(Duration.ofSeconds(6));
        TextView message = content.findViewWithTag(SettingsActionBanner.MESSAGE_TAG);
        assertNotNull("the old timer removed the newer banner", message);
        assertEquals("second", message.getText().toString());

        looper.idleFor(Duration.ofSeconds(5));
        assertNull("the newer banner never left", content.findViewWithTag(
                SettingsActionBanner.BANNER_TAG));
    }

    /** A long result stays long enough to read. A restore result was gone after ten seconds. */
    @Test public void aLongMessageStaysLongerAndAShortOneDoesNot() {
        assertEquals(SettingsActionBanner.VISIBLE_MS, SettingsActionBanner.visibleMs("Saved videos put back"));
        String longResult = "Restored 48 settings. ".repeat(12).trim();
        long longTime = SettingsActionBanner.visibleMs(longResult);
        assertTrue("a " + longResult.length() + " character result got " + longTime + " ms",
                longTime > SettingsActionBanner.VISIBLE_MS);
        assertEquals(SettingsActionBanner.LONGEST_VISIBLE_MS,
                SettingsActionBanner.visibleMs("x".repeat(5_000)));

        var looper = Shadows.shadowOf(android.os.Looper.getMainLooper());
        SettingsActionBanner.showNotice(activity, longResult);
        looper.idleFor(Duration.ofMillis(SettingsActionBanner.VISIBLE_MS + 1_000));
        assertNotNull("the long result left at the short time",
                content.findViewWithTag(SettingsActionBanner.BANNER_TAG));
        looper.idleFor(Duration.ofMillis(SettingsActionBanner.LONGEST_VISIBLE_MS));
        assertNull(content.findViewWithTag(SettingsActionBanner.BANNER_TAG));
    }

    /**
     * A tap on the banner went through to the row under it: the banner wasn't clickable, so the
     * pause notice could sit on Pause Hushfeed and a tap on the notice flipped the switch.
     */
    @Test public void aTapOnTheBannerClosesItAndNeverReachesTheRowUnderIt() {
        AtomicInteger beneath = new AtomicInteger();
        View row = new View(activity);
        row.setOnClickListener(view -> beneath.incrementAndGet());
        content.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        SettingsActionBanner.showNotice(activity, "Today's budget is locked");
        View banner = content.findViewWithTag(SettingsActionBanner.BANNER_TAG);
        assertNotNull(banner);

        AccessibilityNodeInfo node = banner.createAccessibilityNodeInfo();
        boolean labelled = false;
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getId() == AccessibilityNodeInfo.ACTION_CLICK
                    && "Close".contentEquals(action.getLabel())) labelled = true;
        }
        node.recycle();
        assertTrue("TalkBack isn't told what a tap on the banner does", labelled);

        content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 1080, 1920);
        float x = banner.getLeft() + banner.getWidth() / 2f;
        float y = banner.getTop() + banner.getHeight() / 2f;
        android.view.MotionEvent down = android.view.MotionEvent.obtain(
                0, 0, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(
                0, 10, android.view.MotionEvent.ACTION_UP, x, y, 0);
        content.dispatchTouchEvent(down);
        content.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        assertEquals("the tap went through the banner to the row it covered", 0, beneath.get());
        assertNull("a tap on the banner left it up", content.findViewWithTag(
                SettingsActionBanner.BANNER_TAG));
    }

    @Test public void replacingFeedbackLeavesExactlyOneBanner() {
        SettingsActionBanner.showNotice(activity, "first");
        SettingsActionBanner.showUndo(activity, "second", () -> { });

        assertEquals(1, content.getChildCount());
        TextView message = content.findViewWithTag(SettingsActionBanner.MESSAGE_TAG);
        assertNotNull(message);
        assertEquals("second", message.getText().toString());
    }

    @Test public void restartIsAReachableOneShotAction() {
        AtomicInteger restarts = new AtomicInteger();
        RestartPendingPreference.setRestarterForTests(ignored -> restarts.incrementAndGet());

        SettingsActionBanner.showRestart(activity, "Restart TikTok to apply this change");

        TextView action = content.findViewWithTag(SettingsActionBanner.ACTION_TAG);
        assertNotNull("restart feedback has no action", action);
        assertEquals("Restart now", action.getText().toString());
        AccessibilityNodeInfo node = action.createAccessibilityNodeInfo();
        assertEquals(android.widget.Button.class.getName(), node.getClassName());
        assertTrue(node.isClickable());
        node.recycle();

        assertTrue(action.performClick());
        action.performClick();
        assertEquals("one press restarted more than once", 1, restarts.get());
        assertNull(content.findViewWithTag(SettingsActionBanner.BANNER_TAG));
    }

    @Test @Config(sdk = 35)
    public void theRecommendedControlTimeoutKeepsUndoAvailableAndOneShot() {
        recommendedTimeouts(60_000, 45_000);
        AtomicInteger calls = new AtomicInteger();
        SettingsActionBanner.showUndo(activity, "History cleared", calls::incrementAndGet);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(46));
        TextView action = content.findViewWithTag(SettingsActionBanner.ACTION_TAG);
        assertNotNull("Undo ignored the reader's time-to-take-action setting", action);
        action.performClick();
        action.performClick();
        assertEquals(1, calls.get());
        assertNull(content.findViewWithTag(SettingsActionBanner.BANNER_TAG));
    }

    @Test @Config(sdk = 35)
    public void aNoticeUsesTheRecommendedReadingTimeoutWithoutClaimingToHaveControls() {
        recommendedTimeouts(60_000, 45_000);
        SettingsActionBanner.showNotice(activity, "Settings saved");
        var looper = Shadows.shadowOf(android.os.Looper.getMainLooper());
        looper.idleFor(Duration.ofSeconds(11));
        assertNotNull("the notice ignored the reading timeout",
                content.findViewWithTag(SettingsActionBanner.MESSAGE_TAG));
        looper.idleFor(Duration.ofSeconds(35));
        assertNull("a notice requested the unrelated control timeout",
                content.findViewWithTag(SettingsActionBanner.BANNER_TAG));
    }

    @Test @Config(sdk = 35)
    public void anActionAlsoHonorsALongerReadingTimeout() {
        recommendedTimeouts(15_000, 60_000);
        SettingsActionBanner.showRestart(activity, "Restart to apply your settings");
        var looper = Shadows.shadowOf(android.os.Looper.getMainLooper());
        looper.idleFor(Duration.ofSeconds(16));
        assertNotNull("the action forgot that its banner also contains text",
                content.findViewWithTag(SettingsActionBanner.ACTION_TAG));
        looper.idleFor(Duration.ofSeconds(45));
        assertNull(content.findViewWithTag(SettingsActionBanner.BANNER_TAG));
    }

    @Test @Config(sdk = 35)
    public void anOlderAccessibleTimerCannotRemoveANewerBanner() {
        recommendedTimeouts(60_000, 45_000);
        var looper = Shadows.shadowOf(android.os.Looper.getMainLooper());
        SettingsActionBanner.showUndo(activity, "first", () -> { });
        looper.idleFor(Duration.ofSeconds(20));
        SettingsActionBanner.showUndo(activity, "second", () -> { });
        looper.idleFor(Duration.ofSeconds(40));
        TextView message = content.findViewWithTag(SettingsActionBanner.MESSAGE_TAG);
        assertNotNull("the first accessible timer removed the newer action", message);
        assertEquals("second", message.getText().toString());
        looper.idleFor(Duration.ofSeconds(21));
        assertNull(content.findViewWithTag(SettingsActionBanner.BANNER_TAG));
    }

    private void recommendedTimeouts(int controls, int text) {
        var manager = (android.view.accessibility.AccessibilityManager)
                activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
        Shadows.shadowOf(manager).setInteractiveUiTimeout(controls);
        Shadows.shadowOf(manager).setNonInteractiveUiTimeout(text);
    }
}

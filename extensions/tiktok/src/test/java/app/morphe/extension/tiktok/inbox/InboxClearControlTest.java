package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;

/** Enters through the full-width title wrapper served by TikTok 47.1.4. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
public class InboxClearControlTest {
    private static final String[] RESOURCE_NAMES = {"47.4.3:oni", "47.4.3:l92", "47.4.3:q4f", "47.4.3:u2n", "47.4.3:fy6",
            "47.4.3:wrv", "user_name", "47.4.3:uyv", "47.4.3:brh", "47.4.3:w24", "47.4.3:fhd", "47.4.3:kp7", "47.4.3:l7j"};
    private ActivityController<Activity> owner;
    private Activity activity;
    private LinearLayout rows;
    private RecordingHeader header;
    private final List<String> dismissed = new ArrayList<>();

    /**
     * The suggested accounts heading, plus the one thing a plain LinearLayout cannot report:
     * what the control announced. {@code announceForAccessibility} builds an announcement event
     * and hands it to the parent, so the parent is where a test can read it.
     */
    private static final class RecordingHeader extends LinearLayout {
        final List<String> announcements = new ArrayList<>();

        RecordingHeader(android.content.Context context) {
            super(context);
        }

        @Override public boolean requestSendAccessibilityEvent(
                View child, android.view.accessibility.AccessibilityEvent event) {
            if (event.getEventType()
                    == android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT
                    && !event.getText().isEmpty()) {
                announcements.add(event.getText().get(0).toString());
            }
            // Not passed on: above this sits the window, which a unit test has no use for.
            return true;
        }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // announceForAccessibility is a no-op with no service listening, which would make every
        // announcement assertion below pass by reporting nothing.
        Shadows.shadowOf((android.view.accessibility.AccessibilityManager)
                        RuntimeEnvironment.getApplication()
                                .getSystemService(android.content.Context.ACCESSIBILITY_SERVICE))
                .setEnabled(true);
        SettingsStatus.inboxFilterEnabled = true;
        Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(false);
        Settings.HIDE_INBOX_STORIES.save(false);
        Settings.HIDE_INBOX_CUSTOM_TITLES.save("");
        String packageName = RuntimeEnvironment.getApplication().getPackageName();
        for (String name : RESOURCE_NAMES) {
            InboxFilter.resolveForTests(packageName, name, id(name));
        }
        FeedVisibility.resolveForTests(packageName, "47.4.3:oni", id("47.4.3:oni"));
        openInbox();
        ShadowToast.reset();
    }

    @After public void tearDown() {
        if (owner != null) {
            activity.finish();
            layout();
            advance(300);
            owner.close();
        }
        SettingsStatus.inboxFilterEnabled = false;
    }

    @Test public void theControlStartsIdleAndReadsAsAnEnabledButton() {
        TextView clear = clearControl();
        android.view.accessibility.AccessibilityNodeInfo node = clear.createAccessibilityNodeInfo();
        assertEquals("android.widget.Button", node.getClassName().toString());
        assertTrue("a reader was given no way to press it", node.isEnabled());
        assertTrue(node.isClickable());
        assertEquals("Clear all suggested accounts", node.getContentDescription().toString());
        assertEquals(1, node.getActionList().stream()
                .filter(action -> action.getId()
                        == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                .count());
        assertIdle(clear, "before any run");
        assertEquals(List.of(), header.announcements);
    }

    @Test public void theControlAnswersAPressAndCanBeFocused() {
        TextView clear = clearControl();
        // A press and a focus ring, the same pair every control this bundle draws now carries.
        // This header follows TikTok's theme, so the ring and ripple take its own text colour
        // rather than the white the over-video controls use, which would vanish on a light header.
        assertTrue("Clear all cannot be reached by a keyboard or d-pad", clear.isFocusable());
        android.graphics.drawable.Drawable background = clear.getBackground();
        assertTrue("Clear all has no ripple: " + (background == null ? "null"
                        : background.getClass().getSimpleName()),
                background instanceof android.graphics.drawable.RippleDrawable);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theNativeFullWidthTitleWrapperLeavesAVisibleHitTarget() {
        TextView clear = clearControl();
        header.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2316, View.MeasureSpec.AT_MOST));
        header.layout(0, 0, header.getMeasuredWidth(), header.getMeasuredHeight());
        int minimum = Math.round(48 * activity.getResources().getDisplayMetrics().density);
        assertTrue("the full-width title consumed Clear all: " + clear.getWidth(),
                clear.getWidth() >= minimum);
        assertTrue("the native row squeezed the touch target", clear.getHeight() >= minimum);
        assertTrue("Clear all was placed outside the header", clear.getRight() <= header.getWidth());
        assertTrue("Clear all overlapped the native title wrapper",
                header.getChildAt(0).getRight() <= clear.getLeft());
    }

    @Test public void aReplacedControlKeepsTheNativeTitleWrapperAndItsReservedWidth() {
        TextView original = clearControl();
        header.removeView(original);
        layout();
        TextView replacement = clearControl();
        assertNotSame(original, replacement);
        assertEquals(2, header.getChildCount());
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) replacement.getLayoutParams();
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT, params.width);
        assertEquals(0f, params.weight, 0f);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void largerTextKeepsTheNativeHelpIconInsideItsRemainingSpace() {
        LinearLayout nativeTitle = (LinearLayout) header.getChildAt(0);
        ((TextView) nativeTitle.getChildAt(0)).setTextSize(28);
        clearControl().setTextSize(28);
        header.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2316, View.MeasureSpec.AT_MOST));
        header.layout(0, 0, header.getMeasuredWidth(), header.getMeasuredHeight());
        View help = nativeTitle.getChildAt(1);
        assertTrue("the help icon escaped the title wrapper",
                help.getRight() <= nativeTitle.getWidth());
        assertTrue("the action escaped the header", clearControl().getRight() <= header.getWidth());
    }

    @Test public void aRunHoldsTheControlUntilItReportsAndThenHandsItBack() {
        addAccount("A", true);
        addAccount("B", true);
        TextView clear = clearControl();

        clear.performClick();
        // Pressable throughout: the tap is how the reader stops it. The hold is said by the
        // count and shown by the dimming, not by refusing the pointer.
        assertTrue("the control stopped taking the tap that ends a run", clear.isEnabled());
        assertEquals("the label does not say how far the run has got",
                "Clearing, 1 so far", clear.getText().toString());
        assertTrue("the busy control still painted as idle", clear.getAlpha() < 1f);
        assertTrue(clear.createAccessibilityNodeInfo().isEnabled());
        assertEquals("the run announced something before it finished", List.of(),
                header.announcements);

        advance(300);
        assertEquals("the label did not advance with the run",
                "Clearing, 2 so far", clear.getText().toString());
        assertTrue("the control was taken away mid-run", clear.isEnabled());
        advance(300);

        assertIdle(clear, "after a completed run");
        assertEquals("Dismissed 2 suggested accounts", ShadowToast.getTextOfLatestToast());
        assertEquals(List.of("Dismissed 2 suggested accounts"), header.announcements);
    }

    @Test
    @Config(sdk = 30)
    public void theHeldControlSaysItIsClearingRatherThanOnlyReadingAsDisabled() {
        addAccount("A", true);
        TextView clear = clearControl();
        assertNull("an idle control carried a progress state", clear.getStateDescription());

        clear.performClick();
        // The content description names the action, so a reader never hears the changed label.
        // The state is the only place the wait, and how far it has got, can be said.
        assertEquals("Clearing, 1 so far", String.valueOf(clear.getStateDescription()));
        advance(300);
        assertNull("the progress state outlived the run", clear.getStateDescription());
        assertEquals(List.of("Dismissed one suggested account"), header.announcements);
    }

    @Test public void aThrownClickSaysHowManyItGotThroughAndLeavesTheControlUsable() {
        addAccount("A", true);
        View failing = addAccount("B", false);
        failing.setOnClickListener(view -> {
            throw new IllegalStateException("fixture dismiss failure");
        });
        TextView clear = clearControl();

        clear.performClick();
        advance(300);
        assertEquals("the first account was not dismissed before the failure",
                List.of("A"), dismissed);
        assertIdle(clear, "after a thrown click");
        assertEquals("Stopped after dismissing one suggested account",
                ShadowToast.getTextOfLatestToast());
        assertEquals(List.of("Stopped after dismissing one suggested account"),
                header.announcements);

        failing.setOnClickListener(view -> dismissed.add("B"));
        clear.performClick();
        assertEquals("the failed run left the control locked", List.of("A", "B"), dismissed);
        advance(300);
    }

    @Test public void aFailureOnTheFirstAccountReportsThatNothingWasDismissed() {
        View failing = addAccount("A", false);
        failing.setOnClickListener(view -> {
            throw new IllegalStateException("fixture dismiss failure");
        });
        TextView clear = clearControl();

        clear.performClick();
        assertEquals(List.of(), dismissed);
        assertIdle(clear, "after a run that dismissed nothing");
        assertEquals("Couldn't clear the suggested accounts. Reopen the Inbox and try again.", ShadowToast.getTextOfLatestToast());
        assertEquals(List.of("Couldn't clear the suggested accounts. Reopen the Inbox and try again."), header.announcements);
    }

    @Test public void belowApiThirtyTheDescriptionCarriesTheProgressTheLabelCannot() {
        // The content description replaces the label for a reader, so changing the label alone
        // leaves the whole run announced as "Clear all suggested accounts, disabled".
        addAccount("A", true);
        TextView clear = clearControl();
        assertEquals("Clear all suggested accounts",
                String.valueOf(clear.getContentDescription()));

        clear.performClick();
        assertEquals("Clearing, 1 so far", String.valueOf(clear.getContentDescription()));
        assertEquals("Clearing, 1 so far",
                String.valueOf(clear.createAccessibilityNodeInfo().getContentDescription()));

        advance(300);
        assertEquals("the busy description outlived the run", "Clear all suggested accounts",
                String.valueOf(clear.getContentDescription()));
    }

    @Test public void aHeadingRebuiltMidRunGetsAControlThatSaysTheRunIsStillGoing() {
        // Every dismissal relays out the list, and the suggested heading is a list item TikTok
        // can rebuild. A fresh control on a fresh heading used to come up enabled and idle while
        // the run it silently refuses was still working.
        addAccount("A", true);
        addAccount("B", true);
        clearControl().performClick();

        LinearLayout rebuilt = new LinearLayout(activity);
        rebuilt.setId(header.getId());
        TextView title = new TextView(activity);
        title.setText("Suggested accounts");
        title.setTextColor(Color.WHITE);
        rebuilt.addView(title);
        rows.removeView(header);
        rows.addView(rebuilt, 0, new LinearLayout.LayoutParams(-1, 48));
        layout();

        TextView replacement = clearControlIn(rebuilt);
        assertNotSame("the rebuilt heading kept the old control", clearControl(), replacement);
        assertTrue("the replacement control cannot take the tap that stops the run",
                replacement.isEnabled());
        assertEquals("the replacement control lost the count the run is at",
                "Clearing, 1 so far", replacement.getText().toString());
        assertEquals("Clearing, 1 so far", String.valueOf(replacement.getContentDescription()));
        assertTrue("the replacement control came up looking idle mid-run",
                replacement.getAlpha() < 1f);

        advance(300);
        advance(300);
        assertTrue("the replacement control was never handed back", replacement.isEnabled());
        assertEquals("Clear all", replacement.getText().toString());
        assertEquals("Dismissed 2 suggested accounts", ShadowToast.getTextOfLatestToast());
    }

    private void assertIdle(TextView clear, String when) {
        assertTrue(when + ": the control was left disabled", clear.isEnabled());
        assertEquals(when + ": the control kept its progress label",
                "Clear all", clear.getText().toString());
        assertEquals(when + ": the control was left faded",
                Color.WHITE, clear.getCurrentTextColor());
        assertTrue(when + ": a reader was left with a dead control",
                clear.createAccessibilityNodeInfo().isEnabled());
        assertEquals(when + ": the control was left dimmed", 1f, clear.getAlpha(), 0.001f);
    }

    /**
     * A second tap stops the run at its next step and says how far it got, once.
     *
     * <p>Sixty accounts at 300 ms is eighteen seconds, and the control used to refuse the
     * pointer for all of it. The paced step is what honours the stop, so nothing is dismissed
     * after the tap and the count in the outcome is the count on screen.
     */
    @Test public void aSecondTapStopsTheRunAndSaysHowFarItGot() {
        addAccount("A", true);
        addAccount("B", true);
        addAccount("C", true);
        TextView clear = clearControl();
        assertTrue(clear.performClick());
        assertEquals(List.of("A"), dismissed);
        assertTrue(clear.performClick());
        assertEquals("the stop dismissed another account on the spot", List.of("A"), dismissed);
        assertEquals(0, ShadowToast.shownToastCount());

        advance(299);
        assertEquals(List.of("A"), dismissed);
        advance(1);
        assertEquals("the stop was not honoured at the next step", List.of("A"), dismissed);
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals("Stopped after dismissing one suggested account",
                ShadowToast.getTextOfLatestToast());
        assertEquals("the stop was announced other than once",
                List.of("Stopped after dismissing one suggested account"), header.announcements);
        assertIdle(clear, "after a stopped run");

        // Stopped is not broken: the next tap starts a fresh run over what is left.
        advance(300);
        assertEquals("a stopped run went on dismissing", List.of("A"), dismissed);
        clear.performClick();
        assertEquals(List.of("A", "B"), dismissed);
        advance(300);
        assertEquals(List.of("A", "B", "C"), dismissed);
        advance(300);
        assertEquals(2, ShadowToast.shownToastCount());
        assertEquals("Dismissed 2 suggested accounts", ShadowToast.getTextOfLatestToast());
        assertIdle(clear, "after the run that followed a stop");
    }

    @Test public void oneButtonCanBeRecycledButAnUnchangedAccountIsNotClickedTwice() {
        View button = addAccount("A", false);
        button.setOnClickListener(view -> {
            String label = view.getContentDescription().toString();
            dismissed.add(label);
            if (label.equals(description("A"))) view.setContentDescription(description("B"));
        });
        clearControl().performClick();
        assertEquals(List.of(description("A")), dismissed);
        advance(300);
        assertEquals(List.of(description("A"), description("B")), dismissed);
        advance(300);
        assertEquals("the unchanged B row was dismissed again", 2, dismissed.size());
        assertEquals("Dismissed 2 suggested accounts", ShadowToast.getTextOfLatestToast());
    }

    @Test public void theRunStopsAtSixtyEvenWhenTheHostKeepsRefillingTheSameRow() {
        View button = addAccount("0", false);
        button.setOnClickListener(view -> {
            dismissed.add(view.getContentDescription().toString());
            view.setContentDescription(description(Integer.toString(dismissed.size())));
        });
        clearControl().performClick();
        // Include layout work between paced steps. This checks the cap, not an exact deadline.
        advance(60 * 500);
        assertEquals(60, dismissed.size());
        assertEquals("Dismissed 60 suggested accounts", ShadowToast.getTextOfLatestToast());
        assertEquals(List.of("Dismissed 60 suggested accounts"), header.announcements);
        assertIdle(clearControl(), "after the cap ended the run");
        advance(3000);
        assertEquals(60, dismissed.size());

        clearControl().performClick();
        assertEquals("the cap left the next run locked", 61, dismissed.size());
    }

    @Test public void finishingStopsTheQueuedStepAndReleasesTheNextActivity() {
        addAccount("old A", true);
        addAccount("old B", true);
        TextView leaving = clearControl();
        RecordingHeader leavingHeader = header;
        leaving.performClick();
        activity.finish();
        layout();
        advance(300);
        assertEquals(List.of("old A"), dismissed);
        assertIdle(leaving, "after the inbox went away mid-run");
        assertEquals(List.of("Dismissed one suggested account"), leavingHeader.announcements);
        owner.close();

        openInbox();
        addAccount("new A", true);
        clearControl().performClick();
        assertEquals("the old activity kept the next inbox locked", List.of("old A", "new A"), dismissed);
        advance(300);
    }

    @Test public void aThrowingNativeClickReleasesTheRunForRetry() {
        View button = addAccount("A", false);
        int[] attempts = {0};
        button.setOnClickListener(view -> {
            attempts[0]++;
            throw new IllegalStateException("fixture dismiss failure");
        });
        TextView clear = clearControl();
        clear.performClick();
        assertEquals("the fixture never reached the native click", 1, attempts[0]);
        button.setOnClickListener(view -> dismissed.add("A"));
        clear.performClick();
        assertEquals("the failed native click left the run locked", List.of("A"), dismissed);
        advance(300);
    }

    @Test public void repeatedLayoutsKeepOneUsableControlAndAnEmptyRunCanBeRetried() {
        TextView clear = clearControl();
        InboxFilter.install(activity);
        advance(0);
        layout();
        layout();
        assertEquals(2, header.getChildCount());
        assertSame(clear, clearControl());
        assertEquals(Color.WHITE, clear.getCurrentTextColor());
        assertTrue(clear.isClickable());
        assertTrue(clear.isShown());
        assertTrue(clear.getMinimumHeight() >= Math.round(48 * activity.getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) clear.getLayoutParams();
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT, params.width);
        assertEquals(0f, params.weight, 0f);

        clear.performClick();
        advance(0);
        assertEquals("No suggested accounts to clear", ShadowToast.getTextOfLatestToast());
        assertEquals(List.of("No suggested accounts to clear"), header.announcements);
        assertIdle(clear, "after a run with nothing to dismiss");
        addAccount("A", true);
        clear.performClick();
        assertEquals("an empty run kept the control locked", List.of("A"), dismissed);
        advance(300);
    }

    private void openInbox() {
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = owner.get();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        View tab = new View(activity);
        tab.setId(id("47.4.3:oni"));
        tab.setSelected(true);
        root.addView(tab, new LinearLayout.LayoutParams(-1, 48));
        rows = new LinearLayout(activity);
        rows.setId(id("47.4.3:l92"));
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows, new LinearLayout.LayoutParams(-1, -1));
        header = new RecordingHeader(activity);
        header.setId(id("47.4.3:q4f"));
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(activity);
        title.setId(id("47.4.3:u2n"));
        title.setText("Suggested accounts");
        title.setTextColor(Color.WHITE);
        LinearLayout nativeTitle = new LinearLayout(activity);
        nativeTitle.setOrientation(LinearLayout.HORIZONTAL);
        nativeTitle.addView(title);
        nativeTitle.addView(new View(activity), new LinearLayout.LayoutParams(14, 14));
        header.addView(nativeTitle, new LinearLayout.LayoutParams(-1, -2));
        rows.addView(header, new LinearLayout.LayoutParams(-1, 48));
        activity.setContentView(root);
        InboxFilter.install(activity);
        advance(0);
        layout();
    }

    private View addAccount(String account, boolean removeAfterClick) {
        LinearLayout row = new LinearLayout(activity);
        View button = new View(activity);
        button.setId(id("47.4.3:fy6"));
        button.setContentDescription(description(account));
        button.setOnClickListener(view -> {
            dismissed.add(account);
            if (removeAfterClick) rows.removeView(row);
        });
        row.addView(button, new LinearLayout.LayoutParams(48, 48));
        rows.addView(row, new LinearLayout.LayoutParams(-1, 48));
        return button;
    }

    private TextView clearControl() {
        return clearControlIn(header);
    }

    /**
     * Either description, because below API 30 the busy state is carried by the description
     * itself: there is no state description to put it in, and the description is what a reader
     * is given instead of the label.
     */
    private static TextView clearControlIn(LinearLayout suggestedHeader) {
        for (int index = 0; index < suggestedHeader.getChildCount(); index++) {
            View child = suggestedHeader.getChildAt(index);
            String description = String.valueOf(child.getContentDescription());
            // Idle, busy before the first dismissal, or busy with the count so far.
            if ("Clear all suggested accounts".equals(description)
                    || "Clearing suggested accounts".equals(description)
                    || description.startsWith("Clearing")) {
                return (TextView) child;
            }
        }
        throw new AssertionError("the layout hook did not install Clear all on the suggested header");
    }

    private void layout() {
        activity.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnGlobalLayout();
    }

    private static void advance(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static String description(String account) {
        return "Remove " + account + " from suggested accounts";
    }

    private static int id(String name) {
        for (int index = 0; index < RESOURCE_NAMES.length; index++) {
            if (RESOURCE_NAMES[index].equals(name)) return 4000 + index;
        }
        throw new AssertionError("unknown fixture resource " + name);
    }
}

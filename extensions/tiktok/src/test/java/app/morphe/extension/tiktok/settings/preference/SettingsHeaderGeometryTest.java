package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The page header's Back control keeps a whole 48 dp square: drawn, touchable and read out.
 *
 * <p>It used to sit 8 dp outside the toolbar on a negative margin, which the toolbar clipped, so
 * the S25's accessibility tree read it as 40 by 48 dp and the clipped strip took no touches. The
 * arrow and title share an 8 dp inset, with no part of the target outside its parent.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h800dp-mdpi")
public class SettingsHeaderGeometryTest {
    @Before public void installContext() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void leftToRight() {
        assertWholeTarget(View.LAYOUT_DIRECTION_LTR);
    }

    @Test public void rightToLeft() {
        assertWholeTarget(View.LAYOUT_DIRECTION_RTL);
    }

    @Test @Config(fontScale = 2.0f)
    public void atTwiceTheTextSize() {
        assertWholeTarget(View.LAYOUT_DIRECTION_LTR);
    }

    @Test @Config(qualifiers = "night")
    public void inTheDarkTheme() {
        assertWholeTarget(View.LAYOUT_DIRECTION_LTR);
    }

    @Test public void compactHeaderKeepsItsStatusActionInsideThePage() {
        assertCompactHeader(View.LAYOUT_DIRECTION_LTR);
    }

    @Test @Config(fontScale = 2.0f)
    public void compactHeaderKeepsWholeWordsAndTargetsAtTwiceTheTextSize() {
        assertCompactHeader(View.LAYOUT_DIRECTION_LTR);
    }

    @Test @Config(fontScale = 2.0f)
    public void compactHeaderKeepsItsTargetsInRightToLeftLargeText() {
        assertCompactHeader(View.LAYOUT_DIRECTION_RTL);
    }

    @Test @Config(qualifiers = "de-rDE-w320dp-h800dp-night-mdpi", fontScale = 1.3f)
    public void compactHeaderFitsGermanRestartStateAtTheFirstLargeTextPreset() {
        java.util.Set<String> pending = app.morphe.extension.shared.settings.preference
                .AbstractPreferenceFragment.restartPending;
        java.util.Set<String> previous = new java.util.HashSet<>(pending);
        pending.clear();
        pending.add(app.morphe.extension.shared.settings.BaseSettings.PAUSED.key);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FrameLayout page = new FrameLayout(activity);
            int gutter = SettingsUi.dp(activity, 16);
            page.setPadding(gutter, 0, gutter, 0);
            boolean[] opened = {false};
            View header = SettingsHeaderPreference.master(activity, () -> { }, () -> opened[0] = true)
                    .getView(null, null);
            page.addView(header, new FrameLayout.LayoutParams(-1, -2));
            activity.setContentView(page);
            View root = activity.findViewById(android.R.id.content);
            root.measure(View.MeasureSpec.makeMeasureSpec(SettingsUi.dp(activity, 320), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(SettingsUi.dp(activity, 800), View.MeasureSpec.EXACTLY));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

            TextView brand = textViewWithText(header, "METRA PATCHES");
            TextView status = header.findViewWithTag("hushfeed_compact_status");
            assertNotNull(brand);
            assertNotNull(status);
            assertEquals("the fixture did not render the longer German pending state",
                    "Neustart ausstehend", status.getText().toString());
            for (TextView label : new TextView[]{brand, status}) {
                assertNotNull(label.getLayout());
                assertEquals(label.getText() + " broke across lines", 1, label.getLineCount());
                assertEquals(label.getText() + " was truncated", 0, label.getLayout().getEllipsisCount(0));
                assertTrue(label.getText() + " exceeds its visible text area",
                        label.getLayout().getLineWidth(0) <= label.getWidth()
                                - label.getCompoundPaddingLeft() - label.getCompoundPaddingRight());
                assertTrue(label.getText() + " is clipped vertically", label.getHeight()
                        >= label.getLayout().getHeight() + label.getCompoundPaddingTop() + label.getCompoundPaddingBottom());
                for (View child = label; child != page; child = (View) child.getParent()) {
                    View parent = (View) child.getParent();
                    assertTrue(describe(child) + " starts outside its parent", child.getLeft() >= 0);
                    assertTrue(describe(child) + " ends outside its parent", child.getRight() <= parent.getWidth());
                }
            }
            Rect visible = new Rect();
            assertTrue(status.getGlobalVisibleRect(visible));
            int target = SettingsUi.dp(activity, 48);
            assertTrue("the German status lost its full touch target",
                    visible.width() >= target && visible.height() >= target);
            AccessibilityNodeInfo node = status.createAccessibilityNodeInfo();
            assertEquals(android.widget.Button.class.getName(), String.valueOf(node.getClassName()));
            assertTrue(node.isClickable());
            assertTrue(status.performClick());
            assertTrue("the localized status action did not open its destination", opened[0]);
        } finally {
            pending.clear();
            pending.addAll(previous);
        }
    }

    private static TextView textViewWithText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = textViewWithText(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void assertCompactHeader(int direction) {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FrameLayout page = new FrameLayout(activity);
            int gutter = SettingsUi.dp(activity, 16);
            page.setPadding(gutter, 0, gutter, 0);
            boolean[] opened = {false};
            View header = SettingsHeaderPreference.master(activity, () -> { }, () -> opened[0] = true)
                    .getView(null, null);
            page.addView(header, new FrameLayout.LayoutParams(-1, -2));
            setDirection(page, direction);
            activity.setContentView(page);
            View root = activity.findViewById(android.R.id.content);
            root.measure(View.MeasureSpec.makeMeasureSpec(SettingsUi.dp(activity, 360), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(SettingsUi.dp(activity, 800), View.MeasureSpec.EXACTLY));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

            TextView title = header.findViewWithTag("hushfeed_page_title");
            assertEquals("Settings", title.getText().toString());
            assertEquals("the compact title wraps on a standard phone", 1, title.getLineCount());
            float expectedSp = activity.getResources().getConfiguration().fontScale > 1.3f ? 21f : 32f;
            assertEquals("the home title lost its display size or large-text cap",
                    android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                            expectedSp, activity.getResources().getDisplayMetrics()),
                    title.getTextSize(), 0.01f);
            TextView subtitle = header.findViewWithTag("hushfeed_home_subtitle");
            assertNotNull("the home introduction is missing", subtitle);
            assertEquals("MENUS", subtitle.getText().toString());
            ViewGroup toolbar = header.findViewWithTag("hushfeed_toolbar");
            View status = header.findViewWithTag("hushfeed_compact_status");
            int target = SettingsUi.dp(activity, 48);
            for (View control : new View[]{toolbar.getChildAt(0), status}) {
                Rect visible = new Rect();
                assertTrue("a header action is hidden", control.getGlobalVisibleRect(visible));
                assertTrue("a header action lost its full touch target",
                        visible.width() >= target && visible.height() >= target);
                for (View child = control; child != page; child = (View) child.getParent()) {
                    View parent = (View) child.getParent();
                    assertTrue(describe(child) + " starts outside its parent", child.getLeft() >= 0);
                    assertTrue(describe(child) + " ends outside its parent", child.getRight() <= parent.getWidth());
                }
                AccessibilityNodeInfo node = control.createAccessibilityNodeInfo();
                assertEquals(android.widget.Button.class.getName(), String.valueOf(node.getClassName()));
                assertTrue(node.isClickable());
            }
            assertTrue(status.performClick());
            assertTrue("the status action did not reach its destination", opened[0]);
        }
    }

    private static void assertWholeTarget(int direction) {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            int gutter = SettingsUi.dp(activity, 16);
            FrameLayout page = new FrameLayout(activity);
            page.setPadding(gutter, 0, gutter, 0);
            LinearLayout header = SettingsHeaderPreference.createHeader(activity, "Comments", () -> { });
            page.addView(header, new FrameLayout.LayoutParams(-1, -2));
            if (direction == View.LAYOUT_DIRECTION_RTL) setDirection(page, direction);
            activity.setContentView(page);
            View root = activity.findViewById(android.R.id.content);
            root.measure(View.MeasureSpec.makeMeasureSpec(SettingsUi.dp(activity, 360), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(SettingsUi.dp(activity, 800), View.MeasureSpec.EXACTLY));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

            assertEquals("the fixture is not laid out in the direction it names",
                    direction, header.getLayoutDirection());
            ViewGroup toolbar = header.findViewWithTag("hushfeed_toolbar");
            assertNotNull(toolbar);
            View back = toolbar.getChildAt(0);
            int target = SettingsUi.dp(activity, 48);

            // Nothing between the control and the page reaches past its parent's edges, which is
            // what a clipping parent would cut off.
            for (View view = back; view != page; view = (View) view.getParent()) {
                View parent = (View) view.getParent();
                assertTrue(describe(view) + " starts before its parent", view.getLeft() >= 0);
                assertTrue(describe(view) + " ends past its parent", view.getRight() <= parent.getWidth());
            }

            Rect visible = new Rect();
            assertTrue("the Back control is not on screen at all", back.getGlobalVisibleRect(visible));
            assertTrue("the visible Back control is " + visible.width() + " by " + visible.height()
                            + " px, under " + target,
                    visible.width() >= target && visible.height() >= target);

            AccessibilityNodeInfo node = back.createAccessibilityNodeInfo();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            assertTrue("a screen reader is handed a Back control of " + bounds.width() + " by "
                            + bounds.height() + " px, under " + target,
                    bounds.width() >= target && bounds.height() >= target);

            // Metra keeps the title beside the bare Back control in the same toolbar.
            View title = header.findViewWithTag("hushfeed_page_title");
            int inset = SettingsUi.dp(activity, 8);
            if (direction == View.LAYOUT_DIRECTION_RTL) {
                assertEquals("the Back control left the header's start edge",
                        header.getWidth() - inset, toolbar.getLeft() + back.getRight());
                assertEquals("the title moved away from Back", back.getLeft(), title.getRight());
            } else {
                assertEquals("the Back control left the header's start edge", inset,
                        toolbar.getLeft() + back.getLeft());
                assertEquals("the title moved away from Back", back.getRight(), title.getLeft());
            }
        }
    }

    /**
     * Every view in the tree told the direction, the way SettingsPagesTest turns its pages round:
     * a hand-built fixture here doesn't take it from the configuration or reliably from a parent.
     */
    private static void setDirection(View view, int direction) {
        view.setLayoutDirection(direction);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setDirection(group.getChildAt(i), direction);
        }
    }

    private static String describe(View view) {
        Object tag = view.getTag();
        return tag == null ? view.getClass().getSimpleName() : String.valueOf(tag);
    }
}

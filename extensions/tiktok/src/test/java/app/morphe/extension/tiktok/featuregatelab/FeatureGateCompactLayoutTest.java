package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

/** Whole-screen geometry was missing: individual controls can fit while leaving no gate list. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeatureGateCompactLayoutTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before public void setup() throws Exception {
        settle();
        FeatureGateCatalog.awaitForTests();
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabUndo.resetForTests();
        FeatureGateLabFragment.resetForTests();
        FeatureGateCatalog.resetForTests();
        FeatureGateLabSession.resetForTests();
        FeatureGateLabSession.begin();
        var entries = new LinkedHashMap<String, FeatureGateCatalog.Entry>();
        for (int i = 0; i < 12; i++) {
            var entry = new FeatureGateCatalog.Entry("example_gate_" + i, "Example gate " + i,
                    "abmock", "INT", true, true, List.of("0", "1"), List.of(), List.of(), "", "",
                    true, "1", "INT");
            entries.put(entry.identity(), entry);
        }
        var cache = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
        cache.setAccessible(true);
        cache.set(null, new FeatureGateCatalog.Snapshot(List.copyOf(entries.values()), entries, 0, 0, true));
    }

    @After public void cleanup() throws Exception {
        settle();
        FeatureGateLabStore.resetAllLabData();
        FeatureGateCatalog.resetForTests();
    }

    @Test public void compactDarkScreenLeavesHalfTheWindowForGates() throws Exception { exercise(800, "dark"); }
    @Test @Config(qualifiers = "w360dp-h800dp-notnight-mdpi")
    public void compactLightScreenLeavesHalfTheWindowForGates() throws Exception { exercise(800, "light"); }
    @Test @Config(qualifiers = "w360dp-h640dp-night-mdpi", fontScale = 2)
    public void largeDarkTextKeepsControlsAndAUsableList() throws Exception { exercise(640, "dark-large"); }
    @Test @Config(qualifiers = "w360dp-h640dp-notnight-mdpi", fontScale = 2)
    public void largeLightTextKeepsControlsAndAUsableList() throws Exception { exercise(640, "light-large"); }

    private void exercise(int height, String theme) throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var lab = new FeatureGateLabFragment();
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, lab).commit();
            activity.getFragmentManager().executePendingTransactions();
            settle();
            ViewGroup root = (ViewGroup) lab.getView();
            root.findViewWithTag("feature_gate_view_1").performClick();
            settle();
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 360, height);
            ListView list = find(root, ListView.class);
            assertEquals(12, list.getCount());
            int minimumListHeight = height == 800 ? height / 2 : 120;
            assertTrue("Gate list has only " + list.getHeight() + "px of " + height,
                    list.getHeight() >= minimumListHeight);
            assertTrue("No actual gate is reachable", list.getChildCount() > 0);
            for (int i = 0; i < root.getChildCount() - 1; i++) assertTextFits(root.getChildAt(i));
            UiCapture.save(root, "pages/compact/lab-" + theme + ".png", 360, height);

            ViewGroup overrides = root.findViewWithTag("feature_gate_view_2");
            TextView tabLabel = (TextView) overrides.getChildAt(0);
            double neededWidth = Math.ceil(tabLabel.getPaint().measureText(tabLabel.getText().toString()))
                    + tabLabel.getCompoundPaddingLeft() + tabLabel.getCompoundPaddingRight();
            assertTrue("Tab '" + tabLabel.getText() + "' has " + tabLabel.getWidth() + "px; needs "
                    + neededWidth + "; container " + overrides.getWidth() + "; row "
                    + ((View) overrides.getParent()).getWidth() + "; layout width "
                    + tabLabel.getLayout().getWidth() + "; text " + tabLabel.getTextSize(),
                    tabLabel.getWidth() >= neededWidth);
            android.widget.HorizontalScrollView tabs = (android.widget.HorizontalScrollView)
                    overrides.getParent().getParent();
            tabs.scrollTo(overrides.getRight(), 0);
            android.graphics.Rect visible = new android.graphics.Rect();
            assertTrue(overrides.getGlobalVisibleRect(visible));
            assertEquals("The last tab cannot be scrolled into view", overrides.getWidth(), visible.width());
            assertTrue(overrides.performClick());
            settle();
            assertEquals(0, list.getCount());
            root.findViewWithTag("feature_gate_view_1").performClick();
            tabs.scrollTo(0, 0);
            settle();
            assertEquals(12, list.getCount());

            View help = root.findViewWithTag("feature_gate_help");
            assertNotNull("The warning must remain reachable", help);
            assertTrue(help.getWidth() >= 48 && help.getHeight() >= 48);
            AccessibilityNodeInfo node = help.createAccessibilityNodeInfo();
            assertEquals("About overrides", node.getContentDescription().toString());
            assertTrue(help.performClick());
            settle();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            String warning = ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
            assertTrue(warning.contains("whichever account is signed in"));
            assertTrue(warning.contains("server"));
            UiCapture.save(dialog.getWindow().getDecorView(), "pages/compact/lab-help-" + theme + ".png", 360, height);
            dialog.dismiss();

            Switch master = find(root, Switch.class);
            assertTrue(((View) master.getParent()).performClick());
            settle();
            assertTrue(FeatureGateLabStore.masterEnabled());
            EditText search = find(root, EditText.class);
            search.setText("example_gate_11");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            FeatureGateLabFragment.awaitSearchForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, list.getCount());
        }
    }

    private static void assertTextFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            if (label.length() == 0 || label.getLayout() == null) return;
            assertTrue(label.getText() + " is clipped vertically",
                    label.getLayout().getHeight() <= label.getHeight() - label.getCompoundPaddingTop()
                            - label.getCompoundPaddingBottom());
            int top = 0;
            int bottom = view.getHeight();
            View child = view;
            while (child.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) child.getParent();
                top += child.getTop() - parent.getScrollY();
                bottom += child.getTop() - parent.getScrollY();
                assertTrue(label.getText() + " runs outside " + parent.getClass().getSimpleName(),
                        top >= 0 && bottom <= parent.getHeight());
                child = parent;
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertTextFits(group.getChildAt(i));
        }
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void settle() throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Utils.awaitBackgroundTasksForTests();
        FeatureGateLabFragment.awaitSearchForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}

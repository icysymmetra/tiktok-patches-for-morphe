package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Fragment;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.CheckedTextView;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeatureGatePagesTest {
    @Before public void resetSharedState() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Utils.awaitBackgroundTasksForTests();
        FeatureGateDetailFragment.awaitChangesForTests();
        FeatureGateLabFragment.awaitSearchForTests();
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        FeatureGateDetailFragment.setDetailChangeTestHookForTests(null);
        FeatureGateCatalog.resetForTests();
        FeatureGateLabSession.resetForTests();
        FeatureGateLabUndo.resetForTests();
        SettingsManagerObservationRecorder.clear();
    }

    @Test public void darkLabSearchAndOverrideEditorWork() throws Exception { exercise("dark"); }
    @Test @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightLabSearchAndOverrideEditorWork() throws Exception { exercise("light"); }

    @Test public void labSearchRanksOffMainThreadFromPrecomputedEntryText() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            var entry = new FeatureGateCatalog.Entry(
                    "3P_LOGIN-OPTIMIZATION", "Fast sign in", "abmock", "BOOLEAN",
                    true, true, List.of("false", "true"), List.of(), List.of(), "", "",
                    true, "false", "BOOLEAN");
            assertEquals("3p login optimization", entry.normalizedKey);
            assertEquals("fast sign in", entry.normalizedTitle);
            assertArrayEquals(new String[]{"3p", "login", "optimization", "fast", "sign", "in"},
                    entry.searchTokens);
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 1, 0, true));

            FeatureGateLabFragment lab = new FeatureGateLabFragment();
            attach(activity, lab);
            EditText search = find(lab.getView(), EditText.class);
            ListView list = find(lab.getView(), ListView.class);
            assertNotNull(search);
            assertNotNull(list);

            search.setText("login optimiztion");
            Shadows.shadowOf(Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(200));
            FeatureGateLabFragment.awaitSearchForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals("MorpheGateSearch", FeatureGateLabFragment.lastSearchThreadForTests());
            assertEquals("fuzzy matching changed while moving search off the UI thread",
                    1, list.getAdapter().getCount());
        }
    }

    @Test public void anOlderSearchCannotReplaceTheReadersNewerQuery() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            var alpha = new FeatureGateCatalog.Entry(
                    "alpha_gate", "Alpha gate", "abmock", "BOOLEAN", true, true,
                    List.of("false", "true"), List.of(), List.of(), "", "",
                    true, "false", "BOOLEAN");
            var beta = new FeatureGateCatalog.Entry(
                    "beta_gate", "Beta gate", "abmock", "BOOLEAN", true, true,
                    List.of("false", "true"), List.of(), List.of(), "", "",
                    true, "false", "BOOLEAN");
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(alpha, beta), Map.of(alpha.identity(), alpha, beta.identity(), beta),
                    2, 0, true));

            FeatureGateLabFragment lab = new FeatureGateLabFragment();
            attach(activity, lab);
            EditText search = find(lab.getView(), EditText.class);
            ListView list = find(lab.getView(), ListView.class);
            assertNotNull(search);
            assertNotNull(list);
            java.util.concurrent.CountDownLatch oldSearchStarted =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseOldSearch =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean first =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            FeatureGateLabFragment.setSearchWorkHookForTests(() -> {
                if (!first.compareAndSet(true, false)) return;
                oldSearchStarted.countDown();
                try {
                    assertTrue("the test never released the first search",
                            releaseOldSearch.await(2, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            try {
                search.setText("alpha");
                Shadows.shadowOf(Looper.getMainLooper())
                        .idleFor(java.time.Duration.ofMillis(200));
                assertTrue("the first search never reached its worker",
                        oldSearchStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));

                search.setText("beta");
                releaseOldSearch.countDown();
                Shadows.shadowOf(Looper.getMainLooper())
                        .idleFor(java.time.Duration.ofMillis(200));
                FeatureGateLabFragment.awaitSearchForTests();
                Shadows.shadowOf(Looper.getMainLooper()).idle();

                assertEquals(1, list.getAdapter().getCount());
                FeatureGateCatalog.Entry shown =
                        (FeatureGateCatalog.Entry) list.getAdapter().getItem(0);
                assertEquals("beta_gate", shown.key);
            } finally {
                releaseOldSearch.countDown();
                FeatureGateLabFragment.setSearchWorkHookForTests(null);
            }
        }
    }

    @Test public void theCustomValueDialogKeepsItsActionHierarchyInTheDark() throws Exception {
        assertCustomValueActionsAreRanked();
    }

    @Test @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void theCustomValueDialogKeepsItsActionHierarchyInTheLight() throws Exception {
        assertCustomValueActionsAreRanked();
    }

    /**
     * The shared dialog styling ranks the actions, then the Lab's own tree walk used to repaint
     * every Button with the accent, so Use value and Cancel came out looking equally primary on
     * a dialog whose whole point is that one of the two is the unverified one.
     */
    private static void assertCustomValueActionsAreRanked() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            FeatureGateLabStore.setMasterEnabled(true);
            var entry = new FeatureGateCatalog.Entry("ranked_gate", "Ranked gate", "abmock",
                    "INT", true, true, List.of("0", "1"), List.of(), List.of(), "", "",
                    true, "1", "INT");
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));

            FeatureGateDetailFragment detail =
                    FeatureGateDetailFragment.forEntry("abmock", "ranked_gate", "INT");
            attach(activity, detail);

            java.lang.reflect.Method showCustom =
                    FeatureGateDetailFragment.class.getDeclaredMethod("showCustomValue");
            showCustom.setAccessible(true);
            showCustom.invoke(detail);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            android.app.AlertDialog dialog =
                    (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull("the custom value dialog never opened", dialog);
            android.widget.Button use = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            android.widget.Button cancel = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE);
            assertNotNull(use);
            assertNotNull(cancel);

            assertEquals("Use value lost the accent", SettingsUi.accent(),
                    use.getCurrentTextColor());
            assertEquals("Cancel was promoted to a positive action", SettingsUi.textSecondary(),
                    cancel.getCurrentTextColor());
            assertNotEquals("both actions read as equally primary",
                    use.getCurrentTextColor(), cancel.getCurrentTextColor());

            // The state list has to survive too, or a disabled action stops looking disabled.
            use.setEnabled(false);
            assertNotEquals("the positive action kept its live colour while disabled",
                    SettingsUi.accent(), use.getCurrentTextColor());
            dialog.dismiss();
        }
    }

    @Test public void labFiltersExposeActionRolesCountsAndTheFocusedSourceSelection()
            throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            var entry = new FeatureGateCatalog.Entry("source_gate", "Source gate", "abmock",
                    "INT", true, true, List.of("0", "1"), List.of(), List.of(), "", "",
                    true, "1", "INT");
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));

            FeatureGateLabFragment lab = new FeatureGateLabFragment();
            attach(activity, lab);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            for (String tag : new String[]{"feature_gate_view_0", "feature_gate_view_1",
                    "feature_gate_view_2", "feature_gate_filter"}) {
                View action = lab.getView().findViewWithTag(tag);
                assertNotNull(tag, action);
                assertButtonRole(action);
                // Tabbing through the Lab used to show nothing moving at all: every one of
                // these was flat, focused or not, in both themes.
                assertShowsItsFocus(tag, action);
            }
            assertShowsItsFocus("feature_gate_search_row",
                    lab.getView().findViewWithTag("feature_gate_search_row"));

            View allSources = lab.getView().findViewWithTag("feature_gate_source_0");
            View appAb = lab.getView().findViewWithTag("feature_gate_source_1");
            assertShowsItsFocus("feature_gate_source_0", allSources);
            assertEquals(android.widget.Button.class.getName(),
                    allSources.createAccessibilityNodeInfo().getClassName());
            assertTrue("the focused source container does not carry the selected state",
                    allSources.isSelected());
            appAb.performClick();
            assertFalse(allSources.isSelected());
            assertTrue(appAb.isSelected());

            TextView count = lab.getView().findViewWithTag("feature_gate_result_count");
            assertNotNull(count);
            assertEquals("1 result", count.getText().toString());
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    count.getAccessibilityLiveRegion());
            EditText search = find(lab.getView(), EditText.class);
            search.setText("nothing-here");
            Shadows.shadowOf(Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(200));
            FeatureGateLabFragment.awaitSearchForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("0 results", count.getText().toString());
        }
    }

    /**
     * The focus that lands on the search field reaches the row drawn around it.
     *
     * <p>The focus goes to the {@code EditText}; the row carries the accent border. A group only
     * merges its children's states when told to, so without that the border never came on. This
     * asks the real question the render helpers cannot: it moves focus and reads the row's own
     * drawable state, rather than pushing a state onto the drawable by hand.
     */
    @Test public void theSearchRowCarriesTheFocusThatLandsOnItsField() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();

            FeatureGateLabFragment lab = new FeatureGateLabFragment();
            attach(activity, lab);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            View searchRow = lab.getView().findViewWithTag("feature_gate_search_row");
            assertNotNull("the search row is gone", searchRow);
            EditText search = find(searchRow, EditText.class);
            assertNotNull("the search field is gone", search);

            assertFalse("the row was focused before anything asked it to be",
                    contains(searchRow.getBackground().getState(),
                            android.R.attr.state_focused));

            assertTrue("the search field would not take focus", search.requestFocus());
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("focus reached the field but not the row drawn around it",
                    contains(searchRow.getBackground().getState(),
                            android.R.attr.state_focused));
        }
    }

    private static boolean contains(int[] states, int wanted) {
        for (int state : states) if (state == wanted) return true;
        return false;
    }

    @Test public void darkLabFilterUsesTheSharedSingleChoiceTheme() throws Exception {
        assertLabFilterUsesTheSharedTheme("pages/dark/lab-filter.png");
    }

    @Test @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightLabFilterUsesTheSharedSingleChoiceTheme() throws Exception {
        assertLabFilterUsesTheSharedTheme("pages/light/lab-filter.png");
    }

    private void assertLabFilterUsesTheSharedTheme(String screenshot) throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            var entry = new FeatureGateCatalog.Entry("filter_gate", "Filter gate", "abmock",
                    "INT", true, true, List.of("1"), List.of(), List.of(), "", "",
                    true, "1", "INT");
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));

            FeatureGateLabFragment lab = new FeatureGateLabFragment();
            attach(activity, lab);
            TextView filter = lab.getView().findViewWithTag("feature_gate_filter");
            assertTrue(filter.performClick());
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            ListView choices = dialog.getListView();
            assertEquals(ListView.CHOICE_MODE_SINGLE, choices.getChoiceMode());
            View decor = dialog.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 480, 960);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            CheckedTextView first = findCheckedTextView(choices.getChildAt(0));
            assertNotNull("the filter did not render a platform choice row", first);
            assertEquals(SettingsUi.textPrimary(), first.getCurrentTextColor());
            assertNull("the platform check mark leaked through the shared picker style",
                    first.getCheckMarkDrawable());
            assertNotNull("the shared radio indicator is missing",
                    first.getCompoundDrawablesRelative()[0]);
            assertEquals("DialogCheckMarkDrawable",
                    first.getCompoundDrawablesRelative()[0].getClass().getSimpleName());
            assertNotNull("the shared divider is missing", choices.getDivider());
            UiCapture.save(decor, screenshot);
            dialog.dismiss();
        }
    }

    private static CheckedTextView findCheckedTextView(View view) {
        if (view instanceof CheckedTextView) return (CheckedTextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                CheckedTextView found = findCheckedTextView(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * The state column on the right of a Lab row held three stacked 12sp labels in a fixed
     * 116dp. Density scales that number; text size does not, so at double text size the
     * labels have twice the width to fit into the same box. An ellipsis in a value the reader
     * came to the Lab to read is the whole point of the screen going missing.
     */
    @Test @Config(qualifiers = "w360dp-h640dp-night-mdpi", fontScale = 2)
    public void aLabRowKeepsItsStateColumnReadableAtDoubleTextSize() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            // Without this the rest of the test proves nothing: it would be measuring a row
            // at ordinary text size and finding, correctly, that nothing is cut off.
            assertEquals("the font scale did not take",
                    2f, activity.getResources().getConfiguration().fontScale, 0.01f);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            // A gate that has never been read, which is the row that says "No current value",
            // and the state the item names.
            var entry = new FeatureGateCatalog.Entry("3p_login_optimization", "3p Login Optimization",
                    "abmock", "STRING", true, false, List.of(), List.of(), List.of(), "", "", false, null, null);
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));
            FeatureGateLabStore.saveRule("abmock", entry.key, "STRING", "control_group_b", false);
            FeatureGateLabFragment lab = new FeatureGateLabFragment();
            attach(activity, lab);
            var tabs = FeatureGateLabFragment.class.getDeclaredField("viewTabs");
            tabs.setAccessible(true);
            ((ViewGroup) tabs.get(lab)).getChildAt(1).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            ListView list = find(lab.getView(), ListView.class);
            assertEquals(1, list.getAdapter().getCount());
            View row = list.getAdapter().getView(0, null, list);
            int width = View.MeasureSpec.makeMeasureSpec(
                    activity.getResources().getDisplayMetrics().widthPixels,
                    View.MeasureSpec.EXACTLY);
            row.measure(width, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());

            List<TextView> shown = labels(row);
            assertTrue("no labels were measured", shown.size() >= 3);
            assertTrue("the row is not in the state the item describes: "
                            + shown.stream().map(label -> label.getText().toString())
                            .collect(java.util.stream.Collectors.joining(" | ")),
                    shown.stream().anyMatch(label -> "Saved, override off".contentEquals(label.getText()))
                            && shown.stream().anyMatch(label ->
                            label.getText().toString().startsWith("Saved ")));
            for (TextView label : shown) {
                android.text.Layout laidOut = label.getLayout();
                assertNotNull("\"" + label.getText() + "\" was never laid out", laidOut);
                for (int line = 0; line < laidOut.getLineCount(); line++) {
                    assertEquals("\"" + label.getText() + "\" is cut off at double text size",
                            0, laidOut.getEllipsisCount(line));
                }
                // A label wider than the box it sits in is clipped whether or not the
                // ellipsis machinery ran, which is what a fixed width column does to a
                // label that has no room to shorten.
                assertTrue("\"" + label.getText() + "\" is " + laidOut.getWidth()
                                + "px of text in a " + label.getWidth() + "px label",
                        label.getWidth() >= laidOut.getWidth());
            }
            UiCapture.save(row, "pages/dark/lab-row-large-text.png");
        }
    }

    /**
     * The search field at the top of the Lab was a fixed 48dp box around 16sp text. At double
     * text size that is 32sp of glyphs in a box built for 16, so the tops and tails of what the
     * reader had typed were cut off. 48dp is the floor a finger needs, not the height.
     */
    @Test @Config(qualifiers = "w360dp-h640dp-night-mdpi", fontScale = 2)
    public void theLabSearchFieldGrowsWithTheTextAndKeepsItsFingerSizedFloor() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            assertEquals("the font scale did not take, so this proves nothing",
                    2f, activity.getResources().getConfiguration().fontScale, 0.01f);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            var entry = new FeatureGateCatalog.Entry("3p_login_optimization",
                    "3p Login Optimization", "abmock", "INT", true, true,
                    List.of(), List.of(), List.of(), "", "", false, null, null);
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));
            FeatureGateLabFragment lab = new FeatureGateLabFragment();
            attach(activity, lab);

            EditText search = find(lab.getView(), EditText.class);
            assertNotNull("the Lab has no search field, so this proves nothing", search);
            assertEquals("the search field lost the floor a finger needs",
                    FeatureGateLabUi.dp(activity, 48), search.getMinimumHeight());

            search.setText("login");
            View root = lab.getView();
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(
                            activity.getResources().getDisplayMetrics().widthPixels,
                            View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

            android.text.Layout typed = search.getLayout();
            assertNotNull("what was typed was never laid out", typed);
            int needed = typed.getHeight() + search.getPaddingTop() + search.getPaddingBottom();
            assertTrue("the search field is " + search.getHeight() + "px around " + needed
                            + "px of text at double text size",
                    search.getHeight() >= needed);
        }
    }

    private static List<TextView> labels(View view) {
        List<TextView> found = new java.util.ArrayList<>();
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            // The key line is meant to shorten; it is an identifier, shown middle-truncated.
            if (label.getVisibility() == View.VISIBLE && label.length() > 0
                    && label.getEllipsize() != android.text.TextUtils.TruncateAt.MIDDLE) {
                found.add(label);
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                found.addAll(labels(group.getChildAt(index)));
            }
        }
        return found;
    }

    private void exercise(String theme) throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            var entry = new FeatureGateCatalog.Entry("3p_login_optimization", "3p Login Optimization",
                    "abmock", "INT", true, true, List.of("0", "1"), List.of(), List.of(), "", "", false, null, null);
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));
            FeatureGateLabFragment lab = new FeatureGateLabFragment();
            attach(activity, lab);
            var tabs = FeatureGateLabFragment.class.getDeclaredField("viewTabs");
            tabs.setAccessible(true);
            ((ViewGroup) tabs.get(lab)).getChildAt(1).performClick();
            UiCapture.save(lab.getView(), "pages/" + theme + "/lab.png");
            EditText search = find(lab.getView(), EditText.class);
            search.setText("no-such-gate-12345");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            FeatureGateLabFragment.awaitSearchForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ListView list = find(lab.getView(), ListView.class);
            assertEquals(0, list.getAdapter().getCount());
            UiCapture.save(lab.getView(), "pages/" + theme + "/lab-empty.png");
            search.setText("");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            assertEquals(1, list.getAdapter().getCount());
            FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry("abmock", entry.key, "INT");
            attach(activity, detail);
            UiCapture.save(detail.getView(), "pages/" + theme + "/gate_details.png");
            assertFalse(find(detail.getView(), Switch.class).isEnabled());
            Spinner disabledValue = find(detail.getView(), Spinner.class);
            assertNotNull(disabledValue);
            assertFalse(disabledValue.isEnabled());
            TextView disabledChoice = (TextView) disabledValue.getAdapter()
                    .getView(0, null, disabledValue);
            assertEquals("the disabled spinner still looks active",
                    app.morphe.extension.tiktok.settings.preference.SettingsUi.textDisabled(),
                    disabledChoice.getCurrentTextColor());
            FeatureGateLabStore.setMasterEnabled(true);
            detail = FeatureGateDetailFragment.forEntry("abmock", entry.key, "INT");
            attach(activity, detail);
            Switch control = find(detail.getView(), Switch.class);
            assertTrue(control.isEnabled());
            ShadowToast.reset();
            control.performClick();
            // Saving an override goes through the journal now, off this thread and back.
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(FeatureGateLabStore.rule("abmock", entry.key, "INT").enabled);
            assertEquals("Feature gate override saved", ShadowToast.getTextOfLatestToast());
            assertButtonRole(findText(detail.getView(), "Reset override"));
            assertButtonRole(findText(detail.getView(), "Show"));
            UiCapture.save(detail.getView(), "pages/" + theme + "/gate-details-enabled.png");
            attach(activity, FeatureGateDetailFragment.forEntry("abmock", "missing", "INT"));
            UiCapture.save(activity.getFragmentManager().findFragmentById(android.R.id.content).getView(),
                    "pages/" + theme + "/gate-details-unavailable.png");
        }
    }

    @Test public void aFinishedSaveIsReportedAfterTheDetailScreenCloses() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            FeatureGateLabStore.setMasterEnabled(true);
            var entry = new FeatureGateCatalog.Entry("leaving_gate", "Leaving gate", "abmock",
                    "INT", true, true, List.of("0", "1"), List.of(), List.of(), "", "",
                    false, null, null);
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));
            FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                    entry.manager, entry.key, entry.type);
            attach(activity, detail);
            Switch control = find(detail.getView(), Switch.class);
            assertNotNull(control);

            ShadowToast.reset();
            synchronized (FeatureGateLabUndo.class) {
                control.performClick();
                activity.getFragmentManager().beginTransaction().remove(detail).commit();
                activity.getFragmentManager().executePendingTransactions();
                assertNull(detail.getActivity());
            }
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue(FeatureGateLabStore.rule("abmock", entry.key, "INT").enabled);
            assertEquals("Feature gate override saved", ShadowToast.getTextOfLatestToast());
        }
    }

    /**
     * Turn on overrides on a gate's page hands over every control on it.
     *
     * <p>A boolean gate's Forced result stayed greyed out until the page was reopened, so the
     * one value the page offered couldn't be changed right after the action said overrides were on.
     */
    @Test public void turningOverridesOnFromTheGatePageEnablesTheForcedResult() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabStore.setMasterEnabled(false);
            var entry = new FeatureGateCatalog.Entry("held_gate", "Held gate", "abmock",
                    "BOOLEAN", true, true, List.of(), List.of(), List.of(), "", "",
                    true, "true", "BOOLEAN");
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));

            FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                    entry.manager, entry.key, entry.type);
            attach(activity, detail);
            Switch forced = fieldOf(detail, "booleanValue", Switch.class);
            assertNotNull("the detail page has no forced-result switch", forced);
            assertFalse("overrides are off, so the page starts read-only", forced.isEnabled());

            View turnOn = detail.getView().findViewWithTag("feature_gate_enable_overrides");
            assertNotNull("the page has no Turn on overrides action", turnOn);
            turnOn.performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue(FeatureGateLabStore.masterEnabled());
            assertTrue("Forced result stayed disabled after Turn on overrides", forced.isEnabled());
        }
    }

    /**
     * A save that fails puts the switch back where the store is.
     *
     * <p>The toast said it could not save and the switch stayed where the finger left it, so the
     * page claimed a value nothing held. The Lab's own master switch has always put itself back.
     */
    @Test public void aSaveThatFailsPutsTheControlBackOnWhatIsStored() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabStore.setMasterEnabled(true);
            var entry = new FeatureGateCatalog.Entry("refused_gate", "Refused gate", "abmock",
                    "BOOLEAN", true, true, List.of(), List.of(), List.of(), "", "",
                    true, "false", "BOOLEAN");
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));
            FeatureGateLabUndo.saveRule("abmock", entry.key, entry.type, "false", true);

            FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                    entry.manager, entry.key, entry.type);
            attach(activity, detail);
            // The result switch, not the override switch above it.
            Switch forced = fieldOf(detail, "booleanValue", Switch.class);
            assertNotNull("the detail page has no forced-result switch", forced);
            assertFalse("the fixture did not start from the stored value", forced.isChecked());

            TextView status = fieldOf(detail, "status", TextView.class);
            assertNotNull("the detail page has no status line", status);
            String statusBefore = status.getText().toString();
            ShadowToast.reset();
            FeatureGateDetailFragment.setDetailChangeTestHookForTests(
                    new FeatureGateDetailFragment.DetailChangeTestHook() {
                        @Override public void before(long generation) throws Exception {
                            throw new java.io.IOException("storage refused the override");
                        }

                        @Override public void after(long generation) {
                        }
                    });
            try {
                forced.setChecked(true);
                FeatureGateDetailFragment.awaitChangesForTests();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            } finally {
                FeatureGateDetailFragment.setDetailChangeTestHookForTests(null);
            }

            assertFalse("the switch kept a value the store refused", forced.isChecked());
            assertEquals("the store took a value it had refused", "false",
                    FeatureGateLabStore.rule("abmock", entry.key, entry.type).value);
            assertEquals("the status line moved for a save that did not happen",
                    statusBefore, status.getText().toString());
            assertEquals("the reader was not told the save failed", 1, ShadowToast.shownToastCount());
        }
    }

    /**
     * An imported boolean rule arrives with its override off, so one tap has to turn that rule
     * on rather than force the opposite of what the file said.
     *
     * <p>The boolean page had a single switch, Forced result, whose listener always saved with
     * the override enabled. Open an imported gate whose value is true and the switch shows on,
     * so the first tap flips it off and writes ("false", true): the reader has to tap twice to
     * get the file's own value, and what TikTok is handed in between is the opposite of it.
     */
    @Test public void anImportedBooleanOverrideTurnsOnWithOneTap() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabStore.setMasterEnabled(true);
            // Loaded, and TikTok holds false, so the file's true is the value worth forcing.
            var entry = new FeatureGateCatalog.Entry("imported_gate", "Imported gate", "abmock",
                    "BOOLEAN", true, true, List.of(), List.of(), List.of(), "", "",
                    true, "false", "BOOLEAN");
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));

            String profile = new org.json.JSONObject()
                    .put("schema", 1)
                    .put("target", "TikTok global")
                    .put("tiktok_version", FeatureGateLabStore.targetVersion())
                    .put("rules", new org.json.JSONArray().put(new org.json.JSONObject()
                            .put("manager", "abmock").put("key", entry.key)
                            .put("type", "BOOLEAN").put("value", "true")))
                    .toString();
            FeatureGateLabStore.ImportReview review = FeatureGateLabStore.reviewProfile(
                    profile, FeatureGateCatalog.cachedSnapshot().byIdentity);
            assertEquals("the profile was not accepted", 1, review.accepted.size());
            FeatureGateLabUndo.importRules(review);
            var imported = FeatureGateLabStore.rule("abmock", entry.key, "BOOLEAN");
            assertEquals("the import did not keep the file's value", "true", imported.value);
            assertFalse("an import is supposed to land with its override off", imported.enabled);

            FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                    entry.manager, entry.key, entry.type);
            attach(activity, detail);
            Switch force = fieldOf(detail, "force", Switch.class);
            Switch forced = fieldOf(detail, "booleanValue", Switch.class);
            assertNotNull("a boolean page has no override switch", force);
            assertNotNull("the detail page has no forced-result switch", forced);
            assertFalse("the override switch does not show the saved rule is off", force.isChecked());
            assertTrue("the result switch does not show the value the file asked for",
                    forced.isChecked());

            ShadowToast.reset();
            force.performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            var saved = FeatureGateLabStore.rule("abmock", entry.key, "BOOLEAN");
            assertTrue("one tap did not turn the imported override on", saved.enabled);
            assertEquals("one tap forced the opposite of what the file said", "true", saved.value);
            assertTrue("the result switch moved under a tap that was not on it",
                    forced.isChecked());
            TextView status = fieldOf(detail, "status", TextView.class);
            assertEquals("the status still reads as an override that is off",
                    "Not read yet", status.getText().toString());

            // And the other half of the two-row shape: choosing a result while the override is
            // off records the choice without turning anything on, the way the spinner page does.
            force.performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            forced.performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            var parked = FeatureGateLabStore.rule("abmock", entry.key, "BOOLEAN");
            assertEquals("the result switch did not record the choice", "false", parked.value);
            assertFalse("picking a result turned the override on by itself", parked.enabled);
        }
    }

    public static final class Payload {
        public int count = 1;
    }

    /**
     * A refused structured override says why, and what to do about it, under the status.
     *
     * <p>The status said "could not be applied" and stopped. The controller knew the reason
     * (the field that does not exist on this build, the class that cannot be copied, the type
     * the catalogue disagrees on) and put it in logcat, which nobody holding a phone reads.
     * The one reason driven through the live path here is the field one, since that is the
     * reason a reader can act on by editing; the rest are recorded the way the runtime records
     * them and read back off the page.
     */
    @Test public void aRefusedStructuredOverrideSaysWhyUnderTheStatus() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabStore.setMasterEnabled(true);
            FeatureGateLabRuntime.clearTriggered();
            var entry = new FeatureGateCatalog.Entry("payload_config", "Payload config",
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "OBJECT", true, true,
                    List.of(), List.of(), List.of(), "", "", true, "{\"count\":1}", "OBJECT",
                    Payload.class.getName());
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));
            FeatureGateLabStore.saveRule(entry.manager, entry.key, entry.type,
                    "{\"missing_field\":2}", true);

            // The live path: TikTok asks for the object, the override names a field the class
            // does not have, and the runtime records the refusal.
            Object returned = FeatureGateLabRuntime.observeSettingsObject(
                    entry.key, Payload.class, new Payload(), new Payload());
            assertTrue("the refused override changed the object anyway",
                    returned instanceof Payload && ((Payload) returned).count == 1);
            FeatureGateFailure recorded = FeatureGateLabRuntime.structuredFailure(
                    entry.manager, entry.key, entry.type);
            assertNotNull("the runtime recorded no refusal", recorded);
            assertEquals(FeatureGateFailure.Reason.UNSUPPORTED_FIELD, recorded.reason);
            assertEquals("missing_field", recorded.detail);

            FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                    entry.manager, entry.key, entry.type);
            attach(activity, detail);
            TextView status = fieldOf(detail, "status", TextView.class);
            TextView reason = (TextView) detail.getView().findViewWithTag(
                    "feature_gate_status_reason");
            assertNotNull("the page has no line for the reason", reason);
            assertEquals("TikTok read it, but the override couldn't be applied. Reset it and save it again.",
                    status.getText().toString());
            assertEquals(View.VISIBLE, reason.getVisibility());
            assertEquals("Field missing_field can't be changed on this build. Take it out of"
                    + " the override, or reset the override.", reason.getText().toString());
            assertEquals("the reason is not painted as a warning",
                    FeatureGateLabUi.warningColor(activity), reason.getCurrentTextColor());

            // Every other reason the runtime can record, read back through the same line.
            var failures = FeatureGateLabRuntime.class.getDeclaredField("structuredFailures");
            failures.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, FeatureGateFailure> map = (Map<String, FeatureGateFailure>) failures.get(null);
            String id = FeatureGateLabStore.idFor(entry.manager, entry.key, entry.type);
            Map<FeatureGateFailure, String> expected = new java.util.LinkedHashMap<>();
            expected.put(FeatureGateFailure.of(FeatureGateFailure.Reason.NO_OBJECT),
                    "TikTok hasn't loaded this setting yet. Open the part of the app that uses "
                            + "it, then come back.");
            expected.put(FeatureGateFailure.of(FeatureGateFailure.Reason.CANNOT_COPY),
                    "This setting's value can't be copied on this build, so it can't be"
                            + " overridden. Reset the override.");
            expected.put(FeatureGateFailure.of(FeatureGateFailure.Reason.NO_LIST_VALUE),
                    "The override doesn't say what list to return. Edit the field values, or"
                            + " reset the override.");
            expected.put(FeatureGateFailure.of(FeatureGateFailure.Reason.NO_FIELDS),
                    "The override changes no fields. Edit the field values, or reset the"
                            + " override.");
            expected.put(FeatureGateFailure.of(FeatureGateFailure.Reason.THREW,
                            "IllegalArgumentException: count"),
                    "The override couldn't be applied: IllegalArgumentException: count. Edit"
                            + " the field values, or reset the override.");
            expected.put(FeatureGateFailure.of(FeatureGateFailure.Reason.NOT_IN_CATALOGUE),
                    "This key isn't in Metra's list, so its type can't be checked. Reset"
                            + " the override.");
            expected.put(FeatureGateFailure.of(FeatureGateFailure.Reason.TYPE_MISMATCH,
                            "INT", "STRING"),
                    "The catalog says this key is INT and this override is STRING. Reset the"
                            + " override and make a new one.");
            for (var each : expected.entrySet()) {
                map.put(id, each.getKey());
                detail.onResume();
                assertEquals(each.getKey().reason + " is not explained",
                        each.getValue(), reason.getText().toString());
                assertEquals(View.VISIBLE, reason.getVisibility());
            }

            // And once the override goes through, the line goes away rather than staying
            // as a warning about something that is no longer wrong.
            map.remove(id);
            detail.onResume();
            assertEquals("a stale reason was left on the page", View.GONE, reason.getVisibility());
            assertEquals("", reason.getText().toString());
        }
    }

    @Test public void rapidDetailChangesStayOrderedAndShareOneUndoPoint() throws Exception {
        for (boolean firstFails : new boolean[]{false, true, false}) {
            try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
                Activity activity = owner.get();
                Utils.setContext(activity);
                FeatureGateLabStore.resetAllLabData();
                FeatureGateLabStore.setMasterEnabled(true);
                var entry = new FeatureGateCatalog.Entry("ordered_gate", "Ordered gate", "abmock",
                        "INT", true, true, List.of("0", "1", "2"), List.of(), List.of(), "", "",
                        true, "0", "INT");
                var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
                cached.setAccessible(true);
                cached.set(null, new FeatureGateCatalog.Snapshot(
                        List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));
                FeatureGateLabUndo.saveRule("abmock", entry.key, entry.type, "0", true);

                FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                        entry.manager, entry.key, entry.type);
                attach(activity, detail);
                Spinner values = find(detail.getView(), Spinner.class);
                assertNotNull(values);
                assertTrue("the value picker popup kept TikTok's surface",
                        values.getPopupBackground()
                                instanceof android.graphics.drawable.GradientDrawable);
                assertTrue("the value picker is shorter than a 48dp touch target",
                        values.getMinimumHeight() >= SettingsUi.dp(activity, 48));
                var firstStarted = new java.util.concurrent.CountDownLatch(1);
                var releaseFirst = new java.util.concurrent.CountDownLatch(1);
                var secondFinished = new java.util.concurrent.CountDownLatch(1);
                FeatureGateDetailFragment.setDetailChangeTestHookForTests(
                        new FeatureGateDetailFragment.DetailChangeTestHook() {
                            @Override public void before(long generation) throws Exception {
                                if (generation != 1) return;
                                firstStarted.countDown();
                                if (!releaseFirst.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                                    throw new AssertionError("the delayed save was not released");
                                }
                                if (firstFails) throw new java.io.IOException("delayed first save failed");
                            }

                            @Override public void after(long generation) {
                                if (generation == 2) secondFinished.countDown();
                            }
                        });

                boolean secondOvertookFirst = false;
                try {
                    ShadowToast.reset();
                    values.setSelection(1);
                    assertTrue("the first save did not reach its delay",
                            firstStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
                    values.setSelection(2);
                    secondOvertookFirst = secondFinished.await(
                            300, java.util.concurrent.TimeUnit.MILLISECONDS);
                } finally {
                    releaseFirst.countDown();
                    FeatureGateDetailFragment.awaitChangesForTests();
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    FeatureGateDetailFragment.setDetailChangeTestHookForTests(null);
                }

                assertFalse("the second save ran around the delayed first save",
                        secondOvertookFirst);
                FeatureGateLabStore.Rule stored = FeatureGateLabStore.rule(
                        "abmock", entry.key, entry.type);
                assertNotNull(stored);
                assertEquals("the last tap did not win in storage", "2", stored.value);
                var ruleField = FeatureGateDetailFragment.class.getDeclaredField("rule");
                ruleField.setAccessible(true);
                assertEquals("a stale callback repainted the detail screen", "2",
                        ((FeatureGateLabStore.Rule) ruleField.get(detail)).value);
                assertEquals(2, values.getSelectedItemPosition());
                assertEquals("Feature gate override saved", ShadowToast.getTextOfLatestToast());

                FeatureGateLabUndo.undo();
                assertEquals("Undo did not restore the value from before both taps", "0",
                        FeatureGateLabStore.rule("abmock", entry.key, entry.type).value);
            }
        }
    }

    @Test public void rebuildingTheDetailViewDoesNotStackUpTheEditorsOrLeaveADialogUp() throws Exception {
        // This screen sits on a back stack, so its view is built again on a rotation, a font
        // scale change, a theme change, or a return from anything deeper. Every rebuild used to
        // append a second full set of field editors, so the text collected on save came from
        // fields nobody could see, and each of them pinned a destroyed hierarchy.
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            FeatureGateLabStore.setMasterEnabled(true);
            var entry = new FeatureGateCatalog.Entry("object_gate", "Object gate",
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "OBJECT", true, true,
                    List.of(), List.of(), List.of(), "", "", false, null, null,
                    StructuredConfigControllerTest.Config.class.getName());
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));

            FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "object_gate", "OBJECT");
            attach(activity, detail);
            int editors = detail.objectEditorCountForTests();
            assertTrue("the screen built no field editors, so this proves nothing", editors > 0);

            for (int rebuild = 0; rebuild < 2; rebuild++) {
                activity.getFragmentManager().beginTransaction().detach(detail).commit();
                activity.getFragmentManager().executePendingTransactions();
                activity.getFragmentManager().beginTransaction().attach(detail).commit();
                activity.getFragmentManager().executePendingTransactions();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }

            assertEquals("the editors from the earlier views are still in the list",
                    editors, detail.objectEditorCountForTests());
        }
    }

    @Test @Config(fontScale = 1f)
    public void structuredEditorsHaveOneUniqueNamedInputAtNormalTextSize() throws Exception {
        assertStructuredEditorsAreNamed(1f);
    }

    @Test @Config(fontScale = 2f)
    public void structuredEditorsHaveOneUniqueNamedInputAtDoubleTextSize() throws Exception {
        assertStructuredEditorsAreNamed(2f);
    }

    @Test @Config(fontScale = 1f)
    public void structuredBooleanFieldsHaveOneNamedSwitchAtNormalTextSize() throws Exception {
        assertStructuredTogglesAreNamed(1f);
    }

    @Test @Config(fontScale = 2f)
    public void structuredBooleanFieldsHaveOneNamedSwitchAtDoubleTextSize() throws Exception {
        assertStructuredTogglesAreNamed(2f);
    }

    /**
     * A generated Boolean field used to leave three labels in traversal for one value: the
     * readable title, the raw field name, and the raw field name again as the Switch's content
     * description. The text fields beside it had already been reduced to one named editor.
     */
    private static void assertStructuredTogglesAreNamed(float expectedScale) throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            assertEquals(expectedScale,
                    activity.getResources().getConfiguration().fontScale, 0.01f);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            FeatureGateLabStore.setMasterEnabled(true);
            var entry = new FeatureGateCatalog.Entry("boolean_gate", "Boolean gate",
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "OBJECT", true, true,
                    List.of(), List.of(), List.of(), "", "", false, null, null,
                    StructuredConfigControllerTest.TwoBooleans.class.getName());
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));

            FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "boolean_gate", "OBJECT");
            attach(activity, detail);
            java.util.List<Switch> all = new java.util.ArrayList<>();
            collectSwitches(detail.getView(), all);
            // The page carries the gate's own master switch too. A generated field row is a
            // title, a raw name and the control, in that order, which is what separates them.
            java.util.List<Switch> toggles = new java.util.ArrayList<>();
            for (Switch candidate : all) {
                ViewGroup parent = (ViewGroup) candidate.getParent();
                if (parent.getChildCount() >= 3
                        && parent.getChildAt(0) instanceof TextView
                        && parent.getChildAt(1) instanceof TextView
                        && parent.getChildAt(2) == candidate) {
                    toggles.add(candidate);
                }
            }
            assertEquals("the fixture did not build one switch per Boolean field",
                    2, toggles.size());

            java.util.Set<Integer> ids = new java.util.HashSet<>();
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Switch toggle : toggles) {
                ViewGroup field = (ViewGroup) toggle.getParent();
                TextView label = (TextView) field.getChildAt(0);
                TextView technical = (TextView) field.getChildAt(1);

                assertNotEquals("a generated switch has no address for its label",
                        View.NO_ID, toggle.getId());
                assertTrue("two generated switches share the same view id", ids.add(toggle.getId()));
                assertEquals("the field label is not connected to its switch",
                        toggle.getId(), label.getLabelFor());
                assertEquals("the field label is a second accessibility stop",
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        label.getImportantForAccessibility());
                assertEquals("the technical field label is a second accessibility stop",
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        technical.getImportantForAccessibility());
                assertFalse(label.isFocusable());
                assertFalse(technical.isFocusable());
                assertNull("a content description replaced what the switch reports",
                        toggle.getContentDescription());

                android.view.accessibility.AccessibilityNodeInfo node =
                        toggle.createAccessibilityNodeInfo();
                assertEquals("the generated switch stopped announcing itself as a switch",
                        Switch.class.getName(), node.getClassName());
                assertNull(node.getContentDescription());
                String spokenName = String.valueOf(node.getText());
                assertTrue("the node omits the readable field name",
                        spokenName.contains(label.getText()));
                assertTrue("the node omits the exact field identifier or required type",
                        spokenName.contains(technical.getText()));
                assertTrue("two generated switches read the same", names.add(spokenName));
                assertEquals(toggle.isChecked(), node.isChecked());
                assertTrue(node.isEnabled());
                assertEquals("the switch lost its click action", 1, node.getActionList().stream()
                        .filter(action -> action.getId() == android.view.accessibility
                                .AccessibilityNodeInfo.ACTION_CLICK)
                        .count());

                // The state has to survive being switched and being disabled, which is where a
                // content description would have frozen the old value in place.
                toggle.setChecked(!toggle.isChecked());
                assertEquals(toggle.isChecked(),
                        toggle.createAccessibilityNodeInfo().isChecked());
                toggle.setEnabled(false);
                android.view.accessibility.AccessibilityNodeInfo disabled =
                        toggle.createAccessibilityNodeInfo();
                assertFalse(disabled.isEnabled());
                assertEquals(spokenName, String.valueOf(disabled.getText()));
            }
            assertEquals("the two Boolean fields do not read differently", 2, names.size());
        }
    }

    private static void collectSwitches(View view, java.util.List<Switch> found) {
        if (view instanceof Switch) {
            found.add((Switch) view);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectSwitches(group.getChildAt(index), found);
            }
        }
    }

    private static void assertStructuredEditorsAreNamed(float expectedScale) throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            assertEquals(expectedScale,
                    activity.getResources().getConfiguration().fontScale, 0.01f);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            FeatureGateLabStore.setMasterEnabled(true);
            var entry = new FeatureGateCatalog.Entry("object_gate", "Object gate",
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "OBJECT", true, true,
                    List.of(), List.of(), List.of(), "", "", false, null, null,
                    StructuredConfigControllerTest.Config.class.getName());
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));

            FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "object_gate", "OBJECT");
            attach(activity, detail);
            java.util.List<EditText> inputs = new java.util.ArrayList<>();
            collect(detail.getView(), inputs);
            assertTrue("the fixture built no structured text inputs", inputs.size() > 1);
            java.util.Set<Integer> ids = new java.util.HashSet<>();

            for (EditText input : inputs) {
                ViewGroup field = (ViewGroup) input.getParent();
                TextView label = (TextView) field.getChildAt(0);
                TextView technical = (TextView) field.getChildAt(1);
                assertNotEquals("a structured input has no address for its label",
                        View.NO_ID, input.getId());
                assertTrue("two structured inputs share the same view id", ids.add(input.getId()));
                assertEquals("the field label is not connected to its input",
                        input.getId(), label.getLabelFor());
                assertEquals("the field label is a second accessibility stop",
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        label.getImportantForAccessibility());
                assertEquals("the technical field label is a second accessibility stop",
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        technical.getImportantForAccessibility());
                assertFalse(label.isFocusable());
                assertFalse(technical.isFocusable());
                assertTrue(input.isFocusable());
                assertNull("the accessible name replaced the structured value",
                        input.getContentDescription());

                android.view.accessibility.AccessibilityNodeInfo node =
                        input.createAccessibilityNodeInfo();
                String spokenName = String.valueOf(node.getHintText());
                assertTrue("the node omits the readable field name",
                        spokenName.contains(label.getText()));
                assertTrue("the node omits the exact field identifier or required type",
                        spokenName.contains(technical.getText()));
                assertEquals(input.getText().toString(), String.valueOf(node.getText()));
                assertEquals(input.getInputType(), node.getInputType());
                assertTrue(node.isEditable());
                assertTrue(node.isEnabled());
                assertNull(node.getContentDescription());

                input.setEnabled(false);
                android.view.accessibility.AccessibilityNodeInfo disabled =
                        input.createAccessibilityNodeInfo();
                assertFalse(disabled.isEnabled());
                assertEquals(input.getText().toString(), String.valueOf(disabled.getText()));
                assertEquals(input.getInputType(), disabled.getInputType());
                assertEquals(spokenName, String.valueOf(disabled.getHintText()));
            }
        }
    }

    private static void collect(View view, java.util.List<EditText> found) {
        if (view instanceof EditText) {
            found.add((EditText) view);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collect(group.getChildAt(index), found);
            }
        }
    }

    @Test public void enablingAnArrayOverrideKeepsItsGeneratedValues() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            FeatureGateDetailFragment detail = arrayDetail(owner.get(), false, null);
            EditText editor = find(detail.getView(), EditText.class);
            assertNotNull(editor);
            assertEquals("analytics.us.tiktok.com", editor.getText().toString());

            Switch force = find(detail.getView(), Switch.class);
            assertFalse(force.isChecked());
            force.performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            FeatureGateLabStore.Rule saved = FeatureGateLabStore.rule(
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "ad_gaid_whitelist", "OBJECT");
            assertNotNull(saved);
            assertTrue(saved.enabled);
            org.json.JSONArray values = new org.json.JSONObject(saved.value).getJSONArray("$value");
            assertEquals(1, values.length());
            assertEquals("analytics.us.tiktok.com", values.getString(0));
        }
    }

    @Test public void savingAnArrayOverrideKeepsTheLoadedValuesAheadOfItsDefaults() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            FeatureGateDetailFragment detail = arrayDetail(owner.get(), true,
                    "[\"account.one\",\"account.two\"]");
            EditText editor = find(detail.getView(), EditText.class);
            assertNotNull(editor);
            assertEquals("account.one\naccount.two", editor.getText().toString());

            TextView save = findText(detail.getView(), "Save field values");
            assertNotNull(save);
            assertButtonRole(save);
            ShadowToast.reset();
            save.performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            FeatureGateLabStore.Rule saved = FeatureGateLabStore.rule(
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "ad_gaid_whitelist", "OBJECT");
            assertNotNull(saved);
            assertFalse("saving fields must not enable the override", saved.enabled);
            org.json.JSONArray values = new org.json.JSONObject(saved.value).getJSONArray("$value");
            assertEquals(2, values.length());
            assertEquals("account.one", values.getString(0));
            assertEquals("account.two", values.getString(1));
            assertEquals("Feature gate override saved", ShadowToast.getTextOfLatestToast());
        }
    }

    private static void assertButtonRole(View view) {
        assertNotNull(view);
        assertEquals(android.widget.Button.class.getName(),
                view.createAccessibilityNodeInfo().getClassName());
    }

    private static FeatureGateDetailFragment arrayDetail(Activity activity, boolean loaded,
                                                         String currentValue) throws Exception {
        Utils.setContext(activity);
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabSession.begin();
        FeatureGateLabStore.setMasterEnabled(true);
        // The generated 46.2.3 catalogue declares this real gate as a String[] with this default.
        var entry = new FeatureGateCatalog.Entry("ad_gaid_whitelist", "Ad Gaid Whitelist",
                FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "OBJECT", true, true,
                List.of("[\"analytics.us.tiktok.com\"]"), List.of(), List.of(), "", "",
                loaded, currentValue, loaded ? "JSON" : null, String[].class.getName());
        var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
        cached.setAccessible(true);
        cached.set(null, new FeatureGateCatalog.Snapshot(
                List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));
        FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                entry.manager, entry.key, entry.type);
        attach(activity, detail);
        return detail;
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = findText(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * The chevron on the value spinner is a compound drawable, and a compound drawable is only
     * turned round when the view it hangs off resolves its own layout direction. An adapter
     * hands its row back before anything attaches it, so on an Arabic or Hebrew phone the
     * arrow sat at the reading end of the row still pointing the other way.
     */
    @Test @Config(sdk = 23, qualifiers = "ar-rEG-ldrtl-w480dp-h960dp-night-mdpi")
    public void theValueSpinnersChevronTurnsRoundForARightToLeftReader() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            assertEquals("the fixture is not a right to left screen, so this proves nothing",
                    View.LAYOUT_DIRECTION_RTL,
                    activity.getResources().getConfiguration().getLayoutDirection());
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            FeatureGateLabStore.setMasterEnabled(true);
            var entry = new FeatureGateCatalog.Entry("rtl_gate", "Rtl gate", "abmock", "INT",
                    true, true, List.of(), List.of(), List.of(), "", "", false, null, null);
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));

            attach(activity, FeatureGateDetailFragment.forEntry("abmock", "rtl_gate", "INT"));
            Spinner values = find(
                    activity.getFragmentManager().findFragmentById(android.R.id.content).getView(),
                    Spinner.class);
            assertNotNull("the detail screen has no value spinner, so this proves nothing", values);
            View row = values.getAdapter().getView(0, null, values);
            android.graphics.drawable.Drawable chevron =
                    ((TextView) row).getCompoundDrawablesRelative()[2];
            assertNotNull("the spinner row carries no chevron, so this proves nothing", chevron);
            assertEquals("the chevron points the way a left to right reader reads",
                    View.LAYOUT_DIRECTION_RTL, chevron.getLayoutDirection());
        }
    }

    private static void attach(Activity activity, Fragment fragment) {
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    /** A field of the detail fragment, which builds its controls without ids. */
    private static <T> T fieldOf(Object owner, String name, Class<T> type) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(owner);
        return type.isInstance(value) ? type.cast(value) : null;
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
    /**
     * A control that shows where the focus is, by what it paints rather than by a flag.
     *
     * <p>Rendered at rest and focused and compared pixel for pixel: which pixel a focus ring or
     * a wash lands on depends on the radius and the density, so a sample would pass a control
     * that changed nothing the reader can see.
     */
    private static void assertShowsItsFocus(String name, View control) {
        assertNotNull(name, control);
        android.graphics.drawable.Drawable background = control.getBackground();
        assertNotNull(name + " has no background at all, so it cannot show a press or a focus",
                background);
        assertNotEquals(name + " looks exactly the same focused as it does at rest",
                renderOf(background, new int[0]),
                renderOf(background, new int[]{android.R.attr.state_focused}));
    }

    private static int renderOf(android.graphics.drawable.Drawable background, int[] state) {
        background.setState(state);
        background.setBounds(0, 0, 64, 48);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                64, 48, android.graphics.Bitmap.Config.ARGB_8888);
        background.draw(new android.graphics.Canvas(bitmap));
        int hash = 17;
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 48; y++) hash = hash * 31 + bitmap.getPixel(x, y);
        }
        bitmap.recycle();
        return hash;
    }

    /**
     * A structured page says a save is owed, offers a way to drop the edits, and says so when
     * they are dropped by leaving.
     *
     * <p>"Save field values" looked the same before and after typing, and Back popped the page
     * with every edit gone and nothing said, which is the one editor in the bundle that needs an
     * explicit save.
     */
    @Test public void structuredEditsSayTheyArePendingAndCanBeDiscarded() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateDetailFragment detail = structuredDetail(activity, true);
            View save = detail.getView().findViewWithTag("feature_gate_save_fields");
            View discard = detail.getView().findViewWithTag("feature_gate_discard_fields");
            assertNotNull("the structured page has no save action", save);
            assertNotNull("the structured page offers no way to drop an edit", discard);
            java.util.List<EditText> inputs = new java.util.ArrayList<>();
            collect(detail.getView(), inputs);
            assertTrue("the fixture built no structured text inputs", inputs.size() > 0);
            EditText field = inputs.get(0);
            String opened = field.getText().toString();

            assertFalse("Save is offered before anything was typed", save.isEnabled());
            assertEquals("Discard is offered before anything was typed",
                    View.GONE, discard.getVisibility());

            field.setText(opened + "x");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("Save is not offered after typing", save.isEnabled());
            assertEquals(View.VISIBLE, discard.getVisibility());
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                assertEquals("Not saved yet", String.valueOf(save.getStateDescription()));
            }

            // Leaving with an edit pending says so rather than dropping it in silence.
            ShadowToast.reset();
            var leave = FeatureGateDetailFragment.class.getDeclaredMethod("leaveDetail");
            leave.setAccessible(true);
            leave.invoke(detail);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Field edits weren't saved", ShadowToast.getTextOfLatestToast());

            // Discard puts the field back and stands the actions down.
            discard.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Discard did not put the field back", opened, field.getText().toString());
            assertFalse("Save is still offered after a discard", save.isEnabled());
            assertEquals(View.GONE, discard.getVisibility());
        }
    }

    /**
     * With overrides off the page says so above the controls and offers to turn them on, rather
     * than leaving a grey caption after the last row and the switch a screen back.
     */
    @Test public void theDisabledNoteSitsAboveTheControlsAndTurnsThemOn() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateDetailFragment detail = structuredDetail(activity, false);
            View note = detail.getView().findViewWithTag("feature_gate_disabled_note");
            View turnOn = detail.getView().findViewWithTag("feature_gate_enable_overrides");
            assertNotNull("the page does not say overrides are off", note);
            assertNotNull("the page offers no way to turn them on", turnOn);
            assertTrue("the action is not offered as a button",
                    turnOn.createAccessibilityNodeInfo().getClassName().toString()
                            .contains("Button"));

            java.util.List<EditText> inputs = new java.util.ArrayList<>();
            collect(detail.getView(), inputs);
            assertFalse("the fields are live while overrides are off", inputs.get(0).isEnabled());
            // Above the controls: the note is drawn before the first field editor.
            assertTrue("the note sits after the controls it explains",
                    topOf(note, detail.getView()) < topOf(inputs.get(0), detail.getView()));

            turnOn.performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("the overrides were not turned on", FeatureGateLabStore.masterEnabled());
            assertEquals("the note stayed after its action ran", View.GONE, note.getVisibility());
            assertTrue("the fields are still greyed", inputs.get(0).isEnabled());
        }
    }

    /** A structured detail page on a fixture gate, with overrides on or off. */
    private static FeatureGateDetailFragment structuredDetail(Activity activity, boolean overrides)
            throws Exception {
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabSession.begin();
        FeatureGateLabStore.setMasterEnabled(overrides);
        var entry = new FeatureGateCatalog.Entry("object_gate", "Object gate",
                FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "OBJECT", true, true,
                List.of(), List.of(), List.of(), "", "", false, null, null,
                StructuredConfigControllerTest.Config.class.getName());
        var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
        cached.setAccessible(true);
        cached.set(null, new FeatureGateCatalog.Snapshot(
                List.of(entry), Map.of(entry.identity(), entry), 0, 0, true));
        FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "object_gate", "OBJECT");
        attach(activity, detail);
        return detail;
    }

    @Test public void failedStructuredSaveKeepsTheDraftAndAWorkingRetry() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            SettingsUi.syncDarkMode(activity);
            String theme = SettingsUi.isDarkMode() ? "dark" : "light";
            FeatureGateDetailFragment detail = structuredDetail(activity, true);
            EditText count = structuredInput(detail, "count");
            View save = detail.getView().findViewWithTag("feature_gate_save_fields");
            View discard = detail.getView().findViewWithTag("feature_gate_discard_fields");
            count.setText("17");
            FeatureGateDetailFragment.setDetailChangeTestHookForTests(
                    new FeatureGateDetailFragment.DetailChangeTestHook() {
                        @Override public void before(long generation) throws Exception {
                            throw new java.io.IOException("injected structured save failure");
                        }
                        @Override public void after(long generation) { }
                    });
            try {
                save.performClick();
                FeatureGateDetailFragment.awaitChangesForTests();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            } finally {
                FeatureGateDetailFragment.setDetailChangeTestHookForTests(null);
            }
            assertEquals("Couldn't save this override. Try again.", ShadowToast.getTextOfLatestToast());
            UiCapture.save(detail.getView(), "audit/lab-structured-save-failure-" + theme + ".png");
            assertNull(FeatureGateLabStore.rule(
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "object_gate", "OBJECT"));
            assertEquals("17", count.getText().toString());
            assertTrue("a failed save disabled retry of the unchanged draft", save.isEnabled());
            assertEquals("a failed save hid Discard", View.VISIBLE, discard.getVisibility());
            captureStructuredControls(detail, "audit/lab-structured-failed-controls-" + theme + ".png");
            ShadowToast.reset();
            var leave = FeatureGateDetailFragment.class.getDeclaredMethod("leaveDetail");
            leave.setAccessible(true);
            leave.invoke(detail);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Field edits weren't saved", ShadowToast.getTextOfLatestToast());
            save.performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(17, new org.json.JSONObject(FeatureGateLabStore.rule(
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "object_gate", "OBJECT").value).getInt("count"));
            assertFalse(save.isEnabled());
            captureStructuredControls(detail, "audit/lab-structured-retry-controls-" + theme + ".png");
        }
    }

    @Test @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void aLightStructuredPageKeepsItsFailedDraftAndRetry() throws Exception {
        failedStructuredSaveKeepsTheDraftAndAWorkingRetry();
    }

    /** The rule cap is told to the reader and logged at info; any other failure stays a patch error. */
    @Test public void aDetailChangePastTheRuleCapIsLoggedAsARefusal() throws Exception {
        String limit = "The Lab keeps at most 1,024 rules. This change would make 1,025, so nothing was changed.";
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateDetailFragment detail = structuredDetail(activity, true);
            EditText count = structuredInput(detail, "count");
            View save = detail.getView().findViewWithTag("feature_gate_save_fields");
            count.setText("17");
            Exception[] next = {new FeatureGateLabStore.RuleLimitRefused(limit)};
            FeatureGateDetailFragment.setDetailChangeTestHookForTests(
                    new FeatureGateDetailFragment.DetailChangeTestHook() {
                        @Override public void before(long generation) throws Exception {
                            throw next[0];
                        }
                        @Override public void after(long generation) { }
                    });
            try {
                app.morphe.extension.shared.settings.preference.LogBufferManager.clearLogBuffer();
                save.performClick();
                FeatureGateDetailFragment.awaitChangesForTests();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(limit, ShadowToast.getTextOfLatestToast());
                FeatureGateLabActionsTest.assertLoggedOnce("INFO",
                        "Feature Gate detail change refused", "Feature Gate detail change failed");

                next[0] = new java.io.IOException("injected structured save failure");
                app.morphe.extension.shared.settings.preference.LogBufferManager.clearLogBuffer();
                save.performClick();
                FeatureGateDetailFragment.awaitChangesForTests();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("Couldn't save this override. Try again.", ShadowToast.getTextOfLatestToast());
                FeatureGateLabActionsTest.assertLoggedOnce("ERROR",
                        "Feature Gate detail change failed", "Feature Gate detail change refused");
            } finally {
                FeatureGateDetailFragment.setDetailChangeTestHookForTests(null);
            }
            assertNull(FeatureGateLabStore.rule(
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "object_gate", "OBJECT"));
        }
    }

    private static void captureStructuredControls(FeatureGateDetailFragment detail, String name)
            throws Exception {
        View screen = detail.getView();
        screen.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, 480, 960);
        android.widget.ScrollView scroll = find(screen, android.widget.ScrollView.class);
        assertNotNull(scroll);
        View save = screen.findViewWithTag("feature_gate_save_fields");
        scroll.scrollTo(0, Math.max(0, topOf(save, scroll) + scroll.getScrollY() - 140));
        UiCapture.save(screen, name);
        android.graphics.Rect visible = new android.graphics.Rect();
        assertTrue("the control capture did not reach Save", save.getLocalVisibleRect(visible));
    }

    @Test public void structuredDiscardRestoresTheLatestCommittedValues() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateDetailFragment detail = structuredDetail(activity, true);
            EditText count = structuredInput(detail, "count");
            View save = detail.getView().findViewWithTag("feature_gate_save_fields");
            count.setText("17");
            save.performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(17, new org.json.JSONObject(FeatureGateLabStore.rule(
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "object_gate", "OBJECT").value).getInt("count"));
            count.setText("29");
            detail.getView().findViewWithTag("feature_gate_discard_fields").performClick();
            assertEquals("Discard resurrected the page-open value after a later save", "17",
                    count.getText().toString());
            assertFalse(save.isEnabled());
        }
    }

    @Test public void aSaveCompletingAfterANewerStructuredEditKeepsThatEditPending() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateDetailFragment detail = structuredDetail(activity, true);
            EditText count = structuredInput(detail, "count");
            View save = detail.getView().findViewWithTag("feature_gate_save_fields");
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            FeatureGateDetailFragment.setDetailChangeTestHookForTests(
                    new FeatureGateDetailFragment.DetailChangeTestHook() {
                        @Override public void before(long generation) throws Exception {
                            entered.countDown();
                            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new AssertionError("test did not release structured save");
                            }
                        }
                        @Override public void after(long generation) { }
                    });
            try {
                count.setText("17");
                save.performClick();
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                count.setText("29");
            } finally {
                release.countDown();
                FeatureGateDetailFragment.awaitChangesForTests();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                FeatureGateDetailFragment.setDetailChangeTestHookForTests(null);
            }
            assertEquals("29", count.getText().toString());
            assertTrue("completion marked a newer edit as saved", save.isEnabled());
            detail.getView().findViewWithTag("feature_gate_discard_fields").performClick();
            assertEquals("Discard ignored the value that finished saving", "17", count.getText().toString());
        }
    }

    @Test public void resettingAStructuredOverrideRebuildsItsSavedFieldBaseline() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateDetailFragment detail = structuredDetail(activity, true);
            EditText count = structuredInput(detail, "count");
            String initial = count.getText().toString();
            count.setText("17");
            detail.getView().findViewWithTag("feature_gate_save_fields").performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            detail.getView().findViewWithTag("feature_gate_reset").performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(FeatureGateLabStore.rule(
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "object_gate", "OBJECT"));
            assertEquals("the editor still presents the removed override as saved", initial,
                    structuredInput(detail, "count").getText().toString());
            assertFalse(detail.getView().findViewWithTag("feature_gate_save_fields").isEnabled());
        }
    }

    @Test public void untouchedNullFieldsSurviveSavingADifferentStructuredField() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            FeatureGateLabStore.setMasterEnabled(true);
            NullableConfig original = new NullableConfig();
            String recorded = SettingsManagerObservationRecorder.serializeValue(original).toString();
            var entry = new FeatureGateCatalog.Entry("nullable_gate", "Nullable gate",
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "OBJECT", true, true,
                    List.of(), List.of(), List.of(), "", "", true, recorded, "OBJECT",
                    NullableConfig.class.getName());
            var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cached.setAccessible(true);
            cached.set(null, new FeatureGateCatalog.Snapshot(
                    List.of(entry), Map.of(entry.identity(), entry), 1, 0, true));
            FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(
                    entry.manager, entry.key, entry.type);
            attach(activity, detail);
            structuredInput(detail, "count").setText("17");
            detail.getView().findViewWithTag("feature_gate_save_fields").performClick();
            FeatureGateDetailFragment.awaitChangesForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            var saved = FeatureGateLabStore.rule(entry.manager, entry.key, entry.type);
            assertNotNull("an untouched null field blocked saving another field", saved);
            var applied = StructuredConfigController.apply(NullableConfig.class, null, original, saved.value);
            assertTrue("the editor produced an unusable patch", applied.applied);
            NullableConfig result = (NullableConfig) applied.value;
            assertEquals(17, result.count);
            assertNull("saving count changed a null child", result.child);
            assertNull("saving count changed a null name", result.name);
            assertNull("saving count changed a null list", result.values);
            assertNull("saving count changed a null boxed Boolean", result.enabled);
        }
    }

    public static final class NullableConfig {
        public int count = 3;
        public StructuredConfigControllerTest.Child child;
        public String name;
        public List<Integer> values;
        public Boolean enabled;
    }

    private static EditText structuredInput(FeatureGateDetailFragment detail, String name) throws Exception {
        var held = FeatureGateDetailFragment.class.getDeclaredField("objectEditors");
        held.setAccessible(true);
        for (Object editor : (List<?>) held.get(detail)) {
            var key = editor.getClass().getDeclaredField("name");
            key.setAccessible(true);
            if (!name.equals(key.get(editor))) continue;
            var input = editor.getClass().getDeclaredField("input");
            input.setAccessible(true);
            return (EditText) input.get(editor);
        }
        throw new AssertionError("No structured field: " + name);
    }

    /** A view's top edge in the page's own coordinates. */
    private static int topOf(View view, View root) {
        int top = 0;
        for (View at = view; at != null && at != root; at = at.getParent() instanceof View
                ? (View) at.getParent() : null) {
            top += at.getTop();
        }
        return top;
    }
}

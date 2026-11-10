package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

/**
 * The Lab states the polish pass read and left as it found them.
 *
 * <p>A search that matches nothing says so and offers to clear itself, and a detail page whose
 * entry has gone says so and offers the way back. Both were sound on the captures, and neither
 * was held, so a change to either builder would have reached the phone unnoticed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AuditedLabSurfacesTest {
    @Before public void resetSharedState() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Utils.awaitBackgroundTasksForTests();
        FeatureGateDetailFragment.awaitChangesForTests();
        FeatureGateLabFragment.awaitSearchForTests();
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        FeatureGateDetailFragment.setDetailChangeTestHookForTests(null);
        FeatureGateCatalog.resetForTests();
        FeatureGateLabFragment.resetForTests();
        FeatureGateLabSession.resetForTests();
        FeatureGateLabUndo.resetForTests();
        SettingsManagerObservationRecorder.clear();
    }

    /** The fake catalogue and the session this began are not left for the next class to find. */
    @After public void leaveNothingBehind() throws Exception {
        FeatureGateLabFragment.awaitSearchForTests();
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        FeatureGateCatalog.resetForTests();
        FeatureGateLabSession.resetForTests();
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void aSearchThatMatchesNothingSaysSoAndOffersToClearItself() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            cache(entry("gate"));

            FeatureGateLabFragment lab = new FeatureGateLabFragment();
            attach(activity, lab, false);
            settle();
            EditText search = find(lab.getView(), EditText.class);
            ListView list = find(lab.getView(), ListView.class);
            assertNotNull("the Lab has no search field", search);
            assertNotNull("the Lab has no list", list);
            assertEquals("the one gate is not listed before any search",
                    1, list.getAdapter().getCount());

            search.setText("nothing has this name");
            settleSearch();
            assertEquals(0, list.getAdapter().getCount());

            TextView empty = fieldOf(lab, "empty", TextView.class);
            TextView clear = fieldOf(lab, "emptyAction", TextView.class);
            assertNotNull(empty);
            assertNotNull(clear);
            assertEquals("No gates match this search and filter.", empty.getText().toString());
            assertEquals("Clear search", clear.getText().toString());
            assertEquals("the way out is hidden", View.VISIBLE, clear.getVisibility());
            assertEquals("the empty column is not what the list shows",
                    View.VISIBLE, ((View) clear.getParent()).getVisibility());
            assertEquals("the empty list is still shown", View.GONE, list.getVisibility());
            assertEquals("Clear search lost the accent",
                    SettingsUi.accent(), clear.getCurrentTextColor());
            assertTrue("Clear search does not read as the action to take",
                    clear.getTypeface().isBold());

            assertTrue(clear.performClick());
            assertEquals("", search.getText().toString());
            settleSearch();
            assertEquals("clearing the search did not bring the gate back",
                    1, list.getAdapter().getCount());
            assertEquals("the way out stayed after the search was cleared",
                    View.GONE, clear.getVisibility());
            assertEquals(View.VISIBLE, list.getVisibility());
        }
    }

    @Test public void aDetailPageForAnEntryThatHasGoneSaysSoAndOffersTheWayBack() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabSession.begin();
            cache(entry("gate"));

            FeatureGateLabFragment lab = new FeatureGateLabFragment();
            attach(activity, lab, false);
            settle();
            FeatureGateDetailFragment detail =
                    FeatureGateDetailFragment.forEntry("abmock", "gone", "BOOLEAN");
            attach(activity, detail, true);
            FragmentManager manager = activity.getFragmentManager();
            assertEquals(1, manager.getBackStackEntryCount());

            View page = detail.getView();
            assertNotNull(page);
            List<String> texts = texts(page);
            assertTrue("the page does not say the entry has gone: " + texts, texts.contains(
                    "This catalog entry is no longer available. Refresh the Lab and try again."));
            assertNull("an editor is offered for an entry that has gone", find(page, Switch.class));
            TextView back = textViewWith(page, "Back");
            assertNotNull("the way back is missing: " + texts, back);
            assertTrue("Back does nothing", back.hasOnClickListeners());
            assertEquals("Back lost the accent", SettingsUi.accent(), back.getCurrentTextColor());
            assertTrue("Back does not read as the action to take", back.getTypeface().isBold());

            assertTrue(back.performClick());
            manager.executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Back did not leave the page", 0, manager.getBackStackEntryCount());
            assertFalse(detail.isAdded());
            assertTrue("the Lab did not come back", lab.isAdded());
            // No toast at all: nothing was typed, so there was nothing to say was lost.
            assertNull("a toast was shown on the way back: " + ShadowToast.getTextOfLatestToast(),
                    ShadowToast.getTextOfLatestToast());
        }
    }

    private static FeatureGateCatalog.Entry entry(String key) {
        return new FeatureGateCatalog.Entry(key, key, "abmock", "BOOLEAN", true, true,
                List.of("false"), List.of(), List.of(), "", "", true, "false", "BOOLEAN");
    }

    private static void cache(FeatureGateCatalog.Entry entry) throws Exception {
        var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
        cached.setAccessible(true);
        cached.set(null, new FeatureGateCatalog.Snapshot(
                List.of(entry), Map.of(entry.identity(), entry), 1, 0, true));
    }

    private static void attach(Activity activity, Fragment fragment, boolean onBackStack) {
        FragmentTransaction transaction = activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment);
        if (onBackStack) transaction.addToBackStack(null);
        transaction.commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void settle() throws Exception {
        FeatureGateLabFragment.awaitFileIoForTests();
        FeatureGateCatalog.awaitForTests();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void settleSearch() throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
        FeatureGateLabFragment.awaitSearchForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** A field of the fragment, which builds its controls without ids. */
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

    private static TextView textViewWith(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = textViewWith(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<String> texts(View view) {
        List<String> result = new ArrayList<>();
        collect(view, result);
        return result;
    }

    private static void collect(View view, List<String> into) {
        if (view instanceof TextView) into.add(String.valueOf(((TextView) view).getText()));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
        }
    }
}

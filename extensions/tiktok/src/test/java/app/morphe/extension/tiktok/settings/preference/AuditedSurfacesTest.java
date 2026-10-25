package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.navigation.BottomNavigationTabOptions;
import app.morphe.extension.tiktok.navigation.NavigationTabOptions;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/**
 * The states the polish pass read and left as it found them.
 *
 * <p>That pass worked from captures of every settings page in both themes, found these surfaces
 * sound and changed nothing, so nothing held them either: a share catalogue with nothing in it
 * that says what fills it, a tab list that says to open the feed, a Save that reads as the action
 * to take. Walking them for this found one state that could not show at all: the bottom tab list
 * always carries Home and Profile, so its "open the feed once" state, written for a list of one
 * row, sat behind a check for one row that a list of two never met.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AuditedSurfacesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class Host extends android.preference.PreferenceActivity {}

    private static final String OPEN_THE_FEED_FOR_TABS =
            "Open the feed once so Metra can see which tabs TikTok loaded.";
    private static final String OPEN_THE_FEED_FOR_BOTTOM_TABS =
            "Open the feed once so Metra can see which bottom tabs TikTok loaded.";

    @After public void reset() {
        Settings.SHARE_ACTION_CATALOG.save("");
        Settings.SHARE_HIDDEN_ITEMS.save("");
        Settings.FEED_NAVIGATION_OBSERVED_TABS.resetToDefault();
        Settings.FEED_NAVIGATION_TABS.resetToDefault();
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.resetToDefault();
        Settings.BOTTOM_NAVIGATION_TABS.resetToDefault();
    }

    @Test public void anEmptyShareCatalogueSaysSoAndSaysWhatFillsIt() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.SHARE_ACTION_CATALOG.save("");

            ShareActionChecklistPreference preference = new ShareActionChecklistPreference(activity);
            View view = preference.onCreateDialogView();
            List<String> texts = texts(view);
            assertTrue("nothing says the list is empty: " + texts,
                    texts.contains("No share actions have been seen yet"));
            assertTrue("nothing says what fills it: " + texts,
                    texts.contains("Open a share sheet once, then return here to choose its actions."));
            assertNull("a row is offered with nothing seen", find(view, CheckBox.class));
            TextView count = view.findViewWithTag("share_action_result_count");
            assertNotNull("the count is missing", count);
            assertEquals("0 results", count.getText().toString());

            // A search over nothing seen is still nothing seen. The no-match state would send
            // the reader to clear a search that was never what stood between them and a list.
            EditText search = view.findViewWithTag("share_action_search");
            assertNotNull("the search field is missing", search);
            search.setText("copy");
            texts = texts(view);
            assertTrue("the search took the empty state away: " + texts,
                    texts.contains("No share actions have been seen yet"));
            assertFalse("an empty catalogue blamed the search: " + texts,
                    texts.contains("No share actions match this search"));
        }
    }

    @Test public void aFeedTabListWithNothingSeenSaysToOpenTheFeed() throws Exception {
        try (var owner = Robolectric.buildActivity(Host.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.FEED_NAVIGATION_OBSERVED_TABS.resetToDefault();

            TabSelectionPreference preference =
                    new TabSelectionPreference(activity, Settings.FEED_NAVIGATION_TABS);
            // The summary ends with the setting's restart note, which RestartGatedRowsTest holds.
            assertTrue("the row does not say why its list is short: " + preference.getSummary(),
                    String.valueOf(preference.getSummary()).startsWith(OPEN_THE_FEED_FOR_TABS));

            AlertDialog dialog = open(preference);
            View decor = dialog.getWindow().getDecorView();
            List<String> texts = texts(decor);
            assertTrue("the dialog does not say why its list is short: " + texts,
                    texts.contains(OPEN_THE_FEED_FOR_TABS));
            assertNotNull("the tab every feed has is not offered",
                    decor.findViewWithTag("tab_option_" + NavigationTabOptions.HOT));
            assertEquals("more than that one tab is offered", 1, countOf(decor, CheckBox.class));
            assertFalse("Select every tab is offered with nothing seen",
                    dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled());
            dialog.dismiss();
        }
    }

    @Test public void aBottomTabListWithNothingSeenSaysToOpenTheFeedAboveHomeAndProfile() throws Exception {
        try (var owner = Robolectric.buildActivity(Host.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.resetToDefault();

            TabSelectionPreference preference =
                    new TabSelectionPreference(activity, Settings.BOTTOM_NAVIGATION_TABS, true);
            assertTrue("the row does not say why its list is short: " + preference.getSummary(),
                    String.valueOf(preference.getSummary()).startsWith(OPEN_THE_FEED_FOR_BOTTOM_TABS));

            AlertDialog dialog = open(preference);
            View decor = dialog.getWindow().getDecorView();
            List<String> texts = texts(decor);
            assertTrue("the dialog does not say why its list is short: " + texts,
                    texts.contains(OPEN_THE_FEED_FOR_BOTTOM_TABS));
            // Home and Profile are on every install's bar, so they are offered before anything
            // has been seen, and the state sits above them rather than in place of them.
            assertNotNull(decor.findViewWithTag("tab_option_" + BottomNavigationTabOptions.HOME));
            assertNotNull(decor.findViewWithTag("tab_option_" + BottomNavigationTabOptions.PROFILE));
            assertEquals(2, countOf(decor, CheckBox.class));
            assertFalse("Select every tab is offered with nothing seen",
                    dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled());
            dialog.dismiss();
        }
    }

    @Test public void aBottomTabListDropsTheStateOnceATabHasBeenSeen() throws Exception {
        try (var owner = Robolectric.buildActivity(Host.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save(String.join(",",
                    BottomNavigationTabOptions.HOME, BottomNavigationTabOptions.INBOX));

            TabSelectionPreference preference =
                    new TabSelectionPreference(activity, Settings.BOTTOM_NAVIGATION_TABS, true);
            assertFalse("the row still says to open the feed after a tab was seen",
                    String.valueOf(preference.getSummary()).startsWith(OPEN_THE_FEED_FOR_BOTTOM_TABS));

            AlertDialog dialog = open(preference);
            View decor = dialog.getWindow().getDecorView();
            assertFalse("the dialog still says to open the feed after a tab was seen",
                    texts(decor).contains(OPEN_THE_FEED_FOR_BOTTOM_TABS));
            assertEquals(3, countOf(decor, CheckBox.class));
            assertTrue(dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled());
            dialog.dismiss();
        }
    }

    @Test public void theTabDialogRanksSaveAboveCancelAndSelectEveryTab() throws Exception {
        try (var owner = Robolectric.buildActivity(Host.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save(String.join(",",
                    BottomNavigationTabOptions.HOME, BottomNavigationTabOptions.INBOX));
            TabSelectionPreference preference =
                    new TabSelectionPreference(activity, Settings.BOTTOM_NAVIGATION_TABS, true);
            AlertDialog dialog = open(preference);

            Button save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            Button selectAll = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            assertEquals("Save", save.getText().toString());
            assertEquals("Save lost the accent", SettingsUi.accent(), save.getCurrentTextColor());
            assertTrue("Save does not read as the action to take", bold(save.getTypeface()));
            for (Button other : new Button[]{cancel, selectAll}) {
                assertEquals(other.getText() + " reads as a positive action",
                        SettingsUi.textSecondary(), other.getCurrentTextColor());
                assertFalse(other.getText() + " reads as the action to take", bold(other.getTypeface()));
            }
            dialog.dismiss();
        }
    }

    /** Read from the style: under the legacy graphics mode a face built bold still answers isBold false. */
    private static boolean bold(Typeface typeface) {
        return typeface != null && (typeface.getStyle() & Typeface.BOLD) != 0;
    }

    private static AlertDialog open(TabSelectionPreference preference) throws Exception {
        Method show = TabSelectionPreference.class.getDeclaredMethod("showSelectionDialog");
        show.setAccessible(true);
        show.invoke(preference);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("the tab dialog did not open", dialog);
        return dialog;
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

    private static int countOf(View view, Class<? extends View> type) {
        int count = type.isInstance(view) ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += countOf(group.getChildAt(i), type);
            }
        }
        return count;
    }
}

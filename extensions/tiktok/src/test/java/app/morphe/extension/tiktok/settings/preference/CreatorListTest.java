/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.feedfilter.FeedRuleLimits;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/**
 * The editor for locally hidden creators: one row per entry, a search box over them, an add
 * field with the same checks the feed applies, and a save that only lands on Save.
 *
 * <p>Nothing named this class in a test. Its rules live in AdvancedFeedRules, which is
 * tested, but the dialog is where they are wired to the rows a reader sees, and a dialog
 * can call the right helper with the wrong list.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CreatorListTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @org.junit.Before
    public void thePageIsThere() {
        // The Feed filter page only exists on a bundle with the feed filter patch in it.
        app.morphe.extension.tiktok.settings.SettingsStatus.feedFilterEnabled = true;
    }

    @After
    public void reset() {
        app.morphe.extension.tiktok.settings.SettingsStatus.feedFilterEnabled = false;
        Settings.LOCAL_HIDDEN_CREATORS.resetToDefault();
        ShadowToast.reset();
    }

    /**
     * The editor as the Feed filter page carries it. On its own the preference has no
     * PreferenceManager, so persistString writes nowhere and a save proves nothing. On the
     * page it writes the settings' own preferences file, which is what the phone does.
     */
    private static CreatorListPreference open(Activity activity) {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        android.os.Bundle arguments = new android.os.Bundle();
        arguments.putString("morphe_settings_section", "FEED_FILTER");
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        CreatorListPreference preference =
                (CreatorListPreference) fragment.findPreference(Settings.LOCAL_HIDDEN_CREATORS.key);
        assertNotNull("the Feed filter page has no hidden creators row", preference);
        return preference;
    }

    /** The remove buttons present, which is one per visible row, in order. */
    private static java.util.List<String> rows(View view) {
        java.util.List<String> found = new java.util.ArrayList<>();
        collect(view, found);
        return found;
    }

    private static void collect(View view, java.util.List<String> into) {
        Object tag = view.getTag();
        if (tag instanceof String && ((String) tag).startsWith("creator_remove_")) {
            into.add(((String) tag).substring("creator_remove_".length()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) collect(group.getChildAt(index), into);
        }
    }

    /** A handle the way every message carries it: between Unicode's first-strong isolate and its pop. */
    private static String isolated(String handle) {
        return "\u2068" + handle + "\u2069";
    }

    private static View byDescription(View view, String description) {
        if (view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = byDescription(group.getChildAt(index), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView resultCount(View view) {
        return view.findViewWithTag("creator_list_result_count");
    }

    /** The text of the empty state when it is showing, or null when rows are. */
    private static String emptyState(View view) {
        for (String text : new String[]{"No creators are hidden yet. Hide one from a video, or add a handle above.", "No hidden creators match this search. Clear the search to see them all."}) {
            if (visibleText(view, text)) return text;
        }
        return null;
    }

    private static boolean visibleText(View view, String text) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE
                && text.contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (visibleText(group.getChildAt(index), text)) return true;
            }
        }
        return false;
    }

    @Test
    public void theSavedListIsShownOneRowEachAndSavedBackOnlyOnSave() {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.LOCAL_HIDDEN_CREATORS.save("alice, bob");

            CreatorListPreference preference = open(activity);
            View view = preference.onCreateDialogView();
            assertEquals(java.util.List.of("alice", "bob"), rows(view));
            assertNull(emptyState(view));
            assertEquals("2 results", resultCount(view).getText().toString());
            TextView title = (TextView) ((ViewGroup) view).getChildAt(0);
            assertTrue("the dialog title is not exposed as a heading",
                    title.isAccessibilityHeading());

            // Remove one, then cancel: the setting is untouched.
            byDescription(view, "Remove " + isolated("bob")).performClick();
            assertEquals(java.util.List.of("alice"), rows(view));
            preference.onDialogClosed(false);
            assertEquals("alice, bob", Settings.LOCAL_HIDDEN_CREATORS.get());

            // Do it again and save: the setting follows the rows.
            view = preference.onCreateDialogView();
            byDescription(view, "Remove " + isolated("bob")).performClick();
            preference.onDialogClosed(true);
            assertEquals("alice", Settings.LOCAL_HIDDEN_CREATORS.get());
        }
    }

    @Test
    public void addingAnEntryChecksItTheWayTheFeedWould() {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.LOCAL_HIDDEN_CREATORS.save("alice");

            CreatorListPreference preference = open(activity);
            View view = preference.onCreateDialogView();
            EditText add = view.findViewWithTag("creator_list_add");
            View addButton = byDescription(view, "Add hidden creator");
            assertNotNull(add);
            assertNotNull(addButton);

            // Nothing typed: told so under the box, nothing added. These three assertions
            // read the field rather than the toast because the message moved there: a reason
            // shown over the dialog is a reason beside the thing it is about.
            ShadowToast.reset();
            addButton.performClick();
            assertEquals("Enter a creator handle or id", inlineError(add).getText().toString());
            assertNull(ShadowToast.getTextOfLatestToast());
            assertEquals(java.util.List.of("alice"), rows(view));

            // The same creator with a different case and an @: the feed treats those as one
            // entry, so the editor refuses the second copy.
            ShadowToast.reset();
            add.setText("@Alice");
            addButton.performClick();
            assertEquals("That creator is already in the list",
                    inlineError(add).getText().toString());
            assertEquals(java.util.List.of("alice"), rows(view));

            // A pattern that will not compile is refused here rather than at the next feed
            // page, with the entry named.
            ShadowToast.reset();
            add.setText("/[/");
            addButton.performClick();
            assertTrue(inlineError(add).getText().toString(),
                    inlineError(add).getText().toString().contains("/[/"));
            assertEquals(java.util.List.of("alice"), rows(view));

            // A good one lands as a row and clears the field, error and all.
            add.setText("  carol  ");
            addButton.performClick();
            assertEquals(java.util.List.of("alice", "carol"), rows(view));
            assertEquals("", add.getText().toString());
            assertEquals("the last refusal is still under a box that is now fine", View.GONE,
                    inlineError(add).getVisibility());

            preference.onDialogClosed(true);
            assertEquals("alice, carol", Settings.LOCAL_HIDDEN_CREATORS.get());
        }
    }

    @Test
    public void aHandleStillInTheBoxIsSavedWithTheRest() {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.LOCAL_HIDDEN_CREATORS.save("alice");

            // Typed and then Save, with Add never pressed. That is what a reader does, and the
            // handle went nowhere: Save read only the rows.
            CreatorListPreference preference = open(activity);
            View view = preference.onCreateDialogView();
            EditText add = view.findViewWithTag("creator_list_add");
            add.setText("dave");
            preference.onDialogClosed(true);
            assertEquals("alice, dave", Settings.LOCAL_HIDDEN_CREATORS.get());

            // One that would be refused by Add is refused here too, and the reason goes under
            // the box the text is in rather than over whatever was behind the dialog. Nothing
            // is written, and the handle is still there to fix. The assertion moved from
            // ShadowToast to getError() because the message moved with it: the old behaviour
            // was to close first and then say what was wrong, by which point the text was gone.
            ShadowToast.reset();
            preference = open(activity);
            view = preference.onCreateDialogView();
            add = view.findViewWithTag("creator_list_add");
            add.setText("/[/");
            preference.onDialogClosed(true);
            assertEquals("a refused handle was written anyway",
                    "alice, dave", Settings.LOCAL_HIDDEN_CREATORS.get());
            assertNull("the refusal was said over the dialog instead of under the box",
                    ShadowToast.getTextOfLatestToast());
            assertTrue("the box does not say what is wrong with what is in it",
                    inlineError(add).getText().toString().contains("/[/"));
            assertEquals("the handle was taken out of the box the reader typed it into",
                    "/[/", add.getText().toString());
        }
    }

    private static TextView inlineError(EditText field) {
        ViewGroup parent = (ViewGroup) field.getParent();
        return (TextView) parent.getChildAt(parent.indexOfChild(field) + 1);
    }

    @Test
    public void theSearchNarrowsTheRowsAndSaysWhenNothingMatches() {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.LOCAL_HIDDEN_CREATORS.save("alice, bob, bobby");

            CreatorListPreference preference = open(activity);
            View view = preference.onCreateDialogView();
            EditText search = view.findViewWithTag("creator_list_search");
            assertNotNull(search);

            search.setText("BOB");
            assertEquals(java.util.List.of("bob", "bobby"), rows(view));
            assertEquals("2 results", resultCount(view).getText().toString());
            assertNull(emptyState(view));

            search.setText("zed");
            assertEquals(java.util.List.of(), rows(view));
            assertEquals("0 results", resultCount(view).getText().toString());
            assertEquals("No hidden creators match this search. Clear the search to see them all.", emptyState(view));

            search.setText("");
            assertEquals(java.util.List.of("alice", "bob", "bobby"), rows(view));
            assertEquals("3 results", resultCount(view).getText().toString());

            // A search never changes what is saved, only what is shown.
            search.setText("bob");
            preference.onDialogClosed(true);
            assertEquals("alice, bob, bobby", Settings.LOCAL_HIDDEN_CREATORS.get());
        }
    }

    @Test
    public void removingACreatorReportsItAndMovesFocusToTheNextRemoveAction() {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.LOCAL_HIDDEN_CREATORS.save("alice, bob, bobby");

            View view = open(activity).onCreateDialogView();
            View removeBob = byDescription(view, "Remove " + isolated("bob"));
            assertNotNull(removeBob);
            removeBob.requestFocus();
            ShadowToast.reset();
            removeBob.performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            assertEquals("Removed " + isolated("bob"), ShadowToast.getTextOfLatestToast());
            View removeBobby = byDescription(view, "Remove " + isolated("bobby"));
            assertNotNull(removeBobby);
            assertTrue("focus vanished with the removed row", removeBobby.hasFocus());
            assertEquals("2 results", resultCount(view).getText().toString());
        }
    }

    @Test
    public void anEmptyListSaysSoInsideTheListRatherThanUnderIt() {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.LOCAL_HIDDEN_CREATORS.save("");

            View view = open(activity).onCreateDialogView();
            assertEquals(java.util.List.of(), rows(view));
            assertEquals("No creators are hidden yet. Hide one from a video, or add a handle above.", emptyState(view));
        }
    }

    @Test
    public void addingPastTheCreatorLimitIsRefusedWithoutRenderingTenThousandRows() {
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class)
                .setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            CreatorListPreference preference = new CreatorListPreference(activity,
                    "Locally hidden creators", "", Settings.LOCAL_HIDDEN_CREATORS);
            List<String> pending = new ArrayList<>(FeedRuleLimits.MAX_ENTRIES);
            for (int index = 0; index < FeedRuleLimits.MAX_ENTRIES; index++) {
                pending.add("creator" + index);
            }
            EditText editor = new EditText(activity);
            editor.setText("one-too-many");
            ReflectionHelpers.setField(preference, "pendingEntries", pending);
            ReflectionHelpers.setField(preference, "addEditText", editor);

            ReflectionHelpers.callInstanceMethod(preference, "addEntry");

            assertEquals(FeedRuleLimits.MAX_ENTRIES, pending.size());
            assertEquals("one-too-many", editor.getText().toString());
            assertEquals("That list has too many entries. Keep it to 10,000 or fewer.",
                    String.valueOf(editor.getError()));
            assertEquals("", Settings.LOCAL_HIDDEN_CREATORS.get());

            pending.add("forced-over-limit");
            editor.setText("");
            ShadowToast.reset();
            preference.onDialogClosed(true);

            assertEquals("a final dialog save bypassed the list bound", "", preference.getValue());
            assertEquals("That list has too many entries. Keep it to 10,000 or fewer.",
                    String.valueOf(editor.getError()));
            assertEquals("", Settings.LOCAL_HIDDEN_CREATORS.get());
        }
    }
}

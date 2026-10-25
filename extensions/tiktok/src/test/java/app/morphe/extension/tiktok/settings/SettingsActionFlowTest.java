package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.preference.HookStatusPreference;
import app.morphe.extension.tiktok.settings.preference.MorpheTikTokAboutPreference;
import app.morphe.extension.tiktok.settings.preference.SettingsSearchInputPreference;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/** Exercises the controls installed by the real settings page, including their click wiring. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SettingsActionFlowTest {
    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();
    private ActivityController<Activity> controller;

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void tearDown() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) {
            entry.getKey().setBoolean(null, entry.getValue());
        }
        HookStatus.clear();
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void typingAndClearingSearchUpdatesTheInstalledResults() {
        Bundle arguments = new Bundle();
        arguments.putBoolean("morphe_settings_search", true);
        TikTokPreferenceFragment fragment = attach(arguments);
        SettingsSearchInputPreference preference = row(fragment, SettingsSearchInputPreference.class);
        EditText input = fragment.getView().findViewWithTag("settings_search_input");
        View clear = fragment.getView().findViewWithTag("settings_search_clear");
        assertNotNull("the search input was not attached", input);
        assertNotNull(clear);
        assertEquals(View.GONE, clear.getVisibility());
        List<String> empty = titles(fragment);
        assertTrue(empty.contains("Start typing"));

        input.setText("comment");
        idle();
        assertEquals("comment", preference.getQuery());
        assertEquals(View.VISIBLE, clear.getVisibility());
        assertEquals("Clear search", clear.getContentDescription());
        assertTrue(titles(fragment).stream().anyMatch(title -> title.toLowerCase().contains("sort")));
        assertFalse(titles(fragment).contains("Start typing"));

        input.setText("zzq_no_such_setting_zzq");
        idle();
        assertTrue(titles(fragment).contains("No matching settings"));
        Preference none = null;
        for (int i = 0; i < fragment.getPreferenceScreen().getPreferenceCount(); i++) {
            Preference row = fragment.getPreferenceScreen().getPreference(i);
            if ("No matching settings".equals(String.valueOf(row.getTitle()))) none = row;
        }
        assertNotNull(none);
        assertTrue("an empty search does not say where a missing switch went",
                String.valueOf(none.getSummary()).contains("Morphe Manager"));
        assertTrue(clear.performClick());
        idle();
        assertEquals("", input.getText().toString());
        assertEquals("", preference.getQuery());
        assertEquals(View.GONE, clear.getVisibility());
        assertEquals(empty, titles(fragment));
    }

    @Test public void searchQuerySurvivesOpeningAResultAndReturning() {
        Bundle arguments = new Bundle();
        arguments.putBoolean("morphe_settings_search", true);
        TikTokPreferenceFragment fragment = attach(arguments);
        EditText input = fragment.getView().findViewWithTag("settings_search_input");
        assertNotNull("the search input was not attached", input);

        input.setText("speed");
        idle();
        assertTrue(titles(fragment).contains("Default playback speed"));

        clickTitle(fragment, "Default playback speed");
        fragment.getFragmentManager().executePendingTransactions();
        idle();
        assertNull("the search view stayed attached behind its result", fragment.getView());

        assertTrue(fragment.getFragmentManager().popBackStackImmediate());
        idle();
        EditText restored = fragment.getView().findViewWithTag("settings_search_input");
        assertNotNull("the search input was not rebuilt", restored);
        assertEquals("speed", restored.getText().toString());
        assertTrue(titles(fragment).contains("Default playback speed"));
    }

    @Test public void hookStatusShowsTheEmptyReportFromItsInstalledRow() {
        TikTokPreferenceFragment fragment = diagnostics();
        HookStatusPreference preference = row(fragment, HookStatusPreference.class);
        assertEquals("Nothing has been looked up yet. Use the app for a moment, then come back.",
                preference.getSummary());
        clickRow(fragment, HookStatusPreference.class);
        AlertDialog report = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(report);
        assertEquals("Nothing has been checked yet, so there's nothing to report.",
                Shadows.shadowOf(report).getMessage());
        report.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse(report.isShowing());
    }

    @Test public void hookStatusReflectsNewFindingsAndOpensTheirDetails() {
        TikTokPreferenceFragment fragment = diagnostics();
        HookStatusPreference preference = row(fragment, HookStatusPreference.class);
        HookStatus.bound("comments", "like_button");
        assertTrue(preference.getSummary().toString().contains(
                "One area was checked and everything it needs is here."));
        HookStatus.bound("inbox", "itemView");
        assertTrue(preference.getSummary().toString().contains(
                "2 areas were checked and everything they need is here."));
        HookStatus.missingViewId("inbox", "bo5");
        assertTrue(preference.getSummary().toString().contains(
                "Something is missing from inbox. Tap for the whole report."));

        clickRow(fragment, HookStatusPreference.class);
        AlertDialog report = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(report);
        String message = Shadows.shadowOf(report).getMessage().toString();
        assertTrue(message, message.contains("comments: 1 found, 0 missing"));
        assertTrue(message, message.contains("inbox: 1 found, 1 missing. First missing: view id 'bo5'"));
        report.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse(report.isShowing());
    }

    @Test public void theAboutRowOpensTheProjectsSourceAndReleases() {
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_hub", "ABOUT");
        TikTokPreferenceFragment fragment = attach(arguments);
        clickRow(fragment, MorpheTikTokAboutPreference.class);
        Intent opened = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertNotNull("clicking the about row launched nothing", opened);
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals("https://github.com/icysymmetra/tiktok-patches-for-morphe", opened.getDataString());
        assertTrue((opened.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    private TikTokPreferenceFragment diagnostics() {
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "DIAGNOSTICS");
        return attach(arguments);
    }

    private TikTokPreferenceFragment attach(Bundle arguments) {
        controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        controller.visible();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
        idle();
        return fragment;
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private static List<String> titles(TikTokPreferenceFragment fragment) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < fragment.getPreferenceScreen().getPreferenceCount(); i++) {
            CharSequence title = fragment.getPreferenceScreen().getPreference(i).getTitle();
            if (title != null) result.add(title.toString());
        }
        return result;
    }

    private static <T extends Preference> T row(TikTokPreferenceFragment fragment, Class<T> type) {
        ListView list = fragment.getView().findViewById(android.R.id.list);
        for (int i = 0; i < list.getCount(); i++) {
            if (type.isInstance(list.getItemAtPosition(i))) return type.cast(list.getItemAtPosition(i));
        }
        throw new AssertionError("The page did not install " + type.getSimpleName());
    }

    private static void clickRow(TikTokPreferenceFragment fragment, Class<? extends Preference> type) {
        ListView list = fragment.getView().findViewById(android.R.id.list);
        for (int i = 0; i < list.getCount(); i++) {
            if (type.isInstance(list.getItemAtPosition(i))) {
                assertTrue(list.performItemClick(list.getAdapter().getView(i, null, list),
                        i, list.getItemIdAtPosition(i)));
                idle();
                return;
            }
        }
        throw new AssertionError("The page did not install " + type.getSimpleName());
    }

    private static void clickTitle(TikTokPreferenceFragment fragment, String title) {
        ListView list = fragment.getView().findViewById(android.R.id.list);
        for (int i = 0; i < list.getCount(); i++) {
            Object item = list.getItemAtPosition(i);
            if (!(item instanceof Preference)) continue;
            CharSequence itemTitle = ((Preference) item).getTitle();
            if (itemTitle == null || !title.contentEquals(itemTitle)) continue;
            assertTrue(list.performItemClick(list.getAdapter().getView(i, null, list),
                    i, list.getItemIdAtPosition(i)));
            idle();
            return;
        }
        throw new AssertionError("The page did not install " + title);
    }
}

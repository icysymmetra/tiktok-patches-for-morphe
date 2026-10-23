/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.navigation.BottomNavigationTabOptions;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/**
 * Save has to be reachable.
 *
 * <p>The dialog used to be one column: a 20sp title, a two-sentence helper, a list that is
 * never shorter than 220dp, and its own Save and Cancel at the bottom. At twice the text size
 * on a short phone that is taller than the screen, and the platform's custom panel clips rather
 * than scrolls, so the actions were what got cut. The actions are the platform's own now,
 * because it keeps its button panel on screen and shrinks the custom panel instead.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class TabSelectionDialogFitTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class Host extends android.preference.PreferenceActivity {}

    @After public void tearDown() {
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.resetToDefault();
        Settings.BOTTOM_NAVIGATION_TABS.resetToDefault();
        Settings.SIM_SPOOF_ISO.resetToDefault();
        Settings.SIMSPOOF_MCCMNC.resetToDefault();
        Settings.SIMSPOOF_OP_NAME.resetToDefault();
    }

    @Test @Config(qualifiers = "w360dp-h640dp-night-mdpi", fontScale = 2)
    public void saveAndCancelStayOnScreenAtTwiceTheTextSizeInTheDark() throws Exception {
        assertTheActionsAreReachable();
    }

    @Test @Config(qualifiers = "w360dp-h640dp-night-mdpi", fontScale = 2)
    public void theSimPresetActionsStayOnScreenAtTwiceTheTextSize() throws Exception {
        // The same shape and the same repair: Clear preset and Cancel sat under a 320dp list.
        try (var owner = Robolectric.buildActivity(Host.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            assertEquals("the font scale did not take", 2f,
                    activity.getResources().getConfiguration().fontScale, 0.01f);

            SimPresetPreference row = new SimPresetPreference(activity,
                    new InputTextPreference(activity, "ISO", "", Settings.SIM_SPOOF_ISO),
                    new InputTextPreference(activity, "MCC/MNC", "", Settings.SIMSPOOF_MCCMNC),
                    new InputTextPreference(activity, "Operator", "", Settings.SIMSPOOF_OP_NAME));
            Method openPresets = SimPresetPreference.class.getDeclaredMethod("showPresetDialog");
            openPresets.setAccessible(true);
            openPresets.invoke(row);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull("the preset dialog did not open", dialog);
            View decor = dialog.getWindow().getDecorView();
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            decor.measure(
                    View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST));
            decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());

            View clear = decor.findViewWithTag("sim_preset_clear");
            View cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            assertNotNull("there is no way back to no preset", clear);
            assertNotNull("the preset dialog has no Cancel", cancel);
            for (View action : new View[]{clear, cancel}) {
                assertEquals(View.VISIBLE, action.getVisibility());
                assertTrue("an action is cut off at twice the text size",
                        bottomWithin(decor, action) <= metrics.heightPixels);
            }
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-notnight-mdpi", fontScale = 2)
    public void saveAndCancelStayOnScreenAtTwiceTheTextSizeInTheLight() throws Exception {
        assertTheActionsAreReachable();
    }

    private void assertTheActionsAreReachable() throws Exception {
        try (var owner = Robolectric.buildActivity(Host.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            // Without this the rest measures ordinary text and finds, correctly, that it fits.
            assertEquals("the font scale did not take", 2f,
                    activity.getResources().getConfiguration().fontScale, 0.01f);

            // Every tab TikTok can show, so the list is as tall as it ever gets.
            Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save(String.join(",",
                    BottomNavigationTabOptions.HOME, BottomNavigationTabOptions.FRIENDS,
                    BottomNavigationTabOptions.PUBLISH, BottomNavigationTabOptions.INBOX,
                    BottomNavigationTabOptions.PROFILE, BottomNavigationTabOptions.MALL));

            TabSelectionPreference preference =
                    new TabSelectionPreference(activity, Settings.BOTTOM_NAVIGATION_TABS, true);
            Method show = TabSelectionPreference.class.getDeclaredMethod("showSelectionDialog");
            show.setAccessible(true);
            show.invoke(preference);

            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull("the tab dialog did not open", dialog);
            View decor = dialog.getWindow().getDecorView();
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            decor.measure(
                    View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST));
            decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());

            View save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            View cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            View selectAll = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            assertNotNull("the dialog has no Save", save);
            assertNotNull("the dialog has no Cancel", cancel);
            assertNotNull("the dialog has no Select every tab", selectAll);

            int screen = metrics.heightPixels;
            for (View action : new View[]{save, cancel, selectAll}) {
                String label = String.valueOf(((android.widget.Button) action).getText());
                assertEquals(label + " is not on screen at all", View.VISIBLE,
                        action.getVisibility());
                assertTrue(label + " has no height at twice the text size",
                        action.getHeight() > 0);
                int bottom = bottomWithin(decor, action);
                assertTrue(label + " is cut off: its bottom is " + bottom
                        + " on a screen " + screen + " tall", bottom <= screen);
            }
        }
    }

    /** How far down {@code root} the bottom of {@code view} sits, following its parents up. */
    private static int bottomWithin(View root, View view) {
        int top = 0;
        View current = view;
        while (current != null && current != root) {
            top += current.getTop();
            ViewParent parent = current.getParent();
            current = parent instanceof ViewGroup ? (ViewGroup) parent : null;
        }
        return top + view.getHeight();
    }
}

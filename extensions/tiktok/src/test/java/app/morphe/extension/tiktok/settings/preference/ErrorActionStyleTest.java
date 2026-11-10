/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The page that appears when settings will not open has to say which way is forward.
 *
 * <p>Try again and Go back were drawn identically, so the only screen whose entire purpose is a
 * way out offered two choices that looked the same. The capture of that page is taken through a
 * bare test fragment, which supplies no palette, so the colouring is checked here instead.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ErrorActionStyleTest {

    /** A row shaped like the one the preference framework binds: a title inside a container. */
    private static LinearLayout row(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        TextView title = new TextView(activity);
        title.setId(android.R.id.title);
        row.addView(title);
        return row;
    }

    private static TextView titleOf(LinearLayout row) {
        return row.findViewById(android.R.id.title);
    }

    @Test public void tryAgainCarriesTheAccentAndGoBackDoesNot() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setIsDarkModeEnabled(true);

        AbstractPreferenceFragment.ErrorActionStyler styler =
                new TikTokPreferenceFragment().errorActionStyler();
        assertNotNull("the settings page hands the shared library no way to draw its actions",
                styler);

        LinearLayout primary = row(activity);
        LinearLayout secondary = row(activity);
        styler.style(primary, true);
        styler.style(secondary, false);

        assertEquals("the recovery action is not drawn in the accent",
                SettingsUi.accent(), titleOf(primary).getCurrentTextColor());
        assertNotEquals("both actions are drawn the same, which is what this fixes",
                titleOf(primary).getCurrentTextColor(),
                titleOf(secondary).getCurrentTextColor());
        assertEquals("the way out took the accent instead of the way forward",
                SettingsUi.textPrimary(), titleOf(secondary).getCurrentTextColor());
    }

    @Test public void theRecoveryActionIsTheHeavierOfTheTwo() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setIsDarkModeEnabled(false);

        AbstractPreferenceFragment.ErrorActionStyler styler =
                new TikTokPreferenceFragment().errorActionStyler();
        LinearLayout primary = row(activity);
        LinearLayout secondary = row(activity);
        styler.style(primary, true);
        styler.style(secondary, false);

        Typeface heavier = titleOf(primary).getTypeface();
        assertNotNull(heavier);
        assertEquals("the recovery action is not bold", Typeface.BOLD, heavier.getStyle());
        assertEquals("the way out should stay in the normal weight",
                Typeface.NORMAL, titleOf(secondary).getTypeface().getStyle());
    }

    /** The light theme has its own accent, so the styler must not be holding a fixed colour. */
    @Test public void theAccentFollowsTheTheme() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);

        AbstractPreferenceFragment.ErrorActionStyler styler =
                new TikTokPreferenceFragment().errorActionStyler();

        Utils.setIsDarkModeEnabled(true);
        LinearLayout dark = row(activity);
        styler.style(dark, true);

        Utils.setIsDarkModeEnabled(false);
        LinearLayout light = row(activity);
        styler.style(light, true);

        assertNotEquals("the recovery action is the same colour in both themes",
                titleOf(dark).getCurrentTextColor(), titleOf(light).getCurrentTextColor());
    }
}

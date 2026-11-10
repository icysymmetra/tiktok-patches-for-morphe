package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import com.bytedance.ies.ugc.aweme.commercialize.compliance.personalization.AdPersonalizationActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * TikTok targets SDK 36, so its windows are edge to edge and cannot opt out, and the two calls
 * the settings screen used to colour the bars with have done nothing since target 35.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@SuppressWarnings("deprecation")
public class SystemBarInsetsTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsUi.syncDarkMode(RuntimeEnvironment.getApplication());
    }

    @Test public void theBarsAndTheCutoutAreBothPaddedFor() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View view = new FrameLayout(activity);
        SystemBarInsets.applyTo(view);

        // A cutout on the left edge, which is where it lands held sideways, and bars top and
        // bottom. Reading only the system bars would leave the first column under the cutout.
        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 48))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(36, 0, 0, 0))
                .build();
        view.dispatchApplyWindowInsets(insets);

        assertEquals("the status bar", 24, view.getPaddingTop());
        assertEquals("the navigation bar", 48, view.getPaddingBottom());
        assertEquals("the display cutout", 36, view.getPaddingLeft());
        assertEquals(0, view.getPaddingRight());
    }

    @Test public void theInsetsAreHandedOnRatherThanEatenOnTheWayPast() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout outer = new FrameLayout(activity);
        View inner = new View(activity);
        outer.addView(inner);
        SystemBarInsets.applyTo(outer);

        final int[] seenByInner = {-1};
        inner.setOnApplyWindowInsetsListener((target, insets) -> {
            seenByInner[0] = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            return insets;
        });
        outer.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 48))
                .build());

        assertEquals("an overlay above this view was left with nothing to read", 48, seenByInner[0]);
    }

    @Config(sdk = 29)
    @Test public void aBuildWithoutTheTypedInsetsApiIsStillPadded() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View view = new FrameLayout(activity);
        SystemBarInsets.applyTo(view);

        view.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setSystemWindowInsets(Insets.of(0, 24, 0, 48))
                .build());

        assertEquals("the status bar", 24, view.getPaddingTop());
        assertEquals("the navigation bar", 48, view.getPaddingBottom());
    }

    @Test public void theSettingsScreenItselfMovesOutFromUnderTheBars() {
        Intent intent = new Intent().putExtra("morphe", true);
        Activity activity = Robolectric.buildActivity(AdPersonalizationActivity.class, intent)
                .setup().get();
        assertTrue("the settings screen did not open at all",
                TikTokActivityHook.initialize((AdPersonalizationActivity) activity));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        ViewGroup content = activity.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        assertNotNull("the settings screen has no root view", root);

        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 48))
                .build());

        assertEquals("the first row still sits under the status bar", 24, root.getPaddingTop());
        assertEquals("the last row still sits under the navigation bar", 48, root.getPaddingBottom());
        // The padding only moves the rows. Without a background here the padded strip would show
        // whatever TikTok's own theme paints, which is what the dead colour calls used to hide.
        assertTrue("the strip behind the bars is not painted",
                root.getBackground() instanceof ColorDrawable);
        assertEquals("the strip behind the bars is the wrong colour",
                SettingsUi.background(), ((ColorDrawable) root.getBackground()).getColor());
    }

    @Config(qualifiers = "notnight")
    @Test public void aLightActivityPaintsTheBarsLightDespiteAStaleDarkOverride() {
        assertThemeAfterOpening(true, SettingsUi.LIGHT_BACKGROUND, true);
    }

    @Config(qualifiers = "night")
    @Test public void aDarkActivityPaintsTheBarsDarkDespiteAStaleLightOverride() {
        assertThemeAfterOpening(false, SettingsUi.DARK_BACKGROUND, false);
    }

    private static void assertThemeAfterOpening(boolean staleDark, int background, boolean lightBars) {
        Utils.setIsDarkModeEnabled(staleDark);
        Intent intent = new Intent().putExtra("morphe", true);
        AdPersonalizationActivity activity = Robolectric
                .buildActivity(AdPersonalizationActivity.class, intent).setup().get();
        assertTrue(TikTokActivityHook.initialize(activity));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        ViewGroup content = activity.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        assertEquals("the activity theme must decide the fragment colour", background, SettingsUi.background());
        assertEquals("the strip was painted before the activity theme was known",
                background, ((ColorDrawable) root.getBackground()).getColor());
        int visibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        assertEquals("status icons must contrast with the strip", lightBars,
                (visibility & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0);
        assertEquals("navigation icons must contrast with the strip", lightBars,
                (visibility & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) != 0);
    }
}

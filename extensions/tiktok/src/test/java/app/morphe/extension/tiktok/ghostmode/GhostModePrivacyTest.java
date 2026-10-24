/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
import org.robolectric.shadows.ShadowAlertDialog;

/** A saved privacy switch must disclose a detected local reporting failure. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class GhostModePrivacyTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private boolean installed;
    private boolean chosen;

    @Before public void setUp() throws Exception {
        installed = SettingsStatus.ghostModeEnabled;
        chosen = Settings.GHOST_MODE.savedValue();
        SettingsStatus.ghostModeEnabled = true;
        Settings.GHOST_MODE.save(true);
        resetProcessEvidence();
        HookStatus.clear();
    }

    @After public void tearDown() throws Exception {
        PausedProcess.set(false);
        idle();
        SettingsStatus.ghostModeEnabled = installed;
        Settings.GHOST_MODE.save(chosen);
        resetProcessEvidence();
        HookStatus.clear();
        Utils.setActivity(null);
    }

    @Test public void aDetectedFailureUpdatesTheOpenPrivacyRowAndKeepsTheSwitch() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment page = privacy(owner.get());
            Preference row = page.findPreference(Settings.GHOST_MODE.key);
            View shown = row.getView(null, (ViewGroup) page.getView());
            assertTrue("the untouched process was not identified", summary(row).contains("hasn't seen TikTok send any reports since it started."));
            failStoryGetter();
            idle();
            assertTrue("the open row never learned about the failure", summary(row).contains("may still be reported"));
            assertTrue(Settings.GHOST_MODE.savedValue());
            shown = row.getView(shown, (ViewGroup) page.getView());
            assertTrue(String.valueOf(shown.getStateDescription()).contains("Problem"));
            TextView text = shown.findViewById(android.R.id.summary);
            assertTrue(text.getText().toString().contains("may still be reported"));
            UiCapture.save(page.getView(), "ghost-warning-dark.png", 480, 960);
        }
    }

    @Test public void clearingDiagnosticsAndTogglingCannotEraseADetectedFailure() {
        failStoryGetter();
        HookStatus.clear();
        GhostMode.shouldBlockProfileView();
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Preference row = privacy(owner.get()).findPreference(Settings.GHOST_MODE.key);
            assertTrue(summary(row).contains("may still be reported"));
            Settings.GHOST_MODE.save(false);
            row.getView(null, null);
            assertTrue(summary(row).contains("Off."));
            Settings.GHOST_MODE.save(true);
            row.getView(null, null);
            assertTrue(summary(row).contains("may still be reported"));
        }
    }

    @Test public void aBlockedCallNeverClaimsVerifiedViewerListPrivacy() {
        GhostMode.shouldBlockProfileView();
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Preference row = privacy(owner.get()).findPreference(Settings.GHOST_MODE.key);
            assertTrue(summary(row), summary(row).contains("It hasn't confirmed that you stay off viewer lists."));
            PausedProcess.set(true);
            row.getView(null, null);
            assertTrue(summary(row), summary(row).contains("paused"));
        }
    }

    @Test @Config(qualifiers = "w320dp-h800dp-notnight-mdpi", fontScale = 2f)
    public void theWarningAndItsDiagnosticsActionRemainReadableAtLargeText() throws Exception {
        failStoryGetter();
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment page = privacy(owner.get());
            Preference row = page.findPreference(Settings.GHOST_MODE.key);
            View shown = row.getView(null, (ViewGroup) page.getView());
            shown.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            shown.layout(0, 0, 320, shown.getMeasuredHeight());
            UiCapture.save(page.getView(), "ghost-warning-light-large.png", 320, 960);
            TextView text = shown.findViewById(android.R.id.summary);
            assertTrue(text.getText().toString().contains("may still be reported"));
            assertTrue(text.getMeasuredWidth() > 0);
            Preference diagnostics = page.findPreference("action_ghost_mode_diagnostics");
            assertNotNull("no adjacent route to diagnostics", diagnostics);
            assertTrue(diagnostics.getOnPreferenceClickListener().onPreferenceClick(diagnostics));
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            dialog.dismiss();
        }
    }

    private static TikTokPreferenceFragment privacy(Activity activity) {
        Utils.setContext(activity);
        Utils.setActivity(activity);
        TikTokPreferenceFragment page = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "PRIVACY");
        page.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, page).commit();
        activity.getFragmentManager().executePendingTransactions();
        idle();
        return page;
    }

    private static String summary(Preference row) {
        assertNotNull(row);
        return row.getSummary().toString();
    }

    private static void failStoryGetter() {
        assertFalse(GhostMode.shouldBlockStoryStats(new Aweme() {
            @Override public boolean getIsTikTokStory() { throw new NoSuchMethodError("getIsTikTokStory"); }
        }));
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    /** Process-local evidence resets only at process start, simulated here without a shipping reset hook. */
    private static void resetProcessEvidence() throws Exception {
        for (Field field : GhostMode.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == boolean.class) {
                field.setAccessible(true);
                field.setBoolean(null, false);
            }
        }
    }
}

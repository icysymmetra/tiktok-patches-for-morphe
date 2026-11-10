/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The names under the bottom tab icons: hidden or renamed, and held there before each frame. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BottomTabLabelsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private FrameLayout bar;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        bar = new FrameLayout(activity);
        activity.setContentView(bar);
    }

    @After public void tearDown() {
        Settings.HIDE_BOTTOM_TAB_LABELS.resetToDefault();
        Settings.BOTTOM_TAB_NAME_HOME.resetToDefault();
        Settings.BOTTOM_TAB_NAME_FRIENDS.resetToDefault();
        Settings.BOTTOM_TAB_NAME_INBOX.resetToDefault();
        Settings.BOTTOM_TAB_NAME_PROFILE.resetToDefault();
        Settings.BOTTOM_TAB_NAME_SHOP.resetToDefault();
        PausedProcess.set(false);
        HookStatus.clear();
    }

    /** A tab icon on the bar, built the way TikTok builds it: tag first, name view later. */
    private FrameLayout icon(String tag) {
        FrameLayout icon = new FrameLayout(activity);
        BottomTabLabels.tabCreated(icon, tag);
        bar.addView(icon);
        return icon;
    }

    private TextView label(FrameLayout icon, String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        icon.addView(label);
        BottomTabLabels.labelCreated(icon, label);
        return label;
    }

    private void frame() {
        bar.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test public void withEverythingOffTheNameIsTikToks() {
        TextView label = label(icon("HOME"), "Home");
        frame();
        assertEquals("Home", label.getText().toString());
        assertEquals(View.VISIBLE, label.getVisibility());
    }

    @Test public void aNameOfYourOwnReplacesTikToksAndHoldsThroughItsRewrites() {
        Settings.BOTTOM_TAB_NAME_HOME.save("  Feed  ");
        TextView label = label(icon("HOME"), "Home");
        assertEquals("Feed", label.getText().toString());
        // A tab that writes its name straight into the view, as some kinds of tab do.
        label.setText("Home");
        frame();
        assertEquals("Feed", label.getText().toString());
    }

    @Test public void clearingTheNamePutsBackWhatTikTokLastShowed() {
        Settings.BOTTOM_TAB_NAME_HOME.save("Feed");
        TextView label = label(icon("HOME"), "Home");
        label.setText("Start");
        frame();
        assertEquals("Feed", label.getText().toString());

        Settings.BOTTOM_TAB_NAME_HOME.save("");
        frame();
        assertEquals("Start", label.getText().toString());
        frame();
        assertEquals("Start", label.getText().toString());
    }

    @Test public void changingTheNameKeepsTikToksForLater() {
        Settings.BOTTOM_TAB_NAME_INBOX.save("Chats");
        TextView label = label(icon("NOTIFICATION"), "Inbox");
        Settings.BOTTOM_TAB_NAME_INBOX.save("Messages");
        frame();
        assertEquals("Messages", label.getText().toString());
        Settings.BOTTOM_TAB_NAME_INBOX.resetToDefault();
        frame();
        assertEquals("Inbox", label.getText().toString());
    }

    @Test public void eachTabTakesItsOwnName() {
        assertSame(Settings.BOTTOM_TAB_NAME_HOME, setting("HOME"));
        assertSame(Settings.BOTTOM_TAB_NAME_FRIENDS, setting("homepage_friends"));
        assertSame(Settings.BOTTOM_TAB_NAME_INBOX, setting("NOTIFICATION"));
        assertSame(Settings.BOTTOM_TAB_NAME_PROFILE, setting("USER"));
        assertSame(Settings.BOTTOM_TAB_NAME_SHOP, setting("mall"));
        assertNull(setting("PUBLISH"));
        assertNull(setting("SERIES_DISCOVER"));
        assertNull(setting(null));

        Settings.BOTTOM_TAB_NAME_PROFILE.save("Me");
        TextView home = label(icon("HOME"), "Home");
        TextView profile = label(icon("USER"), "Profile");
        TextView other = label(icon("SERIES_DISCOVER"), "Series");
        frame();
        assertEquals("Home", home.getText().toString());
        assertEquals("Me", profile.getText().toString());
        assertEquals("Series", other.getText().toString());
    }

    private static Object setting(String tag) {
        return BottomTabLabels.settingFor(BottomNavigationTabOptions.normalizeRuntimeTag(tag));
    }

    @Test public void hiddenTheNameStaysGoneAndComesBackWhenTheSwitchGoesOff() {
        Settings.HIDE_BOTTOM_TAB_LABELS.save(true);
        TextView label = label(icon("HOME"), "Home");
        assertEquals(View.GONE, label.getVisibility());
        label.setVisibility(View.VISIBLE);
        frame();
        assertEquals(View.GONE, label.getVisibility());

        Settings.HIDE_BOTTOM_TAB_LABELS.save(false);
        frame();
        assertEquals(View.VISIBLE, label.getVisibility());
    }

    @Test public void aNameTikTokHidItselfStaysHiddenWithTheSwitchOff() {
        TextView label = label(icon("PUBLISH"), "");
        label.setVisibility(View.GONE);
        frame();
        assertEquals(View.GONE, label.getVisibility());
    }

    @Test public void aNameWithNoIconBehindItIsStillHidden() {
        Settings.HIDE_BOTTOM_TAB_LABELS.save(true);
        Settings.BOTTOM_TAB_NAME_HOME.save("Feed");
        TextView label = new TextView(activity);
        label.setText("Home");
        bar.addView(label);
        BottomTabLabels.labelCreated(null, label);
        assertEquals(View.GONE, label.getVisibility());
        assertEquals("Home", label.getText().toString());
    }

    @Test public void aNameMadeBeforeItsTabIsOnScreenIsHeldOnceItIs() {
        Settings.BOTTOM_TAB_NAME_HOME.save("Feed");
        FrameLayout icon = new FrameLayout(activity);
        BottomTabLabels.tabCreated(icon, "HOME");
        TextView label = new TextView(activity);
        label.setText("Home");
        icon.addView(label);
        BottomTabLabels.labelCreated(icon, label);
        assertEquals("Feed", label.getText().toString());

        label.setText("Home");
        bar.addView(icon);
        frame();
        assertEquals("Feed", label.getText().toString());

        // Off the screen it's left alone, and back on it's held again.
        bar.removeView(icon);
        label.setText("Home");
        frame();
        assertEquals("Home", label.getText().toString());
        bar.addView(icon);
        frame();
        assertEquals("Feed", label.getText().toString());
    }

    @Test public void pausedEverythingIsTikToks() {
        Settings.HIDE_BOTTOM_TAB_LABELS.save(true);
        Settings.BOTTOM_TAB_NAME_HOME.save("Feed");
        PausedProcess.set(true);
        TextView label = label(icon("HOME"), "Home");
        frame();
        assertEquals("Home", label.getText().toString());
        assertEquals(View.VISIBLE, label.getVisibility());
    }

    @Test public void theExportNamesTheFamilyOnceItWasReached() {
        label(icon("HOME"), "Home");
        assertTrue(HookStatus.report().toString(),
                HookStatus.report().stream().anyMatch(line -> line.startsWith(BottomTabLabels.FAMILY + ": 2 found, 0 missing")));
    }
}

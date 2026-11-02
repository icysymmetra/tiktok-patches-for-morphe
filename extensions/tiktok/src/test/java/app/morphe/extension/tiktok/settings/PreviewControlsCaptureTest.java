/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Looper;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;

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
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29, qualifiers = "en-rUS-w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class PreviewControlsCaptureTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();
    private static BooleanSetting[] controls() {
        return new BooleanSetting[] {Settings.DM_TRANSLATION_PREVIEW, Settings.RECEIVED_DM_TRANSLATION_PREVIEW,
                Settings.LOCAL_PROFILE_VERIFIED_BADGE, Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE};
    }

    @Before public void setUp() throws Exception {
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true); statuses.put(field, field.getBoolean(null)); field.setBoolean(null, false);
            }
        }
        BaseSettings.PAUSED.save(false); BaseSettings.SAFE_MODE.save(false); PausedProcess.set(false);
        for (BooleanSetting setting : controls()) { assertFalse(setting.defaultValue); setting.resetToDefault(); }
    }

    @After public void tearDown() throws Exception {
        for (BooleanSetting setting : controls()) setting.resetToDefault();
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) entry.getKey().setBoolean(null, entry.getValue());
        PausedProcess.set(false); Utils.setIsDarkModeEnabled(true);
    }

    @Test public void inboxPreviewRowsAreReachableOffAndClickableInDarkMode() throws Exception { inbox(true); }
    @Test @Config(qualifiers = "en-rUS-w480dp-h960dp-notnight-mdpi")
    public void inboxPreviewRowsAreReachableOffAndClickableInLightMode() throws Exception { inbox(false); }
    @Test public void ownProfileRowsAreReachableOffAndClickableInDarkMode() throws Exception { profile(true); }
    @Test @Config(qualifiers = "en-rUS-w480dp-h960dp-notnight-mdpi")
    public void ownProfileRowsAreReachableOffAndClickableInLightMode() throws Exception { profile(false); }

    @Test public void inboxAvailabilityAndRowsRequireTheirOwnSuccessfulHooks() {
        SettingsStatus.commentTranslationEnabled = true;
        assertFalse("comment translation alone cannot expose an empty Inbox page", InboxPreferenceCategory.isAvailable());
        for (boolean outgoing : new boolean[] {true, false}) {
            SettingsStatus.dmTranslationPreviewEnabled = outgoing;
            SettingsStatus.receivedDmTranslationPreviewEnabled = !outgoing;
            assertTrue(InboxPreferenceCategory.isAvailable());
            try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
                Activity activity = owner.get(); configure(activity, true);
                TikTokPreferenceFragment page = section(activity, "interface");
                assertEquals(outgoing, page.findPreference(Settings.DM_TRANSLATION_PREVIEW.key) != null);
                assertEquals(!outgoing, page.findPreference(Settings.RECEIVED_DM_TRANSLATION_PREVIEW.key) != null);
            }
        }
    }

    @Test public void ownProfileRowsAreAbsentWithoutTheNativeProfileHooks() {
        assertFalse(ExtensionPreferenceCategory.isAvailable());
        SettingsStatus.systemFontEnabled = true;
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get(); configure(activity, true);
            TikTokPreferenceFragment page = section(activity, "behavior");
            assertNull(page.findPreference(Settings.LOCAL_PROFILE_VERIFIED_BADGE.key));
            assertNull(page.findPreference(Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE.key));
        }
        SettingsStatus.followStatusEnabled = true;
        assertTrue(ExtensionPreferenceCategory.isAvailable());
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get(); configure(activity, true);
            TikTokPreferenceFragment page = section(activity, "behavior");
            assertNotNull(page.findPreference(Settings.LOCAL_PROFILE_VERIFIED_BADGE.key));
            assertNotNull(page.findPreference(Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE.key));
        }
    }

    private static void inbox(boolean dark) throws Exception {
        SettingsStatus.commentTranslationEnabled = true;
        SettingsStatus.dmTranslationPreviewEnabled = true;
        SettingsStatus.receivedDmTranslationPreviewEnabled = true;
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get(); configure(activity, dark);
            TikTokPreferenceFragment page = section(activity, "interface");
            SwitchPreference received = toggle(page, Settings.RECEIVED_DM_TRANSLATION_PREVIEW, "Received message translation");
            SwitchPreference outgoing = toggle(page, Settings.DM_TRANSLATION_PREVIEW, "DM translation preview");
            assertEquals("Preview a translation from a received text message's menu. The original message stays unchanged.", received.getSummary().toString());
            assertEquals("Preview a translation of your draft before sending it.", outgoing.getSummary().toString());
            capture(activity, page, Settings.RECEIVED_DM_TRANSLATION_PREVIEW.key,
                    "pages/" + theme(dark) + "/interface-translation-previews.png");
            exerciseToggle(activity, page, Settings.RECEIVED_DM_TRANSLATION_PREVIEW);
            exerciseToggle(activity, page, Settings.DM_TRANSLATION_PREVIEW);
        }
    }

    private static void profile(boolean dark) throws Exception {
        SettingsStatus.followStatusEnabled = true;
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get(); configure(activity, dark);
            TikTokPreferenceFragment page = section(activity, "behavior");
            SwitchPreference badge = toggle(page, Settings.LOCAL_PROFILE_VERIFIED_BADGE, "Local verified badge");
            SwitchPreference notice = toggle(page, Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE, "Hide your account warning");
            assertTrue(badge.getSummary().toString().contains("Other people see your real status."));
            assertTrue(notice.getSummary().toString().contains("TikTok's restrictions and appeals still apply."));
            assertTrue(badge.getSummary().toString().contains("Restart"));
            assertTrue(notice.getSummary().toString().contains("Restart"));
            capture(activity, page, Settings.LOCAL_PROFILE_VERIFIED_BADGE.key,
                    "pages/" + theme(dark) + "/own-profile-appearance.png");
            exerciseToggle(activity, page, Settings.LOCAL_PROFILE_VERIFIED_BADGE);
            exerciseToggle(activity, page, Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE);
        }
    }

    private static void configure(Activity activity, boolean dark) {
        Utils.setContext(activity); SettingsUi.syncDarkMode(activity); assertEquals(dark, SettingsUi.isDarkMode());
    }
    private static TikTokPreferenceFragment section(Activity activity, String section) {
        TikTokPreferenceFragment home = new TikTokPreferenceFragment();
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, home).commit(); settle(activity);
        TikTokPreferenceFragment page = open(activity, home, "section_" + section);
        assertEquals(section.toUpperCase(java.util.Locale.ROOT), page.getArguments().getString("morphe_settings_section"));
        return page;
    }
    private static TikTokPreferenceFragment open(Activity activity, TikTokPreferenceFragment parent, String key) {
        Preference route = parent.findPreference(key); assertNotNull("missing reachable route " + key, route);
        assertTrue(route.isEnabled()); assertNotNull(route.getOnPreferenceClickListener());
        assertTrue(route.getOnPreferenceClickListener().onPreferenceClick(route)); settle(activity);
        return (TikTokPreferenceFragment) activity.getFragmentManager().findFragmentById(android.R.id.content);
    }
    private static SwitchPreference toggle(TikTokPreferenceFragment page, BooleanSetting setting, String title) {
        Preference found = page.findPreference(setting.key); assertTrue(found instanceof SwitchPreference);
        SwitchPreference toggle = (SwitchPreference) found;
        assertEquals(title, toggle.getTitle().toString()); assertTrue(toggle.isEnabled()); assertFalse(toggle.isChecked());
        assertFalse(setting.savedValue()); return toggle;
    }
    private static int position(ListView list, String key) {
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            Object item = list.getAdapter().getItem(i);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) return i;
        }
        throw new AssertionError("missing preference row " + key);
    }
    private static void capture(Activity activity, TikTokPreferenceFragment page, String first, String name) throws Exception {
        ListView list = page.getView().findViewById(android.R.id.list);
        list.setSelectionFromTop(Math.max(0, position(list, first) - 4), 0); settle(activity);
        for (BooleanSetting setting : controls()) {
            Preference preference = page.findPreference(setting.key); if (preference == null) continue;
            int index = position(list, setting.key); View row = list.getChildAt(index - list.getFirstVisiblePosition());
            assertNotNull("control is outside the captured viewport " + setting.key, row);
            TextView title = row.findViewById(android.R.id.title); assertNotNull(title);
            assertEquals(preference.getTitle().toString(), title.getText().toString());
            assertTrue(title.getWidth() > 0); assertTrue(title.getHeight() > 0);
        }
        UiCapture.save(page.getView(), name, 480, 960);
    }
    private static void exerciseToggle(Activity activity, TikTokPreferenceFragment page, BooleanSetting setting) {
        ListView list = page.getView().findViewById(android.R.id.list);
        SwitchPreference toggle = (SwitchPreference) page.findPreference(setting.key);
        for (boolean expected : new boolean[] {true, false}) {
            int index = position(list, setting.key);
            list.setSelectionFromTop(index, 0); settle(activity);
            View row = list.getChildAt(index - list.getFirstVisiblePosition()); assertNotNull(row);
            assertTrue(list.performItemClick(row, index, list.getAdapter().getItemId(index))); settle(activity);
            assertEquals(expected, toggle.isChecked()); assertEquals(expected, setting.savedValue());
        }
    }
    private static void settle(Activity activity) {
        activity.getFragmentManager().executePendingTransactions(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 480, 960); Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static String theme(boolean dark) { return dark ? "dark" : "light"; }
}

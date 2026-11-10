/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Bring back TikTok's translation (#143). TikTok hides the translation rows on its Language page
 * when either of two gates answers true, and takes See translation off videos when
 * {@code feed_translation_reverse} is 1. The switch starts off, answers each gate false and the
 * reverse value 0 while it is on, never takes translation away itself, and leaves TikTok's answer
 * alone while Hushfeed is paused or before the settings context exists.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LanguagePageTranslationsTest {
    private static final String TITLE = "Bring back TikTok's translation";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
        PausedProcess.set(false);
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.SHOW_TRANSLATION_SETTINGS.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndOnlyItShowsTheRows() {
        assertEquals("show_translation_settings", Settings.SHOW_TRANSLATION_SETTINGS.key);
        assertEquals(Boolean.FALSE, Settings.SHOW_TRANSLATION_SETTINGS.defaultValue);
        assertTrue("the videos' answer is read once per process", Settings.SHOW_TRANSLATION_SETTINGS.rebootApp);

        assertTrue("off, TikTok hides them", LanguagePageTranslations.hidden(true));
        assertFalse("off, TikTok shows them", LanguagePageTranslations.hidden(false));

        Settings.SHOW_TRANSLATION_SETTINGS.save(true);
        assertFalse(LanguagePageTranslations.hidden(true));
        assertFalse("a gate TikTok already opened stays open", LanguagePageTranslations.hidden(false));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("language page translations"));
    }

    @Test
    public void aPausedHushfeedLeavesTikTokAlone() {
        Settings.SHOW_TRANSLATION_SETTINGS.save(true);
        PausedProcess.set(true);
        assertTrue(LanguagePageTranslations.hidden(true));
        PausedProcess.set(false);
        assertFalse(LanguagePageTranslations.hidden(true));
    }

    @Test
    public void withoutAContextTheGateIsTikToks() {
        Settings.SHOW_TRANSLATION_SETTINGS.save(true);
        Utils.setContext(null);
        try {
            assertTrue(LanguagePageTranslations.hidden(true));
        } finally {
            Utils.setContext(RuntimeEnvironment.getApplication());
        }
        assertFalse(LanguagePageTranslations.hidden(true));
    }

    @Test
    public void onlyAOneTakesTranslationOffVideos() {
        assertEquals("off, TikTok's 1 stands", 1, LanguagePageTranslations.feedReverse(1));
        assertEquals(0, LanguagePageTranslations.feedReverse(0));

        Settings.SHOW_TRANSLATION_SETTINGS.save(true);
        assertEquals(0, LanguagePageTranslations.feedReverse(1));
        assertEquals("a value that already leaves translation on passes through", 2, LanguagePageTranslations.feedReverse(2));
        assertEquals(0, LanguagePageTranslations.feedReverse(0));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("video translations"));

        PausedProcess.set(true);
        assertEquals("paused, TikTok's 1 stands", 1, LanguagePageTranslations.feedReverse(1));
        PausedProcess.set(false);
        Utils.setContext(null);
        try {
            assertEquals("no context, TikTok's 1 stands", 1, LanguagePageTranslations.feedReverse(1));
        } finally {
            Utils.setContext(RuntimeEnvironment.getApplication());
        }
    }

    /** The row shows on the Comments page where the patch hooked either part, and says which. */
    @Test
    public void theRowShowsOnlyWhereTheGatesWereHooked() {
        boolean page = SettingsStatus.languagePageTranslationsEnabled;
        boolean videos = SettingsStatus.feedTranslationsEnabled;
        try (var controller = Robolectric.buildActivity(DoNotAutoTranslateTest.TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.languagePageTranslationsEnabled = false;
            SettingsStatus.feedTranslationsEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, without);
            assertNull("the row showed on a build where nothing was hooked",
                    without.findPreference(Settings.SHOW_TRANSLATION_SETTINGS.key));

            SettingsStatus.feedTranslationsEnabled = true;
            assertTrue("the Comments page opens for this row alone", CommentsPreferenceCategory.isAvailable());
            String videosOnly = summaryOn(activity);
            assertTrue(videosOnly, videosOnly.contains("See translation") && !videosOnly.contains("Language page"));

            SettingsStatus.feedTranslationsEnabled = false;
            SettingsStatus.languagePageTranslationsEnabled = true;
            assertTrue("the Comments page opens for this row alone", CommentsPreferenceCategory.isAvailable());
            String pageOnly = summaryOn(activity);
            assertTrue(pageOnly, pageOnly.contains("Language page") && !pageOnly.contains("See translation"));

            SettingsStatus.feedTranslationsEnabled = true;
            String both = summaryOn(activity);
            assertTrue(both, both.contains("Language page") && both.contains("See translation"));
        } finally {
            SettingsStatus.languagePageTranslationsEnabled = page;
            SettingsStatus.feedTranslationsEnabled = videos;
        }
    }

    private static String summaryOn(DoNotAutoTranslateTest.TestActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new CommentsPreferenceCategory(activity, screen);
        Preference row = screen.findPreference(Settings.SHOW_TRANSLATION_SETTINGS.key);
        assertNotNull(row);
        assertEquals(TITLE, String.valueOf(row.getTitle()));
        return String.valueOf(row.getSummary());
    }
}

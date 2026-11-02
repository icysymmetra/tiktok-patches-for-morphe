/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlyApplication;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Keep the app language's switch (#61). It starts off and a paused build answers off. TikTok's
 * reset runs in the application's attachBaseContext, before the settings context and before
 * ActivityThread has an application, so the answer is read from the saved file through the
 * context the reset is handed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AppLanguageTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private final Locale defaultBefore = Locale.getDefault();
    private final AppLanguage.Language languageBefore = AppLanguage.language;

    /** TikTok's language calls, recorded. */
    private static final class FakeLanguage implements AppLanguage.Language {
        Locale picked;
        Locale strings;
        int applied;
        final List<Locale> loaded = new ArrayList<>();
        RuntimeException failure;

        @Override public Locale picked(Context context) {
            if (failure != null) throw failure;
            return picked;
        }

        @Override public void apply(Context context) {
            applied++;
        }

        @Override public Locale strings() {
            return strings;
        }

        @Override public void loadStrings(Locale locale) {
            loaded.add(locale);
            strings = locale;
        }
    }

    @After public void tearDown() {
        Locale.setDefault(defaultBefore);
        AppLanguage.language = languageBefore;
        PausedProcess.set(false);
        EarlyApplication.reset();
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.KEEP_APP_LANGUAGE.save(false);
        saveInTheFile(Settings.KEEP_APP_LANGUAGE.key, false);
        SettingsStatus.appLanguageEnabled = false;
    }

    /** The early read opens the application's own preferences file, as in PasskeySignInTest. */
    private static void saveInTheFile(String key, boolean value) {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit();
    }

    @Test public void theSwitchStartsOffAndOnlyItKeepsTheLanguage() {
        Context application = RuntimeEnvironment.getApplication();
        assertEquals(Boolean.FALSE, Settings.KEEP_APP_LANGUAGE.defaultValue);
        assertEquals(AppLanguage.SWITCH_KEY, Settings.KEEP_APP_LANGUAGE.key);
        assertFalse("the reset runs once a start, so nothing to restart for", Settings.KEEP_APP_LANGUAGE.rebootApp);

        assertFalse("off, TikTok's reset runs as it ships", AppLanguage.keepChosenLanguage(application));

        Settings.KEEP_APP_LANGUAGE.save(true);
        assertTrue(AppLanguage.keepChosenLanguage(application));

        PausedProcess.set(true);
        assertFalse("paused, TikTok's reset runs as it ships", AppLanguage.keepChosenLanguage(application));
    }

    @Test public void beforeTheSettingsContextTheResetsOwnContextReadsTheFile() {
        Context application = RuntimeEnvironment.getApplication();
        Settings.KEEP_APP_LANGUAGE.save(true);
        saveInTheFile(Settings.KEEP_APP_LANGUAGE.key, true);
        // Inside attachBaseContext ActivityThread has no application yet.
        EarlyApplication.set(null);
        Utils.setContext(null);
        try {
            assertTrue("read through the reset's context", AppLanguage.keepChosenLanguage(application));
            assertFalse("no context and no application answers off", AppLanguage.keepChosenLanguage(null));

            EarlyApplication.set(application);
            assertTrue("a null context falls back to the application", AppLanguage.keepChosenLanguage(null));

            saveInTheFile(Settings.KEEP_APP_LANGUAGE.key, false);
            assertFalse(AppLanguage.keepChosenLanguage(application));
        } finally {
            Utils.setContext(application);
        }
    }

    /**
     * The #61 reporter's TikTok came up in English after a reboot with Italian picked and the
     * reset skipped. At the start the picked language becomes the default, TikTok's apply runs,
     * and strings already loaded for another language switch to it.
     */
    @Test public void atTheStartThePickedLanguageComesBackWithItsStrings() {
        Context application = RuntimeEnvironment.getApplication();
        FakeLanguage language = new FakeLanguage();
        AppLanguage.language = language;
        Locale italian = Locale.ITALIAN;
        language.picked = italian;
        language.strings = Locale.US;
        Locale.setDefault(Locale.US);

        AppLanguage.applyAtStart(application);
        assertEquals("off, nothing changes", Locale.US, Locale.getDefault());
        assertEquals(0, language.applied);
        assertTrue(language.loaded.isEmpty());

        Settings.KEEP_APP_LANGUAGE.save(true);
        AppLanguage.applyAtStart(application);
        assertEquals(italian, Locale.getDefault());
        assertEquals(1, language.applied);
        assertEquals(Collections.singletonList(italian), language.loaded);

        AppLanguage.applyAtStart(application);
        assertEquals("strings already in the picked language switch again, which empties TikTok's cache",
                2, language.loaded.size());
        assertEquals("the apply is TikTok's own and cheap, so it runs every start", 2, language.applied);

        PausedProcess.set(true);
        Locale.setDefault(Locale.US);
        AppLanguage.applyAtStart(application);
        assertEquals("paused, nothing changes", Locale.US, Locale.getDefault());
        assertEquals(2, language.applied);
    }

    /**
     * A process TikTok's search service started at boot (emulator, 2026-10-10): the table had
     * loaded for Italian by the application's onCreate, but TikTok's string cache had filled in
     * English first and the UI came up in English. The switch is what empties that cache, so it
     * runs even when the strings already say they are in the picked language.
     */
    @Test public void aStartWhoseStringsAlreadySayThePickedLanguageStillSwitchesThem() {
        Context application = RuntimeEnvironment.getApplication();
        FakeLanguage language = new FakeLanguage();
        AppLanguage.language = language;
        Settings.KEEP_APP_LANGUAGE.save(true);
        Locale.setDefault(Locale.US);
        language.picked = Locale.ITALY;
        language.strings = Locale.ITALY;

        AppLanguage.applyAtStart(application);
        assertEquals(Locale.ITALY, Locale.getDefault());
        assertEquals(1, language.applied);
        assertEquals("switched once, to the pick", Collections.singletonList(Locale.ITALY), language.loaded);
    }

    @Test public void stringsNotLoadedYetFollowTheDefaultAndNothingPickedFollowsThePhone() {
        Context application = RuntimeEnvironment.getApplication();
        FakeLanguage language = new FakeLanguage();
        AppLanguage.language = language;
        Settings.KEEP_APP_LANGUAGE.save(true);
        Locale.setDefault(Locale.US);

        language.picked = Locale.ITALIAN;
        AppLanguage.applyAtStart(application);
        assertEquals(Locale.ITALIAN, Locale.getDefault());
        assertTrue("no strings loaded yet, so they load for the new default", language.loaded.isEmpty());

        Locale.setDefault(Locale.GERMANY);
        language.picked = null;
        language.strings = Locale.GERMANY;
        AppLanguage.applyAtStart(application);
        assertEquals("nothing picked, the phone's language stays", Locale.GERMANY, Locale.getDefault());
        assertEquals(1, language.applied);
    }

    @Test public void aFailingCallLeavesTheStartAlone() {
        FakeLanguage language = new FakeLanguage();
        AppLanguage.language = language;
        Settings.KEEP_APP_LANGUAGE.save(true);
        language.failure = new IllegalStateException("not on this build");
        Locale.setDefault(Locale.US);

        AppLanguage.applyAtStart(RuntimeEnvironment.getApplication());
        assertEquals(Locale.US, Locale.getDefault());
        assertEquals(0, language.applied);
    }

    @Test public void unpatchedTheStubsDoNothing() {
        Settings.KEEP_APP_LANGUAGE.save(true);
        Locale.setDefault(Locale.US);
        AppLanguage.applyAtStart(RuntimeEnvironment.getApplication());
        assertEquals(Locale.US, Locale.getDefault());
        assertNull(AppLanguage.pickedLocale(RuntimeEnvironment.getApplication()));
        assertNull(AppLanguage.stringsLocale());
    }

    @Test public void theRowIsOnTheAppPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            assertNull(build(activity).findPreference(Settings.KEEP_APP_LANGUAGE.key));

            SettingsStatus.appLanguageEnabled = true;
            assertTrue(ExtensionPreferenceCategory.isAvailable());
            Preference row = build(activity).findPreference(Settings.KEEP_APP_LANGUAGE.key);
            assertNotNull(row);
            assertEquals("Keep the language picked in TikTok", String.valueOf(row.getTitle()));
            assertFalse("no restart note", String.valueOf(row.getSummary()).contains("Restart"));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new ExtensionPreferenceCategory(activity, screen);
        return screen;
    }
}

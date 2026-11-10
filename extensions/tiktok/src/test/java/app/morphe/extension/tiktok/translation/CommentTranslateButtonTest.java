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

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlyApplication;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.settings.Setting;
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
 * TikTok's translate button in the comment header (#85): its gates answer TikTok's own values
 * until the reader turns the switch on, and a toggle the button turned on reads off again once
 * the switch is off or Hushfeed is paused, while a toggle TikTok set itself keeps TikTok's answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentTranslateButtonTest {
    /** What TikTok hands over as the source of a change it makes on its own. */
    private static final Object TIKTOKS_OWN_SOURCE = new Object();

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void tearDown() {
        PausedProcess.set(false);
        EarlyApplication.reset();
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.COMMENT_TRANSLATE_BUTTON.save(false);
        saveInTheFile(Settings.COMMENT_TRANSLATE_BUTTON.key, false);
        Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.save(false);
        SettingsStatus.commentTranslateButtonEnabled = false;
    }

    /**
     * The early read opens the application's own preferences file, which a save through the
     * setting doesn't reach once another class has loaded Setting first, so write it as well.
     */
    private static void saveInTheFile(String key, boolean value) {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit();
    }

    @Test public void theSwitchStartsOffAndPauseHandsTheGatesBack() {
        assertEquals(Boolean.FALSE, Settings.COMMENT_TRANSLATE_BUTTON.defaultValue);
        assertEquals(CommentTranslateButton.SWITCH_KEY, Settings.COMMENT_TRANSLATE_BUTTON.key);
        assertFalse("the header asks again on every comment sheet", Settings.COMMENT_TRANSLATE_BUTTON.rebootApp);
        assertFalse(CommentTranslateButton.isOn());

        Settings.COMMENT_TRANSLATE_BUTTON.save(true);
        assertTrue(CommentTranslateButton.isOn());

        PausedProcess.set(true);
        assertFalse(CommentTranslateButton.isOn());
    }

    @Test public void beforeTheSettingsContextTheSavedSwitchIsRead() {
        Context application = RuntimeEnvironment.getApplication();
        Settings.COMMENT_TRANSLATE_BUTTON.save(true);
        saveInTheFile(Settings.COMMENT_TRANSLATE_BUTTON.key, true);
        EarlyApplication.set(application);
        Utils.setContext(null);
        try {
            assertTrue(CommentTranslateButton.isOn());
            EarlyApplication.set(null);
            assertFalse("no application yet answers off", CommentTranslateButton.isOn());
        } finally {
            Utils.setContext(application);
        }
    }

    @Test public void aToggleTheButtonTurnedOnReadsOffOnceTheSwitchIsOff() {
        Settings.COMMENT_TRANSLATE_BUTTON.save(true);
        CommentTranslateButton.onToggleWritten(Boolean.TRUE, null);
        assertTrue(Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.get());
        assertTrue(CommentTranslateButton.toggleOn(true));
        assertFalse(CommentTranslateButton.toggleOn(false));

        PausedProcess.set(true);
        assertFalse("paused, the toggle Metra turned on is off", CommentTranslateButton.toggleOn(true));
        PausedProcess.set(false);
        assertTrue(CommentTranslateButton.toggleOn(true));

        Settings.COMMENT_TRANSLATE_BUTTON.save(false);
        assertFalse("the button is gone, so the toggle it turned on is too", CommentTranslateButton.toggleOn(true));
        assertFalse(CommentTranslateButton.toggleOn(false));
    }

    @Test public void turningTheToggleOffWithTheButtonClearsTheMark() {
        Settings.COMMENT_TRANSLATE_BUTTON.save(true);
        CommentTranslateButton.onToggleWritten(Boolean.TRUE, null);
        CommentTranslateButton.onToggleWritten(Boolean.FALSE, null);
        assertFalse(Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.get());

        CommentTranslateButton.onToggleWritten(Boolean.TRUE, null);
        CommentTranslateButton.onToggleWritten(null, null);
        assertFalse("an erased toggle isn't one the button turned on",
                Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.get());
    }

    @Test public void aToggleTikTokSetItselfKeepsTikToksAnswer() {
        Settings.COMMENT_TRANSLATE_BUTTON.save(true);
        CommentTranslateButton.onToggleWritten(Boolean.TRUE, null);
        CommentTranslateButton.onToggleWritten(Boolean.TRUE, TIKTOKS_OWN_SOURCE);
        assertFalse("TikTok's sync took the toggle over", Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.get());
        Settings.COMMENT_TRANSLATE_BUTTON.save(false);
        assertTrue(CommentTranslateButton.toggleOn(true));

        // The button on an account TikTok built it for, with Hushfeed's switch off.
        CommentTranslateButton.onToggleWritten(Boolean.TRUE, null);
        assertFalse(Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.get());
        assertTrue(CommentTranslateButton.toggleOn(true));

        PausedProcess.set(true);
        assertTrue("paused, TikTok's own toggle is TikTok's", CommentTranslateButton.toggleOn(true));
    }

    @Test public void beforeTheSettingsContextTikToksAnswerStands() {
        Context application = RuntimeEnvironment.getApplication();
        Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.save(true);
        Utils.setContext(null);
        try {
            assertTrue(CommentTranslateButton.toggleOn(true));
            assertFalse(CommentTranslateButton.toggleOn(false));
            CommentTranslateButton.onToggleWritten(Boolean.FALSE, null);
        } finally {
            Utils.setContext(application);
        }
        assertTrue("nothing was written without a context", Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.get());
    }

    @Test public void theMarkIsThisPhonesOwnState() {
        assertEquals(Boolean.FALSE, Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.defaultValue);
        assertFalse("it describes TikTok's storage on this phone",
                Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.includeWithImportExport);
        assertTrue("a pause has to read it to answer the toggle off",
                Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.isKeptWhenPaused());
        assertTrue(Settings.COMMENT_TRANSLATE_BUTTON.includeWithImportExport);
        assertFalse(Settings.COMMENT_TRANSLATE_BUTTON.isKeptWhenPaused());
    }

    /** The row shows only where the patch hooked the button's gates, on the Comments page. */
    @Test public void theRowShowsOnlyWhereTheButtonWasHooked() {
        try (var controller = Robolectric.buildActivity(DoNotAutoTranslateTest.TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.COMMENT_TRANSLATE_BUTTON.key));

            SettingsStatus.commentTranslateButtonEnabled = true;
            assertTrue(CommentsPreferenceCategory.isAvailable());
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, with);
            assertNotNull(with.findPreference(Settings.COMMENT_TRANSLATE_BUTTON.key));
        }
    }
}

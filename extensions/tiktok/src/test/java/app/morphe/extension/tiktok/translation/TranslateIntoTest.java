package app.morphe.extension.tiktok.translation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceGroup;
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
import org.robolectric.annotation.Config;

/**
 * Translate into: the language TikTok's translation service answers with, swapped for the one the
 * reader typed in TikTok's spelling of it, TikTok's own answer kept while the row is empty or
 * Hushfeed is paused, and a row that takes one language code and nothing else.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TranslateIntoTest {
    private static final String TITLE = "Translate into";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
        PausedProcess.set(false);
        HookStatus.clear();
        Settings.TRANSLATE_INTO.resetToDefault();
    }

    @Test
    public void anEmptyRowKeepsTikToksAnswer() {
        assertEquals("", Settings.TRANSLATE_INTO.defaultValue);
        String tiktok = "de";
        assertSame(tiktok, TranslateInto.target(tiktok));
        assertNull(TranslateInto.target(null));
    }

    @Test
    public void theTypedLanguageGoesOutAsTikTokSpellsIt() {
        Settings.TRANSLATE_INTO.save("ES");
        assertEquals("es", TranslateInto.target("de"));
        Settings.TRANSLATE_INTO.save(" pt_BR ");
        assertEquals("the region isn't part of a target", "pt", TranslateInto.target("de"));
        Settings.TRANSLATE_INTO.save("spa");
        assertEquals("three letters with two go out as two", "es", TranslateInto.target("de"));
        Settings.TRANSLATE_INTO.save("zh-TW");
        assertEquals("zh-Hant", TranslateInto.target("en"));
        Settings.TRANSLATE_INTO.save("zh");
        assertEquals("zh-Hans", TranslateInto.target("en"));
        Settings.TRANSLATE_INTO.save("zh-Hans-TW");
        assertEquals("the script beats the region", "zh-Hans", TranslateInto.target("en"));
    }

    @Test
    public void somethingThatIsNotALanguageKeepsTikToksAnswer() {
        Settings.TRANSLATE_INTO.save("xx");
        assertEquals("de", TranslateInto.target("de"));
        Settings.TRANSLATE_INTO.save("Spanish");
        assertEquals("de", TranslateInto.target("de"));
        Settings.TRANSLATE_INTO.save("abc");
        assertEquals("three letters that name no language", "de", TranslateInto.target("de"));
        Settings.TRANSLATE_INTO.save("fil");
        assertEquals("three letters for a language with no two", "fil", TranslateInto.target("de"));
    }

    @Test
    public void aPausedHushfeedLeavesTikTokAlone() {
        Settings.TRANSLATE_INTO.save("es");
        PausedProcess.set(true);
        assertEquals("de", TranslateInto.target("de"));
        PausedProcess.set(false);
        assertEquals("es", TranslateInto.target("de"));
    }

    @Test
    public void theRowTakesOneLanguageCode() {
        assertNull(TranslateInto.languageProblem(""));
        assertNull(TranslateInto.languageProblem("es"));
        assertNull(TranslateInto.languageProblem("zh-Hant"));
        String list = TranslateInto.languageProblem("es, de");
        assertNotNull("a list was taken for one target", list);
        String word = TranslateInto.languageProblem("Spanish");
        assertNotNull(word);
        assertTrue(word, word.contains("Spanish"));
        assertNotNull("two letters that name no language", TranslateInto.languageProblem("xx"));
        assertNotNull("three letters that name no language", TranslateInto.languageProblem("abc"));
        assertNull(TranslateInto.languageProblem("fil"));
    }

    /** The row shows only where the patch hooked the service's answer, on the Comments page. */
    @Test
    public void theRowShowsOnlyWhereTheAnswerWasHooked() {
        boolean translation = SettingsStatus.commentTranslationEnabled;
        boolean status = SettingsStatus.translateIntoEnabled;
        try (var controller = Robolectric.buildActivity(DoNotAutoTranslateTest.TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.commentTranslationEnabled = true;

            SettingsStatus.translateIntoEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, without);
            assertNotNull(find(without, "Auto translate comments"));
            assertNull("the row showed on a build where the answer wasn't hooked", find(without, TITLE));

            SettingsStatus.translateIntoEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, with);
            assertNotNull(find(with, TITLE));
        } finally {
            SettingsStatus.commentTranslationEnabled = translation;
            SettingsStatus.translateIntoEnabled = status;
        }
    }

    private static Preference find(PreferenceGroup group, String title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference.getTitle() != null && title.contentEquals(preference.getTitle())) return preference;
            if (preference instanceof PreferenceGroup) {
                Preference nested = find((PreferenceGroup) preference, title);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}

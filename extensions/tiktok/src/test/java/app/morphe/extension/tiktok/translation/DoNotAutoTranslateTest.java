package app.morphe.extension.tiktok.translation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceActivity;
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
 * Don't auto translate these languages (#121): the codes added to TikTok's Don't translate list
 * where the patch asks, what the row accepts, and that nothing changes while the setting is empty.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DoNotAutoTranslateTest {
    private static final String TITLE = "Don't auto translate these languages";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After
    public void tearDown() {
        PausedProcess.set(false);
        HookStatus.clear();
        Settings.DONT_AUTO_TRANSLATE_LANGUAGES.resetToDefault();
    }

    @Test
    public void theSettingStartsEmptyAndTikTokKeepsItsOwnListUntilItIsFilled() {
        assertEquals("", Settings.DONT_AUTO_TRANSLATE_LANGUAGES.defaultValue);
        String[] tiktok = {"fr", "de"};
        assertSame("an empty setting handed back a different array", tiktok, DoNotAutoTranslate.withExcluded(tiktok));
        assertNull(DoNotAutoTranslate.withExcluded(null));
        assertFalse(DoNotAutoTranslate.hasExcluded());
    }

    @Test
    public void theUsersLanguagesAreAddedToTikToksListAndNothingIsRepeated() {
        Settings.DONT_AUTO_TRANSLATE_LANGUAGES.save("es, de-AT\npt_BR, ES");
        assertArrayEquals(new String[]{"fr", "de", "es", "pt"},
                DoNotAutoTranslate.withExcluded(new String[]{"fr", "de"}));
        assertTrue(String.join(" ", HookStatus.report()).contains("do not auto translate"));
    }

    @Test
    public void aNullListFromTikTokStillGetsTheUsersLanguages() {
        Settings.DONT_AUTO_TRANSLATE_LANGUAGES.save("es");
        assertArrayEquals(new String[]{"es"}, DoNotAutoTranslate.withExcluded(null));
        assertArrayEquals(new String[]{"es"}, DoNotAutoTranslate.withExcluded(new String[0]));
    }

    @Test
    public void aLanguageTikTokAlreadyListsChangesNothing() {
        Settings.DONT_AUTO_TRANSLATE_LANGUAGES.save("es");
        String[] tiktok = {"es", "fr"};
        assertSame(tiktok, DoNotAutoTranslate.withExcluded(tiktok));
    }

    /** TikTok tags Chinese items zh-Hans, so zh alone matched none of them. */
    @Test
    public void chineseIsAddedTheWayTikTokSpellsIt() {
        Settings.DONT_AUTO_TRANSLATE_LANGUAGES.save("zh");
        assertArrayEquals(new String[]{"zh", "zh-Hans"}, DoNotAutoTranslate.withExcluded(new String[0]));
        Settings.DONT_AUTO_TRANSLATE_LANGUAGES.save("chi");
        assertArrayEquals(new String[]{"zh", "zh-Hans"}, DoNotAutoTranslate.withExcluded(new String[0]));
    }

    /** zh-Hant and zh-TW added zh-Hans, so Simplified was left alone instead of Traditional. */
    @Test
    public void traditionalChineseKeepsItsScript() {
        for (String entry : new String[]{"zh-Hant", "zh_TW", "ZH-hk", "zh-Hant-TW"}) {
            Settings.DONT_AUTO_TRANSLATE_LANGUAGES.save(entry);
            assertArrayEquals(entry, new String[]{"zh", "zh-Hant"},
                    DoNotAutoTranslate.withExcluded(new String[0]));
        }
        for (String entry : new String[]{"zh-CN", "zh-Hans-TW", "zh_Hans_HK"}) {
            // The script says which characters, whatever the place: Simplified written in Taiwan.
            Settings.DONT_AUTO_TRANSLATE_LANGUAGES.save(entry);
            assertArrayEquals(entry, new String[]{"zh", "zh-Hans"},
                    DoNotAutoTranslate.withExcluded(new String[0]));
        }
    }

    @Test
    public void entriesThatAreNotLanguagesAreSkippedAndTheListIsCapped() {
        Settings.DONT_AUTO_TRANSLATE_LANGUAGES.save("English, es, 12, , de");
        assertArrayEquals(new String[]{"es", "de"}, DoNotAutoTranslate.withExcluded(new String[0]));

        StringBuilder many = new StringBuilder();
        for (char a = 'a'; a <= 'z'; a++) many.append(a).append("a,");
        for (char a = 'a'; a <= 'j'; a++) many.append(a).append("b,");
        Settings.DONT_AUTO_TRANSLATE_LANGUAGES.save(many.toString());
        assertEquals(32, DoNotAutoTranslate.withExcluded(new String[0]).length);
    }

    @Test
    public void aPausedHushfeedLeavesTikTokAlone() {
        Settings.DONT_AUTO_TRANSLATE_LANGUAGES.save("es");
        PausedProcess.set(true);
        String[] tiktok = {"fr"};
        assertSame(tiktok, DoNotAutoTranslate.withExcluded(tiktok));
        PausedProcess.set(false);
        assertArrayEquals(new String[]{"fr", "es"}, DoNotAutoTranslate.withExcluded(tiktok));
    }

    @Test
    public void theRowCatchesAnEntryThatIsNotALanguageCode() {
        assertNull(DoNotAutoTranslate.languageProblem("es, de-AT"));
        assertNull(DoNotAutoTranslate.languageProblem(""));
        String problem = DoNotAutoTranslate.languageProblem("es, Spanish");
        assertNotNull(problem);
        assertTrue(problem, problem.contains("Spanish"));
        // This list only keeps text untranslated, so the feed filter's warning about videos doesn't fit.
        assertFalse(problem, problem.contains("video"));
    }

    /** Hushfeed's own comment translator reads TikTok's list too, and has to see the added languages. */
    @Test
    public void theCommentTranslatorSeesTheAddedLanguages() throws Exception {
        java.lang.reflect.Method read = CommentBatchTranslator.class.getDeclaredMethod("getNativeDoNotTranslateLanguages");
        read.setAccessible(true);
        assertEquals(0, ((String[]) read.invoke(null)).length);
        Settings.DONT_AUTO_TRANSLATE_LANGUAGES.save("es, fr");
        assertArrayEquals(new String[]{"es", "fr"}, (String[]) read.invoke(null));
    }

    /** The row shows only where the patch found TikTok's reads, on the Comments page under Reading. */
    @Test
    public void theRowShowsOnlyWhereTheReadsWereHooked() {
        boolean translation = SettingsStatus.commentTranslationEnabled;
        boolean status = SettingsStatus.doNotAutoTranslateEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.commentTranslationEnabled = true;

            SettingsStatus.doNotAutoTranslateEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, without);
            assertNotNull(find(without, "Auto translate comments"));
            assertNull("the row showed on a build where the reads weren't hooked", find(without, TITLE));

            SettingsStatus.doNotAutoTranslateEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, with);
            assertNotNull(find(with, TITLE));
        } finally {
            SettingsStatus.commentTranslationEnabled = translation;
            SettingsStatus.doNotAutoTranslateEnabled = status;
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

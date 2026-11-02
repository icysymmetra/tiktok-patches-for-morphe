package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import app.morphe.extension.shared.Utils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A count and its noun are chosen by the language's plural rule, not by {@code count == 1}.
 *
 * <p>Ten rows hand-paired a singular with a {@code %1$d} form, which serves German, Spanish,
 * Portuguese and Turkish and is wrong for Indonesian (no one form) and for any language with
 * a few or many form. The rule comes from ICU's CLDR data on the phone; the extra forms are rows
 * keyed {@code other + "|" + category}, so a Polish or Russian table can carry them the day it
 * arrives without a code change.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class L10nQuantityTest {
    @Test @Config(sdk = {23, 24, 28})
    public void shippedIntegerRulesSelectEveryExpectedCategory() {
        long[] counts = {0, 1, 2, 5, 11, 21, 22, 101};
        for (String language : new String[]{"en", "az", "de", "es", "it", "tr", "pt-BR", "ru", "id", "in"}) {
            String[] expected = language.equals("ru")
                    ? new String[]{"many", "one", "few", "many", "many", "one", "few", "one"}
                    : language.equals("pt-BR")
                    ? new String[]{"one", "one", "other", "other", "other", "other", "other", "other"}
                    : language.equals("id") || language.equals("in")
                    ? new String[]{"other", "other", "other", "other", "other", "other", "other", "other"}
                    : new String[]{"other", "one", "other", "other", "other", "other", "other", "other"};
            for (int index = 0; index < counts.length; index++) {
                assertEquals(language + " " + counts[index], expected[index],
                        L10n.pluralCategory(Locale.forLanguageTag(language), counts[index]));
            }
        }
    }

    @Test @Config(sdk = 23)
    public void integerFallbackHandlesMillionFormsAndLongBoundariesWithoutOverflow() {
        for (String language : new String[]{"es", "it", "pt-BR", "pt-PT"}) {
            Locale locale = Locale.forLanguageTag(language);
            assertEquals("many", L10n.pluralCategory(locale, 1_000_000));
            assertEquals("many", L10n.pluralCategory(locale, 2_000_000));
            assertEquals("other", L10n.pluralCategory(locale, 999_999));
            assertEquals("other", L10n.pluralCategory(locale, 1_000_001));
        }
        assertEquals("other", L10n.pluralCategory(Locale.forLanguageTag("pt-PT"), 0));
        assertEquals("one", L10n.pluralCategory(new Locale("ru"), 9_007_199_254_741_001L));
        assertEquals("many", L10n.pluralCategory(new Locale("ru"), Long.MAX_VALUE));
        for (String language : L10nTranslations.LANGUAGES) {
            assertEquals("other", L10n.pluralCategory(Locale.forLanguageTag(language), Long.MIN_VALUE));
            assertEquals("other", L10n.pluralCategory(Locale.forLanguageTag(language), -1));
        }
    }

    @Test @Config(sdk = {24, 28})
    public void modernCountsKeepThePlatformsOwnIcuRules() {
        for (String language : new String[]{"es", "it", "pt-BR", "ru", "id"}) {
            Locale locale = Locale.forLanguageTag(language);
            for (long count : new long[]{-1, Long.MIN_VALUE, 1_000_000, 2_000_000,
                    9_007_199_254_741_001L, Long.MAX_VALUE}) {
                assertEquals(android.icu.text.PluralRules.forLocale(locale).select(count),
                        L10n.pluralCategory(locale, count));
            }
        }
    }

    @Test @Config(sdk = {23, 24, 28}, qualifiers = "ja")
    public void anUnavailableDisplayedLanguageUsesEnglishQuantityRules() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals("one", L10n.pluralCategory(context, 1));
        assertEquals("1 result", L10n.quantity(context, 1, "1 result", "%1$d results"));
        assertEquals("2 results", L10n.quantity(context, 2, "1 result", "%1$d results"));
        assertEquals("21 results", L10n.quantity(context, 21, "1 result", "%1$d results"));
    }

    @Test
    public void missingQuantityRowsFallBackToEnglishWithoutBorrowingAnIncompatibleSingular() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("1 result", "a translated singular the displayed rule doesn't use");
        assertEquals("1 result", L10n.pluralRow("other", 1, "1 result", "%1$d results", table));
        table.put("%1$d results", "");
        assertEquals("1 result", L10n.pluralRow("other", 1, "1 result", "%1$d results", table));
        assertEquals("1 result", L10n.pluralRow("other", 1, "1 result", "%1$d results", null));
        assertEquals("%1$d results", L10n.pluralRow("one", 21, "1 result", "%1$d results", null));
        table.put("%1$d results", "%1$d translated results");
        assertEquals("%1$d translated results", L10n.pluralRow("few", 2, "1 result", "%1$d results", table));
        assertEquals("%1$d translated results", L10n.pluralRow("other", 1, "1 result", "%1$d results", table));
    }

    @Test @Config(sdk = 28, qualifiers = "en")
    public void englishTakesTheOneFormForOneAndTheOtherFormForTheRest() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        assertEquals("1 result", L10n.quantity(context, 1, "1 result", "%1$d results"));
        assertEquals("0 results", L10n.quantity(context, 0, "1 result", "%1$d results"));
        assertEquals("2 results", L10n.quantity(context, 2, "1 result", "%1$d results"));
        assertEquals("21 results", L10n.quantity(context, 21, "1 result", "%1$d results"));
        // Both forms take the same values; the one form leaves the count unused.
        assertEquals("Reset 1 gate of 4.", L10n.quantity(context, 1,
                "Reset 1 gate of %2$d.", "Reset %1$d gates of %2$d.", 1, 4));
        assertEquals("Reset 3 gates of 4.", L10n.quantity(context, 3,
                "Reset 1 gate of %2$d.", "Reset %1$d gates of %2$d.", 3, 4));
    }

    @Test @Config(sdk = {23, 24, 28}, qualifiers = "in")
    public void indonesianHasNoOneFormSoOneTakesTheOtherRow() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        assertEquals("other", L10n.pluralCategory(context, 1));
        // "1 result" has a row of its own in the Indonesian table, and it is not used: the
        // language reads every count the same way.
        assertEquals(L10n.f(context, "%1$d results", 1),
                L10n.quantity(context, 1, "1 result", "%1$d results"));
    }

    @Test
    public void aLanguageWithFewAndManySelectsThoseRows() {
        // ICU's rule for Polish: 1 is one, 2 to 4 are few, 5 to 21 are many, 22 to 24 few again.
        // Polish has no table yet, so this asks the rule itself; a Polish phone reads English.
        Locale polish = new Locale("pl");
        assertEquals("one", L10n.pluralCategory(polish, 1));
        assertEquals("few", L10n.pluralCategory(polish, 3));
        assertEquals("many", L10n.pluralCategory(polish, 5));
        assertEquals("many", L10n.pluralCategory(polish, 21));
        assertEquals("few", L10n.pluralCategory(polish, 22));

        // The table a Polish translator would write: the two English forms plus the two extra.
        Map<String, String> table = new LinkedHashMap<>();
        table.put("1 result", "1 wynik");
        table.put("%1$d results", "%1$d wyników");
        table.put("%1$d results|few", "%1$d wyniki");
        table.put("%1$d results|many", "%1$d wyników");
        assertEquals("1 wynik", L10n.pluralRow("one", 1, "1 result", "%1$d results", table));
        assertEquals("%1$d wyniki", L10n.pluralRow("few", 3, "1 result", "%1$d results", table));
        assertEquals("%1$d wyników", L10n.pluralRow("many", 5, "1 result", "%1$d results", table));
        assertEquals("%1$d wyników", L10n.pluralRow("other", 5, "1 result", "%1$d results", table));
        // A table that has not written its few row yet falls back to its other form, which is
        // what every hand-rolled == 1 gave every count above one.
        table.remove("%1$d results|few");
        assertEquals("%1$d wyników", L10n.pluralRow("few", 3, "1 result", "%1$d results", table));
        // No table at all: English.
        assertEquals("%1$d results", L10n.pluralRow("few", 3, "1 result", "%1$d results", null));
    }

    @Test @Config(sdk = 28, qualifiers = "ru")
    public void russiansOneCategoryAboveOneKeepsItsCount() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        // ICU's rule for Russian: 1, 21 and 101 are one, 2 to 4 and 22 few, 5 to 20 many.
        assertEquals("one", L10n.pluralCategory(context, 1));
        assertEquals("one", L10n.pluralCategory(context, 21));
        assertEquals("few", L10n.pluralCategory(context, 22));
        assertEquals("many", L10n.pluralCategory(context, 11));

        Map<String, String> table = new LinkedHashMap<>();
        table.put("1 result", "1 результат");
        table.put("%1$d results", "%1$d результатов");
        table.put("%1$d results|one", "%1$d результат");
        table.put("%1$d results|few", "%1$d результата");
        table.put("%1$d results|many", "%1$d результатов");
        assertEquals("1 результат", L10n.pluralRow("one", 1, "1 result", "%1$d results", table));
        // 21 is in the one category too, and the one form's "1" would show 21 results as one.
        assertEquals("%1$d результат", L10n.pluralRow("one", 21, "1 result", "%1$d results", table));
        assertEquals("%1$d результата", L10n.pluralRow("few", 22, "1 result", "%1$d results", table));
        // A table without the row gives 21 its other form, which at least says 21.
        table.remove("%1$d results|one");
        assertEquals("%1$d результатов", L10n.pluralRow("one", 21, "1 result", "%1$d results", table));
    }

    @Test @Config(sdk = {23, 24, 28}, qualifiers = "ru")
    public void theShippedRussianTableWordsEachCountItsOwnWay() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        assertEquals("1 результат", L10n.quantity(context, 1, "1 result", "%1$d results"));
        assertEquals("21 результат", L10n.quantity(context, 21, "1 result", "%1$d results"));
        assertEquals("2 результата", L10n.quantity(context, 2, "1 result", "%1$d results"));
        assertEquals("22 результата", L10n.quantity(context, 22, "1 result", "%1$d results"));
        assertEquals("5 результатов", L10n.quantity(context, 5, "1 result", "%1$d results"));
        assertEquals("11 результатов", L10n.quantity(context, 11, "1 result", "%1$d results"));
        assertEquals("0 результатов", L10n.quantity(context, 0, "1 result", "%1$d results"));
    }

    /**
     * The rule is the shown language's, not the phone's first one. Japanese has no table, so a
     * phone set to Japanese and then Russian reads Russian, and Japanese rules (one form for
     * every count) gave it "1 результатов". A phone whose only language has no table reads
     * English, and the same mix-up gave it "1 results".
     */
    @Test
    public void theShownLanguagesRulePicksTheForm() {
        Configuration configuration = new Configuration(
                RuntimeEnvironment.getApplication().getResources().getConfiguration());
        configuration.setLocales(new LocaleList(new Locale("ja", "JP"), new Locale("ru", "RU")));
        Context context = RuntimeEnvironment.getApplication().createConfigurationContext(configuration);
        assertEquals("1 результат", L10n.quantity(context, 1, "1 result", "%1$d results"));
        assertEquals("21 результат", L10n.quantity(context, 21, "1 result", "%1$d results"));
        assertEquals("2 результата", L10n.quantity(context, 2, "1 result", "%1$d results"));
        assertEquals("5 результатов", L10n.quantity(context, 5, "1 result", "%1$d results"));

        configuration.setLocales(new LocaleList(new Locale("ja", "JP")));
        context = RuntimeEnvironment.getApplication().createConfigurationContext(configuration);
        assertEquals("1 result", L10n.quantity(context, 1, "1 result", "%1$d results"));
        assertEquals("2 results", L10n.quantity(context, 2, "1 result", "%1$d results"));
    }

    @Test @Config(sdk = {23, 24, 28}, qualifiers = "pt-rBR")
    public void brazilianPortugueseShowsNoneAsNoneNotOne() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        // CLDR puts 0 in Portuguese's one category, beside 1.
        assertEquals("one", L10n.pluralCategory(context, 0));
        assertEquals("1 resultado", L10n.quantity(context, 1, "1 result", "%1$d results"));
        assertEquals("0 resultados", L10n.quantity(context, 0, "1 result", "%1$d results"));
        assertEquals("2 resultados", L10n.quantity(context, 2, "1 result", "%1$d results"));
    }

    @Test public void readerTextIsIsolatedFromTheSentenceAroundIt() {
        assertEquals("⁨dana⁩", L10n.isolate("dana"));
        assertEquals("⁨שלום⁩", L10n.isolate("שלום"));
        assertEquals("", L10n.isolate(null));
        assertEquals("", L10n.isolate(""));
    }
}

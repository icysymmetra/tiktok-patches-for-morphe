/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import app.morphe.extension.shared.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings text in the phone's language.
 *
 * The English string in the code is the lookup key. The translations are generated into
 * {@link L10nTranslations} from extensions/tiktok/src/main/l10n by scripts/gen-l10n.py, so
 * they travel in the extension's own code rather than in TikTok's resources: merging a few
 * hundred strings into a resource table of 74,765 costs more than 250 MB of patching memory,
 * which is more than Morphe Manager allows by default.
 *
 * A language with no table, or a string nobody translated yet, falls back to the English
 * text, so nothing here can leave a label empty.
 */
public final class L10n {
    /** A set of language tags and the answer they came to, so neither can go stale alone. */
    private static final class Table {
        final String key;
        final Map<String, String> translations;
        /** The language the text is shown in: the table's, or English when there is none. */
        final Locale shown;
        /** The table's tag as looked up ("pt-rbr"), or null for English. */
        final String tag;

        Table(String key, Map<String, String> translations, Locale shown, String tag) {
            this.key = key;
            this.translations = translations;
            this.shown = shown;
            this.tag = tag;
        }
    }

    private static volatile Table cached;

    private L10n() {
    }

    public static String t(String english) {
        return t(Utils.getContext(), english);
    }

    /** Text that may be anything: only plain strings are looked up, styled text passes through. */
    public static CharSequence t(Context context, CharSequence text) {
        return text instanceof String ? t(context, (String) text) : text;
    }

    public static String t(Context context, String english) {
        if (english == null || english.isEmpty()) {
            return english;
        }
        Map<String, String> translations = tableFor(tags(context)).translations;
        if (translations == null) {
            return english;
        }
        String translated = translations.get(english);
        return translated == null || translated.isEmpty() ? english : translated;
    }

    /**
     * Whether the settings are shown from a translation table. False for English and for a
     * language with no table, where every label is English already.
     */
    public static boolean isTranslated(Context context) {
        return tableFor(tags(context)).translations != null;
    }

    /** The tag of the table the settings are shown from ("de", "pt-rbr"), or null for English. */
    public static String tableTag(Context context) {
        return tableFor(tags(context)).tag;
    }

    /** The language the settings are shown in: the table's, or English. */
    public static Locale shownLocale(Context context) {
        return tableFor(tags(context)).shown;
    }

    /** {@link String#format} over the translated form of {@code english}. */
    public static String f(String english, Object... args) {
        return f(Utils.getContext(), english, args);
    }

    public static String f(Context context, String english, Object... args) {
        try {
            return String.format(Locale.getDefault(), t(context, english), args);
        } catch (Throwable ignored) {
            return String.format(Locale.ROOT, english, args);
        }
    }

    /**
     * A count and its noun, in the phone's language, chosen by that language's plural rule.
     *
     * <p>{@code one} is the English for exactly one ("1 result") and {@code other} the English
     * for the rest ("%1$d results"); both are keys. Which one a count takes is the language's
     * decision, not {@code count == 1}: Indonesian has no "one" form and takes the other row for
     * 1, and a language with more forms than English (Polish, Russian, Arabic) keeps its extra
     * forms as rows keyed {@code other + "|" + category}, where the category is the CLDR name
     * ("few", "many", "zero", "two", and "one" for a one-category count other than 1). A table
     * with no such row falls back to its other form, which is what every hand-rolled
     * {@code == 1} did for every count above one.
     */
    public static String quantity(Context context, long count, String one, String other) {
        return quantity(context, count, one, other, new Object[]{count});
    }

    /**
     * As above, with the values both forms are formatted from. Both keys number their
     * placeholders the same way ("Reset 1 gate of %2$d." beside "Reset %1$d gates of %2$d."),
     * so the one form simply leaves the count unused.
     */
    public static String quantity(Context context, long count, String one, String other, Object... args) {
        Table table = tableFor(tags(context));
        String category = pluralCategory(table.shown, count);
        String row = pluralRow(category, count, one, other, table.translations);
        return format(row, oneForm(category, count) ? one : other, args);
    }

    /**
     * The row a category takes: the one form's translation for a count of 1, a
     * {@code |category} row for a form English does not have, and the other form's translation
     * for everything else. English itself when the table has no row. Kept apart from the phone
     * so a table with more forms than any shipped language can be checked without one.
     *
     * <p>The one form says "1" outright, so it only fits 1. A language whose one category takes
     * other counts as well (Russian's 21 and 101, Brazilian Portuguese's 0) reads those from an
     * {@code other|one} row, which carries the count, or from its other form when it has none.
     */
    static String pluralRow(String category, long count, String one, String other, Map<String, String> table) {
        if (oneForm(category, count)) {
            return rowOrKey(table, one);
        }
        if (!"other".equals(category) && table != null) {
            String variant = table.get(other + "|" + category);
            if (variant != null && !variant.isEmpty()) return variant;
        }
        String translated = table == null ? null : table.get(other);
        return translated == null || translated.isEmpty() ? count == 1 ? one : other : translated;
    }

    private static boolean oneForm(String category, long count) {
        return "one".equals(category) && count == 1;
    }

    private static String rowOrKey(Map<String, String> table, String key) {
        String translated = table == null ? null : table.get(key);
        return translated == null || translated.isEmpty() ? key : translated;
    }

    /**
     * The plural category {@code count} takes in the language the settings are shown in. That
     * is the table's language, not the phone's first one: a phone set to Japanese and then
     * Russian reads Russian words, and Japanese rules gave them "1 результатов". A phone with no
     * table reads English, by English rules.
     */
    static String pluralCategory(Context context, long count) {
        return pluralCategory(tableFor(tags(context)).shown, count);
    }

    /** The plural category {@code locale}'s language gives {@code count}. */
    static String pluralCategory(Locale locale, long count) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                return android.icu.text.PluralRules.forLocale(locale).select(count);
            } catch (Throwable ignored) {
                // Fall through to the shipped languages' integer rules below.
            }
        }
        // CLDR 48 cardinal rules for integer quantities. Negative counts aren't supported.
        // Keep long arithmetic so large counts don't lose precision or overflow on abs().
        if (count < 0) return "other";
        String language = locale.getLanguage();
        if ("id".equals(language) || "in".equals(language)) return "other";
        if ("ru".equals(language)) {
            long last = count % 10;
            long lastTwo = count % 100;
            if (last == 1 && lastTwo != 11) return "one";
            if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) return "few";
            return "many";
        }
        if ("pt".equals(language)) {
            if (count == 1 || count == 0 && !"PT".equals(locale.getCountry())) return "one";
            if (count != 0 && count % 1_000_000 == 0) return "many";
        } else if ("es".equals(language) || "it".equals(language)) {
            if (count != 0 && count % 1_000_000 == 0) return "many";
        }
        return count == 1 ? "one" : "other";
    }

    private static String format(String translated, String english, Object... args) {
        try {
            return String.format(Locale.getDefault(), translated, args);
        } catch (Throwable ignored) {
            return String.format(Locale.ROOT, english, args);
        }
    }

    /**
     * Text the reader wrote (a handle, a name, a caption word) set inside a sentence of ours.
     *
     * <p>Wrapped in Unicode's first-strong isolate so that a right-to-left name does not bend
     * the words around it, and so that a sentence in a right-to-left language keeps a Latin
     * handle where it was put. The same pair {@code VideoAuthor.label()} uses; a screen reader
     * does not voice the marks.
     */
    public static String isolate(CharSequence text) {
        if (text == null || text.length() == 0) return "";
        return "⁨" + text + "⁩";
    }

    /**
     * Every tag worth trying, in the order the phone asks for them, each one most specific
     * first, so a table for "pt-rbr" wins over one for "pt". Android resolves a string
     * resource against the whole language list rather than the first entry alone, and reading
     * the table by hand has to do the same or a second choice language never shows.
     */
    static List<String> tags(Context context) {
        List<String> tags = new ArrayList<>(4);
        List<String> seenLanguages = new ArrayList<>(4);
        for (Locale locale : locales(context)) {
            String language = locale.getLanguage().toLowerCase(Locale.ROOT);
            if (language.isEmpty() || seenLanguages.contains(language)) {
                continue;
            }
            seenLanguages.add(language);
            String country = locale.getCountry();
            if ("zh".equals(language)) {
                String script = locale.getScript();
                boolean traditional = "Hant".equalsIgnoreCase(script)
                        || script.isEmpty() && ("TW".equalsIgnoreCase(country)
                        || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country));
                String family = traditional ? "zh-hant" : "zh-hans";
                if (country != null && !country.isEmpty()) {
                    tags.add(family + "-r" + country.toLowerCase(Locale.ROOT));
                }
                tags.add(family);
                continue;
            }
            if (country != null && !country.isEmpty()) {
                tags.add(language + "-r" + country.toLowerCase(Locale.ROOT));
            }
            tags.add(language);
        }
        if (tags.isEmpty()) {
            tags.add(Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT));
        }
        return tags;
    }

    /** The languages the phone is set to, or the default when there is no context to ask. */
    private static List<Locale> locales(Context context) {
        try {
            Resources resources = context == null ? null : context.getResources();
            if (resources != null) {
                Configuration configuration = resources.getConfiguration();
                if (Build.VERSION.SDK_INT >= 24) {
                    LocaleList list = configuration.getLocales();
                    List<Locale> found = new ArrayList<>(list.size());
                    for (int index = 0; index < list.size(); index++) {
                        found.add(list.get(index));
                    }
                    if (!found.isEmpty()) {
                        return found;
                    }
                } else if (configuration.locale != null) {
                    return Collections.singletonList(configuration.locale);
                }
            }
        } catch (Throwable ignored) {
            // No context yet, or none with resources: the default locale still answers.
        }
        return Collections.singletonList(Locale.getDefault());
    }

    /**
     * The first tag with a table, remembered until the phone's languages change. English ends
     * the search: it is the text itself, so a phone that lists it ahead of German reads English,
     * as TikTok does, rather than the first language further down with a table.
     */
    private static Table tableFor(List<String> tags) {
        StringBuilder builder = new StringBuilder();
        for (String tag : tags) {
            builder.append(tag).append(',');
        }
        String key = builder.toString();

        Table table = cached;
        if (table != null && key.equals(table.key)) {
            return table;
        }

        Map<String, String> found = null;
        Locale shown = Locale.ENGLISH;
        String foundTag = null;
        for (String tag : tags) {
            if (tag.equals("en") || tag.startsWith("en-r")) {
                break;
            }
            found = L10nTranslations.of(tag);
            if (found != null) {
                shown = localeOf(tag);
                foundTag = tag;
                break;
            }
        }
        table = new Table(key, found, shown, foundTag);
        cached = table;
        return table;
    }

    /** A table tag as a locale: "pt-rbr" is Portuguese in Brazil, whose plural rule differs. */
    static Locale localeOf(String tag) {
        int region = tag.indexOf("-r");
        String languageTag = region < 0 ? tag
                : tag.substring(0, region) + "-" + tag.substring(region + 2).toUpperCase(Locale.ROOT);
        return Locale.forLanguageTag(languageTag);
    }
}

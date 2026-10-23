/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;

/**
 * Keeps only videos whose original caption is in a language on the reader's list. TikTok marks
 * one caption track as the original ({@code isOriginalCaption}); the others are its translations.
 * A video with no original track, a tag that isn't a language, or two originals that disagree
 * is always kept: the list says which languages to keep, not what to do when nothing says.
 *
 * <p>Read on every batch rather than once when the feed path is built, so a change takes effect
 * without a restart. The parse is cached by the setting's text.
 */
public final class CaptionLanguageFilter implements IFilter {
    /** How many entries of a list and how many caption tracks of a video are looked at. */
    public static final int MAX_ENTRIES = 32;

    private static final String SEPARATOR = "\\s*[,\\n]\\s*";
    /** A BCP 47 primary language subtag: two or three letters. */
    private static final Pattern PRIMARY = Pattern.compile("[a-z]{2,3}");
    /**
     * Three-letter codes of the languages that have two-letter ones, mapped to those. TikTok
     * tags captions with the two letters, so "eng, spa" matched nothing and hid every English
     * and Spanish video the list was meant to keep.
     */
    private static final java.util.Map<String, String> TWO_LETTER = twoLetterCodes();

    private static java.util.Map<String, String> twoLetterCodes() {
        java.util.Map<String, String> codes = new java.util.HashMap<>();
        for (String two : Locale.getISOLanguages()) {
            try {
                String three = new Locale(two).getISO3Language();
                if (three.length() == 3) codes.put(three, two);
            } catch (java.util.MissingResourceException ignored) {
                // No three-letter form for this one; its two letters are the only spelling.
            }
        }
        // ISO 639-2 spells twenty of them a second way, the bibliographic codes, which the
        // platform's tables don't give: "ger" and "fre" are as likely to be typed as "deu".
        String[][] bibliographic = {{"alb", "sq"}, {"arm", "hy"}, {"baq", "eu"}, {"bur", "my"},
                {"chi", "zh"}, {"cze", "cs"}, {"dut", "nl"}, {"fre", "fr"}, {"geo", "ka"},
                {"ger", "de"}, {"gre", "el"}, {"ice", "is"}, {"mac", "mk"}, {"mao", "mi"},
                {"may", "ms"}, {"per", "fa"}, {"rum", "ro"}, {"slo", "sk"}, {"tib", "bo"},
                {"wel", "cy"}};
        for (String[] pair : bibliographic) codes.put(pair[0], pair[1]);
        return codes;
    }

    private static String parsedFrom;
    private static Set<String> parsed = Collections.emptySet();

    /** The languages to keep, as lower-case primary subtags. */
    static synchronized Set<String> languages(String value) {
        if (value == null) value = "";
        if (value.equals(parsedFrom)) return parsed;
        Set<String> codes = new LinkedHashSet<>();
        for (String entry : value.trim().split(SEPARATOR)) {
            if (codes.size() >= MAX_ENTRIES) break;
            String code = primary(entry);
            if (code != null) codes.add(code);
        }
        parsedFrom = value;
        parsed = Collections.unmodifiableSet(codes);
        return parsed;
    }

    /**
     * What is wrong with a language list, for the row to say before it saves one, or null when
     * every entry is a language code. A misspelt entry would match no video, so the feed would
     * lose every captioned video in the languages the reader meant to keep.
     */
    public static String languageProblem(String value) {
        String bad = firstBadEntry(value);
        return bad == null ? null : L10n.f(
                "%1$s isn't a language code, so no video would ever match it. Use two letters, like en, es or de.",
                bad);
    }

    /** The first entry of a language list that isn't a language code, or null. */
    public static String firstBadEntry(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        for (String entry : trimmed.split(SEPARATOR)) {
            // "en,,es" left a blank entry, which the filter skips, and the check refused the list
            // with an empty name in its message. The country check skips blanks the same way.
            if (entry.isEmpty() || primary(entry) != null) continue;
            return entry.trim();
        }
        return null;
    }

    /** The primary subtag of a tag like "en", "en-US" or "pt_BR", lower case, or null. */
    public static String primary(String tag) {
        if (tag == null) return null;
        String value = tag.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = value.indexOf('-');
        String first = dash < 0 ? value : value.substring(0, dash);
        if (!PRIMARY.matcher(first).matches()) return null;
        String two = TWO_LETTER.get(first);
        return two != null ? two : first;
    }

    @Override
    public boolean getEnabled() {
        return !languages(Settings.CAPTION_LANGUAGES.get()).isEmpty();
    }

    @Override
    public boolean getFiltered(Aweme item) {
        Set<String> keep = languages(Settings.CAPTION_LANGUAGES.get());
        if (keep.isEmpty()) return false;
        String language = originalLanguage(item);
        return language != null && !keep.contains(language);
    }

    /**
     * The primary language of the video's original caption track, or null when there is none,
     * its tag isn't a language, or two original tracks disagree.
     */
    static String originalLanguage(Aweme item) {
        Object video = Reflect.property(item, "getVideo", "video");
        Object model = Reflect.property(video, "getCaptionModel", "captionModel");
        Object list = Reflect.readField(model, "captionList");
        if (!(list instanceof List<?>)) return null;
        String found = null;
        int seen = 0;
        for (Object caption : (List<?>) list) {
            if (++seen > MAX_ENTRIES) break;
            if (!Boolean.TRUE.equals(Reflect.property(caption, "isOriginalCaption", "isOriginalCaption"))) continue;
            String language = primary(Reflect.string(caption, "getLanguageCode", "languageCode"));
            if (language == null) return null;
            if (found != null && !found.equals(language)) return null;
            found = language;
        }
        return found;
    }
}

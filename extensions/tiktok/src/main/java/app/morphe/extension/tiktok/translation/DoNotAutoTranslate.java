/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.feedfilter.CaptionLanguageFilter;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Languages TikTok's automatic translation leaves alone, on top of its own Don't translate list
 * (#121).
 *
 * <p>Every automatic translation decision in TikTok compares an item's language, exactly, with
 * the codes {@code getSelectedDoNotTranslateLanguageCodes} returns. The patch asks
 * {@link #withExcluded} at the seven places that read that list and hands TikTok the list with
 * these codes added. Nothing here is written back, so TikTok's own list, which has its own
 * pages, stays as the user made it, and turning the setting off puts every decision back.
 * Translating by hand with See translation doesn't ask the list.
 */
@SuppressWarnings("unused")
public final class DoNotAutoTranslate {
    private static final String ZH = "zh";
    /** TikTok tags Chinese items with the script, so the plain code alone matched none of them. */
    private static final String ZH_HANS = "zh-Hans";
    private static final String ZH_HANT = "zh-Hant";
    /** Where Traditional characters are the default, for an entry that names no script. */
    private static final Set<String> TRADITIONAL_REGIONS = new HashSet<>(
            Arrays.asList("tw", "hk", "mo"));

    private static String parsedFrom;
    private static Set<String> parsed = Collections.emptySet();

    private DoNotAutoTranslate() {
    }

    /**
     * TikTok's list with the user's languages added, or the very same array when there is
     * nothing to add. Called with whatever TikTok's getter gave, null included.
     */
    public static String[] withExcluded(String[] original) {
        try {
            Set<String> extra = excludedCodes();
            if (extra.isEmpty()) return original;
            Set<String> merged = new LinkedHashSet<>();
            if (original != null) {
                for (String code : original) {
                    if (code != null) merged.add(code);
                }
            }
            int before = merged.size();
            merged.addAll(extra);
            HookStatus.bound("do not auto translate", "languages added");
            if (original != null && merged.size() == before && before == original.length) return original;
            return merged.toArray(new String[0]);
        } catch (Exception ex) {
            // A translation decision runs on the feed's path; the list as TikTok has it is the safe answer.
            Logger.printDebug(() -> "[DoNotAutoTranslate] left TikTok's list as it was", ex);
            return original;
        }
    }

    /**
     * What is wrong with the list the user typed, or null: the same rule the caption languages
     * use, without their warning about videos, since this list hides nothing.
     */
    public static String languageProblem(String value) {
        String bad = CaptionLanguageFilter.firstBadEntry(value);
        return bad == null ? null : L10n.f("%1$s isn't a language code. Use two letters, like en, es or de.", bad);
    }

    /** Whether any language is excluded. The row's summary and the tests ask. */
    public static boolean hasExcluded() {
        return !excludedCodes().isEmpty();
    }

    /**
     * The codes to add: each entry's primary subtag, and zh also with its script the way TikTok
     * spells it. zh-Hant or zh-TW used to add zh-Hans, leaving Simplified alone instead of the
     * Traditional the entry named.
     */
    static synchronized Set<String> excludedCodes() {
        String value = Settings.DONT_AUTO_TRANSLATE_LANGUAGES.get();
        if (value == null) value = "";
        if (value.equals(parsedFrom)) return parsed;
        Set<String> codes = new LinkedHashSet<>();
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            for (String entry : trimmed.split("\\s*[,\\n]\\s*")) {
                if (codes.size() >= CaptionLanguageFilter.MAX_ENTRIES) break;
                String code = CaptionLanguageFilter.primary(entry);
                if (code == null) continue;
                codes.add(code);
                if (code.equals(ZH)) codes.add(traditional(entry) ? ZH_HANT : ZH_HANS);
            }
        }
        parsedFrom = value;
        parsed = Collections.unmodifiableSet(codes);
        return parsed;
    }

    /**
     * The script decides when the entry names one (zh-Hans-TW is Simplified), else the region.
     * {@link TranslateInto} spells its Chinese the same way.
     */
    static boolean traditional(String entry) {
        String[] subtags = entry.trim().toLowerCase(Locale.ROOT).split("[-_]");
        boolean region = false;
        for (int index = 1; index < subtags.length; index++) {
            if (subtags[index].equals("hant")) return true;
            if (subtags[index].equals("hans")) return false;
            if (TRADITIONAL_REGIONS.contains(subtags[index])) region = true;
        }
        return region;
    }
}

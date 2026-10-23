/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.feedfilter.CaptionLanguageFilter;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The language TikTok translates into, when the reader picks one other than TikTok's own (the
 * trg_lang BMTikTok forces).
 *
 * <p>TikTok's translation service answers one question for the translation requests it builds:
 * which language to translate into. It reads its own stored translation language and, when that
 * is empty, the app language. The patch sends that answer through {@link #target} as it returns,
 * so a code typed here goes into the request instead, and an empty row leaves TikTok's answer as
 * it was. Translations TikTok already holds stay in the language they came in until it fetches
 * them again.
 */
@SuppressWarnings("unused")
public final class TranslateInto {
    private static final Set<String> TWO_LETTER = new HashSet<>(Arrays.asList(Locale.getISOLanguages()));

    private TranslateInto() {
    }

    /** TikTok's answer, or the reader's language as TikTok spells it. Called with whatever TikTok had. */
    public static String target(String tikToks) {
        try {
            String chosen = code(Settings.TRANSLATE_INTO.get());
            if (chosen == null) return tikToks;
            HookStatus.bound("translate into", "language chosen");
            return chosen;
        } catch (Exception ex) {
            // The answer is asked on the comment list's path; TikTok's own is the safe one.
            Logger.printDebug(() -> "[TranslateInto] left TikTok's language as it was", ex);
            return tikToks;
        }
    }

    /**
     * The code TikTok spells for what was typed, or null when the row is empty or holds no
     * language. The primary subtag, as TikTok sends for every language but Chinese, which it
     * sends with the script.
     */
    static String code(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String primary = CaptionLanguageFilter.primary(value);
        if (primary == null) return null;
        // Two letters are checked against the language list. Three are left only for languages
        // without two (fil), which the list doesn't hold, so they need a name the phone knows.
        if (primary.length() == 2 ? !TWO_LETTER.contains(primary) : !named(primary)) return null;
        if (primary.equals("zh")) return DoNotAutoTranslate.traditional(value) ? "zh-Hant" : "zh-Hans";
        return primary;
    }

    /** Whether the phone has a name for the language {@code code}; it hands an unknown code back as its name. */
    private static boolean named(String code) {
        String name = new Locale(code).getDisplayLanguage(Locale.ENGLISH);
        return !name.isEmpty() && !name.equalsIgnoreCase(code);
    }

    /** What is wrong with the typed language, for the row to say before it saves, or null. */
    public static String languageProblem(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        // A translation has one target, so a list is refused rather than its first entry taken.
        if (trimmed.contains(",") || trimmed.contains("\n") || trimmed.contains(" ")) {
            return L10n.t("Type one language code here, like en.");
        }
        if (code(trimmed) == null) {
            return L10n.f("%1$s isn't a language code. Use two letters, like en, es or de.", trimmed);
        }
        return null;
    }
}

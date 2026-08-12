package app.morphe.extension.tiktok.translation;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

public final class TranslationFilter {
    private TranslationFilter() {
    }

    public static Set<String> getExcludedLanguageCodes() {
        String settingValue = Settings.COMMENT_TRANSLATION_EXCLUDED_LANGUAGES.get();
        if (settingValue == null || settingValue.trim().isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> codes = new LinkedHashSet<>();
        for (String token : settingValue.split("[,;\\s]+")) {
            String code = primaryLanguageTag(token);
            if (code != null && !code.isEmpty()) {
                codes.add(code);
            }
        }
        return codes;
    }

    public static boolean isLanguageExcluded(String languageCode) {
        String primary = primaryLanguageTag(languageCode);
        if (primary == null || primary.isEmpty()) {
            return false;
        }
        return getExcludedLanguageCodes().contains(primary);
    }

    public static boolean shouldSkipCommentTranslation(Object comment) {
        if (comment == null) return false;
        String commentLanguage = primaryLanguageTag(invokeStringQuiet(comment, "getCommentLanguage"));
        if (isBlank(commentLanguage)) return false;

        return isLanguageExcluded(commentLanguage);
    }

    public static boolean shouldSkipAwemeTranslation(Object aweme, String srcLang) {
        String language = primaryLanguageTag(srcLang);
        if (isBlank(language) && aweme != null) {
            language = extractAwemeLanguage(aweme);
        }

        if (isBlank(language)) return false;
        return isLanguageExcluded(language);
    }

    public static boolean shouldSkipAwemeTranslation(Object param1, Object param2, Object param3) {
        Object aweme = null;
        String srcLang = null;

        if (param1 != null && param1.getClass().getName().contains("Aweme")) {
            aweme = param1;
        } else if (param2 != null && param2.getClass().getName().contains("Aweme")) {
            aweme = param2;
        }

        if (param2 instanceof String) {
            srcLang = (String) param2;
        } else if (param3 instanceof String) {
            srcLang = (String) param3;
        }

        return shouldSkipAwemeTranslation(aweme, srcLang);
    }

    public static String extractAwemeLanguage(Object aweme) {
        if (aweme == null) return null;
        String[] candidateMethods = {
            "getCaptionLanguage",
            "getOriginalLanguage",
            "getLanguage",
            "getSrcLanguage"
        };
        for (String methodName : candidateMethods) {
            String lang = invokeStringQuiet(aweme, methodName);
            if (!isBlank(lang)) {
                return primaryLanguageTag(lang);
            }
        }
        return null;
    }

    public static String primaryLanguageTag(String language) {
        if (isBlank(language)) return null;

        String normalized = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        int separatorIndex = normalized.indexOf('-');
        if (separatorIndex > 0) {
            normalized = normalized.substring(0, separatorIndex);
        }

        if ("in".equals(normalized)) normalized = "id";
        if ("iw".equals(normalized)) normalized = "he";
        if ("ji".equals(normalized)) normalized = "yi";
        if ("und".equals(normalized)) return null;

        return normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String invokeStringQuiet(Object instance, String methodName) {
        if (instance == null) return null;
        try {
            Method method = instance.getClass().getMethod(methodName);
            Object value = method.invoke(instance);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

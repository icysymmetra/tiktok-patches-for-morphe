/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.popups;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Lets you switch off TikTok's popup nags one label at a time.
 *
 * <p>TikTok shows most of its in-app prompts through one popup layer. Each prompt is a task
 * that carries a label, and before a task is shown the layer asks a filter whether to drop it.
 * The patch hands this class the task and the filter's answer. The label is recorded so the
 * checklist can offer it, and a label the reader ticked turns the answer into "drop it".
 *
 * <p>Nothing is blocked until a label is ticked. A prompt the account has to answer, such as a
 * CAPTCHA, verification, sign-in, age, ban or appeal screen or a consent the law asks for, is
 * never recorded and never dropped, even if its label was typed into the saved list by hand.
 */
public final class PopupLabels {
    static final int MAX_LABELS = 40;
    static final int MAX_LABEL_LENGTH = 80;

    // Word parts that make a prompt one the account has to answer. Matched inside a word.
    private static final List<String> SAFETY_CONTAINS = Arrays.asList(
            "captcha", "verif", "passw", "passcode", "consent", "complian", "gdpr", "ccpa",
            "privacy", "appeal", "violat", "suspend", "geoblock", "parental", "regulat");
    // Matched at the start of a word.
    private static final List<String> SAFETY_PREFIX = Arrays.asList(
            "login", "logout", "signin", "signup", "auth", "birth", "secur", "restrict", "legal",
            "terms", "minor", "familypair", "safety", "kyc", "identity", "account", "banned");
    // Matched as a whole word, because the short ones sit inside ordinary words ("banner").
    private static final Set<String> SAFETY_WORD = new HashSet<>(Arrays.asList(
            "age", "ban", "pin", "tos", "dsa", "coppa", "pns"));

    private static volatile Method labelGetter;
    private static final Set<String> seen = new HashSet<>();

    private PopupLabels() {
    }

    /**
     * The popup layer's answer for a task: its own filter's answer ({@code filtered}, true drops
     * the popup) or the reader's ticks.
     */
    public static boolean filter(@Nullable Object task, boolean filtered) {
        return filtered || shouldDrop(task);
    }

    /**
     * The same, for the patched call site that only asks when the layer's own filter kept the
     * popup. True drops the popup.
     */
    public static boolean shouldDrop(@Nullable Object task) {
        if (task == null) return false;
        try {
            return shouldDrop(labelOf(task), task.getClass().getName());
        } catch (Throwable error) {
            Logger.printException(() -> "Popup labels: could not read a popup's label", error);
            return false;
        }
    }

    /** Records the label and says whether the reader ticked it. */
    static boolean shouldDrop(@Nullable String rawLabel, @Nullable String owner) {
        String label = clean(rawLabel);
        if (label.isEmpty() || Setting.isPaused()) return false;
        if (isSafety(label) || isSafety(owner)) return false;
        String key = key(label);
        boolean first;
        synchronized (seen) {
            first = seen.add(key);
        }
        if (first) PopupLabelCatalog.observe(label);
        return picked().contains(key);
    }

    /** What TikTok calls the popup, or empty when it names none. */
    static String labelOf(Object task) throws ReflectiveOperationException {
        Method getter = labelGetter;
        if (getter == null || !getter.getDeclaringClass().isInstance(task)) {
            getter = task.getClass().getMethod("getElementLabel");
            labelGetter = getter;
        }
        Object value = getter.invoke(task);
        return value instanceof String ? (String) value : "";
    }

    /** Whether the label, or the class that raised it, belongs to a prompt that is never blocked. */
    public static boolean isSafety(@Nullable String text) {
        if (text == null || text.isEmpty()) return false;
        for (String word : words(text)) {
            if (SAFETY_WORD.contains(word)) return true;
            for (String part : SAFETY_CONTAINS) if (word.contains(part)) return true;
            for (String prefix : SAFETY_PREFIX) if (word.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Splits on anything that isn't a letter or digit and on camelCase, then lowercases. */
    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (!Character.isLetterOrDigit(c)) {
                flush(words, word);
                continue;
            }
            if (Character.isUpperCase(c) && word.length() > 0
                    && Character.isLowerCase(word.charAt(word.length() - 1))) {
                flush(words, word);
            }
            word.append(c);
        }
        flush(words, word);
        // The whole text as one run too, so a part split across a case change ("ReCaptcha")
        // still matches. Prefix and whole-word checks are not meant for it, so it only joins
        // the contains-style checks through the same loop.
        words.add(text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
        return words;
    }

    private static void flush(List<String> words, StringBuilder word) {
        if (word.length() > 0) words.add(word.toString().toLowerCase(Locale.ROOT));
        word.setLength(0);
    }

    static Set<String> picked() {
        Set<String> keys = new LinkedHashSet<>();
        String stored = Settings.POPUP_LABEL_PICKS.get();
        if (stored == null) return keys;
        for (String token : stored.split("[,\\n]")) {
            String key = key(token);
            if (!key.isEmpty() && !isSafety(token)) keys.add(key);
        }
        return keys;
    }

    static String clean(@Nullable String value) {
        if (value == null) return "";
        String clean = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                .replace(',', ' ').trim();
        if (clean.length() > MAX_LABEL_LENGTH) clean = clean.substring(0, MAX_LABEL_LENGTH).trim();
        return clean;
    }

    /** What the checklist saves and the gate compares: the label, trimmed and lowercased. */
    public static String key(@Nullable String label) {
        if (label == null) return "";
        return label.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** For tests: forget which labels this process has recorded. */
    static void forgetSeen() {
        synchronized (seen) {
            seen.clear();
        }
    }
}

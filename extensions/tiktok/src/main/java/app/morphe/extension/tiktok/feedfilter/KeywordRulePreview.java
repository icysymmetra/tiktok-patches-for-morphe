/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.tiktok.settings.L10n;

/**
 * What a typed word list would do to a sample caption, said in a sentence, before it is saved.
 *
 * <p>Checks go in the order the editor and the feed use: the list's own limits and quote
 * pairing first ({@link KeywordRules#problem}), then {@link KeywordRules#parse} and
 * {@link KeywordRules#firstMatch}, the same calls the caption and LIVE category filters make.
 * Nothing here reads or writes a setting, counts a hit, records feedback or leaves the phone.
 *
 * <p>Both inputs are capped, so a paste of a whole book into either box costs a bounded amount
 * of work on each keystroke. The work is a few string searches, short enough to run inline, so
 * a newer keystroke simply replaces the older answer and nothing stays queued.
 */
public final class KeywordRulePreview {
    /** Longest sample checked. TikTok captions are 4,000 characters at most. */
    public static final int MAX_SAMPLE_CHARS = 4_000;
    /** Longest rule list previewed. Saving still follows {@link FeedRuleLimits}. */
    public static final int MAX_RULE_CHARS = 20_000;
    /** Most rule entries previewed. */
    public static final int MAX_RULE_ENTRIES = 500;
    /** Longest rule entry quoted back in the answer. */
    private static final int MAX_ECHO_CHARS = 80;

    private KeywordRulePreview() {
    }

    /**
     * @param rules  the list as typed, saved or not
     * @param sample the caption or LIVE category text to try it on
     * @return the answer under the heading, never null
     */
    public static String result(@Nullable String rules, @Nullable String sample) {
        String heading = L10n.t("Word rules only, not every feed filter:");
        return heading + "\n" + answer(rules == null ? "" : rules, sample == null ? "" : sample);
    }

    private static String answer(String rules, String sample) {
        if (rules.length() > MAX_RULE_CHARS || KeywordRules.split(rules).size() > MAX_RULE_ENTRIES) {
            return L10n.t("This list is too long to preview here. Saving still follows the usual limit.");
        }
        String problem = KeywordRules.problem(rules);
        if (problem != null) {
            return L10n.f("Can't check yet: %1$s", problem);
        }
        List<KeywordRules.Rule> parsed = KeywordRules.parse(rules);
        if (parsed.isEmpty()) {
            return L10n.t("No rules yet, so nothing would match.");
        }
        if (sample.trim().isEmpty()) {
            return L10n.t("Type some sample text to see whether these rules match it.");
        }
        boolean shortened = sample.length() > MAX_SAMPLE_CHARS;
        String checked = shortened ? sample.substring(0, MAX_SAMPLE_CHARS) : sample;
        KeywordRules.Rule hit = KeywordRules.firstMatch(parsed, checked);
        String line = hit == null
                ? L10n.t("No rule matches this text.")
                : L10n.f("Matches the rule: %1$s", echo(hit.entry()));
        if (shortened) {
            line += "\n" + L10n.f("Only the first %1$s characters of the sample were checked.",
                    java.text.NumberFormat.getInstance().format(MAX_SAMPLE_CHARS));
        }
        return line;
    }

    private static String echo(String entry) {
        return entry.length() <= MAX_ECHO_CHARS ? entry : entry.substring(0, MAX_ECHO_CHARS) + "…";
    }
}

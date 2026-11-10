/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The blocked word lists, which are plain phrases plus two operators.
 *
 * <p>A phrase on its own still means what it always did: hide anything containing it. Two quoted
 * phrases joined by {@code &} mean both have to be there, and joined by {@code !&} mean the first
 * has to be there and the second must not. That is as far as this goes on purpose. The one fork
 * that took keyword lists further settled on the same two rather than regular expressions,
 * because a settings box is read by the person who typed into it.
 *
 * <p>A line that opens a rule and does not finish it is left out rather than matched literally,
 * and the row that holds the list says which line it was. Quietly treating {@code "cat" & } as a
 * phrase would hide nothing and say nothing about why.
 */
public final class KeywordRules {
    /** Both spellings of the second operator, because people write it either way round. */
    private static final Pattern COMPOSITE =
            Pattern.compile("^\\s*\"([^\"]+)\"\\s*(!?&!?)\\s*\"([^\"]+)\"\\s*$");

    /** Test-only observer for proving the feed path does not rebuild unchanged rules. */
    private static volatile Runnable parseTestHook;
    private static final Object CACHE_LOCK = new Object();
    private static volatile CachedRules cachedRules;

    private static final class CachedRules {
        final String source;
        final List<Rule> rules;

        CachedRules(String source, List<Rule> rules) {
            this.source = source;
            this.rules = rules;
        }
    }

    private KeywordRules() {
    }

    /** One entry from a list: a phrase, or two phrases and how they go together. */
    public static final class Rule {
        private final String first;
        private final String second;
        private final boolean without;
        private final String entry;

        private Rule(String first, String second, boolean without, String entry) {
            this.first = first;
            this.second = second;
            this.without = without;
            this.entry = entry;
        }

        /** The entry as it was typed, for saying which rule matched. */
        public String entry() {
            return entry;
        }

        /** @param lowerCased the text to test, already lower cased with {@link Locale#ROOT}. */
        public boolean matches(String lowerCased) {
            if (lowerCased == null || first.isEmpty() || !lowerCased.contains(first)) return false;
            if (second == null) return true;
            return without != lowerCased.contains(second);
        }
    }

    /**
     * The entries in a stored list.
     *
     * <p>Commas and newlines separate, except inside quotes: a rule is allowed to hold a phrase
     * with a comma in it, and splitting that would leave two halves that match nothing.
     */
    public static List<String> split(String stored) {
        List<String> entries = new ArrayList<>();
        if (stored == null) return entries;
        // One stray quote used to make every comma and newline after it part of the phrase, so a
        // three line list collapsed into one entry that matched nothing and said nothing about
        // why. When the quotes do not pair up they cannot be marking phrases, so they are
        // ignored and the list separates the way it always did. The row says so on save.
        boolean honourQuotes = !hasUnpairedQuote(stored);
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < stored.length(); index++) {
            char character = stored.charAt(index);
            if (honourQuotes && character == '"') quoted = !quoted;
            if (!quoted && (character == ',' || character == '\n')) {
                add(entries, current);
                continue;
            }
            current.append(character);
        }
        add(entries, current);
        return entries;
    }

    /** True when the quote characters in a stored list do not pair up. */
    static boolean hasUnpairedQuote(String stored) {
        if (stored == null) return false;
        int quotes = 0;
        for (int index = 0; index < stored.length(); index++) {
            if (stored.charAt(index) == '"') quotes++;
        }
        return quotes % 2 != 0;
    }

    private static void add(List<String> entries, StringBuilder current) {
        String entry = current.toString().trim();
        current.setLength(0);
        if (!entry.isEmpty()) entries.add(entry);
    }

    /** The rules a stored list holds, with anything unfinished left out. */
    public static List<Rule> parse(String stored) {
        Runnable hook = parseTestHook;
        if (hook != null) hook.run();
        List<Rule> rules = new ArrayList<>();
        for (String entry : split(stored)) {
            Rule rule = rule(entry);
            if (rule != null) rules.add(rule);
        }
        return rules;
    }

    /** Immutable feed rules, rebuilt only when the exact stored string changes. */
    static List<Rule> cached(String stored) {
        CachedRules found = cachedRules;
        if (found != null && Objects.equals(found.source, stored)) return found.rules;
        synchronized (CACHE_LOCK) {
            found = cachedRules;
            if (found == null || !Objects.equals(found.source, stored)) {
                found = new CachedRules(stored,
                        Collections.unmodifiableList(parse(stored)));
                cachedRules = found;
            }
            return found.rules;
        }
    }

    static void setParseTestHookForTests(Runnable hook) {
        synchronized (CACHE_LOCK) {
            parseTestHook = hook;
            cachedRules = null;
        }
    }

    /**
     * The entries that read as a rule and are not one, in the order they were typed. A phrase
     * with an ampersand and no quotes is not one of these: "AT&T" is a phrase.
     */
    public static List<String> malformed(String stored) {
        List<String> broken = new ArrayList<>();
        for (String entry : split(stored)) {
            if (rule(entry) == null) broken.add(entry);
        }
        return broken;
    }

    /** @return null when the entry opens a rule and does not finish it. */
    private static Rule rule(String entry) {
        Matcher matcher = COMPOSITE.matcher(entry);
        if (matcher.matches()) {
            String operator = matcher.group(2);
            return new Rule(lower(matcher.group(1)), lower(matcher.group(3)),
                    operator.contains("!"), entry);
        }
        if (looksComposite(entry)) return null;
        return new Rule(lower(entry), null, false, entry);
    }

    /**
     * Whether an entry was meant as a rule and did not finish.
     *
     * <p>Only an entry that opens with a quote and then puts an ampersand outside quotes. Asking
     * merely for an ampersand and a quote anywhere refused working phrases: "Q&A" holds both,
     * and so does {@code R&B "remix"}, and both were being thrown away with no way to tell.
     */
    private static boolean looksComposite(String entry) {
        if (!entry.startsWith("\"")) return false;
        boolean quoted = false;
        for (int index = 0; index < entry.length(); index++) {
            char character = entry.charAt(index);
            if (character == '"') quoted = !quoted;
            else if (character == '&' && !quoted) return true;
        }
        return false;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * What is wrong with a stored list, or null when nothing is.
     *
     * <p>Refused while the dialog is open rather than left to go wrong later: a line that opens
     * a rule and does not finish it would match nothing and say nothing about why.
     */
    public static String problem(String stored) {
        String limitProblem = FeedRuleLimits.captionProblem(stored);
        if (limitProblem != null) return limitProblem;
        if (hasUnpairedQuote(stored)) {
            return app.morphe.extension.tiktok.settings.L10n.t(
                    "One of the quote marks in that list has nothing to close it, so the rest of the line reads as one phrase. Add the missing quote or take the stray one out.");
        }
        List<String> broken = malformed(stored);
        if (broken.isEmpty()) return null;
        // One literal, because the translation gate reads the literal handed to L10n and a
        // string built from two of them is two entries it cannot find.
        return app.morphe.extension.tiktok.settings.L10n.f(
                "This line opens a rule and doesn't finish it: %1$s. Write two phrases as \"a\" & \"b\" for both, or \"a\" !& \"b\" for the first without the second.",
                broken.get(0));
    }

    /** Whether any rule in the list matches. The text is lower cased once, here. */
    public static boolean anyMatches(List<Rule> rules, String text) {
        return firstMatch(rules, text) != null;
    }

    /**
     * The first rule, in list order, that matches, or null. {@link #anyMatches} is this with the
     * answer thrown away, so the editor's preview and the feed cannot disagree.
     */
    public static Rule firstMatch(List<Rule> rules, String text) {
        if (text == null || rules.isEmpty()) return null;
        String lowerCased = text.toLowerCase(Locale.ROOT);
        for (Rule rule : rules) {
            if (rule.matches(lowerCased)) return rule;
        }
        return null;
    }
}

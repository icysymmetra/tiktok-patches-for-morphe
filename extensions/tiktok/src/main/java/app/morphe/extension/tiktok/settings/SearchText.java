/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import java.text.Normalizer;
import java.util.Locale;

/**
 * How the settings search and the Feature Gate Lab search compare what was typed with what they
 * hold. There were two copies that folded differently: the settings search stripped accents but
 * kept punctuation, and the Lab turned punctuation into spaces (so enable_live_tab matches
 * "enable live tab") but kept accents. Both sides of every comparison go through here.
 */
public final class SearchText {
    private SearchText() {}

    /** Lower case, accents taken off, and every run of anything but letters and digits one space. */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        String bare = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(bare.length());
        boolean previousWasSpace = true;
        for (int index = 0; index < bare.length(); index++) {
            char character = bare.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(character);
                previousWasSpace = false;
            } else if (!previousWasSpace) {
                normalized.append(' ');
                previousWasSpace = true;
            }
        }
        int length = normalized.length();
        if (length > 0 && normalized.charAt(length - 1) == ' ') normalized.setLength(length - 1);
        return normalized.toString();
    }

    /**
     * Whether {@code query} stands in {@code text} as whole words, with no letter or digit
     * touching it on either side. Both are already {@link #normalize normalized}, and the test is
     * Character.isLetterOrDigit, so a translated table's words count the same as English ones.
     */
    public static boolean containsWord(String text, String query) {
        if (text == null || query == null || query.isEmpty()) return false;
        for (int at = text.indexOf(query); at >= 0; at = text.indexOf(query, at + 1)) {
            int end = at + query.length();
            if ((at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1)))
                    && (end == text.length() || !Character.isLetterOrDigit(text.charAt(end)))) {
                return true;
            }
        }
        return false;
    }
}

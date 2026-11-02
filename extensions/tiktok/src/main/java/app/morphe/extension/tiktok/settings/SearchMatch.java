/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Which settings a search finds, and in what order. Kept apart from the search page so the
 * matching can be checked against a handful of plain rows, without building eleven pages.
 *
 * <p>A row matches when the folded query (lower case, accents off, see {@link SearchText}) sits
 * anywhere in its folded title, summary or category. A hit on a whole word comes first, each
 * group in page order: "counts" listed Hide verified accounts and Blocked creators, where it
 * sits inside "accounts", above the rows that are about counts. Inside each group a hit in the
 * title comes before one only in the description: "original photos" listed Photo filename, whose
 * description names Download original photos, above that switch.
 */
public final class SearchMatch {
    private SearchMatch() {
    }

    /** What the ranking reads from one row, both already passed through {@link SearchText#normalize}. */
    public interface Row {
        /** The title, summary, category and any keywords, folded. */
        String searchedText();

        /** The title alone, folded. */
        String titleText();
    }

    /** The rows {@code query} finds, best first. A query that folds to nothing finds nothing. */
    public static <T extends Row> List<T> rank(List<T> rows, String query) {
        String folded = SearchText.normalize(query == null ? "" : query);
        List<T> titleWords = new ArrayList<>();
        if (folded.isEmpty()) return titleWords;
        List<T> words = new ArrayList<>();
        List<T> insideTitle = new ArrayList<>();
        List<T> inside = new ArrayList<>();
        for (T row : rows) {
            String searched = row.searchedText();
            if (!searched.contains(folded)) continue;
            if (SearchText.containsWord(searched, folded)) {
                (SearchText.containsWord(row.titleText(), folded) ? titleWords : words).add(row);
            } else {
                (row.titleText().contains(folded) ? insideTitle : inside).add(row);
            }
        }
        titleWords.addAll(words);
        titleWords.addAll(insideTitle);
        titleWords.addAll(inside);
        return titleWords;
    }
}

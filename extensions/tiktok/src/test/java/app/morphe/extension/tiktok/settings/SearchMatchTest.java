package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** The settings search's matching, over a few plain rows rather than eleven built pages. */
public class SearchMatchTest {
    /** One row as the search sees it: a title and a summary, and the key that identifies it. */
    private static final class Fixture implements SearchMatch.Row {
        final String key;
        private final String searched;
        private final String title;

        Fixture(String title, String summary, String key) {
            this.key = key;
            this.searched = SearchText.normalize(title + " " + summary);
            this.title = SearchText.normalize(title);
        }

        @Override public String searchedText() {
            return searched;
        }

        @Override public String titleText() {
            return title;
        }
    }

    private static final List<Fixture> ROWS = Arrays.asList(
            new Fixture("Photo filename", "Name saved photos after Download original photos.", "photo_filename"),
            new Fixture("Ghost mode", "Keep your views and typing to yourself.", "ghost_mode"),
            new Fixture("Hide verified accounts", "Hide posts from accounts with a badge.", "hide_verified"),
            new Fixture("Download original photos", "Save photos at full size.", "download_original_photos"),
            new Fixture("Hide counts", "Hide like and comment counts.", "hide_counts"),
            new Fixture("Comment search", "Search the comments under a video.", "comment_search"));

    private static List<String> keys(String query) {
        List<String> keys = new ArrayList<>();
        for (Fixture row : SearchMatch.rank(ROWS, query)) keys.add(row.key);
        return keys;
    }

    @Test public void aTitleOrASummaryMatchesWhateverTheCase() {
        assertEquals(Arrays.asList("ghost_mode"), keys("GHOST"));
        assertEquals("the summary is searched as well as the title",
                Arrays.asList("ghost_mode"), keys("Typing"));
        assertEquals("accents are folded the same way on both sides",
                Arrays.asList("ghost_mode"), keys("gHóst MODE"));
    }

    @Test public void wholeWordsComeFirstAndATitleHitBeatsASummaryHit() {
        // "original photos" is a whole word in both rows, but only one says it in its title.
        assertEquals(Arrays.asList("download_original_photos", "photo_filename"), keys("original photos"));
        // "counts" is a word in Hide counts and only part of a word in Hide verified accounts.
        assertEquals(Arrays.asList("hide_counts", "hide_verified"), keys("counts"));
        // Inside a word: in a title first, then in a summary.
        assertEquals(Arrays.asList("comment_search", "hide_counts"), keys("comm"));
    }

    @Test public void anEmptyQueryAndANonMatchFindNothing() {
        assertTrue("clearing the field should leave no results", keys("").isEmpty());
        assertTrue("punctuation alone folds to nothing", keys("???").isEmpty());
        assertTrue(keys("nothing could match this").isEmpty());
    }
}

package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** One folding for the settings search and the Lab search, each keeping what the other did. */
public class SearchTextTest {
    @Test public void accentsCaseAndPunctuationFoldTheSameWayForBothSearches() {
        assertEquals("the settings search already took accents off", "cafe resume", SearchText.normalize("Café Résumé"));
        assertEquals("the Lab already read a gate key as words", "enable live tab", SearchText.normalize("enable_live_tab"));
        assertEquals("pre roll ad", SearchText.normalize("  Pre-roll   ad.. "));
        assertEquals("", SearchText.normalize(null));
        assertEquals("", SearchText.normalize("--"));
    }
}

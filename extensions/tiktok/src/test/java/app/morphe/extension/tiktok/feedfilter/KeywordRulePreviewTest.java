package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The editors for blocked caption words and hidden LIVE categories try an unsaved list on a
 * sample. The answer has to be the one the feed would give, so these tests compare it with
 * {@link KeywordRules} itself and check that looking changes nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class KeywordRulePreviewTest {
    private static final String HEADING = "Word rules only, not every feed filter:\n";

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    private static String say(String rules, String sample) {
        String shown = KeywordRulePreview.result(rules, sample);
        assertTrue("every answer says what it covers: " + shown, shown.startsWith(HEADING));
        return shown.substring(HEADING.length());
    }

    @Test public void caseAndUnicodeAreFoldedTheWayTheFeedFoldsThem() {
        assertEquals("Matches the rule: CRYPTO", say("CRYPTO", "a Crypto giveaway"));
        assertEquals("Matches the rule: École", say("École", "visite de l'ÉCOLE"));
        // Locale.ROOT, so a Turkish dotted capital I does not change the answer.
        assertEquals("Matches the rule: title", say("title", "A TITLE"));
        assertEquals("No rule matches this text.", say("cafe", "CAFÉ"));
    }

    @Test public void aLiteralPhraseMatchesAsAWholeAndReportsTheFirstRuleInOrder() {
        assertEquals("Matches the rule: free money", say("giveaway, free money, money", "Free money"));
        assertEquals("No rule matches this text.", say("free money", "free and easy money"));
        assertEquals("the first rule in list order wins",
                "Matches the rule: cat", say("cat, category", "category of cats"));
    }

    @Test public void quotedAndAndAndNotRulesFollowTheProductionGrammar() {
        String both = "\"cat\" & \"dog\"";
        assertEquals("Matches the rule: " + both, say(both, "a dog and a cat"));
        assertEquals("No rule matches this text.", say(both, "just a cat"));

        String without = "\"cat\" !& \"dog\"";
        assertEquals("Matches the rule: " + without, say(without, "just a cat"));
        assertEquals("No rule matches this text.", say(without, "a cat and a dog"));
    }

    @Test public void syntaxErrorsAreToldApartFromNoMatch() {
        String unfinished = say("\"cat\" & ", "a cat");
        assertTrue(unfinished, unfinished.startsWith("Can't check yet: "));
        assertTrue(unfinished, unfinished.contains("\"cat\" &"));
        // The words are the editor's own refusal.
        assertTrue(unfinished, unfinished.contains(KeywordRules.problem("\"cat\" & ")));

        String unpaired = say("cat, \"dog", "a cat");
        assertTrue(unpaired, unpaired.startsWith("Can't check yet: "));

        assertFalse(say("dog", "a cat").startsWith("Can't check yet"));
    }

    @Test public void emptyInputsSayWhatIsMissing() {
        assertEquals("No rules yet, so nothing would match.", say("", "a cat"));
        assertEquals("No rules yet, so nothing would match.", say(null, null));
        assertEquals("Type some sample text to see whether these rules match it.", say("cat", ""));
        assertEquals("Type some sample text to see whether these rules match it.", say("cat", "   "));
    }

    @Test public void theAnswerAgreesWithTheProductionMatcherForEveryPair() {
        String[] lists = {"cat", "\"cat\" & \"dog\"", "\"cat\" !& \"dog\"", "AT&T, Q&A", "a, b, c"};
        String[] samples = {"cat", "cat dog", "dog", "at&t ad", "q&a", "B", "none", "ÀB"};
        for (String list : lists) {
            for (String sample : samples) {
                boolean production = KeywordRules.anyMatches(KeywordRules.parse(list), sample);
                String shown = say(list, sample);
                assertEquals(list + " on " + sample, production, shown.startsWith("Matches the rule: "));
            }
        }
    }

    @Test public void largeTextIsBoundedAndSaysSo() {
        StringBuilder big = new StringBuilder();
        while (big.length() < KeywordRulePreview.MAX_SAMPLE_CHARS) big.append("filler words ");
        String sample = big + "needle";
        String shown = say("needle", sample);
        assertTrue("the match past the cap is not seen: " + shown,
                shown.startsWith("No rule matches this text."));
        assertTrue(shown, shown.contains("Only the first 4,000 characters of the sample were checked."));

        // A match inside the cap is still found, with the note.
        String early = say("filler", sample);
        assertTrue(early, early.startsWith("Matches the rule: filler"));

        StringBuilder longList = new StringBuilder();
        for (int index = 0; index <= KeywordRulePreview.MAX_RULE_ENTRIES; index++) {
            longList.append("word").append(index).append(',');
        }
        assertEquals("This list is too long to preview here. Saving still follows the usual limit.",
                say(longList.toString(), "word1"));

        StringBuilder wide = new StringBuilder();
        while (wide.length() <= KeywordRulePreview.MAX_RULE_CHARS) wide.append('x');
        assertEquals("This list is too long to preview here. Saving still follows the usual limit.",
                say(wide.toString(), "x"));
    }

    @Test public void aLongMatchingEntryIsCutWhenQuotedBack() {
        StringBuilder phrase = new StringBuilder();
        while (phrase.length() < 200) phrase.append("long ");
        String shown = say(phrase.toString().trim(), phrase.toString());
        assertTrue(shown, shown.endsWith("…"));
        assertTrue(shown, shown.length() <= "Matches the rule: ".length() + 81);
    }
}

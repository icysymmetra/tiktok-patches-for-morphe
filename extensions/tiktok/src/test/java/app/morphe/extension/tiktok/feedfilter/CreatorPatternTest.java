/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * A blocked creator entry is normally a handle to match exactly. Between slashes it is a
 * pattern instead, which covers a family of accounts rather than one at a time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CreatorPatternTest {
    /** Stands in for the author the feed model carries. */
    public static final class Author {
        public final String uid;
        public final String uniqueId;
        public final String nickname;

        Author(String uid, String uniqueId, String nickname) {
            this.uid = uid;
            this.uniqueId = uniqueId;
            this.nickname = nickname;
        }
    }

    private static Aweme video(String handle, String nickname) {
        Author author = new Author("1234", handle, nickname);
        return new Aweme() {
            @SuppressWarnings("unused")
            public Author getAuthor() {
                return author;
            }
        };
    }

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.BLOCKED_CREATORS.save("");
    }

    @Test public void aPatternTooLongToBeMeantIsRefused() {
        // A creator pattern runs against every name in every feed page, and the list travels
        // in a settings backup, so somebody else's pattern can arrive that way.
        StringBuilder huge = new StringBuilder("/");
        for (int index = 0; index < 60; index++) huge.append("(a+)+");
        huge.append("$/");
        assertTrue(huge.length() > 200);
        assertNull(AdvancedFeedRules.compiled(huge.toString()));

        // Something a person would actually type still compiles.
        assertNotNull(AdvancedFeedRules.compiled("/^news_/"));
    }

    @Test(timeout = 20_000) public void aPatternThatCouldStallTheFeedIsRefused() {
        // A group repeated against its own back-reference, on a name that almost matches: the
        // cost doubles with every character. Ten characters of pattern, so the length limit never
        // sees it, and the blocked list travels in a settings backup, which is how somebody
        // else's pattern gets here. The budget that used to stop it counted the engine's reads
        // of the name, and on a phone ICU reads a copy it made, so it never ran out there.
        ShadowToast.reset();
        String runaway = "/^(a+)+\\1$/";
        String problem = AdvancedFeedRules.creatorEntryProblem(runaway);
        assertNotNull("a pattern that can stall the feed was accepted as typed", problem);
        assertTrue(problem, problem.contains("could stall the feed"));

        // Arriving another way, a restored backup or a list kept from before, it is skipped
        // and said once, and the feed never runs it.
        Settings.BLOCKED_CREATORS.save(runaway);
        AdvancedFeedRules.CreatorFilter filter = new AdvancedFeedRules.CreatorFilter();
        StringBuilder nickname = new StringBuilder();
        for (int index = 0; index < 39; index++) nickname.append('a');
        nickname.append('!');
        for (int index = 0; index < 50; index++) {
            assertFalse(filter.getFiltered(video("someone", nickname.toString())));
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the reader is told once, and not once per video", 1, ShadowToast.shownToastCount());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("could stall the feed"));

        // Written to match a handle, a pattern keeps working.
        Settings.BLOCKED_CREATORS.save("/(shop|store|deals|discount|promo|sale)/");
        AdvancedFeedRules.CreatorFilter ordinary = new AdvancedFeedRules.CreatorFilter();
        assertTrue(ordinary.getFiltered(video("someone", "The Very Long Display Name Of "
                + "Someone With Deals To Offer You Today 12345")));
        assertFalse(ordinary.getFiltered(video("someone", "The Very Long Display Name Of "
                + "Someone Who Types A Lot Indeed 12345")));
    }

    @Test public void theShapesThatStallAreTheOnesRefused() {
        String[] refused = {
                "^(a+)+\\1$",          // a back-reference
                "(\\w)\\k<w>",          // a named one
                "^(x+)+y$",             // a repeat inside a repeated group
                "(\\w+\\s?)*$",          // two of them
                "(a|ab)+c",             // a choice inside a repeated group
                "((a|b)c){2,}",         // one level further in
                "(?x)(a+) +",           // comment mode, whose space hides the repeat
                ".*.*.*.*z",            // four wildcards: the name's length to the fifth
                ".{0,50}.{0,50}.{0,50}.{0,50}",
                "a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?aaaaaaaaaaaaaaaa",
                "(a|a)(a|a)(a|a)(a|a)(a|a)(a|a)(a|a)(a|a)(a|a)(a|a)(a|a)(a|a)(a|a)(a|a)(a|a)(a|a)",
                // A quoted or control bracket is one character, so the class ends before the
                // repeat: read as a real bracket, it swallowed (a+)+ into the class.
                "[\\Q[\\E](a+)+]",
                "([\\Q[\\E]a+)+",
                "\\c[(a+)+\\c]",
                "[\\x{5B}](a+)+",
                // Held to the start, the fifth wildcard is still one too many, and multiline
                // mode lets ^ match after every break, so it isn't held to the start at all.
                "^.*.*.*.*.*z", "(?m)^\\w+ \\w+ \\w+ \\w+$",
                // Lookarounds, atomic groups and possessive repeats are read as groups and
                // repeats like any other, so what they wrap is still refused.
                "(?=(a+)+)b", "(?<!x)(a|ab)*c", "(?>a+)+", "(a++)+", "(?:a*+b?)+",
        };
        for (String source : refused) {
            assertTrue("not refused: " + source, AdvancedFeedRules.couldStall(source));
        }
        String[] kept = {
                "dropship", "^news_", "^The Very", "^[a-z]+_[0-9]+$", "^user\\d{6,}$", "\\d{4}",
                "(shop|store|deals|discount|promo|sale)", "colou?r", "(.*)(.*)(.*)z",
                ".*(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve).*",
                "(ab)+", "(a{3})+", "(?i)^crypto", "(?<name>shop)\\d+", "\\p{L}+shop",
                "\\Q(a+)+\\E", "[(+*]+", "\\(a+\\)+", "[a-z&&[^aeiou]]+\\d?",
                // Alternatives run one at a time, so their repeats don't add up; four words held
                // to the start cost what three loose ones do; (?-x) turns comment mode off; and
                // a ] straight after [ is a member of the class.
                ".*shop.*|.*store.*", "^\\w+ \\w+ \\w+ \\w+$", "(?-x)shop", "[](]x", "[^]a]+",
                // A lookaround is one group: (?<= and (?<! aren't taken for a named group, and a
                // possessive or lazy suffix isn't taken for a second repeat.
                "(?<=@)news", "(?<!fan)shop", "(?=.*shop)\\w+", "(?!bot)\\w+", "a++b", "(?>shop|store)\\d*?",
        };
        for (String source : kept) {
            assertFalse("refused: " + source, AdvancedFeedRules.couldStall(source));
        }
    }

    @Test
    public void aPatternMatchesAFamilyOfHandles() {
        Settings.BLOCKED_CREATORS.save("/^news_/");
        AdvancedFeedRules.CreatorFilter filter = new AdvancedFeedRules.CreatorFilter();

        assertTrue(filter.getEnabled());
        assertTrue(filter.getFiltered(video("news_uk", "The Paper")));
        assertTrue(filter.getFiltered(video("NEWS_US", "Another Paper")));
        assertFalse(filter.getFiltered(video("goodnews_uk", "Good News")));
    }

    @Test
    public void aPatternAlsoReadsTheDisplayName() {
        Settings.BLOCKED_CREATORS.save("/dropship/");
        AdvancedFeedRules.CreatorFilter filter = new AdvancedFeedRules.CreatorFilter();

        assertTrue(filter.getFiltered(video("someone", "Best Dropship Deals")));
        assertFalse(filter.getFiltered(video("someone", "Woodwork")));
    }

    @Test
    public void anOrdinaryHandleStillMatchesExactly() {
        Settings.BLOCKED_CREATORS.save("@someone, other");
        AdvancedFeedRules.CreatorFilter filter = new AdvancedFeedRules.CreatorFilter();

        assertTrue(filter.getFiltered(video("someone", "Some One")));
        assertTrue(filter.getFiltered(video("other", "Other")));
        // An exact entry is not a substring match, which is what patterns are for.
        assertFalse(filter.getFiltered(video("someone_else", "Some One Else")));
    }

    @Test
    public void aPatternThatWillNotCompileIsDroppedAndSaidOnce() {
        ShadowToast.reset();
        Settings.BLOCKED_CREATORS.save("/([unclosed/");
        AdvancedFeedRules.CreatorFilter filter = new AdvancedFeedRules.CreatorFilter();

        assertFalse(filter.getFiltered(video("anyone", "Anyone")));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("nothing was said", ShadowToast.shownToastCount() >= 1);

        int shown = ShadowToast.shownToastCount();
        assertFalse(filter.getFiltered(video("someone", "Someone")));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(ShadowToast.shownToastCount() <= shown);
    }

    @Test
    public void aPatternIsCompiledOncePerEntry() {
        assertNotNull(AdvancedFeedRules.compiled("/^a/"));
        // The same entry gives back the same compiled pattern rather than a new one.
        assertTrue(AdvancedFeedRules.compiled("/^a/") == AdvancedFeedRules.compiled("/^a/"));
        assertNull(AdvancedFeedRules.compiled("/([/"));
    }

    @Test
    public void aCommaInsideAPatternIsNotASeparator() {
        // The list splits on commas, and a repetition count contains one.
        assertArrayEquals(new String[]{"/a{2,3}/", "someone"},
                AdvancedFeedRules.rawTerms("/a{2,3}/, someone"));
        assertArrayEquals(new String[]{"@one", "two"}, AdvancedFeedRules.rawTerms("@one, two"));

        Settings.BLOCKED_CREATORS.save("/^aa{1,2}b/");
        assertTrue(new AdvancedFeedRules.CreatorFilter().getFiltered(video("aaab", "Anyone")));
    }

    @Test
    public void aPatternKeepsItsCase() {
        // Lower casing the entry would turn \D into \d and match the opposite thing.
        Settings.BLOCKED_CREATORS.save("/^\\D+$/");
        AdvancedFeedRules.CreatorFilter filter = new AdvancedFeedRules.CreatorFilter();

        assertTrue(filter.getFiltered(video("letters", "Letters")));
        assertFalse(filter.getFiltered(video("1234", "1234")));
    }

    @Test
    public void aStraySlashDoesNotSwallowTheNamesAfterIt() {
        // An entry that opens a pattern and never closes it used to take the rest of the
        // list with it, and the result matched nothing at all.
        assertArrayEquals(new String[]{"/", "someone", "other"},
                AdvancedFeedRules.rawTerms("/, someone, other"));
        assertArrayEquals(new String[]{"/^news", "someone"},
                AdvancedFeedRules.rawTerms("/^news, someone"));

        Settings.BLOCKED_CREATORS.save("/, someone");
        assertTrue(new AdvancedFeedRules.CreatorFilter().getFiltered(video("someone", "Some One")));
    }

    @Test
    public void aPatternDoesNotReachPastItsLine() {
        // A pattern is written on one line; the next line starts a new entry whatever the
        // one above it left open.
        assertArrayEquals(new String[]{"/^a", "someone"},
                AdvancedFeedRules.rawTerms("/^a\nsomeone"));
        assertArrayEquals(new String[]{"/a{2,3}/", "someone"},
                AdvancedFeedRules.rawTerms("/a{2,3}/\nsomeone"));
    }

    @Test
    public void severalPatternsEachKeepTheirOwnCompiledForm() {
        // Every item on a page runs through every entry, so the entries interleave. A cache
        // of one would hand the second entry the first one's pattern.
        Settings.BLOCKED_CREATORS.save("/^news_/, /dropship/");
        AdvancedFeedRules.CreatorFilter filter = new AdvancedFeedRules.CreatorFilter();

        for (int i = 0; i < 3; i++) {
            assertTrue(filter.getFiltered(video("news_uk", "The Paper")));
            assertTrue(filter.getFiltered(video("someone", "Best Dropship Deals")));
            assertFalse(filter.getFiltered(video("someone", "Woodwork")));
        }
        assertNotNull(AdvancedFeedRules.compiled("/^news_/"));
        assertNotNull(AdvancedFeedRules.compiled("/dropship/"));
        assertTrue(AdvancedFeedRules.compiled("/^news_/") != AdvancedFeedRules.compiled("/dropship/"));
    }

    @Test
    public void oneBadPatternAmongGoodOnesIsSaidOnceAndTheOthersKeepWorking() {
        ShadowToast.reset();
        Settings.BLOCKED_CREATORS.save("/^news_/, /([bad/");
        AdvancedFeedRules.CreatorFilter filter = new AdvancedFeedRules.CreatorFilter();

        for (int i = 0; i < 4; i++) {
            assertTrue(filter.getFiltered(video("news_uk", "The Paper")));
            assertFalse(filter.getFiltered(video("someone", "Some One")));
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the bad pattern is named once, not once per video",
                1, ShadowToast.shownToastCount());
    }

    @Test
    public void slashesOnTheirOwnAreNotAPattern() {
        assertFalse(AdvancedFeedRules.isPattern("/"));
        assertFalse(AdvancedFeedRules.isPattern("//"));
        assertTrue(AdvancedFeedRules.isPattern("/a/"));
    }
}

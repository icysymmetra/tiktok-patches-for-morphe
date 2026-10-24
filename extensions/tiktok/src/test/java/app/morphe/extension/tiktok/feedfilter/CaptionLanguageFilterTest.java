package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.feedfilter.CreatorExceptionsTest.Caption;
import app.morphe.extension.tiktok.feedfilter.CreatorExceptionsTest.CaptionModel;
import app.morphe.extension.tiktok.feedfilter.CreatorExceptionsTest.Item;
import app.morphe.extension.tiktok.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Only these caption languages: a video goes when its original caption track is in a language
 * the list leaves out, and stays whenever nothing reliable says what its language is.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CaptionLanguageFilterTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.CAPTION_LANGUAGES.save("");
    }

    @After public void tearDown() {
        Settings.CAPTION_LANGUAGES.resetToDefault();
    }

    @Test public void theListIsPrimaryLanguagesAndABadEntryIsNamed() {
        assertEquals(Set.of("en", "es", "pt"), CaptionLanguageFilter.languages(" EN, es-MX\npt_BR "));
        assertTrue(CaptionLanguageFilter.languages("").isEmpty());
        assertTrue(CaptionLanguageFilter.languages(null).isEmpty());
        assertNull(CaptionLanguageFilter.languageProblem("en, es-MX, fil"));
        assertNull(CaptionLanguageFilter.languageProblem(""));
        // A doubled or leading comma is skipped by the filter, so the check lets it through.
        assertNull(CaptionLanguageFilter.languageProblem("en,,es"));
        assertNull(CaptionLanguageFilter.languageProblem(", en"));
        String problem = CaptionLanguageFilter.languageProblem("en, English");
        assertNotNull(problem);
        assertTrue(problem, problem.startsWith("English isn't a language code"));
        StringBuilder many = new StringBuilder();
        for (int index = 0; index < 100; index++) many.append((char) ('a' + index / 26)).append((char) ('a' + index % 26)).append(',');
        assertEquals("the list is bounded", CaptionLanguageFilter.MAX_ENTRIES,
                CaptionLanguageFilter.languages(many.toString()).size());
    }

    /** TikTok tags captions with two letters, so "eng" used to keep no English video at all. */
    @Test public void aThreeLetterCodeIsReadAsItsTwoLetterOne() {
        assertEquals(Set.of("en", "es", "de"), CaptionLanguageFilter.languages("eng, spa, deu"));
        assertEquals(Set.of("de", "fr", "zh"), CaptionLanguageFilter.languages("ger, fre, chi"));
        assertEquals("a language with no two-letter code keeps its own", "fil",
                CaptionLanguageFilter.primary("fil"));
        Settings.CAPTION_LANGUAGES.save("eng");
        assertFalse("an English original was hidden by eng",
                new CaptionLanguageFilter().getFiltered(captioned(new Caption("en", true))));
    }

    @Test public void aVideoInALanguageOffTheListGoesAndOnTheListStays() {
        Settings.CAPTION_LANGUAGES.save("en, pt");
        CaptionLanguageFilter filter = new CaptionLanguageFilter();
        assertTrue(filter.getEnabled());
        assertTrue("a Spanish original went through", filter.getFiltered(captioned(new Caption("es", true))));
        assertFalse(filter.getFiltered(captioned(new Caption("en-US", true))));
        assertFalse("pt_BR is Portuguese", filter.getFiltered(captioned(new Caption("pt_BR", true))));
        // The original decides, not a translation beside it.
        assertTrue(filter.getFiltered(captioned(new Caption("en", false), new Caption("ko", true))));
    }

    @Test public void nothingReliableMeansTheVideoStays() {
        Settings.CAPTION_LANGUAGES.save("en");
        CaptionLanguageFilter filter = new CaptionLanguageFilter();
        assertFalse("no captions", filter.getFiltered(new Item("bare")));
        assertFalse("only a translation", filter.getFiltered(captioned(new Caption("ko", false))));
        assertFalse("not a language tag", filter.getFiltered(captioned(new Caption("english", true))));
        assertFalse("two originals disagree",
                filter.getFiltered(captioned(new Caption("es", true), new Caption("fr", true))));
        List<Caption> late = new ArrayList<>();
        for (int index = 0; index < CaptionLanguageFilter.MAX_ENTRIES; index++) late.add(new Caption("en", false));
        late.add(new Caption("es", true));
        assertFalse("tracks past the bound aren't read", filter.getFiltered(captioned(late.toArray(new Caption[0]))));
    }

    @Test public void anEmptyListIsOffAndAChangeAppliesWithoutARestart() {
        CaptionLanguageFilter filter = new CaptionLanguageFilter();
        assertFalse(filter.getEnabled());
        assertFalse(filter.getFiltered(captioned(new Caption("es", true))));
        Settings.CAPTION_LANGUAGES.save("en");
        assertTrue("the same filter object saw the new list", filter.getFiltered(captioned(new Caption("es", true))));
    }

    private static Item captioned(Caption... captions) {
        return new Item("captioned") {
            @Override public Object getVideo() {
                CreatorExceptionsTest.Duration video = (CreatorExceptionsTest.Duration) super.getVideo();
                video.captionModel = new CaptionModel(List.of(captions));
                return video;
            }
        };
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The country a video was posted from is the same value the author row can show. Two lists
 * read it: one says which countries to keep, the other which to drop.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RegionFilterTest {
    private static Aweme from(String region) {
        return new Aweme() {
            @SuppressWarnings("unused")
            public String getRegion() {
                return region;
            }
        };
    }

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.REGION_ONLY_FROM.save("");
        Settings.REGION_NEVER_FROM.save("");
    }

    @Test
    public void theRegionFilterIsWiredIntoTheFeedFilter() throws Exception {
        java.lang.reflect.Field field = FeedItemsFilter.class.getDeclaredField("CONTENT_FILTERS");
        field.setAccessible(true);
        java.util.List<?> registered = (java.util.List<?>) field.get(null);

        boolean present = false;
        for (Object filter : registered) present |= filter instanceof RegionFilter;
        assertTrue("the region filter is not in the content set", present);
    }

    @Test
    public void twoEmptyListsFilterNothing() {
        assertFalse(new RegionFilter().getEnabled());
    }

    @Test
    public void onlyFromKeepsTheCountriesItNames() {
        Settings.REGION_ONLY_FROM.save("gb, ie");
        RegionFilter filter = new RegionFilter();

        assertTrue(filter.getEnabled());
        assertFalse(filter.getFiltered(from("GB")));
        // The list is written by hand, so its case is not the model's.
        assertFalse(filter.getFiltered(from("ie")));
        assertTrue(filter.getFiltered(from("US")));
    }

    @Test
    public void neverFromDropsTheCountriesItNames() {
        Settings.REGION_NEVER_FROM.save("RU,CN");
        RegionFilter filter = new RegionFilter();

        assertTrue(filter.getFiltered(from("RU")));
        assertTrue(filter.getFiltered(from("cn")));
        assertFalse(filter.getFiltered(from("GB")));
    }

    @Test
    public void neverFromWinsOverOnlyFrom() {
        Settings.REGION_ONLY_FROM.save("GB, US");
        Settings.REGION_NEVER_FROM.save("US");
        RegionFilter filter = new RegionFilter();

        assertFalse(filter.getFiltered(from("GB")));
        assertTrue(filter.getFiltered(from("US")));
    }

    @Test
    public void aVideoWithNoCountryIsKept() {
        Settings.REGION_ONLY_FROM.save("GB");
        RegionFilter filter = new RegionFilter();

        // The lists say what to do with a country, not what to do without one.
        assertFalse(filter.getFiltered(from(null)));
        assertFalse(filter.getFiltered(from("  ")));
        assertFalse(filter.getFiltered(new Aweme()));
    }

    @Test
    public void aListIsReadAsCountryCodesWhateverTheSpacing() {
        assertTrue(RegionFilter.countries("").isEmpty());
        assertTrue(RegionFilter.countries(null).isEmpty());
        assertTrue(RegionFilter.countries("  ,  ").isEmpty());
        assertTrue(new LinkedHashSet<>(Arrays.asList("GB", "IE", "US"))
                .equals(RegionFilter.countries(" gb ,ie\nus ")));
    }

    @Test
    public void ukIsTheCodePeopleTypeAndGbIsTheOneVideosCarry() {
        Settings.REGION_ONLY_FROM.save("UK");
        RegionFilter filter = new RegionFilter();

        assertTrue("a list of only UK filtered nothing at all", filter.getEnabled());
        assertFalse("a British video was hidden by a list that names the UK",
                filter.getFiltered(from("GB")));
        assertTrue(filter.getFiltered(from("US")));
    }

    @Test
    public void anEntryThatIsNotACountryCannotEmptyTheFeed() {
        // The only-from list is the one place a typo did more than nothing: every video's
        // country failed to match it, so the feed went blank with nothing on screen saying why.
        Settings.REGION_ONLY_FROM.save("United Kingdom");
        RegionFilter filter = new RegionFilter();

        assertFalse("a list of one unrecognised entry still filtered", filter.getEnabled());
        assertFalse(filter.getFiltered(from("GB")));
        assertFalse(filter.getFiltered(from("US")));

        // Alongside a real code it is the entry that is dropped, not the list.
        Settings.REGION_ONLY_FROM.save("GB, United Kingdom");
        RegionFilter mixed = new RegionFilter();
        assertTrue(mixed.getEnabled());
        assertFalse(mixed.getFiltered(from("GB")));
        assertTrue(mixed.getFiltered(from("US")));
    }

    @Test
    public void theRowSaysWhichEntryIsNotACountryBeforeItSavesOne() {
        assertNull("a list of real codes was refused", RegionFilter.countryProblem("gb, ie\nUS"));
        assertNull("the UK was refused", RegionFilter.countryProblem("uk"));
        assertNull(RegionFilter.countryProblem(""));
        assertNull(RegionFilter.countryProblem(null));
        assertNull("an empty entry between separators was treated as a typo",
                RegionFilter.countryProblem("GB,,IE"));

        String problem = RegionFilter.countryProblem("GB, United Kingdom");
        assertNotNull("a list with words in it was accepted", problem);
        assertTrue("the message does not name the entry that is wrong: " + problem,
                problem.contains("United Kingdom"));

        // Two letters that are not a country are the harder case: the shape is right and the
        // code is not one, which is exactly what a check on length alone would let through.
        assertNotNull(RegionFilter.countryProblem("XQ"));
    }
}

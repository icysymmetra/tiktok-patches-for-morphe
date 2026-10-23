/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Issue #2 as it stood on 0.40.0: a creator's video pager asks a commerce endpoint of its own for
 * ads, and none of them were ever in the profile list the other routes filter.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProfileAdRequestTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        FeedItemsFilter.resetDiagnosticsForTests();
        Settings.REMOVE_ADS.save(true);
    }

    @After public void tearDown() {
        Settings.REMOVE_ADS.resetToDefault();
        FeedItemsFilter.resetDiagnosticsForTests();
        HookStatus.clear();
    }

    @Test public void theRequestIsRefusedOnlyWhenTikTokWantedItAndAdsAreBeingRemoved() {
        assertFalse("a profile TikTok wanted ads on was still asked for them",
                FeedItemsFilter.allowProfileAdRequest(true));
        assertFalse("a profile TikTok did not want ads on was asked for them",
                FeedItemsFilter.allowProfileAdRequest(false));

        Settings.REMOVE_ADS.save(false);
        assertTrue("with Remove feed ads off, TikTok's own yes was overruled",
                FeedItemsFilter.allowProfileAdRequest(true));
        assertFalse("with Remove feed ads off, TikTok's own no was overruled",
                FeedItemsFilter.allowProfileAdRequest(false));
    }

    @Test public void theExportNamesTheRequestWhicheverWayItWent() {
        FeedItemsFilter.allowProfileAdRequest(false);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("profile ads"));
    }

    @Test public void aResponseThatGetsOutAnywayHasItsAdsTakenOutInPlace() {
        List<Aweme> ads = new ArrayList<>(Arrays.asList(new Aweme(), new Aweme()));

        List<?> filtered = FeedItemsFilter.filterProfileAdResponse(ads);

        assertSame("the response's own list was not the one emptied", ads, filtered);
        assertTrue("an ad was left in the profile ad response", ads.isEmpty());
        assertEquals(Collections.singletonList(
                        "ProfileAdResponse: 1 lists, 2 items, 2 removed. Last reason: ProfileAdFilter"),
                FeedFilterCounters.report());

        // The coroutine that inserts the ads reads the same field again. It finds nothing, and
        // nothing is counted twice.
        assertSame(ads, FeedItemsFilter.filterProfileAdResponse(ads));
        assertEquals(Collections.singletonList(
                        "ProfileAdResponse: 1 lists, 2 items, 2 removed. Last reason: ProfileAdFilter"),
                FeedFilterCounters.report());
    }

    @Test public void anImmutableResponseListIsReplacedByAnEmptyOne() {
        List<Aweme> ads = Collections.singletonList(new Aweme());

        List<?> filtered = FeedItemsFilter.filterProfileAdResponse(ads);

        assertNotSame(ads, filtered);
        assertTrue(filtered.isEmpty());
    }

    @Test public void withAdsKeptTheResponseIsLeftAloneAndStillCounted() {
        Settings.REMOVE_ADS.save(false);
        List<Aweme> ads = new ArrayList<>(Collections.singletonList(new Aweme()));

        assertSame(ads, FeedItemsFilter.filterProfileAdResponse(ads));
        assertEquals(1, ads.size());
        assertEquals(Collections.singletonList("ProfileAdResponse: 1 lists, 1 items, 0 removed"),
                FeedFilterCounters.report());
    }

    @Test public void anEmptyOrMissingResponseIsPassedThrough() {
        assertEquals(null, FeedItemsFilter.filterProfileAdResponse(null));
        List<Aweme> none = new ArrayList<>();
        assertSame(none, FeedItemsFilter.filterProfileAdResponse(none));
        assertTrue("an empty response was counted", FeedFilterCounters.report().isEmpty());
    }
}

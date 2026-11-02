package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The per-route counters that let an ad report name the route that delivered the ad.
 *
 * <p>Three routes can put a video on a profile page and a screenshot cannot tell them apart.
 * The filter has always written a line per batch, but only with diagnostic logging on, and no
 * reporter knows to turn that on: every report attached to an ad complaint so far arrived with
 * nothing in it about the route. These counters are always on, so the export answers the
 * question without anyone having to ask for a second report.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FeedFilterCountersTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // Off, which is the state every real report is exported in.
        BaseSettings.DEBUG.save(false);
        BaseSettings.DEBUG_LOG_FILTERS.save("feed");
        Settings.REMOVE_ADS.save(true);
        FeedItemsFilter.resetDiagnosticsForTests();
        LogBufferManager.clearLogBuffer();
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
    }

    @After public void tearDown() {
        BaseSettings.DEBUG.resetToDefault();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        Settings.REMOVE_ADS.resetToDefault();
        FeedItemsFilter.resetDiagnosticsForTests();
        LogBufferManager.clearLogBuffer();
    }

    private static AdsFilterTest.Video video(boolean advert) {
        AdsFilterTest.Video item = new LoggableVideo();
        item.ad = advert;
        return item;
    }

    /** The fixture video with the getters a log line reads, which the bare stub throws on. */
    public static class LoggableVideo extends AdsFilterTest.Video {
        @Override public String getAid() { return "v1"; }
        @Override public com.ss.android.ugc.aweme.feed.model.AwemeStatistics getStatistics() { return null; }
        @Override public String getShareUrl() { return null; }
    }

    private static List<Object> profileList(int organic, int ads) {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < organic; i++) items.add(video(false));
        for (int i = 0; i < ads; i++) items.add(video(true));
        return items;
    }

    private static String lineFor(String source) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(source + ":")) return line;
        }
        return null;
    }

    @Test public void theProfileRouteIsCountedEvenWhenItRemovesNothing() {
        // The whole point. A profile grid that ran four times and took nothing out looks
        // identical to a hook that never fired, unless something counts the lists.
        for (int pass = 0; pass < 4; pass++) {
            FeedItemsFilter.filterProfileAds(profileList(3, 0));
        }

        String line = lineFor("ProfileAwemeList");
        assertEquals("Expected the profile route to be counted, got "
                + FeedFilterCounters.report(), "ProfileAwemeList: 4 lists, 12 items, 0 removed", line);
    }

    @Test public void aRouteHandedNothingIsStillCountedAsARun() {
        // Issue #4's export carried no profile line while the reporter sat on an empty
        // Favorites tab. A delivery of nothing has to leave a line too, or an empty answer from
        // the server reads the same as a hook that never fired.
        FeedItemsFilter.filterProfileAds(new ArrayList<>());
        FeedItemsFilter.filterProfileAds(null);
        FeedItemsFilter.filterLateInsertedAds("top_view", new ArrayList<>());

        assertEquals("ProfileAwemeList: 2 lists, 0 items, 0 removed", lineFor("ProfileAwemeList"));
        assertEquals("FeedInsertion:top_view: 1 lists, 0 items, 0 removed", lineFor("FeedInsertion:top_view"));
    }

    @Test public void theDetailPagersAdEventHasALineOfItsOwn() {
        // Issue #2 is an ad seen while paging videos opened from a profile. That pager raises
        // its own ad event, and while it shared the grid's line nothing in a report could say
        // whether the pager's route ran at all.
        FeedItemsFilter.filterProfileAds(profileList(3, 0));
        FeedItemsFilter.filterProfileDetailAds(profileList(2, 1));

        assertEquals("ProfileAwemeList: 1 lists, 3 items, 0 removed", lineFor("ProfileAwemeList"));
        String detail = lineFor("ProfileDetailAdEvent");
        assertTrue(detail, detail.startsWith("ProfileDetailAdEvent: 1 lists, 3 items, 1 removed"));
    }

    @Test public void eachCacheRouteHasALineOfItsOwn() {
        // The cache chain, the play-lag insert and the reach-bottom delivery hand over one cached
        // video at a time through a check that counted nothing, so no report ever had a line for
        // them: exactly the "never ran" the table is there to rule out.
        assertTrue(FeedItemsFilter.shouldKeepChainedCache(video(false)));
        assertTrue(FeedItemsFilter.shouldKeepNormalizedCache(video(false)));
        assertFalse(FeedItemsFilter.shouldKeepPlayLagCache(video(true)));
        assertTrue(FeedItemsFilter.shouldKeepReachBottomCache(null));

        assertEquals("CachedItem:cache-chain: 1 lists, 1 items, 0 removed", lineFor("CachedItem:cache-chain"));
        assertEquals("CachedItem:cache-result: 1 lists, 1 items, 0 removed", lineFor("CachedItem:cache-result"));
        String lag = lineFor("CachedItem:play-lag");
        assertTrue(lag, lag != null && lag.startsWith("CachedItem:play-lag: 1 lists, 1 items, 1 removed"));
        assertEquals("CachedItem:reach-bottom: 1 lists, 0 items, 0 removed", lineFor("CachedItem:reach-bottom"));
    }

    @Test public void theMidRollSpliceIsRefusedAndCountedOnItsOwnLine() {
        // Issue #2's ads never appeared in any list: the mid-roll component swaps an ad into
        // the pager adapter directly, after every list hook has run. The export has to say the
        // route ran and what it did, whether the ad filter is on or off.
        assertTrue("the splice was allowed with Remove ads on", FeedItemsFilter.dropMidAd(video(true)));
        assertEquals("MidAdInsert: 1 lists, 1 items, 1 removed. Last reason: MidAdFilter",
                lineFor("MidAdInsert"));
        assertTrue(app.morphe.extension.shared.diagnostics.HookStatus.report().toString(),
                app.morphe.extension.shared.diagnostics.HookStatus.report().toString().contains("mid-roll ads"));

        Settings.REMOVE_ADS.save(false);
        assertFalse("the splice was refused with Remove ads off", FeedItemsFilter.dropMidAd(video(true)));
        assertEquals("MidAdInsert: 2 lists, 2 items, 1 removed. Last reason: MidAdFilter",
                lineFor("MidAdInsert"));

        assertFalse("a null ad is nothing to refuse", FeedItemsFilter.dropMidAd(null));
    }

    @Test public void theTopViewPreloadIsCountedOnEveryFetchAndEmptiedAsItIsRead() {
        // The cold-start TopView never passes through a list the other hooks filter: the feed
        // fetch hands the response's preload ads to the splash service before fetchFeedList
        // returns. The hook sits where the fetch reads the list, before TikTok's own "nothing to
        // preload" check, so an account served no TopView still leaves a line, and an emptied
        // list sends TikTok down that same no-ads path rather than into a handoff it never makes
        // with an empty list in stock.
        assertNull("nothing read is nothing to empty", FeedItemsFilter.dropTopViewPreload(null));
        assertEquals("TopViewPreload: 1 lists, 0 items, 0 removed", lineFor("TopViewPreload"));
        List<Object> none = new ArrayList<>();
        assertSame("an empty list goes through as itself", none, FeedItemsFilter.dropTopViewPreload(none));
        assertEquals("TopViewPreload: 2 lists, 0 items, 0 removed", lineFor("TopViewPreload"));

        List<Object> preloads = profileList(0, 2);
        List<?> carriedOn = FeedItemsFilter.dropTopViewPreload(preloads);
        assertTrue("the ads were still there with Remove ads on", carriedOn.isEmpty());
        assertEquals("TopViewPreload: 3 lists, 2 items, 2 removed. Last reason: TopViewFilter",
                lineFor("TopViewPreload"));
        assertTrue(app.morphe.extension.shared.diagnostics.HookStatus.report().toString(),
                app.morphe.extension.shared.diagnostics.HookStatus.report().toString().contains("topview preload"));

        Settings.REMOVE_ADS.save(false);
        List<Object> kept = profileList(0, 2);
        assertSame("the list goes through untouched with Remove ads off",
                kept, FeedItemsFilter.dropTopViewPreload(kept));
        assertEquals("nothing was taken out of it", 2, kept.size());
        assertEquals("TopViewPreload: 4 lists, 4 items, 2 removed. Last reason: TopViewFilter",
                lineFor("TopViewPreload"));
    }

    @Test public void aFailingTopViewReadNeverReachesTheFetch() {
        // This runs inside TikTok's cold-start fetch. Whatever goes wrong in it, the fetch must
        // get its own list back and carry on, the way filterOnRead fails open inside the getter.
        List<Object> hostile = new ArrayList<Object>() {
            @Override public int size() { throw new IllegalStateException("host list refused"); }
        };
        assertSame("the fetch got its own list back", hostile, FeedItemsFilter.dropTopViewPreload(hostile));
        String report = app.morphe.extension.shared.diagnostics.HookStatus.report().toString();
        assertTrue(report, report.contains("topview preload"));
    }

    @Test public void theTopViewFamilyIsInTheExportBeforeAnyAdIsDue() {
        // Three cold starts on the S22 were served no TopView; without this marker that run
        // reads the same as a build where the fetch was never patched.
        FeedItemsFilter.topViewPreloadInstalled();
        String report = app.morphe.extension.shared.diagnostics.HookStatus.report().toString();
        assertTrue(report, report.contains("topview preload"));
    }

    @Test public void theMidRollFamilyIsInTheExportBeforeAnyAdIsDue() {
        // A run with no mid-roll ad served used to leave no trace, which read the same as a
        // build where the component was never patched.
        FeedItemsFilter.midAdInstalled();
        String report = app.morphe.extension.shared.diagnostics.HookStatus.report().toString();
        assertTrue(report, report.contains("mid-roll ads"));
    }

    @Test public void aBatchFilteredDownToNothingSaysSoOnItsOwnLine() {
        // Upstream reported the feed freezing on the swipe after a livestream was hidden. A
        // batch legitimately can come out empty, and from the outside that is indistinguishable
        // from a feed that has stopped working. "3 items, 3 removed" does not say it either:
        // three lists of one each read the same as one list of three. This does.
        com.ss.android.ugc.aweme.feed.model.FeedItemList response =
                new com.ss.android.ugc.aweme.feed.model.FeedItemList();
        response.items = new ArrayList<>(List.of(video(true), video(true), video(true)));

        FeedItemsFilter.filter(response);

        assertTrue("the ad-only batch should have been emptied", response.items.isEmpty());
        assertEquals("FeedItemList:response: 1 lists, 3 items, 3 removed, 1 left empty. "
                + "Last reason: AdsFilter", lineFor("FeedItemList:response"));
    }

    @Test public void aBatchThatKeptSomethingIsNotCalledEmpty() {
        com.ss.android.ugc.aweme.feed.model.FeedItemList response =
                new com.ss.android.ugc.aweme.feed.model.FeedItemList();
        response.items = new ArrayList<>(List.of(video(true), video(false)));

        FeedItemsFilter.filter(response);

        assertEquals(1, response.items.size());
        assertFalse("a batch with a video left in it is not an empty one",
                String.valueOf(lineFor("FeedItemList:response")).contains("left empty"));
    }

    @Test public void anElementThatIsNotAVideoIsCountedAndNamedOnce() {
        // 64 items, 0 removed, two ads watched: the report could not say whether nothing
        // matched a rule or nothing was ever tested. On 46.2.3 the lists are videos, so this is
        // the line a build that changes that would leave.
        List<Object> mixed = profileList(2, 1);
        mixed.add("not a video");
        mixed.add("not a video either");
        FeedItemsFilter.filterProfileAds(mixed);

        String line = lineFor("ProfileAwemeList");
        assertTrue(line, line.startsWith("ProfileAwemeList: 1 lists, 5 items, 1 removed, 2 not videos"));
        String report = LogBufferManager.buildExportText();
        String named = "ProfileAwemeList was handed java.lang.String, which is not a video";
        assertTrue(report, report.contains(named));
        assertEquals("named once per class, not once per item",
                report.indexOf(named), report.lastIndexOf(named));
    }

    @Test public void withLoggingOnTheProfileRouteSaysWhatItKept() {
        BaseSettings.DEBUG.save(true);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        FeedItemsFilter.filterProfileAds(profileList(2, 1));

        String report = LogBufferManager.buildExportText();
        // The redactor takes the id out of the export, which is right; the shape is the point.
        assertTrue(report, report.contains("ProfileAwemeList kept aid=[omitted] ad=false softAd=false rawAd=false"
                + " promo=false commission=false playCount=-1"));
        // Two organic videos kept, one line each; the ad is the removed line's business.
        assertEquals(report, 2, report.split("ProfileAwemeList kept aid=", -1).length - 1);
    }

    @Test public void aRouteThatRemovesSomethingSaysSoAndNamesAReason() {
        FeedItemsFilter.filterProfileAds(profileList(2, 1));

        String line = lineFor("ProfileAwemeList");
        assertTrue(line, line.startsWith("ProfileAwemeList: 1 lists, 3 items, 1 removed"));
        assertTrue(line, line.contains("Last reason: "));
    }

    @Test public void theLateInsertionRouteIsCountedSeparatelyFromTheProfileOne() {
        FeedItemsFilter.filterProfileAds(profileList(2, 0));
        FeedItemsFilter.filterLateInsertedAds("golden_house", profileList(1, 1));

        assertEquals("ProfileAwemeList: 1 lists, 2 items, 0 removed", lineFor("ProfileAwemeList"));
        String insertion = lineFor("FeedInsertion:golden_house");
        assertTrue(String.valueOf(insertion),
                insertion.startsWith("FeedInsertion:golden_house: 1 lists, 2 items, 1 removed"));
    }

    @Test public void theExportCarriesTheCountersWithDiagnosticLoggingOff() {
        FeedItemsFilter.filterProfileAds(profileList(2, 1));
        // Whatever else put the reporter in front of the export form. The counters ride along
        // with it; they are not what makes a report worth sending, which the case below pins.
        LogBufferManager.appendEvent(DiagnosticCategory.FEED_AND_NAVIGATION,
                "Test", "INFO", "something worth reporting");

        String report = LogBufferManager.buildExportText();
        assertFalse("Diagnostic logging must stay off for this case", BaseSettings.DEBUG.get());
        assertTrue(report, report.contains("[FEED FILTER]"));
        assertTrue(report, report.contains("ProfileAwemeList: 1 lists, 3 items, 1 removed"));
    }

    @Test public void countersAloneDoNotMakeAnOtherwiseEmptyReportWorthSending() {
        // A route that has only ever counted is not a finding. If it made the report non-empty,
        // nobody would ever be told there is nothing to send.
        FeedItemsFilter.filterProfileAds(profileList(3, 0));

        assertEquals("", LogBufferManager.buildExportText());
    }

    @Test public void clearingTheDiagnosticDataZeroesTheCountersAndUndoPutsThemBack() {
        FeedItemsFilter.filterProfileAds(profileList(2, 1));
        assertFalse(FeedFilterCounters.report().isEmpty());

        LogBufferManager.clearLogBuffer();
        assertEquals(List.of(), FeedFilterCounters.report());

        // The feed keeps scrolling while Undo is still on offer, so what is counted in between
        // has to survive the undo rather than be replaced by it.
        FeedItemsFilter.filterProfileAds(profileList(1, 0));
        assertTrue(LogBufferManager.canUndoClear());
        assertEquals(LogBufferManager.UndoResult.RESTORED, LogBufferManager.undoClear());

        String line = lineFor("ProfileAwemeList");
        assertEquals("Expected the cleared counts added to what was counted since, got " + line,
                "ProfileAwemeList: 2 lists, 4 items, 1 removed",
                line.substring(0, line.indexOf(". Last reason")));
    }

    @Test public void aRouteWithADifferentFilterSelectionIsLeftOutOfTheExport() {
        FeedItemsFilter.filterProfileAds(profileList(2, 1));
        BaseSettings.DEBUG_LOG_FILTERS.save("errors");

        String report = LogBufferManager.buildExportText();
        assertFalse(report, report.contains("[FEED FILTER]"));
    }

    @Test public void theRunningCountAddsEveryPostRouteAndOutlivesADiagnosticClear() {
        FeedFilterCounters.resetSessionForTests();
        FeedFilterCounters.removedItems("FeedItemList", 3, "AdsFilter");
        FeedFilterCounters.removedItems("SearchAds", 2, "searchAd");
        FeedFilterCounters.removedItems("FeedItemList", 0, "AdsFilter");
        assertEquals(5, FeedFilterCounters.sessionRemoved());
        FeedFilterCounters.snapshotAndClear();
        FeedFilterCounters.clear();
        assertEquals("a diagnostic clear keeps the reader's count", 5, FeedFilterCounters.sessionRemoved());
        FeedFilterCounters.removedItems("FeedItemList", 1, "KeywordFilter");
        assertEquals(6, FeedFilterCounters.sessionRemoved());
        FeedFilterCounters.resetSessionForTests();
    }

    @Test public void tagsBannersAndCachedRetriesStayOutOfTheRunningCount() {
        FeedFilterCounters.resetSessionForTests();
        // Every render of a caption strip reports its hidden tags again.
        FeedFilterCounters.removed(LocationBadgeFilter.STRIP_SOURCE, 1, "place");
        FeedFilterCounters.removed(LocationBadgeFilter.STRIP_SOURCE, 1, "place");
        assertEquals(0, FeedFilterCounters.sessionRemoved());
        assertFalse("a cached ad is still refused", FeedItemsFilter.shouldKeepPlayLagCache(video(true)));
        assertEquals("a cached retry isn't a new removal", 0, FeedFilterCounters.sessionRemoved());
        FeedFilterCounters.resetSessionForTests();
    }
}

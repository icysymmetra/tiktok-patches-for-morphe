package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;
import com.ss.android.ugc.aweme.feed.model.RecReasonsStruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The For You fill-in rule: TikTok's for_you_page_999 videos with no recommendation reason leave
 * a For You batch, including batches that contain nothing else, and the filter report says
 * how much of each batch came from which pool.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class UnpersonalizedForYouFilterTest {
    private static final String FILL_IN = "for_you_page_999";

    public static final class Author {
        final String handle;
        Author(String handle) { this.handle = handle; }
        public String getUniqueId() { return handle; }
        public String getUid() { return "uid-" + handle; }
        public String getSecUid() { return "sec-" + handle; }
    }

    public static class Item extends Aweme {
        final String aid;
        final String source;
        final RecReasonsStruct reasons;
        Author author = new Author("poster");
        Object video;

        Item(String aid, String source, RecReasonsStruct reasons) {
            this.aid = aid;
            this.source = source;
            this.reasons = reasons;
        }

        @Override public String getAid() { return aid; }
        @Override public String getItemDistributeSource() { return source; }
        @Override public RecReasonsStruct getRecReasonsStruct() { return reasons; }
        @Override public String getShareUrl() { return null; }
        public Object getAuthor() { return author; }
        public String getDesc() { return ""; }
        public Object getVideo() { return video; }
    }

    public static final class Duration {
        public long getDuration() { return 100_000; }
    }

    /** A video whose distribution must never be read: the switch is off. */
    public static final class Untouchable extends Item {
        Untouchable(String aid) { super(aid, null, null); }
        @Override public String getItemDistributeSource() { throw new AssertionError("read with the switch off"); }
        @Override public RecReasonsStruct getRecReasonsStruct() { throw new AssertionError("read with the switch off"); }
    }

    @Before public void reset() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.REMOVE_ADS.save(false);
        Settings.BLOCKED_CAPTION_WORDS.save("");
        Settings.BLOCKED_CREATORS.save("");
        Settings.LOCAL_HIDDEN_CREATORS.save("");
        Settings.CREATOR_FILTER_EXCEPTIONS.save("");
        Settings.MAX_VIDEO_SECONDS.save(0);
        Settings.MAX_PUBLICATION_AGE_DAYS.save(0);
        Settings.MAX_VIEWS_PER_LIKE.save(0);
        Settings.HIDE_UNPERSONALIZED_FOR_YOU.save(true);
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    @After public void restore() {
        Settings.REMOVE_ADS.resetToDefault();
        Settings.BLOCKED_CREATORS.resetToDefault();
        Settings.HIDE_UNPERSONALIZED_FOR_YOU.resetToDefault();
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    private static Item fill(String aid) { return new Item(aid, FILL_IN, null); }

    private static List<String> survivors(Item... items) {
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(Arrays.asList(items));
        FeedItemsFilter.filter(page);
        List<String> aids = new ArrayList<>();
        for (Object item : page.items) aids.add(((Aweme) item).getAid());
        return aids;
    }

    private static String reportLine(String source) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(source + ":")) return line;
        }
        return null;
    }

    @Test public void fillInLeavesForYouAndEverythingTikTokPickedStays() {
        Item picked = new Item("picked", FILL_IN, new RecReasonsStruct());
        Item personal = new Item("personal", "for_you_page_1", null);
        Item unlabelled = new Item("unlabelled", null, null);
        assertEquals(List.of("picked", "personal", "unlabelled"),
                survivors(fill("fill"), picked, personal, unlabelled));
    }

    @Test public void aBatchThatIsAllFillInIsRejectedAndCounted() {
        assertEquals(List.of(), survivors(fill("a"), fill("b"), fill("c")));
        String line = reportLine(FeedItemsFilter.FOR_YOU_DISTRIBUTION_SOURCE);
        assertTrue(FeedFilterCounters.report().toString(), line != null && line.contains("3 removed"));
        String feed = reportLine("FeedItemList:response");
        assertTrue("the emptied batch was missing from diagnostics: " + feed,
                feed != null && feed.contains("left empty"));
    }

    @Test public void aRejectedBatchNeverPutsBackWhatAnyRuleTookOut() {
        Settings.BLOCKED_CREATORS.save("blocked");
        Item blocked = fill("blocked");
        blocked.author = new Author("blocked");
        assertEquals(List.of(), survivors(blocked, fill("a"), fill("b")));
    }

    @Test public void anExceptedCreatorsFillInStays() {
        Settings.CREATOR_FILTER_EXCEPTIONS.save("friend");
        Item friend = fill("friend");
        friend.author = new Author("friend");
        assertEquals(List.of("friend", "personal"),
                survivors(fill("fill"), friend, new Item("personal", "for_you_page_1", null)));
    }

    @Test public void theReportCountsEachPoolNextToWhatTheRuleTookOut() {
        FeedFilterCounters.resetSessionForTests();
        survivors(fill("a"), fill("b"), new Item("c", "for_you_page_1", null));
        String line = reportLine(FeedItemsFilter.FOR_YOU_DISTRIBUTION_SOURCE);
        assertEquals("ForYouDistribution: 1 lists, 3 items, 2 removed. Last reason: UnpersonalizedForYouFilter."
                + " Kinds: for_you_page_999 2, for_you_page_1 1", line);
        // The pool line is a share of the list's own count, so the label counts the two once.
        assertEquals(2, FeedFilterCounters.sessionRemoved());
    }

    @Test public void switchedOffItReadsNothingAndCountsNothing() {
        Settings.HIDE_UNPERSONALIZED_FOR_YOU.save(false);
        Settings.BLOCKED_CREATORS.save("someone_else");
        assertEquals(List.of("a", "b"), survivors(new Untouchable("a"), new Untouchable("b")));
        assertEquals(null, reportLine(FeedItemsFilter.FOR_YOU_DISTRIBUTION_SOURCE));
    }

    @Test public void metraRejectsAnEntireFillInBatchWithoutEndingPagination() {
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(List.of(fill("a"), fill("b")));
        page.hasMore = 1;
        page.cursor = 123;
        FeedItemsFilter.filter(page);
        assertTrue("Anti-slop put every rejected video back into the feed", page.items.isEmpty());
        assertEquals("filtering a batch must not end pagination", 1, page.hasMore);
        assertEquals(123, page.cursor);
    }

    @Test public void metraRejectsFillInFromColdCache() {
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(List.of(fill("cached")));
        assertEquals("an all-slop cache should be rejected", null, FeedItemsFilter.filterCachedFeedList(page));
    }

    @Test public void metraRejectsFillInAtEverySingleCacheBoundary() {
        assertFalse("normalizer let slop bypass the list filter", FeedItemsFilter.shouldKeepNormalizedCache(fill("normal")));
        assertFalse("play-lag cache bypassed Anti-slop", FeedItemsFilter.shouldKeepPlayLagCache(fill("lag")));
        assertFalse("reach-bottom cache bypassed Anti-slop", FeedItemsFilter.shouldKeepReachBottomCache(fill("bottom")));
        assertFalse("cache chain bypassed Anti-slop", FeedItemsFilter.shouldKeepChainedCache(fill("chain")));
    }

    @Test public void metraRejectsFinalCachedInsertsOnlyInForYou() {
        com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel forYou =
                new com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel() {
                    @Override public String getEventType() { return "homepage_hot"; }
                };
        com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel following =
                new com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel() {
                    @Override public String getEventType() { return "homepage_follow"; }
                };
        Item personal = new Item("personal", "for_you_page_1", null);
        List<Aweme> inserted = List.of(fill("final-cache"), personal);
        assertEquals(List.of(personal), FeedItemsFilter.filterInsertedFeedItems(
                forYou, 0, "golden_house", inserted));
        assertEquals(inserted, FeedItemsFilter.filterInsertedFeedItems(
                following, 0, "golden_house", inserted));
    }

    @Test public void metraCacheRuleKeepsPickedAndExceptedVideosAndRespectsItsSwitch() {
        assertTrue(FeedItemsFilter.shouldKeepNormalizedCache(new Item("picked", FILL_IN, new RecReasonsStruct())));
        assertTrue(FeedItemsFilter.shouldKeepNormalizedCache(new Item("personal", "for_you_page_1", null)));
        Settings.CREATOR_FILTER_EXCEPTIONS.save("friend");
        Item friend = fill("friend");
        friend.author = new Author("friend");
        assertTrue(FeedItemsFilter.shouldKeepNormalizedCache(friend));
        Settings.HIDE_UNPERSONALIZED_FOR_YOU.save(false);
        assertTrue(FeedItemsFilter.shouldKeepNormalizedCache(new Untouchable("off")));
    }

    @Test public void metraQualityFallbackCannotRestoreAnAntiSlopRejection() {
        Settings.MAX_VIDEO_SECONDS.save(1);
        try {
            Item longFill = fill("long-fill");
            longFill.video = new Duration();
            assertTrue("the quality fallback restored a hard Anti-slop rejection", survivors(longFill).isEmpty());
            Settings.HIDE_UNPERSONALIZED_FOR_YOU.save(false);
            assertEquals("the quality-only fallback must still work", List.of("long-fill"), survivors(longFill));
        } finally {
            Settings.MAX_VIDEO_SECONDS.resetToDefault();
        }
    }
}

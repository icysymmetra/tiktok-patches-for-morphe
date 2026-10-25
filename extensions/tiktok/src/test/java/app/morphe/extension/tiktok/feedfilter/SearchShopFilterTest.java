/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TikTok Shop cards in search results (issue #21), and the shapes the export counts so that a
 * report from an account that sees a Shop block names it. The fakes carry the field names
 * SearchMixFeed and its base class have on 47.0.3.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SearchShopFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for DynamicPatch, the server-drawn card TikTok's Products block comes from. */
    public static class Patch {
        public Boolean isEcom;

        Patch(Boolean ecom) {
            this.isEcom = ecom;
        }
    }

    /** Stands in for SearchMixFeed: the fields the filter reads, and nothing it says. */
    public static class Card {
        public int feedType = 1;
        public Object aweme;
        public Object productStruct;
        public Object shopCard;
        public Patch dynamicPatch;
        public Object multiAdCard;
        public boolean adOrContainAd;

        public boolean isAdOrContainAd() {
            return adOrContainAd;
        }
    }

    /** Stands in for SearchMixFeedList. */
    public static class Result {
        List mItems;

        Result(List items) {
            this.mItems = items;
        }
    }

    private static Card video() {
        Card card = new Card();
        card.aweme = new Object();
        return card;
    }

    private static String searchLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(FeedItemsFilter.SEARCH_SOURCE + ":")) return line;
        }
        return null;
    }

    @Before public void setUp() {
        Settings.REMOVE_ADS.save(false);
        Settings.HIDE_SEARCH_SHOP.save(true);
        FeedItemsFilter.resetDiagnosticsForTests();
        LogBufferManager.clearLogBuffer();
    }

    @After public void tearDown() {
        Settings.REMOVE_ADS.resetToDefault();
        Settings.HIDE_SEARCH_SHOP.resetToDefault();
        FeedItemsFilter.resetDiagnosticsForTests();
        LogBufferManager.clearLogBuffer();
    }

    @Test public void everyShopShapeIsAShopCard() {
        Card block = new Card();
        block.feedType = 65;
        block.dynamicPatch = new Patch(Boolean.TRUE);
        assertTrue("the Products block from a Shop dynamic patch", FeedItemsFilter.isSearchShopProduct(block));

        Card single = new Card();
        single.feedType = FeedItemsFilter.SHOP_PRODUCT_FEED_TYPE;
        assertTrue("a single product card", FeedItemsFilter.isSearchShopProduct(single));

        Card product = new Card();
        product.productStruct = new Object();
        assertTrue("a product card that is not a video", FeedItemsFilter.isSearchShopProduct(product));

        Card shop = new Card();
        shop.shopCard = new Object();
        assertTrue("a shop card that is not a video", FeedItemsFilter.isSearchShopProduct(shop));
    }

    @Test public void videosAndOtherDrawnCardsAreNotShopCards() {
        Card withCommerce = video();
        withCommerce.productStruct = new Object();
        withCommerce.shopCard = new Object();
        assertFalse("a video carrying commerce data is the feed filter's business",
                FeedItemsFilter.isSearchShopProduct(withCommerce));

        Card drawn = new Card();
        drawn.dynamicPatch = new Patch(Boolean.FALSE);
        assertFalse(FeedItemsFilter.isSearchShopProduct(drawn));
        drawn.dynamicPatch = new Patch(null);
        assertFalse(FeedItemsFilter.isSearchShopProduct(drawn));

        assertFalse(FeedItemsFilter.isSearchShopProduct(new Card()));
        assertFalse(FeedItemsFilter.isSearchShopProduct(video()));
        assertFalse(FeedItemsFilter.isSearchShopProduct(null));
    }

    @Test public void thePageLosesItsShopCardsAndKeepsTheRest() {
        Card first = video();
        Card block = new Card();
        block.dynamicPatch = new Patch(Boolean.TRUE);
        Card second = video();
        Card single = new Card();
        single.feedType = FeedItemsFilter.SHOP_PRODUCT_FEED_TYPE;

        Result result = new Result(new ArrayList<>(Arrays.asList(first, block, second, single)));
        FeedItemsFilter.filterSearchAds(result);

        assertEquals(Arrays.asList(first, second), result.mItems);
        String line = searchLine();
        assertNotNull(FeedFilterCounters.report().toString(), line);
        assertTrue(line, line.startsWith(FeedItemsFilter.SEARCH_SOURCE + ": 1 lists, 4 items, 2 removed. Last reason: searchShop"));
    }

    @Test public void withTheSwitchOffOnlyAdvertsGo() {
        Settings.HIDE_SEARCH_SHOP.save(false);
        Card block = new Card();
        block.dynamicPatch = new Patch(Boolean.TRUE);
        List items = new ArrayList<>(Arrays.asList(video(), block));
        Result result = new Result(items);
        FeedItemsFilter.filterSearchAds(result);
        assertSame("with both switches off the page was rewritten", items, result.mItems);

        Settings.REMOVE_ADS.save(true);
        Card advert = video();
        advert.adOrContainAd = true;
        Card keep = video();
        result = new Result(new ArrayList<>(Arrays.asList(keep, advert, block)));
        FeedItemsFilter.filterSearchAds(result);
        assertEquals(Arrays.asList(keep, block), result.mItems);
    }

    @Test public void advertsAndShopCardsGoTogetherEachUnderItsOwnReason() {
        Settings.REMOVE_ADS.save(true);
        Card keep = video();
        Card advert = video();
        advert.multiAdCard = new Object();
        Card product = new Card();
        product.productStruct = new Object();

        Result result = new Result(new ArrayList<>(Arrays.asList(keep, advert, product)));
        FeedItemsFilter.filterSearchAds(result);

        assertEquals(Arrays.asList(keep), result.mItems);
        assertTrue(searchLine(), searchLine().startsWith(FeedItemsFilter.SEARCH_SOURCE + ": 1 lists, 3 items, 2 removed."));
    }

    @Test public void aPageOfNothingButShopCardsIsLeftAlone() {
        Card block = new Card();
        block.dynamicPatch = new Patch(Boolean.TRUE);
        Card single = new Card();
        single.feedType = FeedItemsFilter.SHOP_PRODUCT_FEED_TYPE;
        List items = new ArrayList<>(Arrays.asList(block, single));
        Result result = new Result(items);

        FeedItemsFilter.filterSearchAds(result);

        assertSame(items, result.mItems);
        // Counted only once a page was rewritten: a refused page removed nothing.
        String line = searchLine();
        assertTrue(line, line.startsWith(FeedItemsFilter.SEARCH_SOURCE + ": 1 lists, 2 items, 0 removed"));
        assertFalse(line, line.contains("Last reason"));
    }

    @Test public void aPageOfOneAdvertAndOneShopBlockLosesOnlyTheAdvertWithBothSwitchesOn() {
        Settings.REMOVE_ADS.save(true);
        Card advert = video();
        advert.adOrContainAd = true;
        Card block = new Card();
        block.dynamicPatch = new Patch(Boolean.TRUE);

        Result result = new Result(new ArrayList<>(Arrays.asList(advert, block)));
        FeedItemsFilter.filterSearchAds(result);

        // Remove ads alone took the advert before the Shop switch existed, and a Shop switch
        // that would empty the page must not give the advert back.
        assertEquals(Arrays.asList(block), result.mItems);
        assertTrue(searchLine(), searchLine().startsWith(FeedItemsFilter.SEARCH_SOURCE + ": 1 lists, 2 items, 1 removed. Last reason: searchAd"));
    }

    @Test public void theExportNamesTheShapeOfEveryCardAndNothingItSays() {
        // Both switches off: the shapes are counted whatever is switched on.
        Settings.HIDE_SEARCH_SHOP.save(false);
        Card block = new Card();
        block.feedType = 65;
        block.dynamicPatch = new Patch(Boolean.TRUE);
        Card drawn = new Card();
        drawn.feedType = 65;
        drawn.dynamicPatch = new Patch(Boolean.FALSE);
        Card single = new Card();
        single.feedType = FeedItemsFilter.SHOP_PRODUCT_FEED_TYPE;
        single.productStruct = "a product name that must never reach the export";
        Card bought = video();
        bought.multiAdCard = new Object();

        FeedItemsFilter.filterSearchAds(new Result(new ArrayList<>(Arrays.asList(video(), video(), block, drawn, single, bought))));

        String line = searchLine();
        assertEquals(FeedItemsFilter.SEARCH_SOURCE + ": 1 lists, 6 items, 0 removed. Kinds: type 1 video 2, "
                + "type 1 video advert 1, type 65 Shop patch 1, type 65 patch 1, type 96 product 1", line);
        // Whatever else puts the reader in front of the export form; the counters ride along.
        LogBufferManager.appendEvent(app.morphe.extension.shared.diagnostics.DiagnosticCategory.FEED_AND_NAVIGATION,
                "Test", "INFO", "something worth reporting");
        String report = LogBufferManager.buildExportText();
        assertTrue("the export has no search card kinds: " + report, report.contains("type 96 product 1"));
        assertFalse("the export carried what a card says", report.contains("a product name"));
    }
}

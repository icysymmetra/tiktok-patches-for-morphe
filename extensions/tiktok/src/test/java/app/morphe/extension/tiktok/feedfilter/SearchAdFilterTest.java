/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeRawAd;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Search result cards are not Awemes, so the card's own verdict is the reliable test. The
 * video it wraps is checked too, for a card that does not admit to being an advert.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SearchAdFilterTest {
    /** Stands in for SearchMixFeed: the app's own ad flag plus the shapes it carries. */
    public static class Card {
        public boolean adOrContainAd;
        public Object multiAdCard;
        public Object aiAdCard;
        public Object brandZoneCard;
        public Object aweme;

        public boolean isAdOrContainAd() {
            return adOrContainAd;
        }
    }

    /** Stands in for SearchMixFeedList, whose items the response walks before the grid does. */
    public static class Result {
        List mItems;

        Result(List items) {
            this.mItems = items;
        }
    }

    private static Card plain() {
        return new Card();
    }

    /** The stub throws for every getter, so a usable video answers all four ad questions. */
    private static Aweme video(boolean advert) {
        return new Aweme() {
            @Override
            public boolean isAd() {
                return advert;
            }

            @Override
            public boolean isSoftAd() {
                return false;
            }

            @Override
            public AwemeRawAd getAwemeRawAd() {
                return null;
            }

            @Override
            public boolean isWithPromotionalMusic() {
                return false;
            }
        };
    }

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.REMOVE_ADS.save(true);
    }

    @Test
    public void aCardTheAppCallsAnAdIsAnAd() {
        Card card = plain();
        card.adOrContainAd = true;

        assertTrue(FeedItemsFilter.isSearchAd(card));
    }

    @Test
    public void everyBoughtCardShapeCounts() {
        Card multi = plain();
        multi.multiAdCard = new Object();
        assertTrue(FeedItemsFilter.isSearchAd(multi));

        Card ai = plain();
        ai.aiAdCard = new Object();
        assertTrue(FeedItemsFilter.isSearchAd(ai));

        Card brand = plain();
        brand.brandZoneCard = new Object();
        assertTrue(FeedItemsFilter.isSearchAd(brand));
    }

    @Test
    public void aCardWrappingAnAdVideoIsAnAd() {
        Card card = plain();
        card.aweme = video(true);

        assertTrue(FeedItemsFilter.isSearchAd(card));
    }

    @Test
    public void anOrdinaryResultIsKept() {
        assertFalse(FeedItemsFilter.isSearchAd(plain()));
        assertFalse(FeedItemsFilter.isSearchAd(null));

        Card withVideo = plain();
        withVideo.aweme = video(false);
        assertFalse(FeedItemsFilter.isSearchAd(withVideo));
    }

    @Test
    public void theGridNeverSeesTheAdverts() {
        Card advert = plain();
        advert.adOrContainAd = true;
        Card bought = plain();
        bought.multiAdCard = new Object();
        Card first = plain();
        Card second = plain();

        Result result = new Result(new ArrayList<>(Arrays.asList(first, advert, second, bought)));
        FeedItemsFilter.filterSearchAds(result);

        assertEquals(Arrays.asList(first, second), result.mItems);
    }

    @Test
    public void aCleanPageIsLeftAlone() {
        List items = new ArrayList<>(Arrays.asList(plain(), plain()));
        Result result = new Result(items);

        FeedItemsFilter.filterSearchAds(result);

        // Nothing to remove, so the native list is not replaced.
        assertSame(items, result.mItems);
    }

    @Test
    public void theFilterDoesNothingWhenAdsAreAllowed() {
        Settings.REMOVE_ADS.save(false);

        Card advert = plain();
        advert.adOrContainAd = true;
        List items = new ArrayList<>(Collections.singletonList(advert));
        Result result = new Result(items);

        FeedItemsFilter.filterSearchAds(result);

        assertSame(items, result.mItems);
    }

    @Test
    public void aPageThatLooksEntirelyLikeAdvertsIsLeftAlone() {
        // A whole page of adverts is far less likely than one of the card shapes being
        // wrong, and an empty grid gives the user nothing to go on.
        Card first = plain();
        first.adOrContainAd = true;
        Card second = plain();
        second.multiAdCard = new Object();

        List items = new ArrayList<>(Arrays.asList(first, second));
        Result result = new Result(items);
        FeedItemsFilter.filterSearchAds(result);

        assertSame(items, result.mItems);
    }

    @Test
    public void aResponseWithNoItemsIsSurvivable() {
        FeedItemsFilter.filterSearchAds(null);
        FeedItemsFilter.filterSearchAds(new Object());
        FeedItemsFilter.filterSearchAds(new Result(null));
        FeedItemsFilter.filterSearchAds(new Result(new ArrayList<>()));
    }
}

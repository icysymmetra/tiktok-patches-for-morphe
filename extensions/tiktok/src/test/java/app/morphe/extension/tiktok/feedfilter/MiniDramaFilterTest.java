package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.*;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Hide mini dramas (upstream #155). A drama episode read on the S22 on 2026-09-23, in TikTok's
 * series viewer after a search for short dramas, carried isPaidContent, a paid collection id and
 * name, episode 1, category 1 (SeriesCategory.MINI_DRAMA), is_limited_free and 1,182 characters
 * of mini_drama_info, and no drama card. TikTok's own code tells a drama from another Series by
 * that category, and builds a promotion card from a feed item whose card type is 92.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MiniDramaFilterTest {
    private final IFilter drama = new ContentMarkerFilters.DramaFilter();
    private final IFilter series = new ContentMarkerFilters.SeriesFilter();

    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.HIDE_MINI_DRAMAS.save(false);
        Settings.HIDE_SERIES.save(false);
    }

    @After public void tearDown() {
        Settings.HIDE_MINI_DRAMAS.resetToDefault();
        Settings.HIDE_SERIES.resetToDefault();
    }

    @Test public void theSwitchIsOffByDefaultAndIndependentOfHideSeries() {
        Settings.HIDE_MINI_DRAMAS.resetToDefault();
        assertFalse(Settings.HIDE_MINI_DRAMAS.get());
        Settings.HIDE_SERIES.save(true);
        assertFalse(drama.getEnabled());
        Settings.HIDE_MINI_DRAMAS.save(true);
        Settings.HIDE_SERIES.save(false);
        assertTrue(drama.getEnabled());
        assertFalse(series.getEnabled());
    }

    @Test public void theEpisodeReadOnThePhoneIsADramaAndASeries() {
        Item episode = new Item();
        episode.isPaidContent = true;
        PaidContent info = new PaidContent();
        info.paidCollectionId = 7675627111381472277L;
        info.collectionName = "x".repeat(56);
        info.episodeNumber = "1";
        info.category = 1L;
        info.isLimitedFreeShortDrama = true;
        info.miniDramaInfo = "{" + "x".repeat(1180) + "}";
        episode.mPaidContentInfo = info;
        assertTrue(drama.getFiltered(episode));
        assertTrue("a drama episode is a paid Series item too", series.getFiltered(episode));
    }

    /** The review of 9939c4dd: the drama text rides on other Series, and only the category tells them apart. */
    @Test public void aSeriesOfTheDefaultCategoryIsNotADramaWhateverItCarries() {
        Item creatorSeries = new Item();
        creatorSeries.isPaidContent = true;
        PaidContent info = new PaidContent();
        info.paidCollectionId = 42L;
        info.episodeNumber = "3";
        info.category = 0L;
        info.isLimitedFreeShortDrama = true;
        info.miniDramaInfo = "{\"inner_feed_panel_title\":\"Episode list\"}";
        creatorSeries.mPaidContentInfo = info;
        assertFalse(drama.getFiltered(creatorSeries));
        assertTrue("it is still a Series", series.getFiltered(creatorSeries));
    }

    @Test public void theCategoryAloneMakesADrama() {
        Item item = new Item();
        PaidContent info = new PaidContent();
        info.category = 1L;
        item.mPaidContentInfo = info;
        assertTrue(drama.getFiltered(item));
    }

    /** The review of 9939c4dd: a promotion card is known by its card type when it is parsed. */
    @Test public void aFeedCardOfTheDramaTypeIsADramaBeforeTikTokBuildsItsCard() {
        Item card = new Item();
        card.cardInsertInfo = new CardInsert(92);
        assertTrue(drama.getFiltered(card));
        assertFalse("a card is not a paid Series item", series.getFiltered(card));
        card.cardInsertInfo = new CardInsert(91);
        assertFalse("another inserted card", drama.getFiltered(card));
        card.cardInsertInfo = new CardInsert(0);
        assertFalse(drama.getFiltered(card));
    }

    @Test public void aBuiltDramaCardStillCounts() {
        Item item = new Item();
        PaidContent typedCard = new PaidContent();
        typedCard.miniDramaCardInfo = new Card("drama_rec", List.of());
        item.mPaidContentInfo = typedCard;
        assertTrue("a card with a type", drama.getFiltered(item));
        PaidContent fullCard = new PaidContent();
        fullCard.miniDramaCardInfo = new Card(null, List.of(new Object(), new Object()));
        item.mPaidContentInfo = fullCard;
        assertTrue("a card holding dramas", drama.getFiltered(item));
    }

    /** PaidContentInfo rides along on ordinary videos with its fields at their defaults. */
    @Test public void theDefaultsTikTokSendsOnOrdinaryVideosAreNotADrama() {
        assertFalse(drama.getFiltered(null));
        assertFalse(drama.getFiltered(new Aweme()));
        Item ordinary = new Item();
        assertFalse(drama.getFiltered(ordinary));
        ordinary.mPaidContentInfo = new PaidContent();
        assertFalse("an empty paid content struct", drama.getFiltered(ordinary));
        PaidContent blank = new PaidContent();
        blank.miniDramaCardInfo = new Card(" ", List.of());
        ordinary.mPaidContentInfo = blank;
        assertFalse("an empty card", drama.getFiltered(ordinary));
        PaidContent textOnly = new PaidContent();
        textOnly.miniDramaInfo = "{}";
        textOnly.isLimitedFreeShortDrama = true;
        ordinary.mPaidContentInfo = textOnly;
        assertFalse("drama text and is_limited_free without the category", drama.getFiltered(ordinary));
    }

    private static final class Item extends Aweme {
        public boolean isPaidContent;
        public Object mPaidContentInfo;
        public Object cardInsertInfo;
    }

    /** TikTok's PaidContentInfo, by the fields the Series and drama filters read. */
    private static final class PaidContent {
        long paidCollectionId;
        String collectionName;
        String episodeNumber;
        boolean isPaidCollectionIntro;
        long category;
        boolean isLimitedFreeShortDrama;
        String miniDramaInfo;
        Object miniDramaCardInfo;
    }

    /** TikTok's CardInsertInfo, by its card type. */
    private static final class CardInsert {
        final int cardType;

        CardInsert(int cardType) {
            this.cardType = cardType;
        }
    }

    /** TikTok's MiniDramaCardInfo, by its card type and the dramas it promotes. */
    private static final class Card {
        final String cardType;
        final List<Object> dramas;

        Card(String cardType, List<Object> dramas) {
            this.cardType = cardType;
            this.dramas = dramas;
        }
    }
}

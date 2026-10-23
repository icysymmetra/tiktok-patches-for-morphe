/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeRawAd;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;
import com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeed;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeedList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Direct fixtures for feed delivery, fallback and the bounded filter probe. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DeepFeedFilterBoundaryTest {
    private static final String UNSET_RANGE = "0-" + Long.MAX_VALUE;

    private final Map<BooleanSetting, Boolean> booleans = new LinkedHashMap<>();
    private final Map<StringSetting, String> strings = new LinkedHashMap<>();
    private final Map<IntegerSetting, Integer> integers = new LinkedHashMap<>();
    private boolean debug;

    /** A feed model whose native cache marker and quality data are explicit. */
    public static final class Video extends Aweme {
        private final String id;
        private final boolean ad;
        private final long durationMs;
        private final String caption;
        private final AwemeStatistics statistics;
        private AwemeRawAd rawAd;
        private String anchorsExtras;
        private Object contentModel;
        private long createTime;
        public List<?> anchors;
        public String commercialVideoInfo;

        Video(String id, boolean ad, long durationMs, String caption) {
            this(id, ad, durationMs, caption, null);
        }

        Video(String id, boolean ad, long durationMs, String caption, AwemeStatistics statistics) {
            this.id = id;
            this.ad = ad;
            this.durationMs = durationMs;
            this.caption = caption;
            this.statistics = statistics;
        }

        @Override public String getAid() { return id; }
        @Override public boolean isAd() { return ad; }
        @Override public boolean isSoftAd() { return false; }
        @Override public AwemeRawAd getAwemeRawAd() { return rawAd; }
        @Override public boolean isWithPromotionalMusic() { return false; }
        public String getAnchorsExtras() { return anchorsExtras; }
        public Object getContentModel() { return contentModel; }
        public List<?> getAnchors() { return anchors; }
        public String getDesc() { return caption; }
        public Object getVideo() { return new Duration(durationMs); }
        @Override public AwemeStatistics getStatistics() { return statistics; }
        @Override public String getShareUrl() { return null; }
        @Override public long getCreateTime() { return createTime; }

        Video postedHoursAgo(long hours) {
            createTime = (System.currentTimeMillis() - hours * 3_600_000L) / 1000L;
            return this;
        }

        Video withRawAd() {
            rawAd = new AwemeRawAd();
            return this;
        }

        Video asPaidPartnership() {
            commercialVideoInfo = "paid partnership";
            return this;
        }

        Video withCreatorCommissionDisclosure() {
            anchorsExtras = "{\"panel_top_disclosure_label\":"
                    + "{\"display_text\":\"Creator earns commission\"}}";
            return this;
        }
    }

    public static final class SearchCard {
        public Aweme aweme;
        SearchCard(Aweme aweme) { this.aweme = aweme; }
        public boolean isAdOrContainAd() { return false; }
    }

    public static final class SearchResult {
        public List<SearchCard> mItems;
        SearchResult(SearchCard... items) { mItems = new ArrayList<>(List.of(items)); }
    }

    public static final class FriendEntry {
        public Aweme aweme;
        FriendEntry(Aweme aweme) { this.aweme = aweme; }
    }

    public static final class FriendsResponse {
        public List<FriendEntry> friendFeedData;
        FriendsResponse(FriendEntry... items) {
            friendFeedData = new ArrayList<>(List.of(items));
        }
    }

    public static final class ForYouPanel extends BaseListFragmentPanel {
        @Override public String getEventType() { return "homepage_hot"; }
    }

    public static final class Duration {
        private final long duration;
        Duration(long duration) { this.duration = duration; }
        public long getDuration() { return duration; }
    }

    private static FollowFeed follow(Video video) {
        FollowFeed entry = new FollowFeed();
        entry.aweme = video;
        return entry;
    }

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        debug = BaseSettings.DEBUG.get();
        BaseSettings.DEBUG.save(false);

        save(Settings.REMOVE_ADS);
        save(Settings.HIDE_LIVE);
        save(Settings.HIDE_STORY);
        save(Settings.HIDE_IMAGE);
        save(Settings.HIDE_SHOP);
        save(Settings.HIDE_BLOCKED_SOUNDS);
        save(Settings.HIDE_PAID_PARTNERSHIP);
        save(Settings.FILTER_LOCATION_VIDEOS);
        save(Settings.HIDE_AI_GENERATED);
        save(Settings.HIDE_VERIFIED);
        save(Settings.HIDE_SERIES);
        save(Settings.HIDE_PLAYLIST_VIDEOS);
        save(Settings.HIDE_INSERTED_CARDS);
        save(Settings.HIDE_SEEN_VIDEOS);
        save(Settings.HIDE_PROMOTIONAL_MUSIC);
        save(Settings.HIDE_LIVE_REPLAYS);
        save(Settings.FILTER_OFFLINE_FALLBACK_VIDEOS);
        save(Settings.HIDE_OFFLINE_VIDEOS);

        save(Settings.BLOCKED_CAPTION_WORDS);
        save(Settings.BLOCKED_CREATORS);
        save(Settings.LOCAL_HIDDEN_CREATORS);
        save(Settings.CREATOR_FILTER_EXCEPTIONS);
        save(Settings.BLOCKED_SOUND_IDS);
        save(Settings.BLOCKED_SOUND_NAMES);
        save(Settings.REGION_ONLY_FROM);
        save(Settings.REGION_NEVER_FROM);
        save(Settings.MIN_MAX_VIEWS);
        save(Settings.MIN_MAX_LIKES);
        save(Settings.MIN_MAX_COMMENTS);
        save(Settings.MIN_MAX_FAVOURITES);
        save(Settings.MIN_MAX_SHARES);

        save(Settings.MAX_VIDEO_SECONDS);
        save(Settings.MAX_PUBLICATION_AGE_DAYS);
        save(Settings.MAX_VIEWS_PER_LIKE);
        FeedItemsFilter.resetDiagnosticsForTests();
        disableAllFilters();
    }

    @After
    public void tearDown() {
        for (Map.Entry<BooleanSetting, Boolean> entry : booleans.entrySet()) {
            entry.getKey().save(entry.getValue());
        }
        for (Map.Entry<StringSetting, String> entry : strings.entrySet()) {
            entry.getKey().save(entry.getValue());
        }
        for (Map.Entry<IntegerSetting, Integer> entry : integers.entrySet()) {
            entry.getKey().save(entry.getValue());
        }
        BaseSettings.DEBUG.save(debug);
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    private void save(BooleanSetting setting) {
        booleans.put(setting, setting.get());
    }

    private void save(StringSetting setting) {
        strings.put(setting, setting.get());
    }

    private void save(IntegerSetting setting) {
        integers.put(setting, setting.get());
    }

    private static void disableAllFilters() {
        Settings.REMOVE_ADS.save(false);
        Settings.HIDE_LIVE.save(false);
        Settings.HIDE_STORY.save(false);
        Settings.HIDE_IMAGE.save(false);
        Settings.HIDE_SHOP.save(false);
        Settings.HIDE_BLOCKED_SOUNDS.save(false);
        Settings.HIDE_PAID_PARTNERSHIP.save(false);
        Settings.FILTER_LOCATION_VIDEOS.save(false);
        Settings.HIDE_AI_GENERATED.save(false);
        Settings.HIDE_VERIFIED.save(false);
        Settings.HIDE_SERIES.save(false);
        Settings.HIDE_PLAYLIST_VIDEOS.save(false);
        Settings.HIDE_INSERTED_CARDS.save(false);
        Settings.HIDE_SEEN_VIDEOS.save(false);
        Settings.HIDE_PROMOTIONAL_MUSIC.save(false);
        Settings.HIDE_LIVE_REPLAYS.save(false);
        Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.save(true);
        Settings.HIDE_OFFLINE_VIDEOS.save(false);
        Settings.BLOCKED_CAPTION_WORDS.save("");
        Settings.BLOCKED_CREATORS.save("");
        Settings.LOCAL_HIDDEN_CREATORS.save("");
        Settings.CREATOR_FILTER_EXCEPTIONS.save("");
        Settings.BLOCKED_SOUND_IDS.save("");
        Settings.BLOCKED_SOUND_NAMES.save("");
        Settings.REGION_ONLY_FROM.save("");
        Settings.REGION_NEVER_FROM.save("");
        Settings.MIN_MAX_VIEWS.save(UNSET_RANGE);
        Settings.MIN_MAX_LIKES.save(UNSET_RANGE);
        Settings.MIN_MAX_COMMENTS.save(UNSET_RANGE);
        Settings.MIN_MAX_FAVOURITES.save(UNSET_RANGE);
        Settings.MIN_MAX_SHARES.save(UNSET_RANGE);
        Settings.MAX_VIDEO_SECONDS.save(0);
        Settings.MAX_PUBLICATION_AGE_DAYS.save(0);
        Settings.MAX_VIEWS_PER_LIKE.save(0);
    }

    @Test
    public void repeatedResponseUsesTheFingerprintCacheWithinItsWindow() {
        Settings.REMOVE_ADS.save(true);
        BaseSettings.DEBUG.save(true);
        FeedItemList page = new FeedItemList();
        Video ordinary = new Video("ordinary", false, 500, "");
        page.items = new ArrayList<>(Arrays.asList(ordinary));

        FeedItemsFilter.filter(page);
        FeedItemsFilter.filter(page);

        assertEquals(1, page.items.size());
        assertSame(ordinary, page.items.get(0));
        String summary = FeedItemsFilter.rotateProbeSummaryForTests(System.currentTimeMillis() + 10_000L);
        assertNotNull(summary);
        assertTrue(summary, summary.contains("cacheHits=1"));
        assertTrue(summary, summary.contains("scans=1"));
    }

    @Test
    public void repeatedResponseIsRescannedWhenCommissionMetadataArrives() {
        Settings.REMOVE_ADS.save(true);
        BaseSettings.DEBUG.save(true);
        FeedItemList page = new FeedItemList();
        Video video = new Video("late-commission", false, 500, "");
        page.items = new ArrayList<>(List.of(video));

        FeedItemsFilter.filter(page);
        video.withCreatorCommissionDisclosure();
        FeedItemsFilter.filter(page);

        assertTrue(page.items.isEmpty());
        String summary = FeedItemsFilter.rotateProbeSummaryForTests(System.currentTimeMillis() + 10_000L);
        assertNotNull(summary);
        assertTrue(summary, summary.contains("cacheHits=0"));
        assertTrue(summary, summary.contains("scans=2"));
    }

    @Test
    public void optionalLocationFilterRemovesPlacesButDoesNotRedefineAds() {
        Settings.FILTER_LOCATION_VIDEOS.save(true);
        Video tagged = new Video("two-places", false, 500, "");
        tagged.anchors = List.of(new LocationBadgeFilterTest.Anchor("anchor_poi"),
                new LocationBadgeFilterTest.Anchor("anchor_poi"));
        Video plain = new Video("plain", false, 500, "Dog Bar is caption text, not a badge");
        plain.anchors = List.of(new LocationBadgeFilterTest.Anchor("anchor_effect"));
        FeedItemList page = new FeedItemList();
        page.items = List.of(tagged, plain); // Immutable cached lists must use the replacement path.
        assertFalse(new AdsFilter().getFiltered(tagged));
        FeedItemsFilter.filterOnRead(page);
        assertEquals(List.of(plain), page.items);
        assertEquals(2, tagged.anchors.size());
        // Profile grids deliberately retain their ad-only policy.
        assertEquals(List.of(tagged, plain), FeedItemsFilter.filterProfileAds(List.of(tagged, plain)));
        FollowFeedList following = new FollowFeedList();
        FollowFeed ordinaryFollow = follow(plain);
        following.mItems = new ArrayList<>(List.of(follow(tagged), ordinaryFollow));
        FeedItemsFilter.filterLate(following);
        assertEquals(List.of(ordinaryFollow), following.mItems);
        FriendEntry ordinaryFriend = new FriendEntry(plain);
        FriendsResponse friends = new FriendsResponse(new FriendEntry(tagged), ordinaryFriend);
        FeedItemsFilter.filterFriendsFeed(friends);
        assertEquals(List.of(ordinaryFriend), friends.friendFeedData);
    }

    @Test
    public void lateLocationAnchorsInvalidateTheDuplicateListCache() {
        Settings.FILTER_LOCATION_VIDEOS.save(true);
        Video late = new Video("late-place", false, 500, "");
        LocationBadgeFilterTest.Anchor anchor = new LocationBadgeFilterTest.Anchor("");
        late.anchors = List.of(anchor);
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(List.of(late));
        FeedItemsFilter.filterOnRead(page);
        assertEquals(1, page.items.size());
        anchor.key = "anchor_poi"; // Same list, Aweme and anchor identities, different evidence.
        FeedItemsFilter.filterOnRead(page);
        assertTrue(page.items.isEmpty());
    }

    @Test
    public void enablingLocationFilteringRechecksRecentlyProcessedLists() {
        Settings.REMOVE_ADS.save(true);
        Video tagged = new Video("place-toggle", false, 500, "");
        tagged.anchors = List.of(new LocationBadgeFilterTest.Anchor("anchor_poi"));
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(List.of(tagged));
        FeedItemsFilter.filterOnRead(page);
        assertEquals(1, page.items.size());
        Settings.FILTER_LOCATION_VIDEOS.save(true);
        FeedItemsFilter.filterOnRead(page);
        assertTrue(page.items.isEmpty());
        Settings.FILTER_LOCATION_VIDEOS.save(false);
        page.items = new ArrayList<>(List.of(tagged));
        FeedItemsFilter.filterOnRead(page);
        assertEquals(1, page.items.size());
    }

    @Test
    public void aKeptVideoThatAgesPastTheLimitStaysWhileANewOldOneGoes() {
        Settings.MAX_PUBLICATION_AGE_DAYS.save(1);
        Video playing = new Video("playing", false, 500, "").postedHoursAgo(23);
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(List.of(playing));
        FeedItemsFilter.filterOnRead(page);
        assertEquals(List.of(playing), page.items);

        // The clock moves past the limit while it plays, and TikTok reads the grown list again.
        playing.postedHoursAgo(25);
        Video fresh = new Video("fresh", false, 500, "").postedHoursAgo(2);
        Video old = new Video("old", false, 500, "").postedHoursAgo(72);
        page.items.add(fresh);
        page.items.add(old);
        FeedItemsFilter.filterOnRead(page);
        assertEquals(List.of(playing, fresh), page.items);

        // Hiding LIVE changes the rules but none that drift, so the playing video stays.
        Settings.HIDE_LIVE.save(true);
        FeedItemsFilter.filterOnRead(page);
        assertEquals(List.of(playing, fresh), page.items);

        // Turning the age rule off and on again is the reader asking for it, so it judges again.
        Settings.MAX_PUBLICATION_AGE_DAYS.save(0);
        FeedItemsFilter.filterOnRead(page);
        assertEquals(List.of(playing, fresh), page.items);
        Settings.MAX_PUBLICATION_AGE_DAYS.save(1);
        FeedItemsFilter.filterOnRead(page);
        assertEquals(List.of(fresh), page.items);
    }

    @Test
    public void aKeptVideoStillGoesWhenItsAdMetadataArrivesLate() {
        Settings.REMOVE_ADS.save(true);
        Settings.MAX_PUBLICATION_AGE_DAYS.save(1);
        Video video = new Video("kept-then-commission", false, 500, "").postedHoursAgo(1);
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(List.of(video));
        FeedItemsFilter.filterOnRead(page);
        assertEquals(1, page.items.size());

        video.withCreatorCommissionDisclosure();
        FeedItemsFilter.filterOnRead(page);
        assertTrue(page.items.isEmpty());
    }

    @Test
    public void feedItemListReadCatchesCommissionDisclosureMissedByTheResponseHook() {
        Settings.REMOVE_ADS.save(true);
        FeedItemList page = new FeedItemList();
        Video video = new Video("late-read-commission", false, 500, "")
                .withCreatorCommissionDisclosure();
        page.items = new ArrayList<>(List.of(video));

        FeedItemsFilter.filterOnRead(page);

        assertTrue(page.items.isEmpty());
    }

    @Test
    public void feedItemListReadCatchesLateLocationAllianceMetadata() {
        Settings.REMOVE_ADS.save(true);
        FeedItemList page = new FeedItemList();
        Video video = new Video("native-commission", false, 500, "");
        page.items = new ArrayList<>(List.of(video));
        FeedItemsFilter.filter(page);
        assertEquals(1, page.items.size());

        video.contentModel = new AdsFilterTest.Content(new AdsFilterTest.Business(
                new AdsFilterTest.Alliance(1, "Creator earns commission")));
        FeedItemsFilter.filterOnRead(page);

        assertTrue(page.items.isEmpty());
    }

    @Test
    public void responseDropsLocationCommissionBeforeDisplayAndKeepsOrdinaryVideo() {
        Settings.REMOVE_ADS.save(true);
        Video commission = new Video("location-commission", false, 500, "");
        commission.contentModel = new AdsFilterTest.Content(new AdsFilterTest.Business(
                new AdsFilterTest.Alliance(1, "Creator earns commission")));
        Video ordinary = new Video("ordinary-location", false, 500, "");
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(List.of(commission, ordinary));

        FeedItemsFilter.filter(page);

        assertEquals(List.of(ordinary), page.items);
        assertEquals(List.of(ordinary), FeedItemsFilter.filterProfileAds(List.of(commission, ordinary)));
        Settings.REMOVE_ADS.save(false);
        assertEquals(List.of(commission, ordinary),
                FeedItemsFilter.filterProfileAds(List.of(commission, ordinary)));
    }

    @Test
    public void rawMetadataAdsReachEverySharedDeliveryDelegate() {
        Settings.REMOVE_ADS.save(true);
        Video rawAd = new Video("raw-ad", false, 500, "").withRawAd();
        Video partnership = new Video("partnership", false, 500, "").asPaidPartnership();

        AdsFilter classifier = new AdsFilter();
        assertTrue(classifier.getFiltered(rawAd));
        assertTrue(classifier.getFiltered(partnership));

        FeedItemList response = new FeedItemList();
        response.items = new ArrayList<>(Arrays.asList(rawAd, partnership));
        FeedItemsFilter.filter(response);
        assertTrue(response.items.isEmpty());

        List<Aweme> inserted = Arrays.asList(
                new Video("inserted-ad", false, 500, "").withRawAd(),
                new Video("inserted-organic", false, 500, ""));
        assertEquals(List.of(inserted.get(1)),
                FeedItemsFilter.filterLateInsertedAds("auction", inserted));

        List<Aweme> cachedItems = Arrays.asList(
                new Video("cached-ad", false, 500, "").withRawAd(),
                new Video("cached-organic", false, 500, ""));
        FeedItemList cached = new FeedItemList();
        cached.items = new ArrayList<>(cachedItems);
        assertSame(cached, FeedItemsFilter.filterCachedFeedList(cached));
        assertEquals(List.of(cachedItems.get(1)), cached.items);

        List<Aweme> profile = Arrays.asList(
                new Video("profile-ad", false, 500, "").withRawAd(),
                new Video("profile-organic", false, 500, ""));
        assertEquals(List.of(profile.get(1)), FeedItemsFilter.filterProfileAds(profile));

        FollowFeed ordinaryFollow = follow(new Video("follow-organic", false, 500, ""));
        FollowFeedList following = new FollowFeedList();
        following.mItems = new ArrayList<>(Arrays.asList(
                follow(new Video("follow-ad", false, 500, "").withRawAd()),
                ordinaryFollow));
        FeedItemsFilter.filterLate(following);
        assertEquals(List.of(ordinaryFollow), following.mItems);

        SearchCard ordinarySearch = new SearchCard(
                new Video("search-organic", false, 500, ""));
        SearchResult search = new SearchResult(
                new SearchCard(new Video("search-ad", false, 500, "").withRawAd()),
                ordinarySearch);
        FeedItemsFilter.filterSearchAds(search);
        assertEquals(List.of(ordinarySearch), search.mItems);

        FriendEntry ordinaryFriend = new FriendEntry(
                new Video("friend-organic", false, 500, ""));
        FriendsResponse friends = new FriendsResponse(
                new FriendEntry(new Video("friend-ad", false, 500, "").withRawAd()),
                ordinaryFriend);
        FeedItemsFilter.filterFriendsFeed(friends);
        assertEquals(List.of(ordinaryFriend), friends.friendFeedData);

        List<Aweme> nativeInsertion = Arrays.asList(
                new Video("native-ad", false, 500, "").withRawAd(),
                new Video("native-organic", false, 500, ""));
        assertEquals(List.of(nativeInsertion.get(1)), FeedItemsFilter.filterInsertedFeedItems(
                new ForYouPanel(), 0, "golden_house", nativeInsertion));
    }

    /** 46.9.3 moved FollowFeedList to Kotlin and renamed mItems to items (issue #12). */
    public static final class FollowFeedList4693 extends FollowFeedList {
        public List items;
    }

    @Test
    public void theFollowingFeedOf4693IsFilteredThroughItsRenamedField() {
        // Reading mItems threw NoSuchFieldError on the real 46.9.3 class and closed TikTok.
        Settings.REMOVE_ADS.save(true);
        FollowFeed ordinary = follow(new Video("ordinary", false, 500, ""));
        FollowFeedList4693 list = new FollowFeedList4693();
        list.items = new ArrayList<>(Arrays.asList(
                follow(new Video("ad", true, 500, "")), ordinary));

        FeedItemsFilter.filterLate(list);

        assertEquals(List.of(ordinary), list.items);
        assertNull("the 46.2.3 field is not the one written on this shape", list.mItems);
    }

    @Test
    public void aFollowingFeedFilterThatThrowsLeavesTikTokRunning() {
        // These run inside TikTok's own getItems. An Error from here is an app crash.
        Settings.REMOVE_ADS.save(true);
        HookStatus.clear();
        FollowFeedList4693 list = new FollowFeedList4693();
        list.items = new java.util.AbstractList<Object>() {
            @Override public Object get(int index) { throw new NoSuchFieldError("hostile"); }
            @Override public int size() { throw new NoSuchFieldError("hostile"); }
        };

        FeedItemsFilter.filterLate(list);
        FeedItemsFilter.filterLateFinal(list);
        FeedItemsFilter.filter(list);

        assertFalse("the failure is named in the export",
                HookStatus.missing("following feed").isEmpty());
        HookStatus.clear();
    }

    @Test
    public void lateFollowDeliveryAndFinalPassBothRemoveNewAds() {
        Settings.REMOVE_ADS.save(true);
        FollowFeedList list = new FollowFeedList();
        list.mItems = new ArrayList<>(Arrays.asList(
                follow(new Video("ordinary", false, 500, "")),
                follow(new Video("first-ad", true, 500, ""))));

        FeedItemsFilter.filterLate(list);
        assertEquals(1, list.mItems.size());

        list.mItems.add(follow(new Video("late-ad", true, 500, "")));
        FeedItemsFilter.filterLateFinal(list);
        assertEquals(1, list.mItems.size());
        assertEquals("ordinary", ((FollowFeed) list.mItems.get(0)).aweme.getAid());
    }

    @Test
    public void cachedAndOfflineDeliveryRespectTheirKeepPolicies() {
        Settings.REMOVE_ADS.save(true);
        Video cachedAd = new Video("cached-ad", true, 500, "");
        cachedAd.cacheSourceType = 0;
        Video cachedOrdinary = new Video("cached-ordinary", false, 500, "");
        cachedOrdinary.cacheSourceType = 2;

        FeedItemList cached = new FeedItemList();
        cached.items = new ArrayList<>(Arrays.asList(cachedAd, cachedOrdinary));
        assertSame(cached, FeedItemsFilter.filterCachedFeedList(cached));
        assertEquals(1, cached.items.size());
        assertSame(cachedOrdinary, cached.items.get(0));

        Video offlineAd = new Video("offline-ad", true, 500, "");
        offlineAd.cacheSourceType = 3;
        FeedItemList offline = new FeedItemList();
        offline.items = new ArrayList<>(List.of(offlineAd));
        Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.save(false);
        assertSame(offline, FeedItemsFilter.filterOfflineFeedList(offline));
        assertTrue(FeedItemsFilter.shouldKeepCachedAweme(offlineAd));

        Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.save(true);
        FeedItemList filteredOffline = new FeedItemList();
        filteredOffline.items = new ArrayList<>(List.of(offlineAd));
        assertNull(FeedItemsFilter.filterOfflineFeedList(filteredOffline));
        assertFalse(FeedItemsFilter.shouldKeepCachedAweme(offlineAd));
    }

    /**
     * Keep offline videos out of the feed takes every offline copy out on each route TikTok puts
     * one in, even with no other filter on and with the other filters told to leave them alone.
     */
    @Test
    public void offlineVideosStayOutOfTheFeedOnlyWhenAsked() {
        Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.save(false);
        Video offline = new Video("offline", false, 500, "");
        offline.cacheSourceType = 3;
        Video fresh = new Video("fresh", false, 500, "");
        Video cached = new Video("cached", false, 500, "");
        cached.cacheSourceType = 2;

        FeedItemList fallback = new FeedItemList();
        fallback.items = new ArrayList<>(List.of(offline));
        assertSame("off, the fallback list passes as before", fallback, FeedItemsFilter.filterOfflineFeedList(fallback));
        assertTrue(FeedItemsFilter.shouldKeepCachedAweme(offline));
        List<Aweme> lagging = Arrays.asList(fresh, offline);
        assertEquals(lagging, FeedItemsFilter.filterInsertedFeedItems(
                new ForYouPanel(), 0, "middle_insert_when_video_lagging", lagging));

        Settings.HIDE_OFFLINE_VIDEOS.save(true);
        assertFalse("the other switch greys out", Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.isAvailable());
        FeedItemList dropped = new FeedItemList();
        // An item the fallback hands over may not carry its cache source yet; the whole list goes.
        dropped.items = new ArrayList<>(List.of(offline, new Video("unmarked", false, 500, "")));
        assertNull(FeedItemsFilter.filterOfflineFeedList(dropped));
        assertTrue(dropped.items.isEmpty());
        assertTrue("counted under its own line: " + FeedFilterCounters.report(), FeedFilterCounters.report().contains(
                FeedItemsFilter.OFFLINE_FALLBACK_SOURCE + ": 1 lists, 2 items, 2 removed. Last reason: OfflineVideoFilter"));
        assertFalse(FeedItemsFilter.shouldKeepCachedAweme(offline));
        assertTrue(FeedItemsFilter.shouldKeepCachedAweme(cached));
        assertEquals(List.of(fresh), FeedItemsFilter.filterInsertedFeedItems(
                new ForYouPanel(), 0, "middle_insert_when_video_lagging", Arrays.asList(fresh, offline)));
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(Arrays.asList(fresh, offline, cached));
        FeedItemsFilter.filter(page);
        assertEquals(Arrays.asList(fresh, cached), page.items);
    }

    @Test
    public void qualityFallbackNeverRestoresHardRejectedItems() {
        Settings.REMOVE_ADS.save(true);
        Settings.BLOCKED_CAPTION_WORDS.save("blocked");
        Settings.MAX_VIDEO_SECONDS.save(1);

        Video ad = new Video("ad", true, 500, "clear");
        Video blocked = new Video("blocked", false, 500, "blocked caption");
        Video closestQuality = new Video("closest", false, 2_000, "clear");
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(Arrays.asList(ad, blocked, closestQuality));

        FeedItemsFilter.filter(page);

        assertEquals(1, page.items.size());
        assertSame(closestQuality, page.items.get(0));
    }

    @Test
    public void probeSummariesRotateAndBothCachesStayBounded() {
        Settings.REMOVE_ADS.save(true);
        BaseSettings.DEBUG.save(true);
        List<FeedItemList> pages = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            FeedItemList page = new FeedItemList();
            page.items = new ArrayList<>(List.of(new Video("probe-" + index, false, 500, "")));
            pages.add(page);
            FeedItemsFilter.filter(page);
        }

        assertTrue(FeedItemsFilter.probeSeenListCountForTests() <= 256);
        assertTrue(FeedItemsFilter.processedListCacheSizeForTests() <= 256);
        String summary = FeedItemsFilter.rotateProbeSummaryForTests(System.currentTimeMillis() + 10_000L);
        assertNotNull(summary);
        assertTrue(summary, summary.contains("calls=300"));
        assertTrue(summary, summary.contains("uniqueLists="));
        assertNull(FeedItemsFilter.rotateProbeSummaryForTests(System.currentTimeMillis() + 10_001L));
    }
}

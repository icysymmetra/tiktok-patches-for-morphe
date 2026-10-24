/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeRawAd;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;
import com.ss.android.ugc.aweme.feed.model.PhotoModeImageInfo;
import com.ss.android.ugc.aweme.feed.model.PhotoModeTextInfo;
import com.ss.android.ugc.aweme.feed.model.RecReasonsStruct;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeed;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeedList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The creators whose posts get past the preference filters.
 *
 * <p>Every case goes in through {@link FeedItemsFilter#filter(FeedItemList)}, the way a feed
 * response reaches the filter, with one filter switched on at a time: an excepted creator's
 * post stays, another creator's identical post goes, and a post that says nobody posted it
 * goes. The hard filters run the same way and hide the excepted creator regardless. The
 * fixture answers every getter the filters read with a plain value, so a filter that starts
 * reading something new fails here instead of being kept quiet by the filter's own catch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CreatorExceptionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String UNSET_RANGE = "0-" + Long.MAX_VALUE;
    private static final String HANDLE = "poster";
    private static final String UID = "123456";
    private static final String SEC_UID = "MS4wLjABAAAAposterSec";
    /** A display name with no space, so only the rule that names never count keeps it out. */
    private static final String NICKNAME = "Posterino";

    /** The account a post came from, with the getters TikTok's User model has. */
    public static final class Author {
        String handle = HANDLE;
        String uid = UID;
        String secUid = SEC_UID;
        String nickname = NICKNAME;
        int verificationType;

        public String getUniqueId() { return handle; }
        public String getUid() { return uid; }
        public String getSecUid() { return secUid; }
        public String getNickname() { return nickname; }
        public int getVerificationType() { return verificationType; }
    }

    /** Counts that pass every range a case sets; a trip lowers one of them. */
    public static final class Stats extends AwemeStatistics {
        long views = 5_000, likes = 5_000, comments = 5_000, favourites = 5_000, shares = 5_000;

        @Override public long getPlayCount() { return views; }
        @Override public long getDiggCount() { return likes; }
        @Override public long getCommentCount() { return comments; }
        @Override public long getCollectCount() { return favourites; }
        @Override public long getShareCount() { return shares; }
    }

    public static final class Duration {
        final long duration;
        CaptionModel captionModel;
        Duration(long duration) { this.duration = duration; }
        public long getDuration() { return duration; }
        public Object getCaptionModel() { return captionModel; }
    }

    /** A caption track as TikTok's model has it: its language tag and whether it is the original. */
    public static final class Caption {
        final String languageCode;
        final boolean original;
        public Caption(String languageCode, boolean original) {
            this.languageCode = languageCode;
            this.original = original;
        }
        public String getLanguageCode() { return languageCode; }
        public boolean isOriginalCaption() { return original; }
    }

    public static final class CaptionModel {
        public final List<Caption> captionList;
        public CaptionModel(List<Caption> captionList) { this.captionList = captionList; }
    }

    public static final class Labelled {
        public final Object aigcLabelType;
        Labelled(int type) { aigcLabelType = type; }
    }

    public static final class Mix {
        public final String mixId = "mix-1";
    }

    public static final class Music {
        public String getMid() { return "77"; }
    }

    public static final class Paid {
        public final Object category = 1L;
    }

    /** A sticker as TikTok's InteractStickerStruct has it: its type and its textStruct string. */
    public static final class Sticker {
        final int type;
        final String textStruct;
        public Sticker(int type, String textStruct) {
            this.type = type;
            this.textStruct = textStruct;
        }
        public int getType() { return type; }
        public String getTextStruct() { return textStruct; }
    }

    /** A post whose every read has a plain answer, so only the trip set on it can hide it. */
    public static class Item extends Aweme {
        final String aid;
        Author author = new Author();
        boolean ad, promotionalMusic, liveReplay, story, paidContent;
        long liveId;
        int awemeType;
        String shareUrl, region, captionLanguage, distributeSource;
        String desc = "";
        List<Object> images;
        List<?> anchors, stickers;
        Object aigcInfo, mixInfo, music, mPaidContentInfo, commercialVideoInfo;
        long createTime;
        long durationMs = 500;
        Stats stats = new Stats();

        Item(String aid) { this.aid = aid; }

        @Override public String getAid() { return aid; }
        @Override public int getAwemeType() { return awemeType; }
        @Override public boolean isAd() { return ad; }
        @Override public boolean isSoftAd() { return false; }
        @Override public AwemeRawAd getAwemeRawAd() { return null; }
        @Override public boolean isLiveReplay() { return liveReplay; }
        @Override public long getLiveId() { return liveId; }
        @Override public String getLiveType() { return null; }
        @Override public boolean isWithPromotionalMusic() { return promotionalMusic; }
        @Override public boolean getIsTikTokStory() { return story; }
        @Override public List getImageInfos() { return images; }
        @Override public PhotoModeImageInfo getPhotoModeImageInfo() { return null; }
        @Override public PhotoModeTextInfo getPhotoModeTextInfo() { return null; }
        @Override public AwemeStatistics getStatistics() { return stats; }
        @Override public String getShareUrl() { return shareUrl; }
        @Override public String getItemDistributeSource() { return distributeSource; }
        @Override public RecReasonsStruct getRecReasonsStruct() { return null; }
        public Object getAuthor() { return author; }
        public String getDesc() { return desc; }
        public String getRegion() { return region; }
        public List<?> getAnchors() { return anchors; }
        public List<?> getInteractStickerStructs() { return stickers; }
        public String getAnchorsExtras() { return null; }
        public Object getContentModel() { return null; }
        public Object getAigcInfo() { return aigcInfo; }
        public Object getMixInfo() { return mixInfo; }
        public Object getMusic() { return music; }
        public Object getMPaidContentInfo() { return mPaidContentInfo; }
        public Object getCommercialVideoInfo() { return commercialVideoInfo; }
        public boolean isPaidContent() { return paidContent; }
        public long getCreateTime() { return createTime; }
        public Object getVideo() {
            Duration video = new Duration(durationMs);
            if (captionLanguage != null) {
                video.captionModel = new CaptionModel(List.of(new Caption(captionLanguage, true)));
            }
            return video;
        }
    }

    /** One filter: its name on the feed path, the switch that turns it on, the post that trips it. */
    private static final class Case {
        final String filter;
        final Runnable enable;
        final Consumer<Item> trip;

        Case(String filter, Runnable enable, Consumer<Item> trip) {
            this.filter = filter;
            this.enable = enable;
            this.trip = trip;
        }
    }

    /** Built after the context is set: a Setting touched from a static initialiser throws. */
    private static List<Case> subjectiveCases() {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        return List.of(
                new Case("StoryFilter", () -> Settings.HIDE_STORY.save(true), item -> item.story = true),
                new Case("ImageVideoFilter", () -> Settings.HIDE_IMAGE.save(true),
                        item -> item.images = List.of(new Object())),
                new Case("LocationBadgeFilter", () -> Settings.FILTER_LOCATION_VIDEOS.save(true),
                        item -> item.anchors = List.of(new LocationBadgeFilterTest.Anchor("anchor_poi"))),
                new Case("AiGeneratedFilter", () -> Settings.HIDE_AI_GENERATED.save(true),
                        item -> item.aigcInfo = new Labelled(2)),
                new Case("VerifiedFilter", () -> Settings.HIDE_VERIFIED.save(true),
                        item -> item.author.verificationType = 1),
                new Case("PlaylistFilter", () -> Settings.HIDE_PLAYLIST_VIDEOS.save(true),
                        item -> item.mixInfo = new Mix()),
                new Case("PublicationAgeFilter", () -> Settings.MAX_PUBLICATION_AGE_DAYS.save(7),
                        item -> item.createTime = nowSeconds - 30L * 86_400L),
                new Case("QualityFilter", () -> Settings.MAX_VIDEO_SECONDS.save(1),
                        item -> item.durationMs = 5_000),
                new Case("UnpersonalizedForYouFilter", () -> Settings.HIDE_UNPERSONALIZED_FOR_YOU.save(true),
                        item -> item.distributeSource = "for_you_page_999"),
                new Case("ViewCountFilter", () -> range(Settings.MIN_MAX_VIEWS), item -> item.stats.views = 5),
                new Case("LikeCountFilter", () -> range(Settings.MIN_MAX_LIKES), item -> item.stats.likes = 5),
                new Case("CommentCountFilter", () -> range(Settings.MIN_MAX_COMMENTS),
                        item -> item.stats.comments = 5),
                new Case("FavouriteCountFilter", () -> range(Settings.MIN_MAX_FAVOURITES),
                        item -> item.stats.favourites = 5),
                new Case("ShareCountFilter", () -> range(Settings.MIN_MAX_SHARES), item -> item.stats.shares = 5));
    }

    private static List<Case> hardCases() {
        return List.of(
                new Case("AdsFilter", () -> Settings.REMOVE_ADS.save(true), item -> item.ad = true),
                new Case("LiveFilter", () -> Settings.HIDE_LIVE.save(true), item -> item.liveId = 42),
                new Case("ShopFilter", () -> Settings.HIDE_SHOP.save(true),
                        item -> item.shareUrl = "https://www.tiktok.com/x?placeholder_product_id=1"),
                new Case("SoundFilter", () -> {
                    Settings.HIDE_BLOCKED_SOUNDS.save(true);
                    Settings.BLOCKED_SOUND_IDS.save("77");
                }, item -> item.music = new Music()),
                new Case("PaidPartnershipFilter", () -> Settings.HIDE_PAID_PARTNERSHIP.save(true),
                        item -> item.commercialVideoInfo = "paid partnership"),
                new Case("SeriesFilter", () -> Settings.HIDE_SERIES.save(true), item -> item.paidContent = true),
                new Case("DramaFilter", () -> Settings.HIDE_MINI_DRAMAS.save(true),
                        item -> item.mPaidContentInfo = new Paid()),
                new Case("InsertedCardFilter", () -> Settings.HIDE_INSERTED_CARDS.save(true),
                        item -> item.awemeType = 105),
                new Case("SeenVideoFilter", () -> Settings.HIDE_SEEN_VIDEOS.save(true),
                        item -> SeenVideoHistory.onPlayProgressChange(item.aid, 9_000, 10_000)),
                new Case("KeywordFilter", () -> Settings.BLOCKED_CAPTION_WORDS.save("sponsored"),
                        item -> item.desc = "a sponsored post"),
                new Case("StickerTextFilter", () -> {
                    Settings.BLOCKED_CAPTION_WORDS.save("sponsored");
                    Settings.BLOCKED_WORDS_IN_STICKERS.save(true);
                }, item -> item.stickers = List.of(new Sticker(AdvancedFeedRules.TEXT_STICKER_TYPE, "a sponsored post"))),
                new Case("CreatorFilter", () -> Settings.BLOCKED_CREATORS.save("@" + HANDLE), item -> { }),
                new Case("CreatorFilter", () -> Settings.LOCAL_HIDDEN_CREATORS.save(UID), item -> { }),
                new Case("PromotionalMusicFilter", () -> Settings.HIDE_PROMOTIONAL_MUSIC.save(true),
                        item -> item.promotionalMusic = true),
                new Case("LiveReplayFilter", () -> Settings.HIDE_LIVE_REPLAYS.save(true),
                        item -> item.liveReplay = true),
                new Case("OfflineVideoFilter", () -> Settings.HIDE_OFFLINE_VIDEOS.save(true),
                        item -> item.cacheSourceType = FeedItemsFilter.CACHE_SOURCE_OFFLINE_MODE),
                new Case("CaptionLanguageFilter", () -> Settings.CAPTION_LANGUAGES.save("en"),
                        item -> item.captionLanguage = "es"));
    }

    private static void range(StringSetting setting) {
        setting.save("1000-" + Long.MAX_VALUE);
        FeedItemsFilter.rebuildRangeFiltersForTests();
    }

    private static BooleanSetting[] switches() {
        return new BooleanSetting[]{
                Settings.REMOVE_ADS, Settings.HIDE_LIVE, Settings.HIDE_STORY, Settings.HIDE_IMAGE,
                Settings.HIDE_SHOP, Settings.HIDE_BLOCKED_SOUNDS, Settings.HIDE_PAID_PARTNERSHIP,
                Settings.FILTER_LOCATION_VIDEOS, Settings.HIDE_AI_GENERATED, Settings.HIDE_VERIFIED,
                Settings.HIDE_SERIES, Settings.HIDE_MINI_DRAMAS, Settings.HIDE_PLAYLIST_VIDEOS,
                Settings.HIDE_INSERTED_CARDS, Settings.HIDE_SEEN_VIDEOS, Settings.HIDE_PROMOTIONAL_MUSIC,
                Settings.HIDE_LIVE_REPLAYS, Settings.HIDE_UNPERSONALIZED_FOR_YOU, Settings.HIDE_OFFLINE_VIDEOS,
                Settings.BLOCKED_WORDS_IN_STICKERS,
        };
    }

    private static StringSetting[] lists() {
        return new StringSetting[]{
                Settings.BLOCKED_CAPTION_WORDS, Settings.BLOCKED_CREATORS, Settings.LOCAL_HIDDEN_CREATORS,
                Settings.CREATOR_FILTER_EXCEPTIONS, Settings.BLOCKED_SOUND_IDS, Settings.BLOCKED_SOUND_NAMES,
                Settings.CAPTION_LANGUAGES,
        };
    }

    private static StringSetting[] ranges() {
        return new StringSetting[]{
                Settings.MIN_MAX_VIEWS, Settings.MIN_MAX_LIKES, Settings.MIN_MAX_COMMENTS,
                Settings.MIN_MAX_FAVOURITES, Settings.MIN_MAX_SHARES,
        };
    }

    private static IntegerSetting[] limits() {
        return new IntegerSetting[]{
                Settings.MAX_VIDEO_SECONDS, Settings.MAX_PUBLICATION_AGE_DAYS,
                Settings.MAX_VIEWS_PER_LIKE, Settings.MAX_VIEWS_PER_COMMENT,
        };
    }

    private static void quiet() {
        for (BooleanSetting setting : switches()) setting.save(false);
        for (StringSetting setting : lists()) setting.save("");
        for (StringSetting setting : ranges()) setting.save(UNSET_RANGE);
        for (IntegerSetting setting : limits()) setting.save(0);
        FeedItemsFilter.rebuildRangeFiltersForTests();
        SeenVideoHistory.clear();
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    @Before
    public void setUp() {
        BaseSettings.DEBUG.save(false);
        quiet();
    }

    @After
    public void tearDown() {
        for (BooleanSetting setting : switches()) setting.resetToDefault();
        for (StringSetting setting : lists()) setting.resetToDefault();
        for (StringSetting setting : ranges()) setting.resetToDefault();
        for (IntegerSetting setting : limits()) setting.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        FeedItemsFilter.rebuildRangeFiltersForTests();
        SeenVideoHistory.clear();
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    private static Item tripped(String aid, Case which) {
        Item item = new Item(aid);
        which.trip.accept(item);
        return item;
    }

    /** A plain post by another account, which no case's trip or creator list reaches. */
    private static Item stranger(String aid) {
        Item item = new Item(aid);
        item.author.handle = "someone_else";
        item.author.uid = "999";
        item.author.secUid = "MS4wLjABAAAAother";
        return item;
    }

    private static FeedItemList page(Item... items) {
        FeedItemList list = new FeedItemList();
        list.items = new ArrayList<>(Arrays.asList(items));
        return list;
    }

    /** The aids left on a page after the filter, in order, with no filter having thrown. */
    private static List<String> survivors(FeedItemList list) {
        FeedItemsFilter.filter(list);
        assertNoFilterThrew();
        List<String> aids = new ArrayList<>();
        for (Object item : list.items) aids.add(((Aweme) item).getAid());
        return aids;
    }

    /** The aids left in a Following list once TikTok reads it, with no filter having thrown. */
    private static List<String> followingSurvivors(Item... items) {
        FollowFeedList list = new FollowFeedList();
        list.mItems = new ArrayList<>();
        return followingSurvivors(list, items);
    }

    /** {@code items} appended to {@code list}, the way TikTok grows it, and the list read again. */
    private static List<String> followingSurvivors(FollowFeedList list, Item... items) {
        for (Item item : items) {
            FollowFeed entry = new FollowFeed();
            entry.aweme = item;
            list.mItems.add(entry);
        }
        FeedItemsFilter.filterLate(list);
        assertNoFilterThrew();
        List<String> aids = new ArrayList<>();
        for (Object entry : list.mItems) aids.add(((FollowFeed) entry).aweme.getAid());
        return aids;
    }

    private static void assertNoFilterThrew() {
        try {
            java.lang.reflect.Field errors = FeedItemsFilter.class.getDeclaredField("filterExceptionLogCount");
            errors.setAccessible(true);
            assertEquals("a filter swallowed an unstubbed getter or another runtime failure", 0,
                    ((AtomicInteger) errors.get(null)).get());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    public void everyPreferenceFilterLetsAnExceptedCreatorThrough() {
        for (Case which : subjectiveCases()) {
            quiet();
            which.enable.run();
            Settings.CREATOR_FILTER_EXCEPTIONS.save("@" + HANDLE.toUpperCase());
            // A plain post rides along so the page is never emptied: a page the quality filter
            // alone empties gets its nearest post back by design, exception or not, and the
            // QualityFilter case would pass on that alone.
            assertEquals(which.filter + " hid a post the exception should have kept",
                    List.of("kept", "plain"), survivors(page(tripped("kept", which), new Item("plain"))));
        }
    }

    @Test
    public void everyPreferenceFilterStillHidesEveryoneElse() {
        for (Case which : subjectiveCases()) {
            quiet();
            which.enable.run();
            Settings.CREATOR_FILTER_EXCEPTIONS.save(HANDLE);
            Item other = tripped("other", which);
            other.author.handle = "someone_else";
            other.author.uid = "999";
            other.author.secUid = "MS4wLjABAAAAother";
            Item plain = new Item("plain");
            plain.author.handle = "someone_else";
            plain.author.uid = "999";
            plain.author.secUid = "MS4wLjABAAAAother";
            assertEquals(which.filter + " did not run beside the exception",
                    List.of("plain"), survivors(page(other, plain)));
        }
    }

    @Test
    public void aPostThatSaysNobodyPostedItIsHiddenLikeAnyOther() {
        for (Case which : subjectiveCases()) {
            quiet();
            which.enable.run();
            Settings.CREATOR_FILTER_EXCEPTIONS.save(HANDLE + ", " + UID + ", " + SEC_UID + ", " + NICKNAME);
            // An author with no ids at all, only a display name, which is never an id. A plain
            // post rides along so the page is never emptied, since a page the quality filter
            // alone empties gets its nearest post back by design.
            Item nameless = tripped("nameless", which);
            nameless.author.handle = "";
            nameless.author.uid = "";
            nameless.author.secUid = "";
            assertEquals(which.filter + " kept a post whose creator has no id to except",
                    List.of("plain"), survivors(page(nameless, new Item("plain"))));
        }
        // No author object at all: a LIVE preview or an end card shape.
        quiet();
        Settings.HIDE_STORY.save(true);
        Settings.CREATOR_FILTER_EXCEPTIONS.save(HANDLE + ", " + UID + ", " + SEC_UID);
        Item anonymous = tripped("anonymous", subjectiveCases().get(0));
        anonymous.author = null;
        assertEquals(List.of("plain"), survivors(page(anonymous, new Item("plain"))));
    }

    @Test
    public void everyHardFilterStillHidesAnExceptedCreator() {
        for (Case which : hardCases()) {
            quiet();
            which.enable.run();
            Settings.CREATOR_FILTER_EXCEPTIONS.save(HANDLE + ", " + UID + ", " + SEC_UID);
            assertEquals(which.filter + " let an excepted creator past a hard block",
                    List.of(), survivors(page(tripped("hidden", which))));
        }
        // The region filter reads its lists once, when the feed path is built, so it is checked
        // where it can be built: the loop gives a hard filter no chance to ask about the creator.
        Settings.REGION_NEVER_FROM.save("RU");
        Item russian = new Item("ru");
        russian.region = "RU";
        RegionFilter region = new RegionFilter();
        assertTrue(region.getEnabled() && region.getFiltered(russian));
        assertFalse(CreatorExceptions.isSubjective(region));
    }

    /**
     * A profile's list, whoever's: TikTok reads it through the main feed's getItems, stamped with
     * the profile's uid. Every filter but the ads one leaves it alone, so a minimum view count
     * never empties a creator's page and a place badge never takes posts off a grid (#35). The
     * same page unstamped is the control: each filter still takes the post from a feed.
     */
    @Test
    public void aProfilesListKeepsEveryPostOnlyTheFeedFiltersHide() {
        List<Case> cases = new ArrayList<>(subjectiveCases());
        for (Case which : hardCases()) if (!which.filter.equals("AdsFilter")) cases.add(which);
        for (Case which : cases) {
            quiet();
            which.enable.run();
            FeedItemList profile = page(tripped("post", which), stranger("plain"));
            profile.dataUserId = "777";
            assertEquals(which.filter + " hid a post on a profile page",
                    List.of("post", "plain"), survivors(profile));
            assertEquals(which.filter + " did not run on the feed, so the profile case proves nothing",
                    List.of("plain"), survivors(page(tripped("post", which), stranger("plain"))));
        }
    }

    /**
     * The Following tab on 47.1.4 reaches the filter only where TikTok reads its list, never
     * through the response hook, and a blocked creator still showed there on a phone. Every feed
     * rule runs on that read now, except the For You padding rule, which reads marks only For You
     * carries. Hide seen videos runs too, on the videos no earlier read of the list kept.
     */
    @Test
    public void theFollowingTabsReadRunsEveryFeedRuleButForYouPadding() {
        List<Case> cases = new ArrayList<>(subjectiveCases());
        cases.addAll(hardCases());
        for (Case which : cases) {
            quiet();
            which.enable.run();
            Item post = tripped("post", which);
            if (which.filter.equals("SeenVideoFilter")) {
                assertTrue("the post was never marked seen, so hiding it proves nothing",
                        SeenVideoHistory.shouldHide("post"));
            }
            List<String> expected = which.filter.equals("UnpersonalizedForYouFilter")
                    ? List.of("post", "plain") : List.of("plain");
            assertEquals(which.filter + " on the Following tab's read",
                    expected, followingSurvivors(post, stranger("plain")));
        }
    }

    /**
     * A video watched while the list grows under it stays where it is, and one seen before that
     * arrives later in the same list goes.
     */
    @Test
    public void aFollowingVideoWatchedWhileTheListGrowsStays() {
        Settings.HIDE_SEEN_VIDEOS.save(true);
        SeenVideoHistory.onPlayProgressChange("earlier", 9_000, 10_000);
        FollowFeedList list = new FollowFeedList();
        list.mItems = new ArrayList<>();
        assertEquals(List.of("playing"), followingSurvivors(list, stranger("playing")));
        SeenVideoHistory.onPlayProgressChange("playing", 9_000, 10_000);
        assertTrue(SeenVideoHistory.shouldHide("playing"));
        assertEquals(List.of("playing", "next"),
                followingSurvivors(list, stranger("earlier"), stranger("next")));
    }

    /**
     * Hiding the first creator turns a rule on, but the videos already watched in the list stay
     * where they are; only the hidden creator's goes. Undo does the same the other way.
     */
    @Test
    public void hidingACreatorKeepsTheWatchedFollowingVideos() {
        Settings.HIDE_SEEN_VIDEOS.save(true);
        FollowFeedList list = new FollowFeedList();
        list.mItems = new ArrayList<>();
        assertEquals(List.of("watched", "theirs", "playing"),
                followingSurvivors(list, stranger("watched"), new Item("theirs"), stranger("playing")));
        SeenVideoHistory.onPlayProgressChange("watched", 9_000, 10_000);
        SeenVideoHistory.onPlayProgressChange("playing", 9_000, 10_000);

        Settings.LOCAL_HIDDEN_CREATORS.save(UID);
        assertEquals(List.of("watched", "playing", "next"), followingSurvivors(list, stranger("next")));
        Settings.LOCAL_HIDDEN_CREATORS.save("");
        assertEquals(List.of("watched", "playing", "next"), followingSurvivors(list));
    }

    /** Other lists read meanwhile never push out what the one being watched kept. */
    @Test
    public void theListBeingWatchedKeepsItsMemoryWhileOthersAreRead() {
        Settings.HIDE_SEEN_VIDEOS.save(true);
        FollowFeedList watched = new FollowFeedList();
        watched.mItems = new ArrayList<>();
        assertEquals(List.of("playing"), followingSurvivors(watched, stranger("playing")));
        SeenVideoHistory.onPlayProgressChange("playing", 9_000, 10_000);
        List<FollowFeedList> others = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 255; i++) {
                FollowFeedList other = new FollowFeedList();
                other.mItems = new ArrayList<>();
                others.add(other);
                followingSurvivors(other, stranger("other-" + round + "-" + i));
            }
            // TikTok reads the list on screen again between the others.
            assertEquals(List.of("playing"), followingSurvivors(watched));
        }
        assertTrue(FeedItemsFilter.keptBeforeOwnersForTests() <= 256);
        assertEquals(List.of("playing", "next"), followingSurvivors(watched, stranger("next")));
    }

    @Test
    public void anExceptedCreatorKeepsTheirFollowingPosts() {
        Settings.HIDE_STORY.save(true);
        Settings.CREATOR_FILTER_EXCEPTIONS.save("@" + HANDLE);
        Item story = new Item("story");
        story.story = true;
        Item other = stranger("other");
        other.story = true;
        assertEquals(List.of("story"), followingSurvivors(story, other));
    }

    /**
     * The For You read puts back the post closest to the length rule when it hides every one.
     * The Following tab doesn't, on either pass: its list grows under the video playing, so one put
     * back would go again from under the player as soon as a post that passes arrived.
     */
    @Test
    public void theFollowingTabPutsNoLongVideoBack() {
        Settings.MAX_VIDEO_SECONDS.save(1);
        Item longOne = stranger("long");
        longOne.durationMs = 5_000;
        assertEquals("the control: For You keeps the closest one", List.of("long"), survivors(page(longOne)));
        FollowFeedList list = new FollowFeedList();
        list.mItems = new ArrayList<>();
        Item longTwo = stranger("long-two");
        longTwo.durationMs = 5_000;
        assertEquals(List.of(), followingSurvivors(list, longTwo));
        assertEquals(List.of("short"), followingSurvivors(list, stranger("short")));

        FollowFeedList arriving = new FollowFeedList();
        arriving.mItems = new ArrayList<>();
        Item longThree = stranger("long-three");
        longThree.durationMs = 5_000;
        FollowFeed entry = new FollowFeed();
        entry.aweme = longThree;
        arriving.mItems.add(entry);
        FeedItemsFilter.filter(arriving);
        assertNoFilterThrew();
        assertTrue("nor does the response the tab arrives in", arriving.mItems.isEmpty());
    }

    /**
     * The same page marked where TikTok parses it and not yet stamped, which is how 47.x's advance
     * request and feed author preload read someone else's profile: on a phone, a minimum view
     * count emptied a small creator's grid page by page. Unmarked, the page is the feed's again.
     */
    @Test
    public void aProfilesListMarkedWhereItIsParsedKeepsItsPostsBeforeTheStamp() {
        List<Case> cases = new ArrayList<>(subjectiveCases());
        for (Case which : hardCases()) if (!which.filter.equals("AdsFilter")) cases.add(which);
        for (Case which : cases) {
            quiet();
            which.enable.run();
            FeedItemList parsed = page(tripped("post", which), stranger("plain"));
            FeedItemsFilter.markProfileResponse(parsed);
            assertEquals(which.filter + " hid a post from a profile page read before its stamp",
                    List.of("post", "plain"), survivors(parsed));
            assertEquals(which.filter + " did not run on an unmarked page, so the marked case proves nothing",
                    List.of("plain"), survivors(page(tripped("post", which), stranger("plain"))));
        }
        quiet();
        Settings.REMOVE_ADS.save(true);
        Item ad = new Item("ad");
        ad.ad = true;
        FeedItemList parsed = page(ad, stranger("plain"));
        FeedItemsFilter.markProfileResponse(parsed);
        assertEquals("a marked profile page still loses its ads", List.of("plain"), survivors(parsed));
        // Whatever else the parse returns (a user, a search result) passes through untouched.
        FeedItemsFilter.markProfileResponse(null);
        FeedItemsFilter.markProfileResponse("{}");
    }

    @Test
    public void aProfilesListStillLosesItsAds() {
        Settings.REMOVE_ADS.save(true);
        Item ad = new Item("ad");
        ad.ad = true;
        FeedItemList profile = page(ad, stranger("plain"));
        profile.dataUserId = "777";
        assertEquals(List.of("plain"), survivors(profile));
        assertTrue("the profile's reads are counted on their own line: " + FeedFilterCounters.report(),
                FeedFilterCounters.report().contains(
                        FeedItemsFilter.PROFILE_LIST_SOURCE + ": 1 lists, 2 items, 1 removed. Last reason: AdsFilter"));
    }

    /**
     * The signed-in reader's own posts stay on every route, stamped or not: TikTok reads your
     * profile's first page before it stamps the uid (#35: a reporter's recent posts carried place
     * badges and only months-old ones were left). Signed out, the same post goes, the control.
     */
    @Test
    public void yourOwnPostsStayOnEveryListAndOthersStillGo() {
        try {
            List<Case> cases = new ArrayList<>(subjectiveCases());
            cases.addAll(hardCases());
            for (Case which : cases) {
                quiet();
                which.enable.run();
                SignedInUser.idForTests = UID;
                assertEquals(which.filter + " hid the reader's own post",
                        List.of("mine", "plain"), survivors(page(tripped("mine", which), stranger("plain"))));
                SignedInUser.idForTests = "";
                assertEquals(which.filter + " did not run signed out, so the own-post case proves nothing",
                        List.of("plain"), survivors(page(tripped("mine", which), stranger("plain"))));
            }
            // The ads filter takes creator-labelled posts too, and on your own stamped profile those
            // are yours: they stay there as well.
            quiet();
            Settings.REMOVE_ADS.save(true);
            for (String signedIn : new String[]{UID, ""}) {
                SignedInUser.idForTests = signedIn;
                Item labelled = new Item("labelled");
                labelled.ad = true;
                FeedItemList profile = page(labelled, stranger("plain"));
                profile.dataUserId = UID;
                assertEquals(signedIn.isEmpty() ? List.of("plain") : List.of("labelled", "plain"), survivors(profile));
            }
            // Signed in as someone else, the post is anyone's.
            quiet();
            Settings.FILTER_LOCATION_VIDEOS.save(true);
            SignedInUser.idForTests = "999";
            Item tagged = new Item("tagged");
            tagged.anchors = List.of(new LocationBadgeFilterTest.Anchor("anchor_poi"));
            assertEquals(List.of("plain"), survivors(page(tagged, stranger("plain"))));
        } finally {
            SignedInUser.idForTests = null;
        }
    }

    @Test
    public void theListNamesACreatorByHandleUidOrSecUidAndNeverByDisplayName() {
        Settings.HIDE_STORY.save(true);
        Case story = subjectiveCases().get(0);
        String[] kept = {" @POSTER ", UID, SEC_UID, "@" + HANDLE + "\nsomeone_else", "someone_else, " + HANDLE};
        for (String list : kept) {
            Settings.CREATOR_FILTER_EXCEPTIONS.save(list);
            assertEquals("the list " + list.replace("\n", "\\n") + " should have kept the story",
                    List.of("s"), survivors(page(tripped("s", story))));
        }
        String[] hidden = {NICKNAME, NICKNAME.toLowerCase(), "Poster Person", "/" + HANDLE + "/", "/^post/",
                "", " , ", "post", HANDLE + "x"};
        for (String list : hidden) {
            Settings.CREATOR_FILTER_EXCEPTIONS.save(list);
            assertEquals("the list " + list + " should have matched nobody",
                    List.of(), survivors(page(tripped("s", story))));
        }
    }

    @Test
    public void anEmptyAuthorFallsBackToTheItemsOwnAuthorUid() {
        Settings.HIDE_STORY.save(true);
        Settings.CREATOR_FILTER_EXCEPTIONS.save(UID);
        Item byUid = new Item("uid-only") {
            public String getAuthorUid() { return UID; }
        };
        byUid.story = true;
        byUid.author = null;
        assertEquals(List.of("uid-only"), survivors(page(byUid)));
        // The block list reads the same fallback: an item with no author but a known uid is blocked.
        Settings.HIDE_STORY.save(false);
        Settings.CREATOR_FILTER_EXCEPTIONS.save("");
        assertEquals(List.of("uid-only"), survivors(page(byUid)));
        Settings.BLOCKED_CREATORS.save(UID);
        assertEquals(List.of(), survivors(page(byUid)));
    }

    @Test
    public void aChangedListRescansAPageTheFilterHasAlreadySeen() {
        BaseSettings.DEBUG.save(true);
        Settings.HIDE_STORY.save(true);
        Settings.CREATOR_FILTER_EXCEPTIONS.save(HANDLE);
        FeedItemList page = page(tripped("story", subjectiveCases().get(0)));
        FeedItemsFilter.filter(page);
        FeedItemsFilter.filter(page);
        assertEquals(1, page.items.size());
        String summary = FeedItemsFilter.rotateProbeSummaryForTests(System.currentTimeMillis() + 10_000L);
        assertNotNull(summary);
        assertTrue(summary, summary.contains("cacheHits=1"));
        assertTrue(summary, summary.contains("scans=1"));

        Settings.CREATOR_FILTER_EXCEPTIONS.save("someone_else");
        FeedItemsFilter.filter(page);
        assertEquals("the page kept the story after its exception was taken away", 0, page.items.size());
    }

    @Test
    public void everyFilterOnTheFeedPathIsClassifiedOnceAndTheSetsHoldNothingElse() {
        Set<Class<?>> onPath = new HashSet<>();
        for (IFilter filter : FeedItemsFilter.allFiltersForTests()) {
            Class<?> type = filter.getClass();
            onPath.add(type);
            boolean subjective = CreatorExceptions.SUBJECTIVE.contains(type);
            boolean hard = CreatorExceptions.HARD.contains(type);
            assertTrue(type.getSimpleName() + " is in neither set: say whether an exception passes it",
                    subjective || hard);
            assertFalse(type.getSimpleName() + " is in both sets", subjective && hard);
        }
        for (Class<?> type : CreatorExceptions.SUBJECTIVE) {
            assertTrue(type.getSimpleName() + " is classified but no longer on the feed path", onPath.contains(type));
        }
        for (Class<?> type : CreatorExceptions.HARD) {
            assertTrue(type.getSimpleName() + " is classified but no longer on the feed path", onPath.contains(type));
        }
        // The documented set. A filter moving between the two is a README change as well.
        Set<String> subjective = new TreeSet<>();
        for (Class<?> type : CreatorExceptions.SUBJECTIVE) subjective.add(type.getSimpleName());
        assertEquals(new TreeSet<>(List.of("AiGeneratedFilter", "CommentCountFilter", "FavouriteCountFilter",
                "ImageVideoFilter", "LikeCountFilter", "LocationBadgeFilter", "PlaylistFilter",
                "PublicationAgeFilter", "QualityFilter", "ShareCountFilter", "StoryFilter",
                "UnpersonalizedForYouFilter", "VerifiedFilter", "ViewCountFilter")), subjective);
    }

    @Test
    public void theEditorRefusesPatternsAndDisplayNamesAndTakesHandlesAndIds() {
        String pattern = CreatorExceptions.entryProblem("alice, /^news_/");
        assertNotNull(pattern);
        assertTrue(pattern, pattern.contains("a pattern can't be one") && pattern.contains("/^news_/"));
        String name = CreatorExceptions.entryProblem("alice, Poster Person");
        assertNotNull(name);
        assertTrue(name, name.contains("not a display name") && name.contains("Poster Person"));
        assertNull(CreatorExceptions.entryProblem("@alice, 123456, " + SEC_UID));
        assertNull(CreatorExceptions.entryProblem(""));
        assertNull(CreatorExceptions.entryProblem(null));
        // The editor's duplicate check and the feed's match read an entry the same way.
        assertTrue(AdvancedFeedRules.hasCreatorEntry("@POSTER, other", " poster "));
        assertEquals("poster", AdvancedFeedRules.normalizedCreator(" @Poster "));
    }

    @Test
    public void theRowNamesTheEntriesABlockListAlsoHolds() {
        Settings.BLOCKED_CREATORS.save("@Alice, /^news_/");
        Settings.LOCAL_HIDDEN_CREATORS.save(UID);
        assertEquals(List.of("alice", UID, "news_daily"),
                CreatorExceptions.alsoBlocked("alice, bob, " + UID + ", news_daily"));
        String note = CreatorExceptions.conflictNote("alice, bob, " + UID + ", news_daily");
        assertNotNull(note);
        assertTrue(note, note.startsWith("Also on a block list, so still hidden: ")
                && note.contains("alice, " + UID + ", news_daily"));
        assertNull(CreatorExceptions.conflictNote("bob, carol"));
        Settings.BLOCKED_CREATORS.save("");
        Settings.LOCAL_HIDDEN_CREATORS.save("");
        assertNull(CreatorExceptions.conflictNote("alice, bob, " + UID + ", news_daily"));
    }

    @Test
    public void theRowNamesTheEntriesThatArrivedUnusable() {
        // A backup carries the list as typed elsewhere; the editor's refusal never saw it.
        Settings.BLOCKED_CREATORS.save("bob");
        String list = "alice, /^news_/, Poster Person, bob";
        assertEquals(List.of("/^news_/", "Poster Person"), CreatorExceptions.inertEntries(list));
        String note = CreatorExceptions.conflictNote(list);
        assertNotNull(note);
        String[] lines = note.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0], lines[0].startsWith("Also on a block list, so still hidden: ") && lines[0].contains("bob"));
        assertTrue(lines[1], lines[1].startsWith("Not a handle or id, so ignored: ")
                && lines[1].contains("/^news_/, Poster Person"));
        Settings.BLOCKED_CREATORS.save("");
        String alone = CreatorExceptions.conflictNote(list);
        assertNotNull(alone);
        assertTrue(alone, alone.startsWith("Not a handle or id, so ignored: ") && !alone.contains("\n"));
        // And they match nobody on the feed: the names parsed out of the list leave them out.
        assertEquals(java.util.Set.of("alice", "bob"), CreatorExceptions.names(list));
    }
}

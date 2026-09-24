package app.morphe.extension.tiktok.feedfilter;

import android.os.SystemClock;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeBizExtKt;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;
import com.ss.android.ugc.aweme.feed.model.friends.FriendsFeed;
import com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeed;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeedList;
import com.ss.android.ugc.aweme.friendstab.api.FriendsFeedResponse;
import com.ss.android.ugc.aweme.discover.model.Banner;
import com.ss.android.ugc.aweme.discover.model.BannerList;
import com.ss.android.ugc.aweme.discover.model.TrendingTopic;
import com.ss.android.ugc.aweme.discover.model.TrendingTopicList;
import com.ss.android.ugc.aweme.search.pages.result.topsearch.core.model.SearchMixFeed;
import com.ss.android.ugc.aweme.search.pages.result.topsearch.core.model.SearchMixFeedList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class FeedItemsFilter {
    private static final AdsFilter ADS_FILTER = new AdsFilter();
    private static final List<IFilter> CONTENT_FILTERS = List.of(
        ADS_FILTER,
        new LiveFilter(),
        new StoryFilter(),
        new ImageVideoFilter(),
        new ShopFilter()
    );
    private static final List<IFilter> RANGE_FILTERS = List.of(
        new ViewCountFilter(),
        new LikeCountFilter()
    );
    private static final List<IFilter> LATE_FOLLOW_FILTERS = List.of(ADS_FILTER);

    private static final int CACHE_SOURCE_COLD_CACHE = 0;
    private static final int CACHE_SOURCE_FEED_UNCONSUMED = 1;
    private static final int CACHE_SOURCE_GOLDEN_HOUSE = 2;
    private static final int CACHE_SOURCE_OFFLINE_MODE = 3;
    private static final int CACHE_SOURCE_MERGE_CACHE = 4;

    private static final int MAX_NULL_ITEMS_LOGS = 3;
    private static final int MAX_BATCH_LOGS = 10;
    private static final int MAX_ITEM_LOGS = 50;
    private static final boolean FILTER_CALL_PROBE_ENABLED = true;
    private static final boolean FILTER_CALL_PROBE_STACKS = false;
    private static final boolean FILTER_CALL_PROBE_SUMMARY_ENABLED = true;
    private static final int FILTER_CALL_PROBE_AID_SAMPLE_SIZE = 5;
    private static final int FILTER_CALL_PROBE_MAX_SEEN_LISTS = 256;
    private static final int FILTER_CALL_PROBE_SLOW_MS = 8;
    private static final long FILTER_CALL_PROBE_SUMMARY_WINDOW_MS = 5000;
    private static final AtomicInteger feedItemListNullItemsLogCount = new AtomicInteger();
    private static final AtomicInteger followFeedListNullItemsLogCount = new AtomicInteger();
    private static final AtomicInteger batchLogCount = new AtomicInteger();
    private static final AtomicInteger itemLogCount = new AtomicInteger();
    private static final AtomicInteger filterExceptionLogCount = new AtomicInteger();
    private static final AtomicInteger listReplacementLogCount = new AtomicInteger();
    private static final AtomicInteger filterCallProbeCount = new AtomicInteger();
    private static final Map<Integer, ProbeSeenList> filterCallProbeSeenLists = new HashMap<>();
    private static final Object filterCallProbeSummaryLock = new Object();
    private static ProbeSummary filterCallProbeSummary = new ProbeSummary(System.currentTimeMillis());
    private static final Object aiSummaryLock = new Object();
    private static AiSummary aiSummary = new AiSummary(SystemClock.elapsedRealtime());
    private static long lastAiErrorLogElapsed;
    private static int pendingAiReadErrors;

    private FeedItemsFilter() {}

    public static void filter(FeedItemList feedItemList) {
        boolean verbose = BaseSettings.DEBUG.get();

        if (feedItemList == null || feedItemList.items == null) {
            if (verbose) {
                logNullItems("FeedItemList", feedItemListNullItemsLogCount);
            }
            return;
        }

        if (verbose && shouldLogBatch()) {
            debugLogBatch(
                "FeedItemList",
                feedItemList.items,
                "fetchType=" + feedItemList.fetchType
                    + " hasMore=" + feedItemList.hasMore
                    + " cursor=" + feedItemList.cursor
                    + " requestId=" + (feedItemList.requestId == null ? "missing" : "present")
            );
        }

        filterFeedList(
            "FeedItemList:response",
            feedItemList,
            feedItemList.items,
            container -> (container instanceof Aweme) ? (Aweme) container : null,
            verbose,
            true,
            FilterPhase.RESPONSE,
            true
        );
    }

    /**
     * Rechecks the concrete items field when TikTok reads a FeedItemList after the
     * response hook. Other getItems() return paths, such as CameoData, are left
     * untouched because they are not the owner's items field.
     */
    public static List filterFeedItemListOnRead(
        FeedItemList feedItemList,
        List originalReturnList
    ) {
        if (feedItemList == null || originalReturnList == null) return originalReturnList;

        try {
            if (feedItemList.items != originalReturnList) return originalReturnList;
        } catch (RuntimeException | LinkageError error) {
            recordAiReadErrors(1);
            return originalReturnList;
        }

        ContentListFilter.Outcome outcome = filterContainerList(
            "FeedItemList:getItems",
            originalReturnList,
            container -> container instanceof Aweme ? (Aweme) container : null,
            null,
            List.of(),
            List.of(),
            false,
            "feed-item-list-getter-ai"
        );
        if (!outcome.changed()) {
            recordAiOutcome(
                "FeedItemList:getItems",
                outcome,
                outcome.staleSnapshot ? "STALE" : "UNCHANGED"
            );
            return originalReturnList;
        }

        try {
            if (feedItemList.items != originalReturnList) {
                recordAiOutcome("FeedItemList:getItems", outcome, "STALE");
                return originalReturnList;
            }
            feedItemList.items = outcome.effectiveList;
            recordAiOutcome("FeedItemList:getItems", outcome, "ASSIGNED_AND_RETURNED");
            return outcome.effectiveList;
        } catch (RuntimeException | LinkageError error) {
            recordAiOutcome("FeedItemList:getItems", outcome, "INSTALL_FAILED");
            logInstallFailure("FeedItemList:getItems", error);
            return originalReturnList;
        }
    }

    public static void filter(FollowFeedList followFeedList) {
        filterFollowFeedList(followFeedList, true, FilterPhase.RESPONSE);
    }

    public static void filterLate(FollowFeedList followFeedList) {
        filterFollowFeedList(followFeedList, true, FilterPhase.LATE_FOLLOW);
    }

    public static List filterLateResult(FollowFeedList followFeedList, List originalReturnList) {
        if (followFeedList == null || originalReturnList == null) return originalReturnList;
        ContentListFilter.Outcome outcome = filterContainerList(
            "FollowFeedList:getItems",
            originalReturnList,
            container -> container instanceof FollowFeed ? ((FollowFeed) container).aweme : null,
            null,
            LATE_FOLLOW_FILTERS,
            List.of(),
            true,
            "following-getter"
        );
        if (!outcome.changed()) {
            recordAiOutcome(
                "FollowFeedList:getItems",
                outcome,
                outcome.staleSnapshot ? "STALE" : "UNCHANGED"
            );
            return originalReturnList;
        }
        try {
            followFeedList.mItems = outcome.effectiveList;
            recordAiOutcome("FollowFeedList:getItems", outcome, "ASSIGNED_AND_RETURNED");
            return outcome.effectiveList;
        } catch (RuntimeException | LinkageError error) {
            recordAiOutcome("FollowFeedList:getItems", outcome, "INSTALL_FAILED");
            logInstallFailure("FollowFeedList:getItems", error);
            return originalReturnList;
        }
    }

    public static void filterLateFinal(FollowFeedList followFeedList) {
        filterFollowFeedList(followFeedList, false, FilterPhase.LATE_FOLLOW);
    }

    public static List filterProfileItems(List items) {
        return filterDirectAwemeList("ProfileAwemeList", items);
    }

    public static boolean filterProfileAdEligibility(boolean eligible) {
        return !ADS_FILTER.getEnabled() && eligible;
    }

    public static void filterSearchContent(SearchMixFeedList response) {
        if (response == null || response.mItems == null) return;

        ContentListFilter.Outcome outcome = filterContainerList(
            "SearchMixFeedList",
            response.mItems,
            container -> container instanceof SearchMixFeed
                ? ((SearchMixFeed) container).getAweme()
                : null,
            container -> container instanceof SearchMixFeed
                && ((SearchMixFeed) container).isAdOrContainAd(),
            List.of(ADS_FILTER),
            List.of(),
            false,
            "wrapped-search"
        );
        if (outcome.changed()) {
            try {
                response.mItems = outcome.effectiveList;
                recordAiOutcome("SearchMixFeedList", outcome, "ASSIGNED");
            } catch (RuntimeException | LinkageError error) {
                recordAiOutcome("SearchMixFeedList", outcome, "INSTALL_FAILED");
                logInstallFailure("SearchMixFeedList", error);
            }
        } else {
            recordAiOutcome("SearchMixFeedList", outcome, outcome.staleSnapshot ? "STALE" : "UNCHANGED");
        }
    }

    public static void filterFriendsContent(Object value) {
        if (!(value instanceof FriendsFeedResponse)) return;

        FriendsFeedResponse response = (FriendsFeedResponse) value;
        ContentListFilter.Outcome outcome = filterContainerList(
            "FriendsFeedResponse:feed",
            response.friendFeedData,
            FeedItemsFilter::extractFriendsAweme,
            null,
            List.of(ADS_FILTER),
            List.of(),
            false,
            "wrapped-friends"
        );
        if (outcome.changed()) {
            try {
                response.friendFeedData = outcome.effectiveList;
                recordAiOutcome("FriendsFeedResponse:feed", outcome, "ASSIGNED");
            } catch (RuntimeException | LinkageError error) {
                recordAiOutcome("FriendsFeedResponse:feed", outcome, "INSTALL_FAILED");
                logInstallFailure("FriendsFeedResponse:feed", error);
            }
        } else {
            recordAiOutcome(
                "FriendsFeedResponse:feed",
                outcome,
                outcome.staleSnapshot ? "STALE" : "UNCHANGED"
            );
        }
    }

    public static void filterDiscoverBanners(BannerList response) {
        if (response == null || response.items == null || !ADS_FILTER.getEnabled()) return;
        removeMatchingInPlace(response.items, item -> item instanceof Banner && ((Banner) item).isAd());
    }

    public static void filterDiscoverTrending(TrendingTopicList response) {
        if (response == null || response.items == null || !ADS_FILTER.getEnabled()) return;
        removeMatchingInPlace(
            response.items,
            item -> item instanceof TrendingTopic && ((TrendingTopic) item).isAd()
        );
    }

    public static Aweme filterMidRollAd(Aweme aweme) {
        return ADS_FILTER.getEnabled() ? null : aweme;
    }

    public static List filterLateInsertedItems(String source, List items) {
        String insertionSource = source == null ? "unknown" : source;
        return filterDirectAwemeList("FeedInsertion:" + insertionSource, items);
    }

    private static List filterDirectAwemeList(String source, List items) {
        ContentListFilter.Outcome outcome = filterContainerList(
            source,
            items,
            container -> container instanceof Aweme ? (Aweme) container : null,
            null,
            List.of(ADS_FILTER),
            List.of(),
            false,
            "direct-wrapped"
        );
        recordAiOutcome(source, outcome, outcome.changed() ? "RETURNED" : "UNCHANGED");
        return outcome.effectiveList;
    }

    private static Aweme extractFriendsAweme(Object container) {
        if (container instanceof FriendsFeed) return ((FriendsFeed) container).getAweme();
        return container instanceof Aweme ? (Aweme) container : null;
    }

    @SuppressWarnings("rawtypes")
    private static void removeMatchingInPlace(List items, ContainerFilter filter) {
        for (int index = items.size() - 1; index >= 0; index--) {
            if (filter.getFiltered(items.get(index))) items.remove(index);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List filterInsertedFeedItems(
        BaseListFragmentPanel panel,
        int insertionPosition,
        String source,
        List items
    ) {
        if (items == null || items.isEmpty()) return items;
        if (panel == null || !"homepage_hot".equals(panel.getEventType())) return items;

        FilterSettingsSnapshot settings = FilterSettingsSnapshot.capture();
        List<IFilter> activeContentFilters = getActiveFilters(CONTENT_FILTERS, settings);
        List<IFilter> activeRangeFilters = getActiveFilters(RANGE_FILTERS, settings);
        boolean cacheInsertion = "golden_house".equals(source)
            || "middle_insert_when_video_lagging".equals(source);
        boolean verbose = BaseSettings.DEBUG.get();
        boolean nonAiActive = !activeContentFilters.isEmpty() || !activeRangeFilters.isEmpty();
        String policyKey = settings.nonAiKey("panel-insertion:" + cacheInsertion);
        ContentListFilter.Outcome outcome = ContentListFilter.filter(new ContentListFilter.Request(
            policyKey,
            items,
            container -> container instanceof Aweme ? (Aweme) container : null,
            null,
            item -> {
                Aweme aweme = (Aweme) item;
                int cacheSourceType = AwemeBizExtKt.getCacheSourceType(aweme);
                if (!cacheInsertion && !isKnownFeedCacheSource(cacheSourceType)) return null;
                if (cacheSourceType == CACHE_SOURCE_OFFLINE_MODE && !settings.filterOffline) return null;
                String reason = getFilterReason(activeContentFilters, aweme);
                return reason == null ? getFilterReason(activeRangeFilters, aweme) : reason;
            },
            (item, observation) -> AiContentFilter.evaluate(
                (Aweme) item,
                settings.hideAiContent,
                observation
            ),
            settings::matchesCurrentPolicy,
            verbose ? FeedItemsFilter::observeRemoval : null,
            false,
            nonAiActive,
            settings.hideAiContent,
            false,
            verbose,
            SystemClock.elapsedRealtime()
        ));
        recordAiOutcome(
            "FeedInsertionPanel:" + (source == null ? "unknown" : source),
            outcome,
            outcome.changed() ? "RETURNED" : outcome.staleSnapshot ? "STALE" : "UNCHANGED"
        );
        return outcome.effectiveList;
    }

    public static FeedItemList filterCachedFeedList(FeedItemList feedItemList) {
        if (feedItemList == null || feedItemList.items == null) return null;
        filterCachedFeedItems("FeedItemList:cold-cache", feedItemList, true);
        return feedItemList.items.isEmpty() ? null : feedItemList;
    }

    public static FeedItemList filterOfflineFeedList(FeedItemList feedItemList) {
        if (feedItemList == null || feedItemList.items == null) return null;
        FilterSettingsSnapshot settings = FilterSettingsSnapshot.capture();
        if (!settings.filterOffline && !settings.hideAiContent) return feedItemList;
        filterCachedFeedItems(
            "FeedItemList:offline-fallback",
            feedItemList,
            settings.filterOffline,
            settings
        );
        return feedItemList.items.isEmpty() ? null : feedItemList;
    }

    public static boolean shouldKeepCachedAweme(Aweme item) {
        if (item == null) return true;

        FilterSettingsSnapshot settings = FilterSettingsSnapshot.capture();
        int cacheSourceType = AwemeBizExtKt.getCacheSourceType(item);
        boolean allowNonAi = cacheSourceType != CACHE_SOURCE_OFFLINE_MODE || settings.filterOffline;
        String reason = null;
        if (allowNonAi) {
            List<IFilter> activeContentFilters = getActiveFilters(CONTENT_FILTERS, settings);
            List<IFilter> activeRangeFilters = getActiveFilters(RANGE_FILTERS, settings);
            reason = getFilterReason(activeContentFilters, item);
            if (reason == null) reason = getFilterReason(activeRangeFilters, item);
        }

        boolean verbose = BaseSettings.DEBUG.get();
        AiObservation observation = verbose && settings.hideAiContent ? new AiObservation() : null;
        int aiResult;
        try {
            aiResult = AiContentFilter.evaluate(item, settings.hideAiContent, observation);
        } catch (RuntimeException | LinkageError error) {
            aiResult = AiContentFilter.READ_ERROR;
        }
        boolean aiRejected = AiContentClassifier.removes(aiResult);
        boolean keep = reason == null && !aiRejected;
        if (reason != null) logItem(item, reason, verbose);
        recordSingleAiDecision(
            "CachedAweme:" + cacheSourceType,
            aiResult,
            settings.hideAiContent,
            reason == null && aiRejected,
            verbose
        );
        return keep;
    }

    private static void filterCachedFeedItems(
        String source,
        FeedItemList feedItemList,
        boolean includeNonAi
    ) {
        filterCachedFeedItems(source, feedItemList, includeNonAi, null);
    }

    private static void filterCachedFeedItems(
        String source,
        FeedItemList feedItemList,
        boolean includeNonAi,
        FilterSettingsSnapshot settings
    ) {
        boolean verbose = BaseSettings.DEBUG.get();
        filterFeedList(
            source,
            feedItemList,
            feedItemList.items,
            container -> (container instanceof Aweme) ? (Aweme) container : null,
            verbose,
            false,
            FilterPhase.RESPONSE,
            includeNonAi,
            settings
        );
    }

    private static boolean isKnownFeedCacheSource(int cacheSourceType) {
        switch (cacheSourceType) {
            case CACHE_SOURCE_COLD_CACHE:
            case CACHE_SOURCE_FEED_UNCONSUMED:
            case CACHE_SOURCE_GOLDEN_HOUSE:
            case CACHE_SOURCE_OFFLINE_MODE:
            case CACHE_SOURCE_MERGE_CACHE:
                return true;
            default:
                return false;
        }
    }

    private static void filterFollowFeedList(
        FollowFeedList followFeedList,
        boolean allowRecentSkip,
        FilterPhase phase
    ) {
        boolean verbose = BaseSettings.DEBUG.get();

        if (followFeedList == null || followFeedList.mItems == null) {
            if (verbose) {
                logNullItems("FollowFeedList", followFeedListNullItemsLogCount);
            }
            return;
        }

        if (verbose && shouldLogBatch()) {
            debugLogBatch(
                "FollowFeedList",
                followFeedList.mItems,
                "phase=" + phase
                    + " feedType=" + followFeedList.feedType
                    + " hasMore=" + followFeedList.hasMore
                    + " cursor=" + followFeedList.cursor
                    + " requestId=" + (followFeedList.requestId == null ? "missing" : "present")
            );
        }

        filterFeedList(
            phase == FilterPhase.RESPONSE ? "FollowFeedList:response" : "FollowFeedList:late",
            followFeedList,
            followFeedList.mItems,
            container -> (container instanceof FollowFeed) ? ((FollowFeed) container).aweme : null,
            verbose,
            allowRecentSkip,
            phase,
            true
        );
    }

    private static void filterFeedList(
        String source,
        Object owner,
        List list,
        AwemeExtractor extractor,
        boolean verbose,
        boolean allowRecentSkip,
        FilterPhase phase,
        boolean includeNonAi
    ) {
        filterFeedList(
            source,
            owner,
            list,
            extractor,
            verbose,
            allowRecentSkip,
            phase,
            includeNonAi,
            null
        );
    }

    private static void filterFeedList(
        String source,
        Object owner,
        List list,
        AwemeExtractor extractor,
        boolean verbose,
        boolean allowRecentSkip,
        FilterPhase phase,
        boolean includeNonAi,
        FilterSettingsSnapshot capturedSettings
    ) {
        if (list == null) return;

        List<IFilter> configuredContentFilters = includeNonAi
            ? (phase == FilterPhase.RESPONSE ? CONTENT_FILTERS : LATE_FOLLOW_FILTERS)
            : List.of();
        List<IFilter> configuredRangeFilters = includeNonAi && phase == FilterPhase.RESPONSE
            ? RANGE_FILTERS
            : List.of();
        FilterSettingsSnapshot settings = capturedSettings == null
            ? FilterSettingsSnapshot.capture()
            : capturedSettings;
        List<IFilter> activeContentFilters = getActiveFilters(configuredContentFilters, settings);
        List<IFilter> activeRangeFilters = getActiveFilters(configuredRangeFilters, settings);
        if (activeContentFilters.isEmpty()
            && activeRangeFilters.isEmpty()
            && !settings.hideAiContent) return;

        String filterMask = getFilterMask(activeContentFilters, activeRangeFilters)
            + (settings.hideAiContent ? "|AiContentFilter" : "");
        boolean probeEnabled = verbose && FILTER_CALL_PROBE_ENABLED;
        int callId = probeEnabled ? filterCallProbeCount.incrementAndGet() : 0;
        long startNs = probeEnabled ? System.nanoTime() : 0;
        int ownerId = probeEnabled ? System.identityHashCode(owner) : 0;
        int listId = System.identityHashCode(list);
        String beforeSample = probeEnabled ? sampleAids(list, extractor) : "";
        int initialSize = list.size();
        if (probeEnabled) {
            recordProbeCall(listId, filterMask);
        }
        ContentListFilter.Outcome outcome = filterContainerListWithSnapshot(
            source,
            list,
            container -> extractor.extract(container),
            null,
            activeContentFilters,
            activeRangeFilters,
            settings,
            allowRecentSkip,
            phase == FilterPhase.RESPONSE ? "response" : "late-follow"
        );

        List resultList = list;
        String installation = outcome.staleSnapshot ? "STALE" : "UNCHANGED";
        if (outcome.changed()) {
            try {
                if (owner instanceof FeedItemList) {
                    ((FeedItemList) owner).items = outcome.effectiveList;
                } else if (owner instanceof FollowFeedList) {
                    ((FollowFeedList) owner).mItems = outcome.effectiveList;
                } else {
                    throw new IllegalStateException(
                        "Unsupported feed list owner: " + (owner == null ? "null" : owner.getClass().getName())
                    );
                }
                resultList = outcome.effectiveList;
                installation = "ASSIGNED";
            } catch (RuntimeException | LinkageError error) {
                installation = "INSTALL_FAILED";
                logInstallFailure(source, error);
            }
        }
        recordAiOutcome(source, outcome, installation);

        if (probeEnabled) {
            logFilterCallProbe(
                callId,
                source,
                ownerId,
                listId,
                initialSize,
                resultList.size(),
                installation.equals("ASSIGNED") ? outcome.removed : 0,
                0,
                filterMask,
                beforeSample,
                sampleAids(resultList, extractor),
                outcome.reasonCounts,
                System.nanoTime() - startNs
            );
        }

        if (probeEnabled) {
            if (outcome.nonAiSkipped) {
                recordProbeCacheHit(listId);
            } else {
                recordProbeScan(
                    listId,
                    installation.equals("ASSIGNED") ? outcome.removed : 0,
                    System.nanoTime() - startNs
                );
            }
        }

        if (verbose && installation.equals("ASSIGNED") && outcome.removed > 0 && shouldLogBatch()) {
            int removedFinal = outcome.removed;
            int resultSize = resultList.size();
            Logger.printInfo(() -> "[Morphe TikTok FeedFilter] filter(" + source + "): size "
                + initialSize + " -> " + resultSize
                + " (removed=" + removedFinal + ")");
        }
    }

    private static ContentListFilter.Outcome filterContainerList(
        String source,
        List list,
        ContentListFilter.Extractor extractor,
        ContentListFilter.ContainerPredicate nativeAdPredicate,
        List<IFilter> configuredContentFilters,
        List<IFilter> configuredRangeFilters,
        boolean allowRecentSkip,
        String policySuffix
    ) {
        FilterSettingsSnapshot settings = FilterSettingsSnapshot.capture();
        List<IFilter> activeContentFilters = getActiveFilters(configuredContentFilters, settings);
        List<IFilter> activeRangeFilters = getActiveFilters(configuredRangeFilters, settings);
        return filterContainerListWithSnapshot(
            source,
            list,
            extractor,
            nativeAdPredicate,
            activeContentFilters,
            activeRangeFilters,
            settings,
            allowRecentSkip,
            policySuffix
        );
    }

    private static ContentListFilter.Outcome filterContainerListWithSnapshot(
        String source,
        List list,
        ContentListFilter.Extractor extractor,
        ContentListFilter.ContainerPredicate nativeAdPredicate,
        List<IFilter> activeContentFilters,
        List<IFilter> activeRangeFilters,
        FilterSettingsSnapshot settings,
        boolean allowRecentSkip,
        String policySuffix
    ) {
        boolean nonAiActive = !activeContentFilters.isEmpty() || !activeRangeFilters.isEmpty();
        boolean verbose = BaseSettings.DEBUG.get();
        String activeMask = getFilterMask(activeContentFilters, activeRangeFilters);
        String policyKey = settings.nonAiKey(policySuffix + ':' + activeMask);

        return ContentListFilter.filter(new ContentListFilter.Request(
            policyKey,
            list,
            extractor,
            nativeAdPredicate,
            item -> {
                Aweme aweme = (Aweme) item;
                String reason = getFilterReason(activeContentFilters, aweme);
                return reason == null ? getFilterReason(activeRangeFilters, aweme) : reason;
            },
            (item, observation) -> AiContentFilter.evaluate(
                (Aweme) item,
                settings.hideAiContent,
                observation
            ),
            settings::matchesCurrentPolicy,
            verbose ? FeedItemsFilter::observeRemoval : null,
            nativeAdPredicate != null && settings.removeAds,
            nonAiActive,
            settings.hideAiContent,
            allowRecentSkip,
            verbose,
            SystemClock.elapsedRealtime()
        ));
    }

    private static void observeRemoval(
        Object item,
        String primaryReason,
        int aiReasonMask,
        AiObservation observation
    ) {
        if (!(item instanceof Aweme)) return;
        if (!AiContentFilter.class.getSimpleName().equals(primaryReason)) {
            logItem((Aweme) item, primaryReason, true);
        }
    }

    private static void logInstallFailure(String source, Throwable error) {
        if (listReplacementLogCount.getAndIncrement() >= 3) return;
        Logger.printException(
            () -> "[Morphe TikTok FeedFilter] Failed to install filtered " + source + " list",
            error
        );
    }

    private static void recordSingleAiDecision(
        String source,
        int aiResult,
        boolean enabled,
        boolean aiOnlyRemoved,
        boolean diagnostics
    ) {
        int aiReasons = aiResult & AiContentClassifier.REMOVAL_MASK;
        int readErrors = (aiResult & AiContentFilter.READ_ERROR) == 0 ? 0 : 1;
        if (readErrors > 0) recordAiReadErrors(readErrors);
        if (!diagnostics) return;
        synchronized (aiSummaryLock) {
            aiSummary.calls++;
            if (enabled) aiSummary.evaluated++;
            aiSummary.addReasons(aiResult);
            if (AiContentClassifier.removes(aiReasons)) aiSummary.matched++;
            if (aiOnlyRemoved) aiSummary.removed++;
            aiSummary.lastEnabled = enabled;
            aiSummary.lastSource = source;
            emitAiSummaryIfReadyLocked(SystemClock.elapsedRealtime());
        }
    }

    private static void recordAiOutcome(
        String source,
        ContentListFilter.Outcome outcome,
        String installation
    ) {
        if (outcome.aiReadErrors > 0) recordAiReadErrors(outcome.aiReadErrors);
        if (!outcome.diagnosticsEnabled) return;

        synchronized (aiSummaryLock) {
            aiSummary.calls++;
            aiSummary.evaluated += outcome.aiEvaluated;
            aiSummary.matched += outcome.aiMatched;
            aiSummary.creatorLabels += outcome.creatorLabels;
            aiSummary.tiktokLabels += outcome.tiktokLabels;
            aiSummary.createdByAi += outcome.createdByAi;
            aiSummary.moderatorLabels += outcome.moderatorLabels;
            aiSummary.unknownLabels += outcome.unknownLabels;
            aiSummary.readErrors += outcome.aiReadErrors;
            aiSummary.callbackErrors += outcome.callbackErrors;
            aiSummary.coalescedCalls += Math.max(0, outcome.aiEvaluated - 1);
            if ("ASSIGNED".equals(installation)
                || "ASSIGNED_AND_RETURNED".equals(installation)
                || "RETURNED".equals(installation)) {
                aiSummary.removed += outcome.aiOnlyRemoved;
            }
            if ("INSTALL_FAILED".equals(installation)) aiSummary.installFailed++;
            if ("STALE".equals(installation)) aiSummary.staleSnapshots++;
            aiSummary.lastEnabled = outcome.aiEnabled;
            aiSummary.lastSource = source;
            aiSummary.lastInstallation = installation;
            emitAiSummaryIfReadyLocked(SystemClock.elapsedRealtime());
        }
    }

    private static void recordAiReadErrors(int count) {
        long now = SystemClock.elapsedRealtime();
        synchronized (aiSummaryLock) {
            pendingAiReadErrors += count;
            if (now - lastAiErrorLogElapsed < 5000) return;
            int errors = pendingAiReadErrors;
            pendingAiReadErrors = 0;
            lastAiErrorLogElapsed = now;
            LogBufferManager.appendEvent(
                DiagnosticCategory.FEED_AND_NAVIGATION,
                "FeedItemsFilter",
                "ERROR",
                "AI_READ_ERROR count=" + errors
            );
        }
    }

    private static void emitAiSummaryIfReadyLocked(long now) {
        long windowMs = now - aiSummary.startedAtElapsedMs;
        if (windowMs < 5000 || aiSummary.calls == 0) return;
        String message = aiSummary.toMessage(windowMs);
        aiSummary = new AiSummary(now);
        LogBufferManager.appendEvent(
            DiagnosticCategory.FEED_AND_NAVIGATION,
            "FeedItemsFilter",
            "DEBUG",
            message
        );
    }

    private static List<IFilter> getActiveFilters(
        List<IFilter> filters, FilterSettingsSnapshot settings
    ) {
        List<IFilter> activeFilters = new ArrayList<>(filters.size());
        for (IFilter filter : filters) {
            boolean enabled;
            if (filter instanceof AdsFilter) enabled = settings.removeAds;
            else if (filter instanceof LiveFilter) enabled = settings.hideLive;
            else if (filter instanceof StoryFilter) enabled = settings.hideStory;
            else if (filter instanceof ImageVideoFilter) enabled = settings.hideImage;
            else if (filter instanceof ShopFilter) enabled = settings.hideShop;
            else enabled = settings.feedFilterEnabled && filter.getEnabled();
            if (enabled) {
                activeFilters.add(filter);
            }
        }
        return activeFilters;
    }

    private static String getFilterReason(List<IFilter> activeFilters, Aweme item) {
        for (IFilter filter : activeFilters) {
            try {
                if (filter.getFiltered(item)) {
                    return filter.getClass().getSimpleName();
                }
            } catch (RuntimeException exception) {
                int count = filterExceptionLogCount.getAndIncrement();
                if (count < 3) {
                    Logger.printException(
                        () -> "[Morphe TikTok FeedFilter] " + filter.getClass().getSimpleName()
                            + " failed for aid=" + item.getAid() + "; keeping the item",
                        exception
                    );
                }
            }
        }
        return null;
    }

    private static void logNullItems(String source, AtomicInteger counter) {
        int count = counter.getAndIncrement();
        if (count < MAX_NULL_ITEMS_LOGS) {
            Logger.printInfo(() -> "[Morphe TikTok FeedFilter] filter(" + source + "): items=null");
        } else if (count == MAX_NULL_ITEMS_LOGS) {
            Logger.printInfo(() -> "[Morphe TikTok FeedFilter] filter(" + source + "): items=null (further logs suppressed)");
        }
    }

    private static void debugLogBatch(String source, List list, String metadata) {
        int size = list == null ? -1 : list.size();
        FilterSettingsSnapshot settings = FilterSettingsSnapshot.capture();
        Logger.printInfo(() ->
            "[Morphe TikTok FeedFilter] filter(" + source + "): size=" + size
                + " " + metadata
                + " remove_ads=" + settings.removeAds
                + " hide_shop=" + settings.hideShop
                + " hide_live=" + settings.hideLive
                + " hide_story=" + settings.hideStory
                + " hide_image=" + settings.hideImage
                + " hide_ai_content=" + settings.hideAiContent
                + " min_max_views=\"" + (settings.feedFilterEnabled ? settings.minMaxViews : "inactive") + "\""
                + " min_max_likes=\"" + (settings.feedFilterEnabled ? settings.minMaxLikes : "inactive") + "\""
        );
    }

    private static void logItem(Aweme item, String reason, boolean verbose) {
        if (!verbose || reason == null || !shouldLogItem()) return;

        String shareUrl = item.getShareUrl();
        if (shareUrl != null && shareUrl.length() > 140) {
            shareUrl = shareUrl.substring(0, 140) + "...";
        }

        String finalShareUrl = shareUrl;
        Logger.printInfo(() -> {
            long playCount = -1;
            long likeCount = -1;

            AwemeStatistics statistics = item.getStatistics();
            if (statistics != null) {
                playCount = statistics.getPlayCount();
                likeCount = statistics.getDiggCount();
            }

            var imageInfos = item.getImageInfos();
            boolean isImage = imageInfos != null && !imageInfos.isEmpty();
            boolean isPhotoMode = item.getPhotoModeImageInfo() != null || item.getPhotoModeTextInfo() != null;

            return "[Morphe TikTok FeedFilter] item"
                + " aid=" + item.getAid()
                + " ad=" + item.isAd()
                + " softAd=" + item.isSoftAd()
                + " promo=" + item.isWithPromotionalMusic()
                + " liveEvidence=" + LiveFilter.getLiveEvidence(item)
                + " story=" + item.getIsTikTokStory()
                + " image=" + isImage
                + " photoMode=" + isPhotoMode
                + " playCount=" + playCount
                + " likeCount=" + likeCount
                + " shareUrl=" + (finalShareUrl == null ? "null" : "\"" + finalShareUrl + "\"")
                + " => " + (reason == null ? "KEEP" : "FILTER(" + reason + ")");
        });
    }

    private static boolean shouldLogBatch() {
        return batchLogCount.getAndIncrement() < MAX_BATCH_LOGS;
    }

    private static boolean shouldLogItem() {
        return itemLogCount.getAndIncrement() < MAX_ITEM_LOGS;
    }

    private static void recordProbeCall(int listId, String filterMask) {
        if (!FILTER_CALL_PROBE_SUMMARY_ENABLED) return;

        synchronized (filterCallProbeSummaryLock) {
            filterCallProbeSummary.calls++;
            filterCallProbeSummary.uniqueListIds.add(listId);
            filterCallProbeSummary.lastFilterMask = filterMask;
        }
    }

    private static void recordProbeCacheHit(int listId) {
        if (!FILTER_CALL_PROBE_SUMMARY_ENABLED) return;

        String summary = null;
        synchronized (filterCallProbeSummaryLock) {
            filterCallProbeSummary.cacheHits++;
            filterCallProbeSummary.uniqueListIds.add(listId);
            summary = rotateProbeSummaryIfReadyLocked(System.currentTimeMillis());
        }
        logProbeSummary(summary);
    }

    private static void recordProbeCacheMiss(String reason) {
        if (!FILTER_CALL_PROBE_SUMMARY_ENABLED) return;

        synchronized (filterCallProbeSummaryLock) {
            if ("newList".equals(reason)) {
                filterCallProbeSummary.missNewList++;
            } else if ("filterMask".equals(reason)) {
                filterCallProbeSummary.missFilterMask++;
            } else if ("size".equals(reason)) {
                filterCallProbeSummary.missSize++;
            } else if ("sample".equals(reason)) {
                filterCallProbeSummary.missSample++;
            } else if ("expired".equals(reason)) {
                filterCallProbeSummary.missExpired++;
            } else {
                filterCallProbeSummary.missOther++;
            }
        }
    }

    private static void recordProbeScan(int listId, int removed, long elapsedNs) {
        if (!FILTER_CALL_PROBE_SUMMARY_ENABLED) return;

        String summary = null;
        long elapsedMs = elapsedNs / 1_000_000L;
        synchronized (filterCallProbeSummaryLock) {
            filterCallProbeSummary.scans++;
            filterCallProbeSummary.removed += removed;
            filterCallProbeSummary.scanElapsedMs += elapsedMs;
            filterCallProbeSummary.maxScanMs = Math.max(filterCallProbeSummary.maxScanMs, elapsedMs);
            if (elapsedMs >= FILTER_CALL_PROBE_SLOW_MS) {
                filterCallProbeSummary.slowScans++;
            }
            filterCallProbeSummary.uniqueListIds.add(listId);
            summary = rotateProbeSummaryIfReadyLocked(System.currentTimeMillis());
        }
        logProbeSummary(summary);
    }

    private static String rotateProbeSummaryIfReadyLocked(long nowMs) {
        long windowMs = nowMs - filterCallProbeSummary.startedAtMs;
        if (windowMs < FILTER_CALL_PROBE_SUMMARY_WINDOW_MS || filterCallProbeSummary.calls == 0) {
            return null;
        }

        String summary = filterCallProbeSummary.toLogMessage(windowMs);
        filterCallProbeSummary = new ProbeSummary(nowMs);
        return summary;
    }

    private static void logProbeSummary(String summary) {
        if (summary == null) return;
        Logger.printInfo(() -> summary);
    }

    private static String getFilterMask(
        List<IFilter> activeContentFilters,
        List<IFilter> activeRangeFilters
    ) {
        StringBuilder builder = new StringBuilder();
        appendFilterMask(builder, activeContentFilters);
        appendFilterMask(builder, activeRangeFilters);
        return builder.toString();
    }

    private static void appendFilterMask(StringBuilder builder, List<IFilter> activeFilters) {
        for (IFilter filter : activeFilters) {
            if (builder.length() > 0) builder.append('|');
            builder.append(filter.getClass().getSimpleName());
        }
    }

    private static String sampleAids(List list, AwemeExtractor extractor) {
        List snapshot = new ArrayList(list);
        StringBuilder builder = new StringBuilder();
        int sampled = 0;
        for (Object container : snapshot) {
            if (sampled >= FILTER_CALL_PROBE_AID_SAMPLE_SIZE) {
                break;
            }

            Aweme item = extractor.extract(container);
            if (item == null) {
                continue;
            }

            if (builder.length() > 0) builder.append(',');
            builder.append(item.getAid());
            sampled++;
        }
        return builder.length() == 0 ? "none" : builder.toString();
    }

    private static void logFilterCallProbe(
        int callId,
        String source,
        int ownerId,
        int listId,
        int beforeSize,
        int afterSize,
        int removed,
        int rangeRejected,
        String filterMask,
        String beforeSample,
        String afterSample,
        Map<String, Integer> reasonCounts,
        long elapsedNs
    ) {
        long elapsedMs = elapsedNs / 1_000_000L;
        ProbeSeenList seen = updateSeenList(listId, beforeSample, afterSample, beforeSize, afterSize);
        boolean interesting = seen.seenCount > 1
            || removed > 0
            || elapsedMs >= FILTER_CALL_PROBE_SLOW_MS;

        if (!interesting) return;

        String counts = reasonCounts == null || reasonCounts.isEmpty() ? "none" : reasonCounts.toString();
        String stack = FILTER_CALL_PROBE_STACKS ? " stack=" + getProbeStack() : "";

        Logger.printInfo(() -> "[Morphe TikTok FeedFilterProbe]"
            + " call=" + callId
            + " source=" + source
            + " owner=" + ownerId
            + " list=" + listId
            + " seen=" + seen.seenCount
            + " previousBefore=\"" + seen.previousBeforeSample + "\""
            + " sameBefore=" + beforeSample.equals(seen.previousBeforeSample)
            + " size=" + beforeSize + "->" + afterSize
            + " removed=" + removed
            + " rangeRejected=" + rangeRejected
            + " reasons=" + counts
            + " filters=\"" + filterMask + "\""
            + " before=\"" + beforeSample + "\""
            + " after=\"" + afterSample + "\""
            + " elapsedMs=" + elapsedMs
            + stack);
    }

    private static ProbeSeenList updateSeenList(
        int listId,
        String beforeSample,
        String afterSample,
        int beforeSize,
        int afterSize
    ) {
        synchronized (filterCallProbeSeenLists) {
            if (filterCallProbeSeenLists.size() > FILTER_CALL_PROBE_MAX_SEEN_LISTS) {
                filterCallProbeSeenLists.clear();
            }

            ProbeSeenList seen = filterCallProbeSeenLists.get(listId);
            if (seen == null) {
                seen = new ProbeSeenList();
                filterCallProbeSeenLists.put(listId, seen);
            }

            String previousBeforeSample = seen.lastBeforeSample;
            seen.seenCount++;
            seen.previousBeforeSample = previousBeforeSample == null ? "none" : previousBeforeSample;
            seen.lastBeforeSample = beforeSample;
            seen.lastAfterSample = afterSample;
            seen.lastBeforeSize = beforeSize;
            seen.lastAfterSize = afterSize;
            return seen;
        }
    }

    private static String getProbeStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder builder = new StringBuilder();
        int added = 0;
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.startsWith("app.morphe.extension.tiktok.feedfilter.")
                || className.startsWith("java.lang.Thread")) {
                continue;
            }

            if (builder.length() > 0) builder.append(" <- ");
            builder.append(className).append('#').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            if (++added >= 4) break;
        }
        return builder.length() == 0 ? "none" : builder.toString();
    }

    private enum FilterPhase {
        RESPONSE,
        LATE_FOLLOW
    }

    @FunctionalInterface
    interface AwemeExtractor {
        Aweme extract(Object source);
    }

    @FunctionalInterface
    interface ContainerFilter {
        boolean getFiltered(Object source);
    }

    private static final class ProbeSeenList {
        int seenCount;
        String previousBeforeSample = "none";
        String lastBeforeSample = "none";
        String lastAfterSample = "none";
        int lastBeforeSize;
        int lastAfterSize;
    }

    private static final class ProbeSummary {
        final long startedAtMs;
        final Set<Integer> uniqueListIds = new HashSet<>();
        int calls;
        int scans;
        int cacheHits;
        int slowScans;
        int removed;
        int missNewList;
        int missFilterMask;
        int missSize;
        int missSample;
        int missExpired;
        int missOther;
        long scanElapsedMs;
        long maxScanMs;
        String lastFilterMask = "";

        ProbeSummary(long startedAtMs) {
            this.startedAtMs = startedAtMs;
        }

        String toLogMessage(long windowMs) {
            int terminalCalls = scans + cacheHits;
            double hitRate = terminalCalls == 0 ? 0 : (cacheHits * 100.0) / terminalCalls;
            double averageScanMs = scans == 0 ? 0 : scanElapsedMs / (double) scans;

            return "[Morphe TikTok FeedFilterProbeSummary]"
                + " windowMs=" + windowMs
                + " calls=" + calls
                + " scans=" + scans
                + " cacheHits=" + cacheHits
                + " cacheHitRate=" + Math.round(hitRate * 10.0) / 10.0 + "%"
                + " removed=" + removed
                + " slowScans=" + slowScans
                + " avgScanMs=" + Math.round(averageScanMs * 10.0) / 10.0
                + " maxScanMs=" + maxScanMs
                + " uniqueLists=" + uniqueListIds.size()
                + " missNewList=" + missNewList
                + " missFilterMask=" + missFilterMask
                + " missSize=" + missSize
                + " missSample=" + missSample
                + " missExpired=" + missExpired
                + " missOther=" + missOther
                + " filters=\"" + lastFilterMask + "\"";
        }
    }

    private static final class FilterSettingsSnapshot {
        final boolean feedFilterEnabled;
        final boolean removeAds;
        final boolean hideLive;
        final boolean hideShop;
        final boolean hideStory;
        final boolean hideImage;
        final boolean filterOffline;
        final boolean hideAiContent;
        final String minMaxViews;
        final String minMaxLikes;

        private FilterSettingsSnapshot(
            boolean feedFilterEnabled,
            boolean removeAds,
            boolean hideLive,
            boolean hideShop,
            boolean hideStory,
            boolean hideImage,
            boolean filterOffline,
            boolean hideAiContent,
            String minMaxViews,
            String minMaxLikes
        ) {
            this.feedFilterEnabled = feedFilterEnabled;
            this.removeAds = removeAds;
            this.hideLive = hideLive;
            this.hideShop = hideShop;
            this.hideStory = hideStory;
            this.hideImage = hideImage;
            this.filterOffline = filterOffline;
            this.hideAiContent = hideAiContent;
            this.minMaxViews = minMaxViews;
            this.minMaxLikes = minMaxLikes;
        }

        static FilterSettingsSnapshot capture() {
            boolean generalEnabled = SettingsStatus.feedFilterEnabled;
            return new FilterSettingsSnapshot(
                generalEnabled,
                generalEnabled && Settings.REMOVE_ADS.get(),
                generalEnabled && Settings.HIDE_LIVE.get(),
                generalEnabled && Settings.HIDE_SHOP.get(),
                generalEnabled && Settings.HIDE_STORY.get(),
                generalEnabled && Settings.HIDE_IMAGE.get(),
                generalEnabled && Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.get(),
                SettingsStatus.hideAiContentEnabled && Settings.HIDE_AI_CONTENT.get(),
                Settings.MIN_MAX_VIEWS.get(),
                Settings.MIN_MAX_LIKES.get()
            );
        }

        String nonAiKey(String routePolicy) {
            return routePolicy
                + "|ads=" + removeAds
                + "|live=" + hideLive
                + "|shop=" + hideShop
                + "|story=" + hideStory
                + "|image=" + hideImage
                + "|offline=" + filterOffline
                + "|views=" + minMaxViews
                + "|likes=" + minMaxLikes;
        }

        boolean matchesCurrentPolicy() {
            FilterSettingsSnapshot current = capture();
            return feedFilterEnabled == current.feedFilterEnabled
                && removeAds == current.removeAds
                && hideLive == current.hideLive
                && hideShop == current.hideShop
                && hideStory == current.hideStory
                && hideImage == current.hideImage
                && filterOffline == current.filterOffline
                && hideAiContent == current.hideAiContent
                && minMaxViews.equals(current.minMaxViews)
                && minMaxLikes.equals(current.minMaxLikes);
        }
    }

    private static final class AiSummary {
        final long startedAtElapsedMs;
        int calls;
        int evaluated;
        int matched;
        int removed;
        int creatorLabels;
        int tiktokLabels;
        int createdByAi;
        int moderatorLabels;
        int unknownLabels;
        int readErrors;
        int callbackErrors;
        int installFailed;
        int staleSnapshots;
        int coalescedCalls;
        String lastSource = "none";
        String lastInstallation = "none";
        boolean lastEnabled;

        AiSummary(long startedAtElapsedMs) {
            this.startedAtElapsedMs = startedAtElapsedMs;
        }

        void addReasons(int reasons) {
            if ((reasons & AiContentClassifier.CREATOR_LABEL) != 0) creatorLabels++;
            if ((reasons & AiContentClassifier.TIKTOK_AI_LABEL) != 0) tiktokLabels++;
            if ((reasons & AiContentClassifier.CREATED_BY_AI) != 0) createdByAi++;
            if ((reasons & AiContentClassifier.MODERATOR_AI_LABEL) != 0) moderatorLabels++;
            if ((reasons & AiContentClassifier.UNKNOWN_LABEL_TYPE) != 0) unknownLabels++;
            if ((reasons & AiContentFilter.READ_ERROR) != 0) readErrors++;
        }

        String toMessage(long windowMs) {
            return "AI summary"
                + " windowMs=" + windowMs
                + " hideAiContent=" + lastEnabled
                + " calls=" + calls
                + " evaluated=" + evaluated
                + " matched=" + matched
                + " removed=" + removed
                + " creatorLabel=" + creatorLabels
                + " tiktokLabel=" + tiktokLabels
                + " createdByAi=" + createdByAi
                + " moderatorLabel=" + moderatorLabels
                + " unknownLabel=" + unknownLabels
                + " readError=" + readErrors
                + " callbackError=" + callbackErrors
                + " installFailed=" + installFailed
                + " staleSnapshot=" + staleSnapshots
                + " coalescedCalls=" + coalescedCalls
                + " lastSource=" + lastSource
                + " lastInstallation=" + lastInstallation;
        }
    }
}


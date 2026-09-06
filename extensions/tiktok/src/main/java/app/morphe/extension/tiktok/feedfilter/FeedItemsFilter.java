package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeBizExtKt;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;
import com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeed;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeedList;

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
    private static final long PROCESSED_LIST_CACHE_TTL_MS = 250;
    private static final int PROCESSED_LIST_CACHE_MAX_SEEN_LISTS = 256;
    private static final AtomicInteger feedItemListNullItemsLogCount = new AtomicInteger();
    private static final AtomicInteger followFeedListNullItemsLogCount = new AtomicInteger();
    private static final AtomicInteger batchLogCount = new AtomicInteger();
    private static final AtomicInteger itemLogCount = new AtomicInteger();
    private static final AtomicInteger filterExceptionLogCount = new AtomicInteger();
    private static final AtomicInteger listReplacementLogCount = new AtomicInteger();
    private static final AtomicInteger filterCallProbeCount = new AtomicInteger();
    private static final Map<Integer, ProbeSeenList> filterCallProbeSeenLists = new HashMap<>();
    private static final Map<Integer, ProcessedListState> processedListCache = new HashMap<>();
    private static final Object filterCallProbeSummaryLock = new Object();
    private static ProbeSummary filterCallProbeSummary = new ProbeSummary(System.currentTimeMillis());

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
            FilterPhase.RESPONSE
        );
    }

    public static void filter(FollowFeedList followFeedList) {
        filterFollowFeedList(followFeedList, true, FilterPhase.RESPONSE);
    }

    public static void filterLate(FollowFeedList followFeedList) {
        filterFollowFeedList(followFeedList, true, FilterPhase.LATE_FOLLOW);
    }

    public static void filterLateFinal(FollowFeedList followFeedList) {
        filterFollowFeedList(followFeedList, false, FilterPhase.LATE_FOLLOW);
    }

    public static List filterProfileAds(List items) {
        return filterAdOnlyAwemeList("ProfileAwemeList", items);
    }

    public static List filterLateInsertedAds(String source, List items) {
        String insertionSource = source == null ? "unknown" : source;
        return filterAdOnlyAwemeList("FeedInsertion:" + insertionSource, items);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List filterAdOnlyAwemeList(String source, List items) {
        if (items == null || items.isEmpty() || !ADS_FILTER.getEnabled()) return items;

        boolean verbose = BaseSettings.DEBUG.get();
        ArrayList kept = null;
        int removed = 0;
        for (int index = 0; index < items.size(); index++) {
            Object container = items.get(index);
            Aweme item = container instanceof Aweme ? (Aweme) container : null;
            String reason = item == null ? null : getFilterReason(LATE_FOLLOW_FILTERS, item);
            if (reason == null) {
                if (kept != null) kept.add(container);
                continue;
            }

            if (kept == null) {
                kept = new ArrayList(items.size());
                kept.addAll(items.subList(0, index));
            }
            removed++;
            logItem(item, reason, verbose);
        }

        if (kept == null) return items;
        if (verbose && shouldLogBatch()) {
            int initialSize = items.size();
            int resultSize = kept.size();
            int removedFinal = removed;
            Logger.printInfo(() -> "[Morphe TikTok FeedFilter] filter(" + source + "): size "
                + initialSize + " -> " + resultSize + " (removed=" + removedFinal + ")");
        }
        return kept;
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

        List<IFilter> activeContentFilters = getActiveFilters(CONTENT_FILTERS);
        List<IFilter> activeRangeFilters = getActiveFilters(RANGE_FILTERS);
        if (activeContentFilters.isEmpty() && activeRangeFilters.isEmpty()) return items;

        boolean cacheInsertion = "golden_house".equals(source)
            || "middle_insert_when_video_lagging".equals(source);
        ArrayList kept = null;
        int removed = 0;

        for (int index = 0; index < items.size(); index++) {
            Object container = items.get(index);
            if (!(container instanceof Aweme)) {
                if (kept != null) kept.add(container);
                continue;
            }

            Aweme item = (Aweme) container;
            int cacheSourceType = AwemeBizExtKt.getCacheSourceType(item);
            if (!cacheInsertion && !isKnownFeedCacheSource(cacheSourceType)) {
                if (kept != null) kept.add(container);
                continue;
            }
            if (cacheSourceType == CACHE_SOURCE_OFFLINE_MODE &&
                    !Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.get()) {
                if (kept != null) kept.add(container);
                continue;
            }

            String reason = getFilterReason(activeContentFilters, item);
            if (reason == null) reason = getFilterReason(activeRangeFilters, item);
            if (reason == null) {
                if (kept != null) kept.add(container);
                continue;
            }

            if (kept == null) {
                kept = new ArrayList(items.size());
                kept.addAll(items.subList(0, index));
            }
            removed++;
            logItem(item, reason, BaseSettings.DEBUG.get());
        }

        if (kept == null) return items;
        if (BaseSettings.DEBUG.get()) {
            int removedCount = removed;
            int keptCount = kept.size();
            Logger.printInfo(() -> "[Morphe TikTok FeedFilter] Final insert source=" + source
                + " removed=" + removedCount + " kept=" + keptCount);
        }
        return kept;
    }

    public static FeedItemList filterCachedFeedList(FeedItemList feedItemList) {
        if (feedItemList == null || feedItemList.items == null) return null;
        filterCachedFeedItems("FeedItemList:cold-cache", feedItemList);
        return feedItemList.items.isEmpty() ? null : feedItemList;
    }

    public static FeedItemList filterOfflineFeedList(FeedItemList feedItemList) {
        if (feedItemList == null || feedItemList.items == null) return null;
        if (!Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.get()) return feedItemList;
        filterCachedFeedItems("FeedItemList:offline-fallback", feedItemList);
        return feedItemList.items.isEmpty() ? null : feedItemList;
    }

    public static boolean shouldKeepCachedAweme(Aweme item) {
        if (item == null) return true;

        int cacheSourceType = AwemeBizExtKt.getCacheSourceType(item);
        if (cacheSourceType == CACHE_SOURCE_OFFLINE_MODE &&
                !Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.get()) {
            return true;
        }

        List<IFilter> activeContentFilters = getActiveFilters(CONTENT_FILTERS);
        List<IFilter> activeRangeFilters = getActiveFilters(RANGE_FILTERS);
        String reason = getFilterReason(activeContentFilters, item);
        if (reason == null) reason = getFilterReason(activeRangeFilters, item);
        if (reason == null) return true;

        logItem(item, reason, BaseSettings.DEBUG.get());
        if (BaseSettings.DEBUG.get()) {
            String rejectionReason = reason;
            Logger.printInfo(() -> "[Morphe TikTok FeedFilter] Cached item aid="
                + item.getAid() + " sourceType=" + cacheSourceType
                + " rejected by " + rejectionReason);
        }
        return false;
    }

    private static void filterCachedFeedItems(String source, FeedItemList feedItemList) {
        boolean verbose = BaseSettings.DEBUG.get();
        filterFeedList(
            source,
            feedItemList,
            feedItemList.items,
            container -> (container instanceof Aweme) ? (Aweme) container : null,
            verbose,
            false,
            FilterPhase.RESPONSE
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
            phase
        );
    }

    private static void filterFeedList(
        String source,
        Object owner,
        List list,
        AwemeExtractor extractor,
        boolean verbose,
        boolean allowRecentSkip,
        FilterPhase phase
    ) {
        if (list == null) return;

        List<IFilter> activeContentFilters = getActiveFilters(
            phase == FilterPhase.RESPONSE ? CONTENT_FILTERS : LATE_FOLLOW_FILTERS
        );
        List<IFilter> activeRangeFilters = phase == FilterPhase.RESPONSE
            ? getActiveFilters(RANGE_FILTERS)
            : List.of();
        if (activeContentFilters.isEmpty() && activeRangeFilters.isEmpty()) return;

        String filterMask = getFilterMask(activeContentFilters, activeRangeFilters);
        ListFingerprint beforeFingerprint = ListFingerprint.from(list, extractor);
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

        if (allowRecentSkip && shouldSkipRecentlyProcessedList(
            listId,
            beforeFingerprint,
            filterMask,
            callId,
            source,
            probeEnabled
        )) {
            return;
        }

        int contentRemoved = 0;
        int rangeRejected = 0;
        Map<String, Integer> reasonCounts = probeEnabled ? new HashMap<>() : null;

        List snapshot = new ArrayList(list);
        List contentKept = new ArrayList(snapshot.size());
        List rangeKept = new ArrayList(snapshot.size());
        for (Object container : snapshot) {
            Aweme item = extractor.extract(container);
            if (item == null) {
                contentKept.add(container);
                rangeKept.add(container);
                continue;
            }

            String contentReason = getFilterReason(activeContentFilters, item);
            if (contentReason != null) {
                contentRemoved++;
                incrementReason(reasonCounts, contentReason);
                logItem(item, contentReason, verbose);
                continue;
            }

            contentKept.add(container);
            String rangeReason = getFilterReason(activeRangeFilters, item);
            if (rangeReason != null) {
                rangeRejected++;
                incrementReason(reasonCounts, rangeReason);
                logItem(item, rangeReason, verbose);
                continue;
            }

            rangeKept.add(container);
        }

        List kept = rangeKept;
        int removed = initialSize - kept.size();

        List resultList = list;
        if (removed > 0) {
            try {
                list.clear();
                list.addAll(kept);
            } catch (RuntimeException exception) {
                resultList = replaceOwnerList(owner, kept);
                if (listReplacementLogCount.getAndIncrement() < 3) {
                    Logger.printException(
                        () -> "[Morphe TikTok FeedFilter] Replaced a non-mutable " + source + " list",
                        exception
                    );
                }
            }
        }

        if (probeEnabled) {
            logFilterCallProbe(
                callId,
                source,
                ownerId,
                listId,
                initialSize,
                resultList.size(),
                removed,
                rangeRejected,
                filterMask,
                beforeSample,
                sampleAids(resultList, extractor),
                reasonCounts,
                System.nanoTime() - startNs
            );
        }

        if (probeEnabled) {
            recordProbeScan(listId, removed, System.nanoTime() - startNs);
        }

        rememberProcessedList(listId, ListFingerprint.from(resultList, extractor), filterMask);

        if (verbose && removed > 0 && shouldLogBatch()) {
            int removedFinal = removed;
            int contentRemovedFinal = contentRemoved;
            int rangeRejectedFinal = rangeRejected;
            int resultSize = resultList.size();
            Logger.printInfo(() -> "[Morphe TikTok FeedFilter] filter(" + source + "): size "
                + initialSize + " -> " + resultSize
                + " (removed=" + removedFinal
                + ", contentRemoved=" + contentRemovedFinal
                + ", rangeRejected=" + rangeRejectedFinal + ")");
        }
    }

    private static List replaceOwnerList(Object owner, List kept) {
        List replacement = new ArrayList(kept);
        if (owner instanceof FeedItemList) {
            ((FeedItemList) owner).items = replacement;
            return replacement;
        }
        if (owner instanceof FollowFeedList) {
            ((FollowFeedList) owner).mItems = replacement;
            return replacement;
        }
        throw new IllegalStateException("Unsupported feed list owner: " + owner.getClass().getName());
    }

    private static void incrementReason(Map<String, Integer> reasonCounts, String reason) {
        if (reasonCounts == null) return;
        Integer count = reasonCounts.get(reason);
        reasonCounts.put(reason, count == null ? 1 : count + 1);
    }

    private static List<IFilter> getActiveFilters(List<IFilter> filters) {
        List<IFilter> activeFilters = new ArrayList<>(filters.size());
        for (IFilter filter : filters) {
            if (filter.getEnabled()) {
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
        Logger.printInfo(() ->
            "[Morphe TikTok FeedFilter] filter(" + source + "): size=" + size
                + " " + metadata
                + " remove_ads=" + Settings.REMOVE_ADS.get()
                + " hide_shop=" + Settings.HIDE_SHOP.get()
                + " hide_live=" + Settings.HIDE_LIVE.get()
                + " hide_story=" + Settings.HIDE_STORY.get()
                + " hide_image=" + Settings.HIDE_IMAGE.get()
                + " min_max_views=\"" + Settings.MIN_MAX_VIEWS.get() + "\""
                + " min_max_likes=\"" + Settings.MIN_MAX_LIKES.get() + "\""
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

    private static boolean shouldSkipRecentlyProcessedList(
        int listId,
        ListFingerprint fingerprint,
        String filterMask,
        int callId,
        String source,
        boolean probeEnabled
    ) {
        ProcessedListState state;
        long now = System.currentTimeMillis();
        String missReason = null;
        int skipCount = 0;

        synchronized (processedListCache) {
            state = processedListCache.get(listId);
            if (state == null) {
                missReason = "newList";
            } else if (!state.filterMask.equals(filterMask)) {
                missReason = "filterMask";
            } else if (state.fingerprint.size != fingerprint.size) {
                missReason = "size";
            } else if (!state.fingerprint.matches(fingerprint)) {
                missReason = "sample";
            } else if (now - state.processedAtMs > PROCESSED_LIST_CACHE_TTL_MS) {
                missReason = "expired";
            } else {
                state.skipCount++;
                skipCount = state.skipCount;
            }
        }

        if (missReason != null) {
            if (probeEnabled) {
                recordProbeCacheMiss(missReason);
            }
            return false;
        }

        if (probeEnabled) {
            recordProbeCacheHit(listId);
        }

        if (probeEnabled && shouldLogCacheSkip(skipCount)) {
            int skipCountFinal = skipCount;
            Logger.printInfo(() -> "[Morphe TikTok FeedFilterProbe]"
                + " call=" + callId
                + " source=" + source
                + " list=" + listId
                + " cacheHit=true"
                + " skipCount=" + skipCountFinal
                + " size=" + fingerprint.size
                + " filters=\"" + filterMask + "\""
                + " sample=\"" + fingerprint.toSampleString() + "\"");
        }

        return true;
    }

    private static boolean shouldLogCacheSkip(int skipCount) {
        return skipCount <= 20 || skipCount % 100 == 0;
    }

    private static void rememberProcessedList(int listId, ListFingerprint fingerprint, String filterMask) {
        synchronized (processedListCache) {
            if (processedListCache.size() > PROCESSED_LIST_CACHE_MAX_SEEN_LISTS) {
                processedListCache.clear();
            }

            processedListCache.put(listId, new ProcessedListState(fingerprint, filterMask, System.currentTimeMillis()));
        }
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

    private static final class ProcessedListState {
        final ListFingerprint fingerprint;
        final String filterMask;
        final long processedAtMs;
        int skipCount;

        ProcessedListState(ListFingerprint fingerprint, String filterMask, long processedAtMs) {
            this.fingerprint = fingerprint;
            this.filterMask = filterMask;
            this.processedAtMs = processedAtMs;
        }

        boolean matches(ListFingerprint currentFingerprint, String currentFilterMask, long nowMs) {
            return nowMs - processedAtMs <= PROCESSED_LIST_CACHE_TTL_MS
                && filterMask.equals(currentFilterMask)
                && fingerprint.matches(currentFingerprint);
        }
    }

    private static final class ListFingerprint {
        final int size;
        final int firstIdentity;
        final int middleIdentity;
        final int lastIdentity;
        final String firstAid;
        final String middleAid;
        final String lastAid;

        private ListFingerprint(
            int size,
            int firstIdentity,
            int middleIdentity,
            int lastIdentity,
            String firstAid,
            String middleAid,
            String lastAid
        ) {
            this.size = size;
            this.firstIdentity = firstIdentity;
            this.middleIdentity = middleIdentity;
            this.lastIdentity = lastIdentity;
            this.firstAid = firstAid;
            this.middleAid = middleAid;
            this.lastAid = lastAid;
        }

        static ListFingerprint from(List list, AwemeExtractor extractor) {
            int size = list.size();
            if (size == 0) {
                return new ListFingerprint(0, 0, 0, 0, "", "", "");
            }

            int middleIndex = size / 2;
            int lastIndex = size - 1;
            Aweme first = extractAt(list, extractor, 0);
            Aweme middle = extractAt(list, extractor, middleIndex);
            Aweme last = extractAt(list, extractor, lastIndex);

            return new ListFingerprint(
                size,
                identity(first),
                identity(middle),
                identity(last),
                aid(first),
                aid(middle),
                aid(last)
            );
        }

        private static Aweme extractAt(List list, AwemeExtractor extractor, int index) {
            try {
                return extractor.extract(list.get(index));
            } catch (RuntimeException ex) {
                return null;
            }
        }

        boolean matches(ListFingerprint other) {
            return size == other.size
                && firstIdentity == other.firstIdentity
                && middleIdentity == other.middleIdentity
                && lastIdentity == other.lastIdentity
                && firstAid.equals(other.firstAid)
                && middleAid.equals(other.middleAid)
                && lastAid.equals(other.lastAid);
        }

        String toSampleString() {
            return firstAid + "|" + middleAid + "|" + lastAid;
        }

        private static int identity(Aweme item) {
            return item == null ? 0 : System.identityHashCode(item);
        }

        private static String aid(Aweme item) {
            if (item == null) return "";
            String aid = item.getAid();
            return aid == null ? "" : aid;
        }
    }
}


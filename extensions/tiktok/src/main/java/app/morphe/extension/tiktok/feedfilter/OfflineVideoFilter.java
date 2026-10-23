/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeBizExtKt;

/**
 * Takes out the videos TikTok saved on the phone for offline viewing, which it slips back into
 * For You when the feed can't load enough new ones. They carry TikTok's offline cache source.
 * The whole offline fallback list goes too, in {@link FeedItemsFilter#filterOfflineFeedList}.
 */
public class OfflineVideoFilter implements IFilter {
    @Override
    public boolean getEnabled() {
        return Settings.HIDE_OFFLINE_VIDEOS.get();
    }

    @Override
    public boolean getFiltered(Aweme item) {
        return AwemeBizExtKt.getCacheSourceType(item) == FeedItemsFilter.CACHE_SOURCE_OFFLINE_MODE;
    }
}

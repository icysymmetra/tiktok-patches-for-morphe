/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;

/**
 * Drops videos the local watch history has already recorded. It rides on the same filter
 * list as every other feed rule, so it runs on each path the feed filter already covers
 * rather than needing its own pass.
 */
public final class SeenVideoFilter implements IFilter {
    @Override
    public boolean getEnabled() {
        return Settings.HIDE_SEEN_VIDEOS.get();
    }

    @Override
    public boolean getFiltered(Aweme item) {
        return SeenVideoHistory.shouldHide(item.getAid());
    }
}

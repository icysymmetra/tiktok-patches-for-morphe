/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.tiktok.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;

/** Favourite count range, read off the same statistics model as the views and likes ranges. */
public final class FavouriteCountFilter implements IFilter {
    final long min;
    final long max;

    FavouriteCountFilter() {
        long[] minMax = Utils.parseMinMax(Settings.MIN_MAX_FAVOURITES);
        min = minMax[0];
        max = minMax[1];
    }

    @Override
    public boolean getEnabled() {
        return min != 0L || max != Long.MAX_VALUE;
    }

    @Override
    public boolean getFiltered(Aweme item) {
        AwemeStatistics statistics = item.getStatistics();
        if (statistics == null) return false;

        long count = statistics.getCollectCount();
        return count < min || count > max;
    }
}

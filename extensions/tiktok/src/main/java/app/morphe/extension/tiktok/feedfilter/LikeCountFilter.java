/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/feedfilter/LikeCountFilter.java
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.tiktok.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;

public final class LikeCountFilter implements IFilter {
    final long minLike;
    final long maxLike;

    LikeCountFilter() {
        long[] minMax = Utils.parseMinMax(Settings.MIN_MAX_LIKES);
        minLike = minMax[0];
        maxLike = minMax[1];
    }

    @Override
    public boolean getEnabled() {
        return minLike != 0L || maxLike != Long.MAX_VALUE;
    }

    @Override
    public boolean getFiltered(Aweme item) {
        AwemeStatistics statistics = item.getStatistics();
        if (statistics == null) return false;

        long likeCount = statistics.getDiggCount();
        return likeCount < minLike || likeCount > maxLike;
    }
}


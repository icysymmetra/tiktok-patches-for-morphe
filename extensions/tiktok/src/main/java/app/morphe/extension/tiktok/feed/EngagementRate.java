/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Show engagement rate: likes, comments, shares and saves together, as a share of the views,
 * worked out from the counts TikTok already sent with each video. Nothing is fetched.
 *
 * <p>The profile grid asks here, through {@link ProfileGridCount}, after it formats a cell's view
 * count, and the rate goes after it ("12.3K · 4.2%"). The video on screen gets it on the creator's row, through
 * {@link AuthorRegion}. A video with no views, or no counts at all, shows nothing, since any
 * number there would be made up.
 */
public final class EngagementRate {
    /** The middle dot the grid count and the creator's row already use. */
    static final String SEPARATOR = " · ";

    /** The author row runs on every layout pass, so its rate is kept until the counts change. */
    private static WeakReference<Object> videoItem = new WeakReference<>(null);
    private static long videoViews = -1;
    private static long videoEngaged = -1;
    private static Locale videoLocale;
    private static String videoRate;

    private EngagementRate() {
    }

    /**
     * Called through {@link ProfileGridCount} right after TikTok formats a cell's view count. Returns
     * the text the cell shows: TikTok's own count, with the rate after it while the switch is on.
     */
    public static String gridCount(String count, Object item) {
        if (count == null) {
            return null;
        }
        try {
            String rate = enabled() ? rateOf(item) : null;
            return rate == null ? count : count + SEPARATOR + rate;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not add the engagement rate to a grid cell", ex);
            return count;
        }
    }

    /** The rate for the video on screen, or null when the switch is off or there's nothing to show. */
    static String forVideo(Object item) {
        if (!enabled()) {
            return null;
        }
        try {
            AwemeStatistics statistics = statisticsOf(item);
            if (statistics == null) {
                return null;
            }
            long views = statistics.getPlayCount();
            long engaged = engaged(statistics);
            Locale locale = Locale.getDefault();
            if (item != videoItem.get() || views != videoViews || engaged != videoEngaged || !locale.equals(videoLocale)) {
                videoItem = new WeakReference<>(item);
                videoViews = views;
                videoEngaged = engaged;
                videoLocale = locale;
                videoRate = format(views, engaged);
            }
            return videoRate;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not work out the engagement rate", ex);
            return null;
        }
    }

    private static boolean enabled() {
        return SettingsStatus.engagementRateEnabled && Settings.SHOW_ENGAGEMENT_RATE.get();
    }

    private static String rateOf(Object item) {
        AwemeStatistics statistics = statisticsOf(item);
        return statistics == null ? null : format(statistics.getPlayCount(), engaged(statistics));
    }

    private static AwemeStatistics statisticsOf(Object item) {
        return item instanceof Aweme ? ((Aweme) item).getStatistics() : null;
    }

    /** Likes, comments, shares and saves. A negative count is TikTok's way of saying it has none. */
    private static long engaged(AwemeStatistics statistics) {
        return Math.max(0, statistics.getDiggCount()) + Math.max(0, statistics.getCommentCount())
                + Math.max(0, statistics.getShareCount()) + Math.max(0, statistics.getCollectCount());
    }

    /**
     * The rate as the phone's locale writes a percentage, to one decimal: 4.2% in English,
     * 4,2 % in German, %4,2 in Turkish. Null without views.
     */
    static String format(long views, long engaged) {
        if (views <= 0 || engaged < 0) {
            return null;
        }
        // A new instance per call: NumberFormat isn't thread safe.
        NumberFormat percent = NumberFormat.getPercentInstance();
        percent.setMinimumFractionDigits(1);
        percent.setMaximumFractionDigits(1);
        return percent.format((double) engaged / views);
    }
}

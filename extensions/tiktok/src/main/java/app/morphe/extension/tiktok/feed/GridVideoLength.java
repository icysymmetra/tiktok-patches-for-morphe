/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.feedfilter.ImageVideoFilter;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import com.ss.android.ugc.aweme.feed.model.Aweme;

/**
 * How long a profile grid cell's video runs ("0:42", "1:02:33"), shown under its view count while
 * Show video length on profile grids is on (#181). It is the length TikTok sent with the video,
 * so a photo post, whose length is its sound's, and a video TikTok sent no length for get none.
 */
public final class GridVideoLength {
    private GridVideoLength() {
    }

    /** The cell's length, or null when the switch is off or there's no real length to show. */
    public static String gridLength(Object item) {
        if (!SettingsStatus.alwaysShowPublishDateEnabled || !Settings.VIDEO_LENGTH_ON_GRID.get()) {
            return null;
        }
        try {
            long ms = lengthMs(item);
            return ms > 0 ? format(ms) : null;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not write a grid cell's length", ex);
            return null;
        }
    }

    static long lengthMs(Object item) {
        if (!(item instanceof Aweme) || ImageVideoFilter.isPhotoPost((Aweme) item)) {
            return 0;
        }
        Object video = Reflect.property(item, "getVideo", "video");
        Object length = video == null ? null : Reflect.property(video, "getDuration", "videoLength");
        return length instanceof Number ? Math.max(0, ((Number) length).longValue()) : 0;
    }

    /** Whole seconds, rounded the way a player's clock shows them: "0:42", "12:05", "1:02:33". */
    static String format(long ms) {
        long seconds = Math.max(1, (ms + 500) / 1000);
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long rest = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }
}

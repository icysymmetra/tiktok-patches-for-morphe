/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.offline;

import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * How long TikTok keeps a video in its offline list (#123).
 *
 * <p>TikTok works out one lifetime for every offline video: a low-storage figure in hours when the
 * phone is short on room, else a server figure in hours, else 48 hours when the offline page was
 * opened in the last two days and 90 days when it wasn't. Every query over the offline table
 * leaves out a row once {@code insert_time + lifetime} has passed, and the next start deletes
 * those rows and their files together. A list saved in one go therefore disappears in one go,
 * watched or not. The patch asks {@link #keepOfflineVideos()} in front of that calculation and,
 * when it says yes, hands TikTok {@link #keptLifetimeMs()} instead.
 *
 * <p>TikTok caches the answer per account for the life of the process, so the switch applies on
 * the next start.
 */
@SuppressWarnings("unused")
public final class OfflineVideoExpiry {
    /**
     * Ten years, which outlasts any phone, but stays well short of the epoch. TikTok also calls its
     * list stale when {@code now - lastRefresh} exceeds the lifetime, and a list that was never
     * filled has a last refresh of 0, so a lifetime longer than the time since 1970 would make an
     * empty list look fresh and keep TikTok from filling it. It also leaves the SQL sum
     * {@code insert_time + lifetime} far from overflowing a long.
     */
    public static final long KEPT_LIFETIME_MS = TimeUnit.DAYS.toMillis(3650);

    private OfflineVideoExpiry() {
    }

    /** Whether TikTok's own lifetime is replaced. Asked once per account per process. */
    public static boolean keepOfflineVideos() {
        boolean keep = Settings.KEEP_OFFLINE_VIDEOS.get();
        HookStatus.bound("offline video expiry", keep ? "kept until cleared" : "TikTok's lifetime");
        return keep;
    }

    /** The lifetime TikTok gets instead of its own while the switch is on. */
    public static long keptLifetimeMs() {
        return KEPT_LIFETIME_MS;
    }

    /**
     * Whether TikTok's Auto adjust sits out this start. At every start it can clamp the offline
     * limit to its server range, move the tier up or down, or fall back to a legacy tier, and a
     * lower tier makes the next step trim the list down to it. With the switch on the limit stays
     * where the user put it, so those steps don't run.
     */
    public static boolean keepThroughAutoAdjust() {
        boolean keep = Settings.KEEP_OFFLINE_VIDEOS.get();
        HookStatus.bound("offline auto adjust", keep ? "limit left alone" : "TikTok's own");
        return keep;
    }

    /**
     * Whether TikTok's clean-up of the list it started by default may run. When the experiment
     * that turned offline mode on ends, TikTok clears everything it saved. {@code cleanup} is its answer
     * and the switch turns it into "nothing to clean up".
     */
    public static boolean keepThroughDefaultEnableCleanup(boolean cleanup) {
        if (!cleanup) return false;
        boolean keep = Settings.KEEP_OFFLINE_VIDEOS.get();
        HookStatus.bound("offline default cleanup", keep ? "skipped" : "TikTok's own");
        return !keep;
    }
}

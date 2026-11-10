/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Keeps the For You feed where it is when TikTok would reload it: a tap on Home or on the For You
 * tab while For You is showing, or a pull down at the top of the feed. The Following, Friends and
 * Nearby feeds are left alone.
 *
 * <p>A tap reaches the For You fragment's refresh from the home pager with TikTok's trigger
 * (CLICK_BOTTOM or CLICK_TOP). TikTok's own reloads, like the one after you block the creator on
 * screen, come through the fragment's wrapper with the same trigger, and the patch marks those, so
 * they go on. A pull is answered earlier, at the refresh panel's pull listener: past the fragment's
 * refresh, that listener tells the feed and the fragment's other refresh listeners, and those load
 * the feed on their own. Either way a kept refresh ends the way TikTok ends one that arrives while
 * the feed is still loading, which the patch writes: the spinner stops and TikTok's refresh-end
 * event goes out, which turns the Home icon back from its refresh arrow.
 */
public final class FeedRefresh {
    static final String FAMILY = "for you refresh";
    private static final String FOR_YOU_FRAGMENT = "com.ss.android.ugc.aweme.feed.ui.FeedRecommendFragment";

    /** Set right before the fragment's own wrapper asks for a refresh, and spent by that ask. */
    private static boolean throughWrapper;

    private FeedRefresh() {
    }

    /**
     * Said by the patch right before the For You fragment's refresh wrapper calls the refresh. The
     * wrapper is how TikTok reloads the feed for its own reasons (after you block the creator on
     * screen, from its observers). A tap on Home or the For You tab comes from the home pager and a
     * pull from the refresh panel's listener, both straight to the refresh, so neither is marked.
     */
    public static void refreshFromWrapper() {
        throughWrapper = true;
    }

    /** Whether the For You fragment may go on with the refresh {@code trigger} asks for. */
    public static boolean allowRefresh(Object trigger) {
        HookStatus.bound(FAMILY, "refresh");
        boolean own = throughWrapper;
        throughWrapper = false;
        if (own) {
            HookStatus.bound(FAMILY, "TikTok's own reload");
            return true;
        }
        String name = trigger instanceof Enum ? ((Enum<?>) trigger).name() : null;
        if (("CLICK_BOTTOM".equals(name) || "CLICK_TOP".equals(name)) && Settings.KEEP_FOR_YOU_ON_TAB_TAP.get()) {
            HookStatus.bound(FAMILY, "tab tap kept");
            return false;
        }
        // The home pager hands on anything that isn't a tab tap with the pull trigger. That is the
        // Previous action TikTok's accessibility tools offer at the top of For You, a pull by
        // other means; a real pull the switch keeps never gets this far.
        if ("PULL_DOWN_REFRESH".equals(name) && Settings.KEEP_FOR_YOU_ON_PULL_DOWN.get()) {
            HookStatus.bound(FAMILY, "previous at the top kept");
            return false;
        }
        return true;
    }

    static void resetForTests() {
        throughWrapper = false;
    }

    /** Whether a pull down on {@code fragment}'s feed ends before it reloads anything. */
    public static boolean keepPull(Object fragment) {
        HookStatus.bound(FAMILY, "pull");
        if (!Settings.KEEP_FOR_YOU_ON_PULL_DOWN.get() || !isForYou(fragment)) return false;
        HookStatus.bound(FAMILY, "pull down kept");
        return true;
    }

    private static boolean isForYou(Object fragment) {
        for (Class<?> type = fragment == null ? null : fragment.getClass(); type != null; type = type.getSuperclass()) {
            if (FOR_YOU_FRAGMENT.equals(type.getName())) return true;
        }
        return false;
    }
}

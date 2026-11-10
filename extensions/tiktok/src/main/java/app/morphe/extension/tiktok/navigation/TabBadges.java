/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.view.View;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The unread badges on TikTok's bottom tabs: the red count on Inbox and the dot on Profile, and
 * the bubble over Inbox that counts new likes, followers, comments and who's LIVE (#170).
 *
 * <p>The count and the dot are drawn by the tab icon view, and every show or hide of either goes
 * through its own setter (the count through {@code setCountDotVisibility}, the dot through
 * {@code setTabDotVisibility}); the callers that fetch the views themselves animate them and
 * never write a visibility. So the answer is taken in the setter: hidden, it is GONE whatever
 * TikTok asked for, and a repeated native update cannot bring it back.
 *
 * <p>The bubble is no view of the tab icon. TikTok floats it up when it opens and again whenever
 * a count comes in, and every show starts in one method of its manager, which returns before
 * anything is built while the badges are hidden.
 */
public final class TabBadges {
    static final String FAMILY = "tab badges";

    private TabBadges() { }

    public static int countDotVisibility(int visibility) {
        HookStatus.bound(FAMILY, "count badge");
        return hide(visibility);
    }

    public static int tabDotVisibility(int visibility) {
        HookStatus.bound(FAMILY, "dot badge");
        return hide(visibility);
    }

    /** Whether the bubble over Inbox stays down this time TikTok would show it. */
    public static boolean hideInboxBubble() {
        HookStatus.bound(FAMILY, "inbox bubble");
        return Settings.HIDE_TAB_BADGES.get();
    }

    private static int hide(int visibility) {
        return Settings.HIDE_TAB_BADGES.get() ? View.GONE : visibility;
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows hxreborn/hxreborn-tiktok-patches (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Answers TikTok's own widget injectors so an Inbox section is never built, rather than
 * hidden once it is on screen the way {@link InboxFilter} does. Both read the same
 * settings, so a switch works with either patch applied, or both.
 */
public final class InboxControls {
    private InboxControls() {
    }

    public static boolean shouldShowSuggestedAccounts() {
        return !Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.get();
    }

    /** Answers the sticker suggestion banner's enable check in a chat. */
    public static boolean shouldShowChatStickerBanner() {
        return !Settings.HIDE_CHAT_STICKER_BANNER.get();
    }

    /** Answers the enable check of TikTok's suggested reply cells and their intro banner. */
    public static boolean shouldShowChatAiReplies() {
        return !Settings.HIDE_CHAT_AI_REPLIES.get();
    }

    /**
     * Whether the Inbox's invitation to start a group chat is withheld. Asked at the top of the
     * banner's update, whose early return is the state it starts in, with nothing to show.
     */
    public static boolean shouldHideGroupChatBanner() {
        boolean hide = Settings.HIDE_INBOX_GROUP_CHAT_BANNER.get();
        HookStatus.bound("group chat banner", hide ? "update skipped" : "update left");
        return hide;
    }

    public static boolean shouldCollapseActivityList(boolean original) {
        return original && !Settings.EXPAND_ACTIVITY_LIST.get();
    }
}

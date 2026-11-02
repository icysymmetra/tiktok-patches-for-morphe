/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Two things TikTok gives no switch for, the notification saying somebody followed you and
 * message streaks, and a switch for push notifications as a whole.
 *
 * <p>A push carries the notification channel it belongs on, and TikTok's own PushService
 * keeps the list of them: {@code im_push}, {@code digg_push}, {@code comment_push},
 * {@code follow_push} and the rest. So a follower notification is the one on
 * {@code follow_push}, and dropping it at the point the app would hand it to Android leaves
 * everything else alone. The push message model kept its field names.
 *
 * <p>The third switch, Turn off push notifications, lives in {@link PushShutoff}.
 */
public final class NotificationControls {
    /** The channel a new follower notification goes out on, from PushService's own list. */
    private static final String FOLLOWER_CHANNEL = "follow_push";

    private NotificationControls() {}

    /**
     * True when this push should never reach the notification drawer: every one while Turn off
     * push notifications is on, which spares the handler building it, and otherwise a follower one
     * when that switch asks.
     */
    public static boolean shouldDropPush(Object message) {
        if (message == null) return false;
        if (PushShutoff.isOn()) return true;
        if (!Settings.HIDE_FOLLOWER_NOTIFICATIONS.get()) return false;
        Object extra = Reflect.property(message, "getExtra", "extra");
        String channel = Reflect.string(extra, "getNotificationChannelId", "notificationChannelId");
        return FOLLOWER_CHANNEL.equals(channel);
    }

    /** True when the streak button and its reminder should not be offered. */
    public static boolean hideMessageStreaks() {
        return Settings.HIDE_MESSAGE_STREAKS.get();
    }
}

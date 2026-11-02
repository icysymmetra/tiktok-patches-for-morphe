/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import androidx.annotation.Nullable;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * The gestures on a message in a chat. TikTok hands every tap, double tap, long press and
 * sideways swipe on a message cell to one dispatcher in its skeleton layout, with the gesture
 * as an enum that keeps its real constant names on every declared build. A double tap there
 * adds a heart reaction and a swipe starts a reply; each can be turned off on its own, and
 * every other gesture goes through.
 */
public final class ChatGestures {
    static final String DOUBLE_TAP = "DOUBLE_TAP";
    static final String SWIPE = "SWIPE";

    private ChatGestures() {
    }

    /** Whether the dispatcher should return before acting on {@code gesture}. */
    public static boolean skip(@Nullable Enum<?> gesture) {
        if (gesture == null) return false;
        switch (gesture.name()) {
            case DOUBLE_TAP:
                return Settings.TURN_OFF_CHAT_DOUBLE_TAP.get();
            case SWIPE:
                return Settings.TURN_OFF_CHAT_SWIPE_REPLY.get();
            default:
                return false;
        }
    }
}

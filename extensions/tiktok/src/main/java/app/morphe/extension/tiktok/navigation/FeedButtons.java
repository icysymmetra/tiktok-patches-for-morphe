/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Shows TikTok's own feed buttons (previous, pause and play, next) without a screen reader.
 *
 * <p>TikTok builds a row of those buttons into every feed page, the one it offers people who use
 * TalkBack, and shows it only while an accessibility service with touch exploration is running
 * and its own switch for the row is on. The patch asks here before that check, so the row can
 * come up for anyone who would rather tap than swipe.
 *
 * <p>The row's layout makes each button focusable in touch mode. A screen reader presses a button
 * directly and never notices, but a finger's first tap on a button that doesn't hold focus only
 * moves focus to it (seen on the S22 with 47.0.3: next, then previous, took two taps). Each button
 * passes through here as the row updates, and loses that while the switch is on.
 */
public final class FeedButtons {
    static final String FAMILY = "feed buttons";

    private FeedButtons() {
    }

    /** Whether the feed shows TikTok's button row whatever the screen reader check would say. */
    public static boolean showWithoutScreenReader() {
        HookStatus.bound(FAMILY, "gate");
        if (!Settings.SHOW_FEED_BUTTONS.get()) return false;
        HookStatus.bound(FAMILY, "shown");
        return true;
    }

    /** One button of the row as TikTok sets it for the video on screen. */
    public static void buttonShown(View button) {
        try {
            HookStatus.bound(FAMILY, "button");
            if (button == null || !Settings.SHOW_FEED_BUTTONS.get() || !button.isFocusableInTouchMode()) return;
            button.setFocusableInTouchMode(false);
            HookStatus.bound(FAMILY, "first tap presses");
        } catch (Throwable failure) {
            Logger.printException(() -> "Feed button focus left as TikTok set it", failure);
        }
    }
}

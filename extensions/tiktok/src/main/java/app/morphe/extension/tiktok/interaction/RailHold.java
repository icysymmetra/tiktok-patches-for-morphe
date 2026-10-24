/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewConfiguration;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * A long press on the Comment, Share or Favorites button plays the video at the hold speed while
 * it's held (upstream #87), the way a long press did before TikTok gave those buttons menus of
 * their own.
 *
 * <p>TikTok's hold gesture sits on the feed cell, sees every touch and speeds a video up when a
 * press lands near the left or right edge of the screen, where the side buttons are. As the press
 * lands it asks the buttons whether the press is on one of them, and a press on one is not a
 * hold. So the patch answers "not on it" for a chosen button, and TikTok's own hold runs there:
 * the hold speed, its banner, the pull-down lock and the release. The button's own long press,
 * the emoji row on Comment, the quick share row on Share and the new collection offer on
 * Favorites, is skipped for that press, so a hold doesn't open a menu too, and letting go doesn't
 * count as a tap. Comment's and Favorites' are skipped in the method that opens the menu, and
 * Share's by wrapping the listeners TikTok sets.
 *
 * <p>Only a press the hold check let through skips the menu, and only while TikTok still means to
 * hold it. The check turns a press down before it asks the buttons when Hushfeed has the edge for
 * a seek or the long press for an action of its own, and when TikTok won't speed up at all. TikTok
 * drops a hold it hasn't started yet when the finger moves off the spot or a second finger lands,
 * and ends every press on release, and each of those forgets the pass. The menu then opens as it
 * always did, as long as the button's own timer hasn't fired yet. Favorites' fires at 200 ms,
 * before the hold starts at 300 ms, so a release between the two gets neither.
 */
public final class RailHold {
    static final String FAMILY = "rail hold";

    /**
     * How long past the long-press timeout a long press still belongs to the press the hold check
     * let through. The check runs as the press lands and the long press comes a timeout later.
     */
    static final long GRACE_MS = 1_000;

    /** A side button a hold can be chosen for, and when the check last let a press on it through. */
    enum Button {
        COMMENT("comment", Settings.RAIL_HOLD_COMMENT),
        SHARE("share", Settings.RAIL_HOLD_SHARE),
        FAVORITES("favorites", Settings.RAIL_HOLD_FAVORITES);

        final String label;
        final BooleanSetting setting;
        /** In uptime, or -1. Touched on the main thread only, where TikTok's touches run. */
        long heldAt = -1;

        Button(String label, BooleanSetting setting) {
            this.label = label;
            this.setting = setting;
        }

        /** TikTok's hold check asks whether a press is on this button, where a hold doesn't start. */
        boolean stopsHold(boolean onButton) {
            HookStatus.bound(FAMILY, label + " check");
            if (!onButton || !setting.get()) return onButton;
            HookStatus.bound(FAMILY, "held on " + label);
            heldAt = SystemClock.uptimeMillis();
            return false;
        }

        /** Whether the long press on this button now is one the hold check just let through. */
        boolean heldJustNow() {
            return heldAt >= 0 && setting.get()
                    && SystemClock.uptimeMillis() - heldAt <= ViewConfiguration.getLongPressTimeout() + GRACE_MS;
        }

        /** TikTok's long press on this button, about to open its menu. True skips the menu. */
        boolean skipsMenu() {
            HookStatus.bound(FAMILY, label + " long press");
            if (!heldJustNow()) return false;
            HookStatus.bound(FAMILY, label + " menu skipped");
            return true;
        }
    }

    private RailHold() {
    }

    public static boolean commentStopsHold(boolean onComment) {
        return Button.COMMENT.stopsHold(onComment);
    }

    public static boolean shareStopsHold(boolean onShare) {
        return Button.SHARE.stopsHold(onShare);
    }

    public static boolean favoritesStopsHold(boolean onFavorites) {
        return Button.FAVORITES.stopsHold(onFavorites);
    }

    /**
     * Comment's long press, about to open the emoji row. TikTok marks the press as a long one
     * before it gets here, so a true answer skips the row and letting go still isn't a tap.
     */
    public static boolean skipCommentMenu() {
        return Button.COMMENT.skipsMenu();
    }

    /**
     * Favorites' long press, about to offer a new collection. TikTok marks the press as a long one
     * before it gets here, so a true answer skips the offer and letting go doesn't save the video.
     */
    public static boolean skipFavoritesMenu() {
        return Button.FAVORITES.skipsMenu();
    }

    /** Where TikTok gives the Share button, or one of its children, its long press. */
    public static void setShareLongClick(View button, View.OnLongClickListener listener) {
        HookStatus.bound(FAMILY, "share listener");
        button.setOnLongClickListener(listener == null ? null : new ShareMenu(listener));
    }

    /**
     * TikTok's hold, dropping the hold it had set to start 300 ms after the press landed: the
     * finger moved off the spot, a second finger landed, or the press ended. Every pass goes with
     * it, since none of them belongs to a press TikTok will hold.
     */
    public static void holdDropped(Handler handler, Runnable pending) {
        handler.removeCallbacks(pending);
        HookStatus.bound(FAMILY, "hold dropped");
        forgetPasses();
    }

    private static void forgetPasses() {
        for (Button button : Button.values()) button.heldAt = -1;
    }

    static void resetForTests() {
        forgetPasses();
    }

    /**
     * Share's own long press, which opens its menu unless the press is being held at the hold
     * speed. Asked each time, so the switch applies to buttons already on screen. Taking the long
     * press is what keeps letting go from counting as a tap.
     */
    static final class ShareMenu implements View.OnLongClickListener {
        final View.OnLongClickListener tikTok;

        ShareMenu(View.OnLongClickListener tikTok) {
            this.tikTok = tikTok;
        }

        @Override public boolean onLongClick(View view) {
            return Button.SHARE.skipsMenu() || tikTok.onLongClick(view);
        }
    }
}

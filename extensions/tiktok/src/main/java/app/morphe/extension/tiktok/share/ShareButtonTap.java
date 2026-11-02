/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.interaction.GestureActions;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Copy the link with one press on Share: a press on the Share button under a video copies that
 * video's link, cleaned the way a shared link is, instead of opening the share sheet. A second
 * press on the same video right after opens the sheet, so it stays one press away.
 *
 * <p>The video is the one TikTok's own click builds its sheet from, the item bound to that cell,
 * not whichever video plays: a press lands on one cell's button, and right after a swipe the
 * player can still name the video before.
 */
public final class ShareButtonTap {
    /** How long a second press on the same video opens the sheet instead of copying again. */
    static final long AGAIN_MS = 3000;

    @Nullable private static String lastCopied;
    private static long lastCopiedAt;

    private ShareButtonTap() {
    }

    private static boolean wanted() {
        return SettingsStatus.shareSheetEnabled && Settings.SHARE_TAP_COPIES_LINK.get();
    }

    /**
     * Called first thing in TikTok's Share button click, with the cell's bound item (TikTok's
     * VideoItemParams, or null before one is bound). True when the press was Hushfeed's and TikTok's
     * sheet stays shut.
     */
    public static boolean onTap(View button, @Nullable Object item) {
        try {
            if (!wanted()) return false;
            bind(button);
            Object aweme = Reflect.property(item, "getAweme", "aweme");
            // Nothing bound yet, or a cell that isn't a post: TikTok knows what its button does.
            if (aweme == null) return false;
            String id = Reflect.string(aweme, "getAid", "aid");
            // Without an id a second press couldn't be told from a first, and the sheet would
            // never open, so such a post keeps TikTok's press.
            if (id == null) return false;
            long now = SystemClock.uptimeMillis();
            if (id.equals(lastCopied) && now - lastCopiedAt < AGAIN_MS) {
                lastCopied = null;
                return false;
            }
            lastCopied = id;
            lastCopiedAt = now;
            GestureActions.copyVideoLink(aweme);
            return true;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not copy the link from the Share button", error);
            return false;
        }
    }

    /**
     * Called for each view TikTok makes the Share button's click listener of, so a screen reader
     * says the press copies the link while the switch is on. Android 10 and newer only: an older
     * one can't hand back a delegate TikTok set, and replacing it would drop what TikTok reads out.
     */
    public static void bind(@Nullable View button) {
        try {
            if (button == null || Build.VERSION.SDK_INT < 29) return;
            View.AccessibilityDelegate current = button.getAccessibilityDelegate();
            if (current instanceof CopyLabel) return;
            button.setAccessibilityDelegate(new CopyLabel(current));
        } catch (Throwable error) {
            Logger.printException(() -> "Could not label the Share button", error);
        }
    }

    /** Hands everything to TikTok's delegate, or the platform's, and renames the click. */
    @RequiresApi(29)
    static final class CopyLabel extends View.AccessibilityDelegate {
        private final View.AccessibilityDelegate base;

        CopyLabel(@Nullable View.AccessibilityDelegate tikTok) {
            base = tikTok != null ? tikTok : new View.AccessibilityDelegate();
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            base.onInitializeAccessibilityNodeInfo(host, info);
            if (!wanted()) return;
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK, L10n.t("Copy the video link")));
        }

        @Override public void sendAccessibilityEvent(View host, int eventType) {
            base.sendAccessibilityEvent(host, eventType);
        }

        @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
            return base.performAccessibilityAction(host, action, args);
        }

        @Override public void sendAccessibilityEventUnchecked(View host, AccessibilityEvent event) {
            base.sendAccessibilityEventUnchecked(host, event);
        }

        @Override public boolean dispatchPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            return base.dispatchPopulateAccessibilityEvent(host, event);
        }

        @Override public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            base.onPopulateAccessibilityEvent(host, event);
        }

        @Override public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            base.onInitializeAccessibilityEvent(host, event);
        }

        @Override public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child, AccessibilityEvent event) {
            return base.onRequestSendAccessibilityEvent(host, child, event);
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
            return base.getAccessibilityNodeProvider(host);
        }

        @Override public void addExtraDataToAccessibilityNodeInfo(
                View host, AccessibilityNodeInfo info, String extraDataKey, Bundle arguments) {
            base.addExtraDataToAccessibilityNodeInfo(host, info, extraDataKey, arguments);
        }
    }

    static void resetForTests() {
        lastCopied = null;
        lastCopiedAt = 0;
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Follows kveld9's TikTok patch notes for the chat title bar.
 */
package app.morphe.extension.tiktok.inbox;

import android.view.View;
import android.view.ViewTreeObserver;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Hides the call buttons at the right of a chat's title bar.
 *
 * <p>TikTok shows and hides each button itself as the chat's state arrives, so setting one GONE
 * when the title bar is built would be undone a moment later. Each button is therefore kept
 * out of sight for as long as it is on screen. Only the two buttons the title bar stores are
 * touched. The chat details button next to them is never handed to this class.
 */
public final class ChatTitleBar {
    private static final Set<View> GUARDED = Collections.newSetFromMap(new WeakHashMap<>());

    private ChatTitleBar() {
    }

    /** Called with each call button as the title bar stores it. */
    public static void hideCallButton(View button) {
        if (button == null || !Settings.HIDE_CHAT_CALL_BUTTONS.get()) return;
        if (button.getVisibility() != View.GONE) button.setVisibility(View.GONE);
        synchronized (GUARDED) {
            if (!GUARDED.add(button)) return;
        }
        new Guard(button);
    }

    /** Whether the button may be drawn. Puts it back out of sight when TikTok showed it. */
    static boolean enforce(View button) {
        if (!Settings.HIDE_CHAT_CALL_BUTTONS.get() || button.getVisibility() == View.GONE) {
            return true;
        }
        button.setVisibility(View.GONE);
        return false;
    }

    /** Keeps one button gone while it is attached to a window. */
    private static final class Guard implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        private final View button;

        Guard(View button) {
            this.button = button;
            button.addOnAttachStateChangeListener(this);
            if (button.isAttachedToWindow()) onViewAttachedToWindow(button);
        }

        @Override
        public boolean onPreDraw() {
            return enforce(button);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            ViewTreeObserver observer = view.getViewTreeObserver();
            observer.removeOnPreDrawListener(this);
            observer.addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            ViewTreeObserver observer = view.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        }
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.view.View;
import android.view.ViewTreeObserver;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Hides the photo, @ and gift buttons in the comment box.
 *
 * <p>Each comment input stores its icon buttons as it sets up its views, and the patch hands each
 * of the three here at that moment. TikTok writes their visibility again from a dozen places (the
 * text typed, the account, the keyboard opening), so a hidden button is held at gone on every
 * layout pass instead of once. The emoji button and sending stay as they are.
 */
public final class CommentBoxButtons {
    static final String FAMILY = "comment box buttons";
    private static final WeakHashMap<View, Keeper> KEEPERS = new WeakHashMap<>();

    private CommentBoxButtons() {
    }

    public static void photo(View button) { hide(button, "photo"); }

    public static void mention(View button) { hide(button, "mention"); }

    public static void gift(View button) { hide(button, "gift"); }

    private static void hide(View button, String which) {
        if (button == null) return;
        HookStatus.bound(FAMILY, which);
        if (!Settings.HIDE_COMMENT_BOX_BUTTONS.get()) return;
        try {
            button.setVisibility(View.GONE);
            if (KEEPERS.containsKey(button)) return;
            Keeper keeper = new Keeper(button);
            KEEPERS.put(button, keeper);
            button.addOnAttachStateChangeListener(keeper);
            if (button.isAttachedToWindow()) keeper.onViewAttachedToWindow(button);
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not hide the comment box " + which + " button", error);
        }
    }

    /** Puts the button back to gone whenever a layout pass finds it showing. */
    private static final class Keeper implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<View> button;
        private ViewTreeObserver observer;

        Keeper(View button) {
            this.button = new WeakReference<>(button);
        }

        @Override public void onViewAttachedToWindow(View view) {
            if (observer != null) return;
            observer = view.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);
            onGlobalLayout();
        }

        @Override public void onViewDetachedFromWindow(View view) {
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            observer = null;
        }

        @Override public void onGlobalLayout() {
            View view = button.get();
            if (view == null || !Settings.HIDE_COMMENT_BOX_BUTTONS.get()) return;
            if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
        }
    }
}

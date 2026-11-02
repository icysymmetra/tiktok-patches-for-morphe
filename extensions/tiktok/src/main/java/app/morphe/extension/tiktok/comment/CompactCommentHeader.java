/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Only receives the three dedicated native header roots, never a sheet or comment row. */
public final class CompactCommentHeader {
    private static final WeakHashMap<View, WeakReference<Binding>> BINDINGS = new WeakHashMap<>();

    public static void bindCountBar(View view) { bind(view, "count bar"); }
    public static void bindLegacyBanner(View view) { bind(view, "legacy banner"); }
    public static void bindCommonBanner(View view) { bind(view, "common banner"); }

    private static void bind(View view, String component) {
        if (view == null) return;
        HookStatus.bound("compact comment header", component);
        if (!Settings.COMPACT_COMMENT_HEADER.get()) return;
        try {
            WeakReference<Binding> existing = BINDINGS.get(view);
            Binding binding = existing == null ? null : existing.get();
            if (binding == null) {
                binding = new Binding(view, "count bar".equals(component));
                BINDINGS.put(view, new WeakReference<>(binding));
                view.addOnAttachStateChangeListener(binding);
            }
            binding.watch();
            // The hook runs before native initialization. Apply after it has populated the root.
            view.post(binding);
        } catch (Exception error) {
            Logger.printException(() -> "Could not bind compact comment header", error);
        }
    }

    private static final class Binding implements Runnable, View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<View> root;
        final boolean keepListTabs;
        ViewTreeObserver observer;
        boolean hidden;
        int height, minimumHeight, visibility, accessibility, topMargin, bottomMargin;

        Binding(View view, boolean keepListTabs) {
            root = new WeakReference<>(view);
            this.keepListTabs = keepListTabs;
        }

        void watch() {
            View view = root.get();
            if (view == null || !view.isAttachedToWindow() || observer != null) return;
            observer = view.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);
        }

        void unwatch() {
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            observer = null;
        }

        @Override public void run() {
            View view = root.get();
            if (view == null) return;
            try {
                // Own-post viewer lists and other tabbed panels still need their native navigation.
                if (!Settings.COMPACT_COMMENT_HEADER.get() || (keepListTabs && hasListTabs(view, 3))) {
                    restore(view);
                    return;
                }
                ViewGroup.LayoutParams params = view.getLayoutParams();
                if (params == null) return;
                if (!hidden) {
                    height = params.height;
                    minimumHeight = view.getMinimumHeight();
                    visibility = view.getVisibility();
                    accessibility = view.getImportantForAccessibility();
                    if (params instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                        topMargin = margins.topMargin;
                        bottomMargin = margins.bottomMargin;
                    }
                    hidden = true;
                }
                boolean changed = params.height != 0;
                params.height = 0;
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    changed |= margins.topMargin != 0 || margins.bottomMargin != 0;
                    margins.topMargin = margins.bottomMargin = 0;
                }
                if (changed) view.setLayoutParams(params);
                if (view.getMinimumHeight() != 0) view.setMinimumHeight(0);
                if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
                if (view.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            } catch (Exception error) {
                unwatch();
                restore(view);
                Logger.printException(() -> "Could not collapse compact comment header", error);
            }
        }

        void restore(View view) {
            if (!hidden) return;
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                params.height = height;
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    margins.topMargin = topMargin;
                    margins.bottomMargin = bottomMargin;
                }
                view.setLayoutParams(params);
            }
            view.setMinimumHeight(minimumHeight);
            view.setVisibility(visibility);
            view.setImportantForAccessibility(accessibility);
            hidden = false;
        }

        @Override public void onGlobalLayout() { run(); }
        @Override public void onViewAttachedToWindow(View view) { watch(); view.post(this); }
        @Override public void onViewDetachedFromWindow(View view) {
            unwatch();
            view.removeCallbacks(this);
            restore(view);
        }

        private boolean hasListTabs(View view, int depth) {
            if (!(view instanceof ViewGroup) || depth == 0) return false;
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                if (child.getVisibility() != View.VISIBLE) continue;
                if (child instanceof HorizontalScrollView || hasListTabs(child, depth - 1)) return true;
            }
            return false;
        }
    }

    private CompactCommentHeader() { }
}

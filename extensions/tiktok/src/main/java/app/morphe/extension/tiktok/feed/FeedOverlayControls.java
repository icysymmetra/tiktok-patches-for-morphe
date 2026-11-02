/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceIdCache;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Receives dedicated native controls proven by the patcher, never arbitrary matching text. */
public final class FeedOverlayControls {
    private static final ResourceIdCache IDS = new ResourceIdCache();
    private static final WeakHashMap<View, WeakReference<Binding>> BINDINGS = new WeakHashMap<>();

    public static void bindFullscreen(View view) {
        VideoOverlayHider.noteFullscreenEntry(view);
        bind(view, Settings.HIDE_FULLSCREEN_BUTTON, false, "full screen entry");
    }

    public static void bindLocation(View view) {
        bind(view, Settings.HIDE_LOCATION_LABELS, true, "location card");
    }

    /**
     * Whether TikTok's regional Report button above the creator's avatar is withheld (issue
     * #21). Asked in front of TikTok's own gate, whose false is what every other region gets.
     */
    public static boolean shouldHideReportButton() {
        boolean hide = Settings.HIDE_FEED_REPORT_BUTTON.get();
        HookStatus.bound("feed report button", hide ? "gate closed" : "gate left");
        return hide;
    }

    /**
     * Whether TikTok's Footnotes banner is withheld. Asked in front of the check that decides
     * whether a video gets the banner at all, so a false there is what a video with no Footnote
     * already gets and the rest of the video is left as it was.
     */
    public static boolean shouldHideFootnotes() {
        boolean hide = Settings.HIDE_FOOTNOTES.get();
        HookStatus.bound("footnote banner", hide ? "gate closed" : "gate left");
        return hide;
    }

    private static void bind(View view, BooleanSetting setting, boolean location, String name) {
        if (view == null) return;
        HookStatus.bound(name, "native control bind");
        if (!setting.get()) return;
        try {
            WeakReference<Binding> ref = BINDINGS.get(view);
            Binding binding = ref == null ? null : ref.get();
            if (binding == null) {
                binding = new Binding(view, setting, location, name);
                BINDINGS.put(view, new WeakReference<>(binding));
                view.addOnAttachStateChangeListener(binding);
            }
            binding.watch();
            view.post(binding); // Native initialization finishes before the first mutation.
        } catch (Exception error) {
            Logger.printException(() -> "Could not bind an optional feed overlay", error);
        }
    }

    private static final class Binding implements Runnable, View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<View> root;
        final BooleanSetting setting;
        final boolean location;
        final String name;
        final Map<View, int[]> hidden = new WeakHashMap<>();
        ViewTreeObserver observer;

        Binding(View view, BooleanSetting setting, boolean location, String name) {
            root = new WeakReference<>(view);
            this.setting = setting;
            this.location = location;
            this.name = name;
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
                if (!setting.get() || !view.isAttachedToWindow() || !insideFeedCell(view)) {
                    restoreExcept(null, null);
                    return;
                }
                View wrapper = location ? exclusiveLocationWrapper(view) : null;
                // A recycled multi-tag host that acquires another child must be restored.
                restoreExcept(view, wrapper);
                hide(view);
                if (wrapper != null) hide(wrapper);
            } catch (Exception error) {
                unwatch();
                restoreExcept(null, null);
                Logger.printException(() -> "Could not hide an optional feed overlay", error);
            }
        }

        void hide(View view) {
            if (!hidden.containsKey(view)) {
                hidden.put(view, new int[]{view.getVisibility(), view.getImportantForAccessibility()});
                HookStatus.bound(name, "dedicated view hidden");
            }
            if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
            if (view.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        void restoreExcept(View keep, View wrapper) {
            var iterator = hidden.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                View view = entry.getKey();
                if (view == keep || view == wrapper) continue;
                view.setVisibility(entry.getValue()[0]);
                view.setImportantForAccessibility(entry.getValue()[1]);
                iterator.remove();
            }
        }

        @Override public void onGlobalLayout() { run(); }
        @Override public void onViewAttachedToWindow(View view) { watch(); view.post(this); }
        @Override public void onViewDetachedFromWindow(View view) {
            unwatch();
            view.removeCallbacks(this);
            restoreExcept(null, null);
        }
    }

    private static boolean insideFeedCell(View view) {
        int cell = IDS.resolve(view.getResources(), view.getContext().getPackageName(), "view_rootview", false);
        if (cell == 0) return false; // Unknown host layout keeps native behavior.
        for (int depth = 0; view != null && depth < 24; depth++) {
            if (view.getId() == cell) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    /** Collapse the shared host only if its entire child chain is this one location card. */
    private static View exclusiveLocationWrapper(View view) {
        int host = IDS.resolve(view.getResources(), view.getContext().getPackageName(), "feed_multi_tag_layout", false);
        if (host == 0) return null;
        for (int depth = 0; depth < 8 && view.getParent() instanceof ViewGroup; depth++) {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent.getChildCount() != 1) return null;
            if (parent.getId() == host) return parent;
            view = parent;
        }
        return null;
    }

    static void resolveForTests(String name, int id) {
        IDS.putForTests(Utils.getContext().getPackageName(), name, id);
    }
    private FeedOverlayControls() { }
}

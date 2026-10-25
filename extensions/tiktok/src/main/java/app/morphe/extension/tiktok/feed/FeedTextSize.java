/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.app.Application;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.morphe.extension.shared.GlobalLayoutHook;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.navigation.FrontWindow;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Native description and author sizes, independent of the spoken-caption renderer.
 *
 * <p>The author size also sizes the post date beside the name. TikTok's own row draws both in the
 * same font (tux font 42), so they share one size, and the row's owner holds both views.
 */
public final class FeedTextSize {
    public static final int MIN_TEXT_SIZE = 8;
    public static final int MAX_TEXT_SIZE = 48;

    /**
     * Native bind hooks supply only the owning author's name and date TextViews. No generic
     * title lookup.
     */
    private static final Map<TextView, AuthorSize> AUTHORS = new WeakHashMap<>();
    /** A controller is retained weakly. Its description View must not retain it through the value. */
    private static final Map<Object, DescriptionSize> DESCRIPTIONS = new WeakHashMap<>();
    private static final Map<Object, WeakReference<View>> BUILDERS = new WeakHashMap<>();
    private static final GlobalLayoutHook LAYOUT_HOOK = new GlobalLayoutHook();
    private static WeakReference<Activity> activityReference = new WeakReference<>(null);
    private static WeakReference<Application> followed = new WeakReference<>(null);
    /**
     * The feed window in front, main or detail. TikTok's launcher entry is a second MainActivity
     * under the feed's, and a theme change resumes it for a moment while the feed can stay in
     * front, so the layout hook goes back to the feed when that copy pauses.
     */
    private static final FrontWindow FRONT = new FrontWindow(true, new FrontWindow.Listener() {
        @Override public void onFront(Activity activity) {
            installNow(activity);
        }

        @Override public void onGone(Activity activity) {
        }
    });

    private static final class AuthorSize {
        float nativePx;
        float writtenPx = Float.NaN;
        float requestedPx;
        /** The post date beside the name: sized with it, never rebinds the row itself. */
        boolean date;
        WeakReference<Object> owner = new WeakReference<>(null);
        WeakReference<Object> item = new WeakReference<>(null);

        AuthorSize(float nativePx) { this.nativePx = nativePx; }
    }

    private static final class DescriptionSize {
        final WeakReference<View> view;
        float requestedPx;
        boolean rebuilding;

        DescriptionSize(View view, float requestedPx) {
            this.view = new WeakReference<>(view);
            this.requestedPx = requestedPx;
        }
    }

    private FeedTextSize() { }

    public static int clampSize(int value) {
        return value <= 0 ? 0 : Math.max(MIN_TEXT_SIZE, Math.min(MAX_TEXT_SIZE, value));
    }

    /** Called after each native description builder selects its font, before any line breaking. */
    public static void descriptionBuilder(Object owner, Object builder) {
        View view = descriptionViewOf(owner);
        if (view == null || builder == null) return;
        BUILDERS.put(builder, new WeakReference<>(view));
        resizeDescriptionBuilder(builder, view);
        DescriptionSize state = DESCRIPTIONS.get(owner);
        if (state == null || state.view.get() != view) {
            DESCRIPTIONS.put(owner, new DescriptionSize(view, descriptionPixels(view)));
        } else state.requestedPx = descriptionPixels(view);
    }

    /** Native fitting can change its input between builds. Reapply only to a marked builder. */
    public static void descriptionBuilding(Object builder) {
        WeakReference<View> reference = BUILDERS.get(builder);
        View view = reference == null ? null : reference.get();
        if (view != null) resizeDescriptionBuilder(builder, view);
    }

    /** A font-ID cache cannot describe arbitrary sizes, including the transition back to native. */
    public static boolean freshDescription(Object owner) {
        DescriptionSize state = DESCRIPTIONS.get(owner);
        View view = descriptionViewOf(owner);
        // A replaced View can reuse its native layout without a builder; track it for the next change.
        // The controller's cached layouts outlive its View, so the replacement inherits the size
        // they were built at. Seeding it from the setting would hide a change made while detached.
        if (view != null && (state == null || state.view.get() != view)) {
            state = new DescriptionSize(view, state == null ? descriptionPixels(view) : state.requestedPx);
            DESCRIPTIONS.put(owner, state);
        }
        return clampSize(Settings.FEED_DESCRIPTION_TEXT_SIZE.get()) != 0
                || state != null && state.rebuilding;
    }

    /**
     * The patch passes the builder's own Paint. Its native font, spans, line limits and ellipsis
     * remain the native builder's responsibility. True tells the bridge to bypass its font-ID cache.
     */
    public static boolean descriptionPaint(Paint paint, View view) {
        float wanted = descriptionPixels(view);
        if (paint == null || wanted == 0) return false;
        paint.setTextSize(wanted);
        return true;
    }

    private static float descriptionPixels(View view) {
        int size = clampSize(Settings.FEED_DESCRIPTION_TEXT_SIZE.get());
        return size == 0 || view == null ? 0 : pixels(view, size);
    }

    private static float pixels(View view, int size) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size,
                view.getResources().getDisplayMetrics());
    }

    public static void beforeAuthorOwnerBind(Object owner) { beforeAuthorBind(authorViewOf(owner)); }

    /** onViewCreated stored a new title. The old one must not keep the owner and its old item. */
    public static void authorOwnerBound(Object owner) {
        TextView view = authorViewOf(owner);
        if (view == null) return;
        TextView date = dateViewOf(owner);
        releaseReplacedAuthors(owner, view, date);
        authorBound(view);
        dateBound(owner, date);
    }

    /**
     * The native binder measures and may shorten a name before setText, so size comes first. It
     * measures the post date beside the name too, so the date is sized before the name.
     */
    public static void authorBinding(Object owner, Object item) {
        TextView view = authorViewOf(owner);
        if (view == null) return;
        TextView date = dateViewOf(owner);
        releaseReplacedAuthors(owner, view, date);
        if (date != null) {
            beforeAuthorBind(date);
            dateBound(owner, date);
        }
        beforeAuthorBind(view);
        authorBound(view);
        AuthorSize state = AUTHORS.get(view);
        state.owner = new WeakReference<>(owner);
        state.item = new WeakReference<>(item);
        state.requestedPx = authorPixels(view);
    }

    /** The post date follows the author size. Its text, spans and visibility stay TikTok's. */
    private static void dateBound(Object owner, TextView date) {
        if (date == null) return;
        authorBound(date);
        AuthorSize state = AUTHORS.get(date);
        state.date = true;
        state.owner = new WeakReference<>(owner);
        state.requestedPx = authorPixels(date);
    }

    /** Restore before native rebinding, even when its new size happens to equal our override. */
    public static void beforeAuthorBind(View view) {
        if (!(view instanceof TextView)) return;
        TextView text = (TextView) view;
        AuthorSize state = AUTHORS.get(text);
        if (state != null) restore(text, state);
    }

    /** Called after the owning native author bind. Text, spans and tap listeners stay untouched. */
    public static void authorBound(View view) {
        if (!(view instanceof TextView)) return;
        TextView text = (TextView) view;
        AuthorSize state = AUTHORS.get(text);
        if (state == null) {
            state = new AuthorSize(text.getTextSize());
            AUTHORS.put(text, state);
        }
        apply(text, state);
    }

    private static void apply(TextView text, AuthorSize state) {
        float current = text.getTextSize();
        // A native write between bind/layout callbacks becomes the next restoration baseline.
        if (Float.isNaN(state.writtenPx) || Float.compare(current, state.writtenPx) != 0) {
            state.nativePx = current;
            state.writtenPx = Float.NaN;
        }
        int size = clampSize(Settings.FEED_AUTHOR_TEXT_SIZE.get());
        if (size == 0) {
            restore(text, state);
            return;
        }
        float wanted = pixels(text, size);
        if (Float.compare(current, wanted) != 0) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, wanted);
        state.writtenPx = wanted;
    }

    private static float authorPixels(TextView text) {
        int size = clampSize(Settings.FEED_AUTHOR_TEXT_SIZE.get());
        return size == 0 ? 0 : pixels(text, size);
    }

    /**
     * A recycled owner binds a new title and date. Its old ones go back to native and stop
     * refreshing it.
     */
    private static void releaseReplacedAuthors(Object owner, TextView current, TextView currentDate) {
        Iterator<Map.Entry<TextView, AuthorSize>> entries = AUTHORS.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<TextView, AuthorSize> entry = entries.next();
            TextView key = entry.getKey();
            if (key != current && key != currentDate && entry.getValue().owner.get() == owner) {
                restore(key, entry.getValue());
                entries.remove();
            }
        }
    }

    private static void refreshAuthorSize(TextView text, AuthorSize state) {
        float wanted = authorPixels(text);
        apply(text, state);
        if (Float.compare(state.requestedPx, wanted) == 0) return;
        state.requestedPx = wanted;
        // The name's entry rebinds the row, and that bind sizes the date before measuring it.
        if (state.date) return;
        Object owner = state.owner.get();
        Object item = state.item.get();
        // Only an owner still holding this title may rebind it, or an old item lands in a new title.
        if (owner != null && item != null && authorViewOf(owner) == text) refreshAuthor(owner, item);
    }

    private static void restore(TextView text, AuthorSize state) {
        float current = text.getTextSize();
        if (!Float.isNaN(state.writtenPx) && Float.compare(current, state.writtenPx) == 0) {
            if (Float.compare(current, state.nativePx) != 0) {
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.nativePx);
            }
        } else {
            // Native code already replaced our size. Keep the newer baseline.
            state.nativePx = current;
        }
        state.writtenPx = Float.NaN;
    }

    /**
     * Main and detail windows share the same native owners, but not a content root. A copy
     * recreated behind the window in front runs the posted install after it has stopped again,
     * and leaves the hook where it is.
     */
    public static void install(Activity activity) {
        if (activity == null) return;
        FRONT.add(activity);
        Utils.runOnMainThread(() -> {
            if (FRONT.mayTake(activity)) installNow(activity);
        });
    }

    private static void installNow(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        activityReference = new WeakReference<>(activity);
        LAYOUT_HOOK.install(root, () -> {
            Activity current = activityReference.get();
            if (current != null) applyTo(current);
        });
        Application application = activity.getApplication();
        if (application != null && followed.get() != application) {
            followed = new WeakReference<>(application);
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                // A feed window coming to the front is FRONT's; anything else gives the sizes back.
                @Override public void onActivityResumed(Activity resumed) {
                    if (FRONT.follows(resumed)) return;
                    restoreAll();
                    LAYOUT_HOOK.detach();
                    activityReference = new WeakReference<>(null);
                }

                @Override public void onActivityDestroyed(Activity destroyed) {
                    if (activityReference.get() == destroyed) {
                        restoreAll();
                        LAYOUT_HOOK.detach();
                        activityReference = new WeakReference<>(null);
                    }
                }

                @Override public void onActivityCreated(Activity created, Bundle state) { }
                @Override public void onActivityStarted(Activity started) { }
                @Override public void onActivityPaused(Activity paused) { }
                @Override public void onActivityStopped(Activity stopped) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            });
        }
        applyTo(activity);
    }

    static void applyTo(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        View root = activity.getWindow().peekDecorView();
        for (TextView text : new ArrayList<>(AUTHORS.keySet())) {
            AuthorSize state = AUTHORS.get(text);
            if (state != null && text.getRootView() == root) refreshAuthorSize(text, state);
        }
        for (Object owner : new ArrayList<>(DESCRIPTIONS.keySet())) {
            DescriptionSize state = DESCRIPTIONS.get(owner);
            View view = state == null ? null : state.view.get();
            if (view != null && view.getRootView() == root) refresh(owner, state, view);
        }
    }

    /** Settings and Pause also refresh already-bound cells behind the settings window. */
    public static void onSettingChanged() {
        Utils.runOnMainThread(() -> {
            // Keys are copied, not entries: a copied entry of Android 6's WeakHashMap can lose its
            // key to a collection mid-loop. Detached cells wait for their next layout pass.
            for (TextView text : new ArrayList<>(AUTHORS.keySet())) {
                AuthorSize state = AUTHORS.get(text);
                if (state != null && text.isAttachedToWindow()) refreshAuthorSize(text, state);
            }
            for (Object owner : new ArrayList<>(DESCRIPTIONS.keySet())) {
                DescriptionSize state = DESCRIPTIONS.get(owner);
                View view = state == null ? null : state.view.get();
                if (view != null && view.isAttachedToWindow()) refresh(owner, state, view);
            }
        });
    }

    private static void refresh(Object owner, DescriptionSize state, View view) {
        // The controller may already hold a replacement View; rebuilding it from the old one is wrong.
        if (descriptionViewOf(owner) != view) return;
        float wanted = descriptionPixels(view);
        if (Float.compare(state.requestedPx, wanted) == 0) return;
        // Native refresh can synchronously cause another layout pass.
        state.requestedPx = wanted;
        state.rebuilding = true;
        try {
            refreshDescription(owner);
        } finally {
            state.rebuilding = false;
        }
    }

    private static void restoreAll() {
        for (Map.Entry<TextView, AuthorSize> entry : AUTHORS.entrySet()) restore(entry.getKey(), entry.getValue());
    }

    static void resetForTests() {
        restoreAll();
        AUTHORS.clear();
        DESCRIPTIONS.clear();
        BUILDERS.clear();
        LAYOUT_HOOK.detach();
        activityReference = new WeakReference<>(null);
        FRONT.resetForTests();
        nativeForTests = null;
    }

    /** Replaced at patch time with resolved native fields and calls on each declared host. */
    interface Native {
        View descriptionViewOf(Object owner);
        TextView authorViewOf(Object owner);
        /** The post date beside the name, or null for a stand-in that has none. */
        default TextView postDateViewOf(Object owner) { return null; }
        void resizeDescriptionBuilder(Object builder, View view);
        void refreshDescription(Object owner);
        void refreshAuthor(Object owner, Object item);
    }

    static volatile Native nativeForTests;

    static View descriptionViewOf(Object owner) {
        Native stand = nativeForTests;
        return stand == null ? null : stand.descriptionViewOf(owner);
    }

    static TextView authorViewOf(Object owner) {
        Native stand = nativeForTests;
        return stand == null ? null : stand.authorViewOf(owner);
    }

    static TextView dateViewOf(Object owner) {
        Native stand = nativeForTests;
        return stand == null ? null : stand.postDateViewOf(owner);
    }

    static void resizeDescriptionBuilder(Object builder, View view) {
        Native stand = nativeForTests;
        if (stand != null) stand.resizeDescriptionBuilder(builder, view);
    }

    static void refreshDescription(Object owner) {
        Native stand = nativeForTests;
        if (stand != null) stand.refreshDescription(owner);
    }

    static void refreshAuthor(Object owner, Object item) {
        Native stand = nativeForTests;
        if (stand != null) stand.refreshAuthor(owner, item);
    }
}

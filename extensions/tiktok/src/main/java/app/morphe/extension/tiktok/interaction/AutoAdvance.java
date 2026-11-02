/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.interaction;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.wellbeing.FeedLock;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

public final class AutoAdvance {
    // Not Supplier: java.util.function arrived at API 24, and a type D8 cannot backport fails
    // to resolve on Android 6, which the payload's own floor of API 23 still allows.
    /** Reads the host's current auto scroll state, which is an enum this cannot name. */
    interface StateReader {
        Object read();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Control> CONTROLS = new WeakHashMap<>();
    private static boolean observing;
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREFERENCES = (preferences, key) -> {
        if (key == null || Settings.AUTO_ADVANCE.key.equals(key)
                || Settings.AUTO_ADVANCE_LIMIT.key.equals(key)) MAIN.post(() -> {
            for (Object component : new ArrayList<>(CONTROLS.keySet())) update(component);
        });
    };
    private AutoAdvance() { }

    public static boolean available(boolean nativeValue) {
        return (app.morphe.extension.tiktok.settings.SettingsStatus.autoAdvanceEnabled && Settings.AUTO_ADVANCE.get())
                || (app.morphe.extension.tiktok.settings.SettingsStatus.forceShowAutoScrollEnabled
                    && Settings.FORCE_SHOW_AUTO_SCROLL.get()) || nativeValue;
    }

    /**
     * The panel's own Auto scroll action, which hangs off a second flag. Hidden, it is absent
     * whatever the flag or the feed gate says; advance on end runs from the component's own
     * start, not from that entry, so the switch takes nothing else with it. The same flag feeds
     * one other reader, the tablet bottom bar's Auto scroll control, which goes with it. The
     * hide switch is a child of Auto-advance: with that off its row is greyed out, and it hides
     * nothing, so an account in TikTok's rollout keeps TikTok's own action.
     */
    public static boolean panelAvailable(boolean nativeValue) {
        if (app.morphe.extension.tiktok.settings.SettingsStatus.autoAdvanceEnabled
                && Settings.AUTO_ADVANCE.get() && Settings.AUTO_ADVANCE_HIDE_PANEL_ACTION.get()) return false;
        return available(nativeValue);
    }

    /**
     * TikTok's search_auto_scroll flag, read where search decides whether its results feed gets
     * auto scroll and which state that starts in. The feed runs the same component as For You, so
     * once the flag lets it exist the hooks below start and stop it like any other. With either
     * switch off, or Hushfeed paused, the server's answer goes through unchanged.
     */
    public static int searchFlag(int nativeValue) {
        try {
            return searchForced() ? 1 : nativeValue;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not read the search auto-advance switch", error);
            return nativeValue;
        }
    }

    private static boolean searchForced() {
        return Settings.AUTO_ADVANCE.get() && Settings.AUTO_ADVANCE_SEARCH.get();
    }

    /**
     * Answers the load strategy the host records beside its auto scroll component. TikTok
     * registers that component lazily, so nothing builds it until somebody opens the video panel
     * and asks for Auto scroll by hand. On a cold start with this setting already on, none of the
     * hooks below had a component to run against and the feature did nothing at all. With the
     * setting on the registration carries the host's own immediate strategy, the one its start
     * phase already uses for the components it always builds. With the setting off the host's own
     * choice is handed straight back.
     */
    public static Object loadStrategy(Object lazy) {
        // The host registers its components outside any try block of its own, so anything
        // thrown here escapes into the panel's own setup. Reading the setting is the first
        // touch of the preference store on this path, and it is reached whether the setting
        // is on or off, so it answers with the host's own choice rather than throwing.
        try {
            if (!Settings.AUTO_ADVANCE.get()) return lazy;
            Object immediate = immediateLoad();
            return immediate != null ? immediate : lazy;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not choose the auto scroll load strategy", error);
            return lazy;
        }
    }

    public static void onView(Object component, View view) {
        if (Looper.myLooper() != Looper.getMainLooper() || component == null || view == null) return;
        // Says which screens build the component at all, the open question for videos opened
        // from a profile (DetailActivity).
        Logger.printDebug(() -> "Auto scroll component built in " + hostOf(view));
        if (!observing) {
            Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(PREFERENCES);
            observing = true;
        }
        Control next = new Control(view);
        Control previous = CONTROLS.put(component, next);
        next.owned = previous != null && previous.owned;
        onResume(component);
    }

    /** The activity a view sits in, by class name, through any theme wrappers. */
    private static String hostOf(View view) {
        android.content.Context context = view.getContext();
        while (context instanceof android.content.ContextWrapper && !(context instanceof android.app.Activity)) {
            android.content.Context base = ((android.content.ContextWrapper) context).getBaseContext();
            if (base == null || base == context) break;
            context = base;
        }
        return context == null ? "no context" : context.getClass().getName();
    }

    public static void onResume(Object component) {
        WeakReference<Object> owner = new WeakReference<>(component);
        MAIN.post(() -> update(owner.get()));
        watchRestore(component);
    }

    /** The component's page coming back, where the host runs the same search restore. */
    public static void onPageResume(Object component) {
        watchRestore(component);
    }

    /**
     * On a search page the host hands every new or resumed component the state search last
     * remembered, and a START there starts the component on its own. The flag answer above is
     * what lets it remember at all, so a scroll Hushfeed started and left running on one page
     * starts again by itself on the next one. Nothing of Hushfeed's started that one, so it was
     * never owned, and the limit, the hold and the shared video check all passed it by. Leaving
     * that read at the server's answer isn't the way out: it then says STOP on every resume and
     * the host turns a running search scroll off each time. So the state is read here, before
     * the host's restore runs, and a component that went from STOP to running in that one step
     * is claimed as Hushfeed's.
     */
    private static void watchRestore(Object component) {
        Control control = CONTROLS.get(component);
        if (control == null) return;
        try {
            if (!control.armRestore(searchForced(), readState(component))) return;
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not watch the search auto scroll restore", error);
            return;
        }
        WeakReference<Object> owner = new WeakReference<>(component);
        MAIN.post(() -> {
            Object live = owner.get();
            Control current = CONTROLS.get(live);
            if (current != null && current.adoptRestore(readState(live))) update(live);
        });
    }

    public static void beforeCompletion(Object component, String completedId) {
        if (Looper.myLooper() != Looper.getMainLooper() || completedId == null) return;
        Control control = CONTROLS.get(component);
        if (control == null) return;
        if (!control.owned) {
            // The host reports every completion, including the ones it played on its own. A
            // session stood down while the feed was off screen, or reset because its limit
            // changed, has nothing else that would look again: the update posted at the time
            // ran while the settings page still had the window. This is that second look.
            update(component);
            return;
        }
        // Before the completion is recorded, not after. Reaching the hold check further down
        // through update() stood down one video late: the video that finished behind the panel
        // still spent a place in this session's limit, and could put its "stopped after N
        // videos" toast on top of the hold. A shared video playing alone stands it down the
        // same way, before its end moves the feed on.
        if (SessionBudget.isLocked() || FeedLock.linkVideoAlone()) {
            update(component);
            return;
        }
        String current = Reflect.string(readAweme(component), "getAid", "aid");
        if (!completedId.equals(current)) return;
        if (!control.recordCompletion(completedId)) return;
        if (control.limitReached() && control.claimLimitNotice()) {
            String message = L10n.quantity(Utils.getContext(), control.completedCount,
                    "Auto-advance stopped after one video", "Auto-advance stopped after %1$d videos");
            BlockAuthorOverlay.showActionBanner(message,
                    L10n.t("Keep going"), () -> {
                        control.completedCount = 0;
                        control.lastCompletedId = null;
                        control.limitNoticeShown = false;
                        update(component);
                    });
        }
        update(component);
    }

    public static void onDestroy(Object component) { CONTROLS.remove(component); }

    private static void update(Object component) {
        Control control = CONTROLS.get(component);
        if (control == null) return;
        try {
            control.update(() -> readState(component), () -> start(component), () -> stop(component));
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not update automatic advance", error);
        }
    }

    static final class Control {
        final WeakReference<View> view;
        boolean owned;
        int completedCount;
        private String lastCompletedId;
        private boolean limitNoticeShown;
        private boolean restoreArmed;
        private int sessionLimit = Settings.AUTO_ADVANCE_LIMIT.get();
        Control(View view) { this.view = new WeakReference<>(view); }

        /**
         * Read just before the host's search restore: armed only when that could start it. An arm
         * already waiting on its check stays as it is. onViewCreated restores and onResume follows
         * in the same message when a page is added to a running activity, and the second read
         * would see the START the first one is waiting to claim and disarm it.
         */
        boolean armRestore(boolean searchForced, Object stateBefore) {
            if (restoreArmed) return false;
            restoreArmed = searchForced && !owned && named(stateBefore, "AUTO_SCROLL_STATE_STOP");
            return restoreArmed;
        }

        /** Claims a scroll the host started on its own since armRestore; true when it did. */
        boolean adoptRestore(Object stateAfter) {
            boolean armed = restoreArmed;
            restoreArmed = false;
            if (!armed || owned || !(stateAfter instanceof Enum<?>)
                    || named(stateAfter, "AUTO_SCROLL_STATE_STOP")) return false;
            owned = true;
            return true;
        }

        private void refreshLimit() {
            int next = Settings.AUTO_ADVANCE_LIMIT.get();
            if (sessionLimit == next) return;
            sessionLimit = next;
            completedCount = 0;
            lastCompletedId = null;
            limitNoticeShown = false;
        }

        boolean recordCompletion(String completedId) {
            // A completion may arrive before the posted preference refresh.
            refreshLimit();
            if (!owned || completedId.equals(lastCompletedId)) return false;
            lastCompletedId = completedId;
            completedCount++;
            return true;
        }

        boolean limitReached() {
            int limit = Settings.AUTO_ADVANCE_LIMIT.get();
            return limit > 0 && completedCount >= limit;
        }

        boolean claimLimitNotice() {
            if (!limitReached() || limitNoticeShown) return false;
            limitNoticeShown = true;
            return true;
        }

        void update(StateReader state, Runnable start, Runnable stop) {
            // The settings page's canonical value is ready when its posted update runs.
            // Returning to the same value or changing another row preserves this session.
            refreshLimit();
            // A shared video opened alone has no feed after it, so nothing moves on from it, and
            // that takes in TikTok's own auto scroll too: started from the panel action, or by
            // search on its own, it isn't Hushfeed's, and the pager's touch guard doesn't stop
            // a move the app makes itself. Ownership is released, so the next look once another
            // video plays starts it again. The switch and Pause Hushfeed are in linkVideoAlone.
            if (FeedLock.linkVideoAlone()) {
                Object now = state.read();
                if (now instanceof Enum<?> && !named(now, "AUTO_SCROLL_STATE_STOP")) stop.run();
                owned = false;
                return;
            }
            if (!Settings.AUTO_ADVANCE.get()) {
                if (owned) { stop.run(); owned = false; }
                return;
            }
            if (limitReached()) {
                if (owned) { stop.run(); owned = false; }
                return;
            }
            // A hold covers the feed. Advancing behind it walks through videos nobody can see,
            // and each one used to spend a place in this session's own limit as well. Ownership
            // is released rather than only stopped, so the next call after the hold ends starts
            // it again from the video that is actually on screen.
            if (SessionBudget.isLocked()) {
                if (owned) { stop.run(); owned = false; }
                return;
            }
            View live = view.get();
            if (live == null || !live.isAttachedToWindow() || !live.hasWindowFocus()) return;
            if (!feedIsOnScreen(live)) return;
            if (!named(state.read(), "AUTO_SCROLL_STATE_STOP")) return;
            start.run();
            owned = !named(state.read(), "AUTO_SCROLL_STATE_STOP");
        }
    }

    /**
     * Whether the feed this component belongs to is on screen.
     *
     * The component's own view is the host's auto scroll indicator, and the host keeps it GONE
     * until scrolling is actually running. Asking that view whether it is shown answers a
     * different question, and on a cold start it always answered no, so nothing ever started.
     * What matters is the feed it sits in, which is what its parent reports. A view with no
     * parent answers no here, which is also what isShown() says about one.
     */
    private static boolean feedIsOnScreen(View view) {
        ViewParent parent = view.getParent();
        return parent instanceof View && ((View) parent).isShown();
    }

    private static boolean named(Object state, String name) {
        return state instanceof Enum<?> && ((Enum<?>) state).name().equals(name);
    }

    // Replaced with native calls when the patch is applied.
    private static Object immediateLoad() { return null; }
    private static Object readAweme(Object component) { return null; }
    private static Object readState(Object component) { return null; }
    private static void start(Object component) { }
    private static void stop(Object component) { }
}

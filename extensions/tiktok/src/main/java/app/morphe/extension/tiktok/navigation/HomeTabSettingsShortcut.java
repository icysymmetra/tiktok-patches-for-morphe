/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.app.Activity;
import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.GlobalLayoutHook;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsReturn;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.TikTokActivityHook;

/**
 * A long press on the Home tab opens Hushfeed's settings (#45), which otherwise sit behind
 * Profile, the menu and TikTok's settings list.
 *
 * <p>TikTok gives that press nothing of its own: on 47.0.3 a held Home acts as a tap. A tab that
 * is already long-clickable when this first sees it is left alone, so a build that does give Home
 * a long press keeps it. Profile's long press is TikTok's account switcher, so not Profile.
 *
 * <p>The tab bar is rebuilt now and then (a tab filter change, a configuration change), so the
 * listener is checked on each layout pass of the main activity, and switching the setting off
 * takes it away again the next time the feed is laid out.
 *
 * <p>TikTok's launcher entry is an alias of MainActivity, so the task can hold two of them, the
 * launcher's under the feed's. Changing TikTok's appearance recreates both, the buried one last,
 * and following the newest one left Home without its long press until TikTok was restarted. The
 * shortcut follows the main activity that is resumed instead ({@link FrontWindow}).
 */
public final class HomeTabSettingsShortcut {
    private static final GlobalLayoutHook LAYOUT = new GlobalLayoutHook();
    /**
     * The main activity in front, out of every one the patched MainActivity.onCreate handed in.
     * The main activity alone has the tab bar; a creator's video opens in another one.
     */
    private static final FrontWindow FRONT = new FrontWindow(false, new FrontWindow.Listener() {
        @Override public void onFront(Activity activity) {
            track(activity);
        }

        @Override public void onGone(Activity activity) {
            LAYOUT.detach();
        }
    });
    /** The tab the listener is on, so a rebuilt tab gets it again and an old one is let go. */
    private static WeakReference<View> attached = new WeakReference<>(null);
    /** A tab that was long-clickable before this touched it, so it is never taken over. */
    private static WeakReference<View> refused = new WeakReference<>(null);

    private static final View.OnLongClickListener OPEN_SETTINGS = view -> {
        try {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            TikTokActivityHook.openSettings();
        } catch (Throwable error) {
            Logger.printException(() -> "Could not open Metra settings from the Home tab", error);
        }
        // Handled, so the release that ends the press isn't also a tap that reloads the feed.
        return true;
    };

    private HomeTabSettingsShortcut() {
    }

    /**
     * Called from the patched {@code MainActivity.onCreate}, right after the extension's context
     * and before the host's own onCreate: nothing here may touch the window, which would build
     * the decor before the host asks for its window features. Views wait for the first resume.
     */
    public static void install(Context context) {
        // Every bundle passes through here on each start, the Home tab's switch or not, so this is
        // where a restart pressed in the settings lands back on its page. It posts the opening and
        // touches no window itself.
        if (context instanceof Activity) SettingsReturn.onMainActivityCreated();
        try {
            if (!(context instanceof Activity) || !SettingsStatus.feedNavigationEnabled) return;
            FRONT.add((Activity) context);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not follow the Home tab", error);
        }
    }

    private static void track(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root != null) LAYOUT.install(root, HomeTabSettingsShortcut::apply);
        apply();
    }

    static void apply() {
        try {
            Activity activity = FRONT.get();
            if (activity == null || activity.isFinishing()) {
                LAYOUT.detach();
                return;
            }
            View home = FeedVisibility.homeTabView(activity);
            View current = attached.get();
            boolean wanted = Settings.HOME_TAB_OPENS_SETTINGS.get();
            if (current != null && (current != home || !wanted)) {
                current.setOnLongClickListener(null);
                current.setLongClickable(false);
                attached = new WeakReference<>(null);
            }
            if (!wanted || home == null || attached.get() == home || refused.get() == home) return;
            if (home.isLongClickable()) {
                refused = new WeakReference<>(home);
                Logger.printInfo(() -> "The Home tab has a long press of its own; leaving it to TikTok");
                return;
            }
            // A view that isn't the one taking the tap would take the whole press once it is
            // long-clickable, and the tap its parent handles would be lost.
            if (!home.isClickable()) {
                refused = new WeakReference<>(home);
                Logger.printInfo(() -> "The Home tab view doesn't take the tap itself; no long press");
                return;
            }
            home.setOnLongClickListener(OPEN_SETTINGS);
            attached = new WeakReference<>(home);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not set the Home tab's long press", error);
        }
    }

    static void resetForTests() {
        LAYOUT.detach();
        FRONT.resetForTests();
        attached = new WeakReference<>(null);
        refused = new WeakReference<>(null);
    }
}

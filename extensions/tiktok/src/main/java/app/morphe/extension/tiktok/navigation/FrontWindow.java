/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;

/**
 * The window a hook works on: whichever of its activities is resumed now.
 *
 * <p>TikTok's launcher entry is an alias of MainActivity, so the task can hold two of them, the
 * launcher's under the feed's. Changing TikTok's appearance recreates both, the buried one last,
 * and every recreated copy is resumed for a moment and stopped again, in front or not. A hook that
 * kept the activity its onCreate handed in, or the one resumed last, ended up on the buried copy
 * while the feed was the one on screen (S22, 47.1.4, Dark then Light). This follows the latest
 * resume, and when the one it follows pauses while another stays resumed, it moves to that one.
 * An install posted from onCreate runs after a recreated copy has stopped again, so it asks
 * {@link #mayTake} first.
 *
 * <p>One per hook, kept in a static field. Main thread only.
 */
public final class FrontWindow {
    /** What a hook does as the window it follows changes. */
    public interface Listener {
        /**
         * {@code activity} is the one in front now: it resumed, or the one followed paused while
         * this one stayed resumed.
         */
        void onFront(Activity activity);

        /** The activity followed was destroyed. Nothing is followed until the next resume. */
        void onGone(Activity activity);
    }

    private final boolean feedWindows;
    private final Listener listener;
    /** Every activity the hook's install handed in. */
    private final Set<Activity> handed = Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    /**
     * The ones resumed now, latest last. A buried copy that a theme change recreates is resumed
     * for a moment while the feed's stays in front, and the feed's gets no new resume.
     */
    private final List<WeakReference<Activity>> resumed = new ArrayList<>();
    private WeakReference<Activity> followed = new WeakReference<>(null);
    private WeakReference<Application> registeredOn = new WeakReference<>(null);

    private final Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityResumed(Activity activity) {
            if (!follows(activity)) return;
            forgetResumed(activity);
            resumed.add(new WeakReference<>(activity));
            follow(activity);
        }

        @Override public void onActivityPaused(Activity activity) {
            if (!follows(activity)) return;
            forgetResumed(activity);
            Activity front = latestResumed();
            if (front != null && followed.get() == activity) follow(front);
        }

        @Override public void onActivityDestroyed(Activity activity) {
            handed.remove(activity);
            forgetResumed(activity);
            if (followed.get() != activity) return;
            followed = new WeakReference<>(null);
            try {
                listener.onGone(activity);
            } catch (Throwable error) {
                Logger.printException(() -> "Could not let go of a closed window", error);
            }
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    };

    /**
     * @param feedWindows also follow every feed window (the main activity and the detail pager),
     *                    not only the activities handed in
     */
    public FrontWindow(boolean feedWindows, Listener listener) {
        this.feedWindows = feedWindows;
        this.listener = listener;
    }

    /**
     * From the patched onCreate, before the activity can resume: the hook follows it from now on.
     * Touches no view, so it's safe before the host has asked for its window features.
     */
    public void add(Activity activity) {
        try {
            if (activity == null) return;
            Application application = activity.getApplication();
            if (application != null && registeredOn.get() != application) {
                // A new application object starts over. On a phone that's the first call.
                forget();
                registeredOn = new WeakReference<>(application);
                application.registerActivityLifecycleCallbacks(callbacks);
            }
            handed.add(activity);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not follow the window in front", error);
        }
    }

    /** The activity followed now, or null before the first resume and once it's destroyed. */
    public Activity get() {
        return followed.get();
    }

    /** Whether {@code activity} is one of the windows this follows. */
    public boolean follows(Activity activity) {
        return activity != null && (handed.contains(activity)
                || (feedWindows && FeedVisibility.isFeedWindow(activity)));
    }

    /**
     * Whether an install posted from {@code activity}'s onCreate may still put the hook there. A
     * recreated copy behind the window in front runs it after it has been resumed for a moment
     * and stopped again, and must leave the hook where it is by then.
     */
    public boolean mayTake(Activity activity) {
        Activity now = followed.get();
        return now == null || now == activity;
    }

    /** Stops listening and forgets every window. */
    public void resetForTests() {
        forget();
    }

    private void forget() {
        Application application = registeredOn.get();
        if (application != null) application.unregisterActivityLifecycleCallbacks(callbacks);
        registeredOn = new WeakReference<>(null);
        handed.clear();
        resumed.clear();
        followed = new WeakReference<>(null);
    }

    private void follow(Activity activity) {
        followed = new WeakReference<>(activity);
        try {
            listener.onFront(activity);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not move to the window in front", error);
        }
    }

    private void forgetResumed(Activity activity) {
        for (Iterator<WeakReference<Activity>> it = resumed.iterator(); it.hasNext(); ) {
            Activity held = it.next().get();
            if (held == null || held == activity) it.remove();
        }
    }

    private Activity latestResumed() {
        for (int i = resumed.size() - 1; i >= 0; i--) {
            Activity held = resumed.get(i).get();
            if (held != null && !held.isFinishing()) return held;
        }
        return null;
    }
}

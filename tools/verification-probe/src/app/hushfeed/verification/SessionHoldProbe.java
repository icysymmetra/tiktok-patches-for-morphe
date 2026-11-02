package app.hushfeed.verification;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONObject;

/** Exercises the real hold controller without changing budget settings, records or focus callbacks. */
final class SessionHoldProbe {
    private static final String PREFIX = "app.morphe.extension.tiktok.";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Owned owned;
    private static boolean watching;

    private static final class Owned {
        final ClassLoader loader;
        final WeakReference<Object> target;
        final long startedAt = SystemClock.elapsedRealtime();
        final Runnable watchdog;

        Owned(ClassLoader loader, Object target) {
            this.loader = loader;
            this.target = new WeakReference<>(target);
            watchdog = () -> {
                try { release(this, "controller_watchdog"); }
                catch (Exception failure) { failure("watchdog", failure); }
            };
        }
    }

    private SessionHoldProbe() { }

    static void run(ClassLoader loader, String op, int seconds) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("hold control requires main thread");
        if (op == null || "status".equals(op)) status(loader, "status");
        else if ("pause".equals(op)) pause(loader, seconds);
        else if ("release".equals(op) || "restore".equals(op)) {
            if (owned == null) throw new IllegalStateException("no probe-owned controller pause");
            release(owned, op);
        } else if ("watch".equals(op)) watch(loader, seconds);
        else throw new IllegalArgumentException("expected status, pause, release, restore or watch");
    }

    private static void pause(ClassLoader loader, int seconds) throws Exception {
        if (seconds < 2 || seconds > 60) throw new IllegalArgumentException("controller pause must be 2 through 60 seconds");
        if (BudgetFocusProbe.active()) throw new IllegalStateException("budget fixture owns playback until restoration");
        Class<?> hold = loader.loadClass(PREFIX + "wellbeing.SessionPlaybackHold");
        if (owned != null && owned.target.get() != null
                && (field(hold, "held").get(null) == owned.target.get()
                || field(hold, "waitingForFocus").get(null) == owned.target.get())) {
            throw new IllegalStateException("controller pause already owned by this probe");
        }
        Class<?> budget = loader.loadClass(PREFIX + "wellbeing.SessionBudget");
        if (Boolean.TRUE.equals(call(budget, "isLocked"))
                || field(hold, "held").get(null) != null || field(hold, "waitingForFocus").get(null) != null
                || field(hold, "switchWanted").getBoolean(null) || field(hold, "lockWanted").getBoolean(null)) {
            throw new IllegalStateException("another hold already owns playback");
        }
        if (Boolean.TRUE.equals(call(loader.loadClass("app.morphe.extension.shared.settings.Setting"), "isPaused"))) {
            throw new IllegalStateException("Hushfeed is paused");
        }
        if (Boolean.TRUE.equals(call(loader.loadClass(PREFIX + "playback.FeedMute"), "isHoldingFocus"))) {
            throw new IllegalStateException("feed mute would bypass native focus waiting");
        }
        Activity activity = (Activity) call(loader.loadClass("app.morphe.extension.shared.Utils"), "getActivity");
        Class<?> visibility = loader.loadClass(PREFIX + "blockauthor.FeedVisibility");
        if (activity == null || !activity.hasWindowFocus()
                || !Boolean.TRUE.equals(method(visibility, "isOnFeed", Activity.class).invoke(null, activity))) {
            throw new IllegalStateException("native feed must be foreground");
        }
        if (!Boolean.TRUE.equals(call(hold, "currentPlaying")) || !Boolean.TRUE.equals(call(hold, "hasNativeFocus"))) {
            throw new IllegalStateException("native feed must be playing with real native focus");
        }
        Object target = field(hold, "current").get(null);
        if (target == null) throw new IllegalStateException("no current native controller");
        Owned pending = new Owned(loader, target);
        owned = pending;
        try {
            call(hold, "pauseIfPlaying");
            if (field(hold, "held").get(null) != target) throw new IllegalStateException("native pause was not owned");
            MAIN.postDelayed(pending.watchdog, seconds * 1000L);
            status(loader, "paused watchdogSeconds=" + seconds);
        } catch (Exception failure) {
            release(pending, "pause_failed");
            throw failure;
        }
    }

    private static void release(Owned pending, String reason) throws Exception {
        MAIN.removeCallbacks(pending.watchdog);
        Class<?> hold = pending.loader.loadClass(PREFIX + "wellbeing.SessionPlaybackHold");
        Object target = pending.target.get();
        boolean matches = target != null && (field(hold, "held").get(null) == target
                || field(hold, "waitingForFocus").get(null) == target);
        // A real budget, pause switch or lock can take ownership while the check runs.
        // Its normal release path must decide when playback may return.
        boolean transferred = Boolean.TRUE.equals(call(pending.loader.loadClass(PREFIX + "wellbeing.SessionBudget"), "isLocked"))
                || field(hold, "switchWanted").getBoolean(null) || field(hold, "lockWanted").getBoolean(null);
        if (matches && transferred) {
            if (owned == pending) owned = null;
            status(pending.loader, reason + " ownership_transferred");
            return;
        }
        if (matches) method(hold, "release", boolean.class).invoke(null, false);
        status(pending.loader, reason);
        if (target == null || field(hold, "held").get(null) != target) {
            if (owned == pending) owned = null;
        }
    }

    private static void watch(ClassLoader loader, int seconds) throws Exception {
        if (seconds < 1 || seconds > 20) throw new IllegalArgumentException("watch must be 1 through 20 seconds");
        if (watching) throw new IllegalStateException("hold watch already running");
        watching = true;
        long until = SystemClock.elapsedRealtime() + seconds * 1000L;
        Runnable sample = new Runnable() {
            @Override public void run() {
                try {
                    status(loader, "watch");
                    if (SystemClock.elapsedRealtime() < until) MAIN.postDelayed(this, 250);
                    else watching = false;
                } catch (Exception failure) {
                    watching = false;
                    failure("watch", failure);
                }
            }
        };
        MAIN.post(sample);
    }

    private static void status(ClassLoader loader, String reason) throws Exception {
        Class<?> hold = loader.loadClass(PREFIX + "wellbeing.SessionPlaybackHold");
        Class<?> budget = loader.loadClass(PREFIX + "wellbeing.SessionBudget");
        Class<?> overlay = loader.loadClass(PREFIX + "wellbeing.SessionLockOverlay");
        Object current = field(hold, "current").get(null);
        Object held = field(hold, "held").get(null);
        Object waiting = field(hold, "waitingForFocus").get(null);
        if (owned != null && (owned.target.get() == null
                || (owned.target.get() != held && owned.target.get() != waiting))) {
            MAIN.removeCallbacks(owned.watchdog);
            owned = null;
        }
        Object controller = current == null ? null : ((WeakReference<?>) field(current.getClass(), "controller").get(current)).get();
        Object manager = controller == null ? null : call(controller, "getPlayerManager");
        boolean sameCell = current != null && controller != null
                && Boolean.TRUE.equals(method(current.getClass(), "isCurrentCell", Object.class).invoke(current, controller));
        Object owner;
        int listeners = 0;
        List<?> requests = (List<?>) field(hold, "nativeFocusListeners").get(null);
        synchronized (requests) {
            owner = ((WeakReference<?>) field(hold, "nativeFocusOwner").get(null)).get();
            for (Object request : requests) if (((WeakReference<?>) request).get() != null) listeners++;
        }
        View panel = (View) ((WeakReference<?>) field(overlay, "overlayReference").get(null)).get();
        JSONObject out = new JSONObject();
        out.put("scenario", BudgetFocusProbe.active() ? "budget_overlay_seeded_count" : "controller_only");
        out.put("reason", reason);
        out.put("elapsedRealtimeMs", SystemClock.elapsedRealtime());
        out.put("probeOwned", owned != null);
        out.put("probeElapsedMs", owned == null ? 0 : SystemClock.elapsedRealtime() - owned.startedAt);
        out.put("held", held != null);
        out.put("waiting", waiting != null);
        out.put("heldIsCurrent", held != null && held == current);
        out.put("waitingIsCurrent", waiting != null && waiting == current);
        out.put("nativeFocus", owner != null);
        out.put("nativeListeners", listeners);
        out.put("holdCanResume", call(hold, "hasNativeFocus"));
        out.put("pauseObserved", field(hold, "heldPauseObserved").getBoolean(null));
        out.put("switchWanted", field(hold, "switchWanted").getBoolean(null));
        out.put("lockWanted", field(hold, "lockWanted").getBoolean(null));
        out.put("sameCell", sameCell);
        out.put("playing", manager == null || !sameCell ? JSONObject.NULL : call(manager, "isPlaying"));
        out.put("paused", manager == null || !sameCell ? JSONObject.NULL : call(manager, "isPaused"));
        Object position = manager == null || !sameCell ? null : optionalCall(manager, "getCurrentPosition");
        out.put("positionMs", position instanceof Number ? position : JSONObject.NULL);
        out.put("budgetLocked", call(budget, "isLocked"));
        out.put("budgetRemainingMs", call(budget, "lockRemainingMs"));
        out.put("budgetCounting", call(budget, "isCounting"));
        out.put("overlayVisible", panel != null && panel.isShown());
        out.put("overlayQuietRequest", field(overlay, "quietened").getBoolean(null));
        out.put("overlayFocusGranted", field(overlay, "focusGranted").getBoolean(null));
        Log.i(Probe.TAG, "session-hold " + out);
    }

    private static Object call(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        return method(type, name).invoke(target instanceof Class<?> ? null : target);
    }

    private static Object optionalCall(Object target, String name) throws Exception {
        try { return call(target, name); }
        catch (NoSuchMethodException unavailable) { return null; }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Method found = owner.getDeclaredMethod(name, parameters);
                found.setAccessible(true);
                return found;
            } catch (NoSuchMethodException inherited) { }
        }
        Method found = type.getMethod(name, parameters);
        found.setAccessible(true);
        return found;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field found = type.getDeclaredField(name);
        found.setAccessible(true);
        return found;
    }

    private static void failure(String op, Exception failure) {
        Log.e(Probe.TAG, "session-hold failed op=" + op + " kind=" + failure.getClass().getSimpleName());
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.diagnostics;

import android.app.Activity;
import android.content.res.Resources;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.shared.Utils;

/**
 * How one screen is built, recorded when the reader asks, for "please hide this" reports.
 *
 * <p>Reports about a banner, a card or a shopping LIVE the test account never gets (#21, #41,
 * #46) stalled because an export carried no view tree, so nobody could say which view to hide.
 * The reader starts a capture in Diagnostics and goes to the screen; {@link #DELAY_MS} later the
 * windows then on screen are walked and kept until the next export. Each line holds a view's
 * class, its resource entry name, its visibility when that isn't visible, and its bounds on
 * screen. No text, content description, handle or id is read, and the export still runs every
 * line through {@link DiagnosticRedactor}.
 */
public final class ScreenLayout {
    /** How long the reader has to reach the screen after starting a capture. */
    public static final long DELAY_MS = 20_000L;
    static final int MAX_VIEWS = 800;
    static final int MAX_CHARS = 40_000;
    static final int MAX_DEPTH = 64;

    public enum Result {
        /** The layout was kept for the next export. */
        RECORDED,
        /** The screen that started the capture was still in front, so nothing was kept. */
        STILL_HERE,
        /** No TikTok screen was open, or it could not be walked. */
        FAILED
    }

    public interface Listener {
        void onResult(Result result);
    }

    private static volatile List<String> captured;
    private static volatile boolean pending;
    private static int generation;

    private ScreenLayout() {
    }

    /**
     * Records the screen in front {@link #DELAY_MS} from now, unless it is still {@code from}.
     * A second call replaces a capture that hasn't run yet. Main thread only.
     */
    public static void start(Activity from, Listener listener) {
        Utils.verifyOnMainThread();
        final int mine = ++generation;
        final WeakReference<Activity> origin = new WeakReference<>(from);
        pending = true;
        Utils.runOnMainThreadDelayed(() -> {
            if (mine != generation) return;
            pending = false;
            Result result;
            try {
                Activity visible = Utils.getVisibleActivity();
                if (visible == null || visible.isFinishing() || visible.isDestroyed()) {
                    result = Result.FAILED;
                } else if (visible == origin.get()) {
                    result = Result.STILL_HERE;
                } else {
                    captured = describe(visible);
                    result = Result.RECORDED;
                }
            } catch (Throwable failure) {
                result = Result.FAILED;
            }
            if (listener != null) listener.onResult(result);
        }, DELAY_MS);
    }

    /** True between {@link #start} and its capture running. */
    public static boolean isPending() {
        return pending;
    }

    /** The recorded lines, or an empty list when nothing was recorded. */
    public static List<String> lines() {
        List<String> held = captured;
        return held == null ? Collections.emptyList() : held;
    }

    /** Takes the recorded layout away, for the clear row's undo. */
    public static List<String> snapshotAndClear() {
        List<String> held = captured;
        captured = null;
        return held == null ? Collections.emptyList() : held;
    }

    /** Puts a cleared layout back, unless another was recorded since. */
    public static void restore(List<String> saved) {
        if (saved != null && !saved.isEmpty() && captured == null) captured = saved;
    }

    static List<String> describe(Activity activity) {
        List<String> lines = new ArrayList<>();
        lines.add("activity: " + activity.getClass().getName());
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        lines.add("recorded_utc: " + utc.format(new Date()));

        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        List<View> roots = new ArrayList<>();
        if (decor != null) roots.add(decor);
        // Dialogs, popups and sheets are windows of their own, and a floating points badge or a
        // promotion sheet is exactly what a reader asks to hide. Only the screen in front's own:
        // a dialog belongs to its activity's token, a popup to the window it opened from (the
        // activity's, or a sheet's own), and another screen's windows, its dialog still up behind
        // this one included, are left out.
        java.util.Set<android.os.IBinder> owners = new java.util.HashSet<>();
        if (activity.getWindow() != null && activity.getWindow().getAttributes().token != null) {
            owners.add(activity.getWindow().getAttributes().token);
        }
        if (decor != null && decor.getWindowToken() != null) owners.add(decor.getWindowToken());
        List<View> others = otherWindows();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (View root : others) {
                if (root == decor || roots.contains(root) || !root.isShown()) continue;
                ViewGroup.LayoutParams params = root.getLayoutParams();
                if (!(params instanceof WindowManager.LayoutParams)) continue;
                WindowManager.LayoutParams window = (WindowManager.LayoutParams) params;
                if (window.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) continue;
                if (window.token == null || !owners.contains(window.token)) continue;
                roots.add(root);
                if (root.getWindowToken() != null && owners.add(root.getWindowToken())) grew = true;
            }
        }

        Resources resources = activity.getResources();
        // The other windows are walked first, with up to half the bounds, so a feed that fills
        // the bounds on its own can't cut a popup over it down to its window line (#162). They
        // still come after the screen in the export.
        int[] budget = {MAX_VIEWS / 2, MAX_CHARS / 2, 0};
        int skipped = 0;
        List<String> overlays = new ArrayList<>();
        for (View root : roots) {
            if (root == decor) continue;
            overlays.add("window: " + windowName(root));
            skipped += walk(root, 0, resources, overlays, budget);
        }
        budget[0] += MAX_VIEWS - MAX_VIEWS / 2;
        budget[1] += MAX_CHARS - MAX_CHARS / 2;
        if (decor != null) {
            lines.add("window: " + windowName(decor));
            skipped += walk(decor, 0, resources, lines, budget);
        }
        lines.addAll(overlays);
        if (budget[2] > 0) lines.add("(" + budget[2] + " views off screen not listed)");
        if (skipped > 0) lines.add("(" + skipped + " more views not listed)");
        return Collections.unmodifiableList(lines);
    }

    /**
     * Adds the view and its shown children; answers how many were left out for the bounds. A
     * group clipped out of sight, like the next video the feed keeps laid out under this one,
     * gets its own line marked offscreen and its children are only counted (#162: the next
     * video took the bounds before the rest of the screen was reached).
     */
    private static int walk(View view, int depth, Resources resources, List<String> lines, int[] budget) {
        if (budget[0] <= 0 || budget[1] <= 0) return 1 + descendants(view);
        boolean offscreen = isClippedGroup(view);
        String line = line(view, depth, resources) + (offscreen ? " offscreen" : "");
        budget[0]--;
        budget[1] -= line.length() + 1;
        lines.add(line);
        if (offscreen) {
            budget[2] += descendants(view);
            return 0;
        }
        if (view.getVisibility() != View.VISIBLE || !(view instanceof ViewGroup)) return 0;
        ViewGroup group = (ViewGroup) view;
        int skipped = 0;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child == null) continue;
            if (depth + 1 >= MAX_DEPTH) {
                skipped += 1 + descendants(child);
                continue;
            }
            skipped += walk(child, depth + 1, resources, lines, budget);
        }
        return skipped;
    }

    /** A shown group with a size and children, none of it inside its window's visible area. */
    private static boolean isClippedGroup(View view) {
        if (view.getVisibility() != View.VISIBLE || !(view instanceof ViewGroup)) return false;
        if (((ViewGroup) view).getChildCount() == 0 || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        return !view.getGlobalVisibleRect(new android.graphics.Rect());
    }

    private static int descendants(View view) {
        if (view.getVisibility() != View.VISIBLE || !(view instanceof ViewGroup)) return 0;
        ViewGroup group = (ViewGroup) view;
        int count = 0;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child != null) count += 1 + descendants(child);
        }
        return count;
    }

    private static String line(View view, int depth, Resources resources) {
        StringBuilder line = new StringBuilder();
        for (int level = 0; level < depth; level++) line.append(' ');
        line.append(className(view.getClass()));
        String entry = entryName(view, resources);
        if (entry != null) line.append(" #").append(entry);
        if (view.getVisibility() == View.INVISIBLE) line.append(" invisible");
        else if (view.getVisibility() == View.GONE) line.append(" gone");
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        line.append(' ').append(at[0]).append(',').append(at[1])
                .append(' ').append(view.getWidth()).append('x').append(view.getHeight());
        return line.toString();
    }

    /** Framework classes by their short name; TikTok's own, which are the point, in full. */
    static String className(Class<?> type) {
        String name = type.getName();
        for (String framework : new String[]{"android.widget.", "android.view.", "android.webkit."}) {
            if (name.startsWith(framework) && name.indexOf('.', framework.length()) < 0) {
                return name.substring(framework.length());
            }
        }
        return name;
    }

    private static String entryName(View view, Resources resources) {
        int id = view.getId();
        if (id == View.NO_ID || resources == null) return null;
        try {
            return resources.getResourceEntryName(id);
        } catch (Resources.NotFoundException generated) {
            // View.generateViewId() and ids set in code have no name.
            return null;
        }
    }

    /**
     * The window's root class and its type. Not its title: a dialog's title is the text it shows
     * (Dialog.setTitle writes it there), which is exactly what a layout must not carry.
     */
    private static String windowName(View root) {
        ViewGroup.LayoutParams params = root.getLayoutParams();
        String type = params instanceof WindowManager.LayoutParams
                ? " type " + ((WindowManager.LayoutParams) params).type : "";
        return className(root.getClass()) + type;
    }

    /** Every window root view in the process, or none when the platform won't say. */
    @SuppressWarnings("unchecked")
    private static List<View> otherWindows() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return new ArrayList<>(android.view.inspector.WindowInspector.getGlobalWindowViews());
            }
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Method instance = global.getMethod("getInstance");
            Field views = global.getDeclaredField("mViews");
            views.setAccessible(true);
            Object held = views.get(instance.invoke(null));
            if (held instanceof List) return new ArrayList<>((List<View>) held);
        } catch (Throwable unavailable) {
            // The activity's own window is still recorded.
        }
        return Collections.emptyList();
    }

    static void resetForTests() {
        generation++;
        pending = false;
        captured = null;
    }
}

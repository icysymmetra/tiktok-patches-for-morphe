/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.app.Application;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hide the status bar in LIVE rooms (#38). A LIVE room is an activity of its own,
 * {@code LivePlayActivity}, so Hide the status bar, which works on the main activity's window,
 * never reached it. Each time a LIVE room comes to the front with the switch on, its status bar
 * is hidden and its window allowed into the display cutout, and the strip TikTok keeps for the
 * bar is taken away so the stream reaches the top edge; all of it goes back as the room leaves,
 * so switching apps or rotating starts again from TikTok's own state.
 *
 * <p>Show a LIVE under the status bar takes only the strip away and leaves the bar showing, see
 * through, over the stream. That's how rooms looked until TikTok started keeping a black strip
 * for the bar in October 2026, with or without Hushfeed (#137). The black is two things: the
 * offset that holds the room a bar lower, and a plain view the bar's height that TikTok draws
 * over the top of the room. Both go while the room is in front, and the room's controls are held
 * a bar lower again on their own, so only the stream reaches up under the clock. TikTok's dark
 * shade across the top goes down with the controls, so its first color is drawn on into the strip.
 * A page of the room pager with no stream, like the LIVE feed's Discover panel above the first
 * room, is held a bar lower as a whole.
 *
 * <p>A LIVE opened from the preview under a creator's card in search isn't a room of its own: it's
 * a page of TikTok's detail pager, the one opened videos scroll in. The detail page is watched for a
 * LIVE page on screen, which gets the same handling as a room until it's swiped away (#137).
 */
public final class LiveStatusBar {
    /** TikTok's LIVE room, real-named on 47.0.3 and 47.1.3 (read off the S22, 2026-09-27). */
    static final String LIVE_ROOM = "com.ss.android.ugc.aweme.live.LivePlayActivity";
    /**
     * TikTok's detail pager, which holds a LIVE as one of its pages when it's opened from a
     * creator's card in search (read off the S22 on 47.1.4, 2026-10-11).
     */
    static final String DETAIL = "com.ss.android.ugc.aweme.detail.ui.DetailActivity";

    private static final String HOOK_FAMILY = "LIVE status bar";
    private static final int LEGACY_FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
    /** How deep the strip is looked for; on 47.1.3 it sits three levels under the content view. */
    private static final int MAX_DEPTH = 12;
    /** Containers looked into on one pass, so a deep room can't make a layout pass slow. */
    private static final int MAX_VISITS = 200;
    /** Layout passes that look for the strip before giving up on a room that has none. */
    private static final int MAX_SEARCHES = 40;
    /**
     * Least time between two shade searches on one layer. A LIVE room lays out many times a second
     * while it loads, so a budget spent per pass was gone before TikTok's shade could be found and
     * some rooms kept a light strip under the clock (#137, S22). Spaced out, it lasts 20 seconds.
     */
    private static final long SHADE_RETRY_MS = 500;
    /** Views looked at for the LIVE on screen in the detail pager, which is deeper than a room. */
    private static final int MAX_DETAIL_VISITS = 2000;
    /**
     * TikTok putting the strip back this many times inside {@link #FIGHT_WINDOW_MS} is a layout
     * fight, and the room keeps its strip from then on. A count per visit stopped too soon: each
     * panel opened in the room (rankings, gifts, comments) puts the strip back once, so after a
     * few taps the black strip stayed (#137).
     */
    private static final int MAX_RELIFTS = 6;
    private static final long FIGHT_WINDOW_MS = 1000;
    /**
     * Most frames in a row a room holds back so a strip it took is laid out before it's drawn. One
     * is enough for each strip; the cap keeps a room drawing while TikTok fights for its strip,
     * until {@link #MAX_RELIFTS} notices the fight and leaves the strip.
     */
    private static final int MAX_HELD_FRAMES = 3;
    /** The view holding a room's stream on 47.1.4 (read off the emulator, 2026-10-11). */
    private static final String VIDEO_CONTAINER = "video_view_container";
    /** Set by tests, where TikTok's resources aren't there to name the stream's view. */
    static int videoContainerForTests;

    private static WeakReference<Application> followed = new WeakReference<>(null);
    /** The rooms whose bar this hid. */
    private static final WeakHashMap<Activity, Room> HIDDEN = new WeakHashMap<>();
    /** The detail pages in front, watched for a LIVE page. */
    private static final WeakHashMap<Activity, Detail> WATCHED = new WeakHashMap<>();

    private LiveStatusBar() {
    }

    /** Registered once per application, from the main activity the overlay hider installs on. */
    public static void follow(Activity activity) {
        Application application = activity == null ? null : activity.getApplication();
        if (application == null || followed.get() == application) return;
        followed = new WeakReference<>(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity resumed) {
                boolean hideBar = Settings.HIDE_STATUS_BAR_IN_LIVE.get();
                if (!hideBar && !Settings.LIVE_UNDER_STATUS_BAR.get()) return;
                if (isLiveRoom(resumed)) hide(resumed, hideBar);
                else if (isDetail(resumed)) watch(resumed, hideBar);
            }

            @Override public void onActivityPaused(Activity paused) {
                unwatch(paused);
                if (HIDDEN.containsKey(paused)) show(paused);
            }

            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityStopped(Activity stopped) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity destroyed) {
                HIDDEN.remove(destroyed);
                WATCHED.remove(destroyed);
            }
        });
    }

    static boolean isLiveRoom(Activity activity) {
        return activity != null && LIVE_ROOM.equals(activity.getClass().getName());
    }

    static boolean isDetail(Activity activity) {
        return activity != null && DETAIL.equals(activity.getClass().getName());
    }

    /** Watches a detail page in front for a LIVE page on screen, which is then handled like a room. */
    static void watch(Activity activity, boolean hideBar) {
        try {
            Window window = activity.getWindow();
            if (window == null || WATCHED.containsKey(activity)) return;
            Detail detail = new Detail(activity, hideBar);
            WATCHED.put(activity, detail);
            window.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(detail);
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "watch detail", failure);
            Logger.printException(() -> "Could not watch the detail page for a LIVE", failure);
        }
    }

    static void unwatch(Activity activity) {
        Detail detail = WATCHED.remove(activity);
        if (detail == null) return;
        Window window = activity.getWindow();
        if (window != null) window.getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(detail);
    }

    static boolean isHidden(Activity activity) {
        return HIDDEN.containsKey(activity);
    }

    static void hide(Activity activity) {
        hide(activity, true);
    }

    /** With {@code hideBar} false only the strip goes, and the bar stays over the stream. */
    static void hide(Activity activity, boolean hideBar) {
        hide(activity, hideBar, null);
    }

    /** {@code stream} is the LIVE page's stream in the detail pager, null for a room of its own. */
    private static void hide(Activity activity, boolean hideBar, View stream) {
        try {
            Window window = activity.getWindow();
            if (window == null || HIDDEN.containsKey(activity)) return;
            WindowManager.LayoutParams attributes = window.getAttributes();
            int cutout = Build.VERSION.SDK_INT >= 28 ? attributes.layoutInDisplayCutoutMode : 0;
            View decor = window.getDecorView();
            Room room = new Room(activity, decor, cutout, hideBar, window.getStatusBarColor(), stream);
            HIDDEN.put(activity, room);
            if (!hideBar) {
                // See through, so the stream shows under the clock. Android 15 draws every app's
                // bar see through already and ignores this; the black there is TikTok's cover.
                window.setStatusBarColor(Color.TRANSPARENT);
            }
            // Only a room that stays out of the cutout is let in. 47.1.3's LIVE window already
            // asks for ALWAYS (read off the S22), which SHORT_EDGES would narrow in landscape.
            if (hideBar && Build.VERSION.SDK_INT >= 28
                    && (cutout == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    || cutout == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER)) {
                attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(attributes);
            }
            if (hideBar) {
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController controller = decor.getWindowInsetsController();
                    if (controller != null) {
                        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                        controller.hide(WindowInsets.Type.statusBars());
                    }
                } else {
                    decor.setSystemUiVisibility(decor.getSystemUiVisibility() | LEGACY_FLAGS);
                }
            }
            // The room's views are laid out after it resumes, and its insets are only known once
            // the window is attached, so the strip is looked for on the layout passes that follow.
            decor.getViewTreeObserver().addOnGlobalLayoutListener(room);
            decor.getViewTreeObserver().addOnPreDrawListener(room.frameWatch);
            if (!hideBar) decor.getViewTreeObserver().addOnPreDrawListener(room.shadeWatch);
            HookStatus.bound(HOOK_FAMILY, hideBar ? "LIVE room status bar hidden" : "LIVE room under the status bar");
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "hide", failure);
            Logger.printException(() -> "Could not hide the status bar in a LIVE room", failure);
        }
    }

    static void show(Activity activity) {
        Room room = HIDDEN.remove(activity);
        if (room == null) return;
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            decor.getViewTreeObserver().removeOnGlobalLayoutListener(room);
            decor.getViewTreeObserver().removeOnPreDrawListener(room.frameWatch);
            decor.getViewTreeObserver().removeOnPreDrawListener(room.shadeWatch);
            room.putBack();
            if (!room.barHidden) {
                window.setStatusBarColor(room.statusBarColor);
                return;
            }
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams attributes = window.getAttributes();
                if (attributes.layoutInDisplayCutoutMode != room.cutout) {
                    attributes.layoutInDisplayCutoutMode = room.cutout;
                    window.setAttributes(attributes);
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) controller.show(WindowInsets.Type.statusBars());
            } else {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~LEGACY_FLAGS);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not bring the status bar back after a LIVE room", failure);
        }
    }

    /**
     * The heights TikTok could have kept a strip for: the status bar as it is when shown, the
     * cutout's safe inset, and the platform's own status bar height. On the S22 the room's content
     * starts 75 px down with the bar hidden, a fixed offset TikTok never takes back.
     */
    static int[] barHeights(View decor) {
        int shown = 0, cutout = 0;
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets != null && Build.VERSION.SDK_INT >= 30) {
            shown = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
        }
        if (insets != null && Build.VERSION.SDK_INT >= 28) {
            DisplayCutout displayCutout = insets.getDisplayCutout();
            if (displayCutout != null) cutout = displayCutout.getSafeInsetTop();
        }
        int platform = 0;
        Resources resources = Resources.getSystem();
        int id = resources.getIdentifier("status_bar_height", "dimen", "android");
        if (id != 0) platform = resources.getDimensionPixelSize(id);
        return new int[] {shown, cutout, platform};
    }

    private static boolean isBar(int offset, int[] bars) {
        if (offset <= 0) return false;
        for (int bar : bars) if (bar == offset) return true;
        return false;
    }

    private static int topMarginOf(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        return params instanceof ViewGroup.MarginLayoutParams ? ((ViewGroup.MarginLayoutParams) params).topMargin : 0;
    }

    /**
     * The first padding or top margin under the content view that is exactly a status bar tall,
     * level by level. Each container's own padding is checked, then its children's top margins
     * (the tallest child first), and every child that still starts at the top is looked into next.
     * One path isn't enough: on 47.1.3 the content view holds AppCompat's empty frame and TikTok's
     * room side by side, both full screen, and the room is the second.
     */
    static Offset findOffset(View content, int[] bars) {
        ArrayDeque<View> level = new ArrayDeque<>();
        level.add(content);
        int visited = 0;
        for (int depth = 0; depth < MAX_DEPTH && !level.isEmpty(); depth++) {
            ArrayDeque<View> next = new ArrayDeque<>();
            for (View at : level) {
                if (!(at instanceof ViewGroup) || visited++ >= MAX_VISITS) continue;
                ViewGroup group = (ViewGroup) at;
                if (isBar(group.getPaddingTop(), bars)) return new Offset(group, true, group.getPaddingTop());
                View margined = null;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE) continue;
                    ViewGroup.LayoutParams params = child.getLayoutParams();
                    if (params instanceof ViewGroup.MarginLayoutParams
                            && isBar(((ViewGroup.MarginLayoutParams) params).topMargin, bars)
                            && (margined == null || child.getHeight() > margined.getHeight())) {
                        margined = child;
                    }
                    if (child instanceof ViewGroup && child.getTop() == group.getPaddingTop()) next.add(child);
                }
                if (margined != null) {
                    return new Offset(margined, false, ((ViewGroup.MarginLayoutParams) margined.getLayoutParams()).topMargin);
                }
            }
            level = next;
        }
        return null;
    }

    /**
     * The strip above a LIVE page's stream in the detail pager: the nearest view on the way up that
     * is padded or held down a status bar's height. On 47.1.4 it's the page's own top padding,
     * sixteen levels under the content view, too deep for {@link #findOffset}.
     */
    static Offset stripAbove(View stream, View content, int[] bars) {
        for (View at = stream; at != null && at != content; ) {
            if (at != stream && at instanceof ViewGroup && isBar(at.getPaddingTop(), bars)) {
                return new Offset(at, true, at.getPaddingTop());
            }
            ViewGroup.LayoutParams params = at.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams && isBar(((ViewGroup.MarginLayoutParams) params).topMargin, bars)) {
                return new Offset(at, false, ((ViewGroup.MarginLayoutParams) params).topMargin);
            }
            at = at.getParent() instanceof View ? (View) at.getParent() : null;
        }
        return null;
    }

    /**
     * The room pager, on the way up from one room's stream: the first view holding two children
     * of one kind with a stream in each. On 47.1.4 that's the room pager with a page per room.
     * Null while it holds a single room.
     */
    static ViewGroup findPager(View stream, View content, int videoId) {
        for (View at = stream; at != content && at.getParent() instanceof ViewGroup; at = (View) at.getParent()) {
            ViewGroup parent = (ViewGroup) at.getParent();
            int rooms = 0;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child.getClass() == at.getClass() && child.findViewById(videoId) != null) rooms++;
            }
            if (rooms >= 2) return parent;
        }
        return null;
    }

    /**
     * The LIVE stream on screen in the detail pager, or null. The pages either side of the one in
     * view are laid out too, so only a stream across the middle of the window counts.
     */
    static View shownStream(View content, int videoId) {
        if (content.findViewById(videoId) == null) return null;
        int middle = content.getRootView().getHeight() / 2;
        int[] at = new int[2];
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(content);
        for (int visited = 0; !queue.isEmpty() && visited < MAX_DETAIL_VISITS; visited++) {
            View view = queue.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            if (view.getId() == videoId) {
                view.getLocationInWindow(at);
                if (view.getHeight() > 0 && at[1] <= middle && at[1] + view.getHeight() >= middle) return view;
            } else if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return null;
    }

    /**
     * TikTok's cover for the bar: a plain view exactly a status bar tall, across the top of the
     * room and drawn over it. On 47.1.4 under Android 16 it's the last child of the content view's
     * root (read off the emulator, 2026-10-11), so with the strip taken away the room's top row,
     * the creator and the close button, sat under a black bar. Looked for three levels down, last
     * child first, and only along views that start at the top.
     */
    static View findCover(View content, int[] bars) {
        if (!(content instanceof ViewGroup)) return null;
        ArrayDeque<ViewGroup> level = new ArrayDeque<>();
        level.add((ViewGroup) content);
        for (int depth = 0; depth < 3 && !level.isEmpty(); depth++) {
            ArrayDeque<ViewGroup> next = new ArrayDeque<>();
            for (ViewGroup group : level) {
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View child = group.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE || child.getTop() != 0) continue;
                    if (child.getClass() == View.class && child.getBackground() != null
                            && child.getWidth() == content.getWidth() && isBar(child.getHeight(), bars)) {
                        return child;
                    }
                    if (child instanceof ViewGroup) next.add((ViewGroup) child);
                }
            }
            level = next;
        }
        return null;
    }

    /**
     * TikTok's Clear display cover on a room's page: a plain view exactly a status bar tall across
     * the top of the page, shown while Clear display is on. On 47.1.4 it sits beside the layout
     * with the stream and the controls (read off the S22, 2026-10-11). Looked for three levels
     * down, only along views that start at the top.
     */
    static View findPageCover(View page, int[] bars) {
        if (!(page instanceof ViewGroup)) return null;
        ArrayDeque<ViewGroup> level = new ArrayDeque<>();
        level.add((ViewGroup) page);
        int visited = 0;
        for (int depth = 0; depth < 3 && !level.isEmpty(); depth++) {
            ArrayDeque<ViewGroup> next = new ArrayDeque<>();
            for (ViewGroup group : level) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (visited++ >= MAX_VISITS) return null;
                    View child = group.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE || child.getTop() != 0) continue;
                    if (child.getClass() == View.class && child.getBackground() != null
                            && child.getWidth() == page.getWidth() && isBar(child.getHeight(), bars)) {
                        return child;
                    }
                    if (child instanceof ViewGroup) next.add((ViewGroup) child);
                }
            }
            level = next;
        }
        return null;
    }

    /**
     * The layers beside each room's stream. On 47.1.4 a room's page holds the stream's view and,
     * beside it, a layer as big with the creator row, the chips and the comments. Every page the
     * room's pager has made is taken, so the next room is ready before it's swiped in; the search
     * stops at the level the first stream is found on.
     */
    static List<View> findControls(View root, int videoId) {
        List<View> layers = new ArrayList<>();
        ArrayDeque<View> level = new ArrayDeque<>();
        level.add(root);
        int visited = 0;
        for (int depth = 0; depth < MAX_DEPTH && !level.isEmpty(); depth++) {
            ArrayDeque<View> next = new ArrayDeque<>();
            boolean found = false;
            for (View at : level) {
                if (visited++ >= MAX_VISITS) return layers;
                if (at.getId() == videoId && at.getParent() instanceof ViewGroup) {
                    found = true;
                    ViewGroup page = (ViewGroup) at.getParent();
                    for (int i = 0; i < page.getChildCount(); i++) {
                        View child = page.getChildAt(i);
                        if (child != at && child instanceof ViewGroup && child.getVisibility() == View.VISIBLE
                                && child.getTop() == 0 && child.getHeight() == page.getHeight()) {
                            layers.add(child);
                        }
                    }
                } else if (at instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) at;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child.getVisibility() != View.GONE) next.add(child);
                    }
                }
            }
            if (found) break;
            level = next;
        }
        return layers;
    }

    /**
     * TikTok's dark shade across the top of a room's controls: a view as wide as the layer, at the
     * top of what the layer lays out, no more than a third of it tall, with a gradient behind it.
     * On 47.1.4 it sits three levels down (read off the S22, 2026-10-11). A hidden one counts too:
     * TikTok keeps it hidden for a while as a room opens, and the fill follows it.
     */
    static View findShade(ViewGroup layer) {
        ArrayDeque<View> level = new ArrayDeque<>();
        for (int i = 0; i < layer.getChildCount(); i++) level.add(layer.getChildAt(i));
        int visited = 0;
        for (int depth = 0; depth < 4 && !level.isEmpty(); depth++) {
            ArrayDeque<View> next = new ArrayDeque<>();
            for (View at : level) {
                if (visited++ >= MAX_VISITS) return null;
                if (at.getVisibility() == View.GONE || topIn(at, layer) != layer.getPaddingTop()) continue;
                if (at.getBackground() instanceof GradientDrawable && at.getWidth() * 10 >= layer.getWidth() * 9
                        && at.getHeight() > 0 && at.getHeight() * 3 <= layer.getHeight()) {
                    return at;
                }
                if (at instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) at;
                    for (int i = 0; i < group.getChildCount(); i++) next.add(group.getChildAt(i));
                }
            }
            level = next;
        }
        return null;
    }

    private static int topIn(View view, View layer) {
        int top = 0;
        for (View at = view; at != layer; ) {
            top += at.getTop();
            if (!(at.getParent() instanceof View)) return -1;
            at = (View) at.getParent();
        }
        return top;
    }

    /**
     * The color a top-to-bottom shade starts with, as drawn: its own alpha and the view's taken in.
     * 0 when the shade isn't a plain vertical gradient this can read.
     */
    static int topColor(View shade) {
        if (!(shade.getBackground() instanceof GradientDrawable)) return 0;
        GradientDrawable gradient = (GradientDrawable) shade.getBackground();
        return asDrawn(firstColor(gradient), gradient.getAlpha(), shade.getAlpha());
    }

    /** The color a top-to-bottom gradient starts with, 0 when it isn't a plain vertical one this can read. */
    static int firstColor(GradientDrawable gradient) {
        if (Build.VERSION.SDK_INT < 24) return 0;
        int[] colors = gradient.getColors();
        if (colors == null || colors.length == 0 || gradient.getGradientType() != GradientDrawable.LINEAR_GRADIENT) return 0;
        if (gradient.getOrientation() == GradientDrawable.Orientation.TOP_BOTTOM) return colors[0];
        if (gradient.getOrientation() == GradientDrawable.Orientation.BOTTOM_TOP) return colors[colors.length - 1];
        return 0;
    }

    private static int asDrawn(int color, int drawableAlpha, float viewAlpha) {
        int alpha = Math.round(Color.alpha(color) * (drawableAlpha / 255f) * viewAlpha);
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    /**
     * Fills the top of a controls layer held a bar lower with the color TikTok's shade starts with,
     * so the strip under the clock is shaded like the rest of the top instead of showing as a
     * lighter band above it (#137). It follows the shade: nothing while TikTok keeps the shade
     * hidden, and the shade's own fade as it shows.
     */
    static final class BarShade extends Drawable {
        final int height;
        private final WeakReference<View> shade;
        private final Paint paint = new Paint();
        /** The shade's background {@link #first} was read from, read again if TikTok swaps it. */
        private Drawable read;
        private int first;
        /** The color the strip was last drawn in. */
        private int drawn;

        BarShade(View shade, int height) {
            this.shade = new WeakReference<>(shade);
            this.height = height;
        }

        /** The strip's color now: the shade's first color as drawn, or 0 while the shade is hidden. */
        int color() {
            View at = shade.get();
            if (at == null || !at.isShown() || at.getWidth() == 0 || at.getHeight() == 0) return 0;
            Drawable background = at.getBackground();
            if (!(background instanceof GradientDrawable)) return 0;
            if (background != read) {
                read = background;
                first = firstColor((GradientDrawable) background);
            }
            return asDrawn(first, background.getAlpha(), drawnAlpha(at));
        }

        /**
         * The shade's alpha times that of every view between it and the layer this fills. Clear
         * display in a LIVE fades the controls, and a strip that went by the shade's own alpha
         * alone stayed shaded under the clock once the rest of the top was clear (#137).
         */
        private float drawnAlpha(View at) {
            View layer = getCallback() instanceof View ? (View) getCallback() : null;
            if (layer == null) return at.getAlpha();
            float alpha = 1f;
            for (View up = at; up != null && up != layer; up = up.getParent() instanceof View ? (View) up.getParent() : null) {
                alpha *= up.getAlpha();
            }
            return alpha;
        }

        /**
         * Whether the shade it follows is still laid out. On 47.1.4 TikTok shows one shade as a
         * room opens and swaps it for another a moment later, putting the first out of the layout,
         * so a fill left on the first stayed clear over a shaded room (S22, #137).
         */
        boolean following() {
            View at = shade.get();
            if (at == null || !at.isAttachedToWindow()) return false;
            for (View up = at; up != null; up = up.getParent() instanceof View ? (View) up.getParent() : null) {
                if (up.getVisibility() == View.GONE) return false;
            }
            return true;
        }

        /** Whether the shade showed, hid or faded since the strip was last drawn. */
        boolean changed() {
            return color() != drawn;
        }

        @Override public void draw(Canvas canvas) {
            drawn = color();
            if (Color.alpha(drawn) == 0) return;
            paint.setColor(drawn);
            android.graphics.Rect bounds = getBounds();
            canvas.drawRect(bounds.left, bounds.top, bounds.right, Math.min(bounds.bottom, bounds.top + height), paint);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** A padding or margin this took away, with the height it had. */
    static final class Offset {
        final WeakReference<View> view;
        final boolean padding;
        final int value;

        Offset(View view, boolean padding, int value) {
            this.view = new WeakReference<>(view);
            this.padding = padding;
            this.value = value;
        }

        int current(View target) {
            if (padding) return target.getPaddingTop();
            ViewGroup.LayoutParams params = target.getLayoutParams();
            return params instanceof ViewGroup.MarginLayoutParams ? ((ViewGroup.MarginLayoutParams) params).topMargin : -1;
        }

        void set(View target, int top) {
            if (padding) {
                target.setPadding(target.getPaddingLeft(), top, target.getPaddingRight(), target.getPaddingBottom());
            } else {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) target.getLayoutParams();
                params.topMargin = top;
                target.setLayoutParams(params);
            }
        }
    }

    /** One visit to a LIVE room with its bar hidden. It holds its views weakly. */
    /** The shade searches spent on one layer and when the last one ran. */
    private static final class ShadeSearches {
        int spent;
        long last;

        @Override public String toString() {
            return spent + " last at " + last;
        }
    }

    private static final class Room implements ViewTreeObserver.OnGlobalLayoutListener {
        final int cutout;
        /** Whether the bar was hidden, or only the strip taken away under a bar left showing. */
        final boolean barHidden;
        /** The bar's color as TikTok set it, given back as the room leaves. */
        final int statusBarColor;
        private final WeakReference<Activity> activity;
        private final WeakReference<View> decor;
        /** The LIVE page's stream in the detail pager, or nothing for a room of its own. */
        final WeakReference<View> stream;
        /** The strip found by the search over the window, where there's no stream to go by. */
        private Offset lifted;
        /**
         * The strips taken from above each room's stream, by the view that held them. On the S22
         * every room is a page of the room pager, and the pager pads a page as it binds a room to
         * it, so some rooms kept a black band under the clock (#137).
         */
        private final WeakHashMap<View, Offset> strips = new WeakHashMap<>();
        /** The room pager, found from a stream once it holds two rooms. */
        private WeakReference<ViewGroup> pager = new WeakReference<>(null);
        /** The height of the last strip taken from above a room's stream, 0 before one is. */
        private int stripHeight;
        /**
         * The room pager's pages without a stream, held a bar lower under a bar left showing, with
         * the top padding each had of its own and how much lower it went. The LIVE feed's Discover
         * panel above the first room is such a page: it went up under the clock with the rooms, or
         * sat under TikTok's cover that's hidden now, and its top row was hard to reach (#137).
         */
        private final WeakHashMap<View, int[]> loweredPages = new WeakHashMap<>();
        /**
         * Pager pages already described in the log: a room's page with where its stream starts, and
         * a page without a stream left where TikTok put it, with why. On an S23 Ultra the Discover
         * panel still reached under the clock and no page was padded, while on the S22 it stays
         * below the clock, so the log has to say which kind of page holds it there (#137).
         */
        private final WeakHashMap<View, Boolean> pagesLogged = new WeakHashMap<>();
        private int searches, relifts, coverSearches;
        /**
         * Shade searches spent on each layer held lower. One budget for the whole visit ran out
         * in the first few rooms, so rooms further on kept a light strip under the clock (#137).
         */
        private final WeakHashMap<View, ShadeSearches> shadeSearches = new WeakHashMap<>();
        private boolean shadeWatchFailed;
        /** Redraws a strip whose shade TikTok showed, hid or faded since the last frame. */
        final ViewTreeObserver.OnPreDrawListener shadeWatch = () -> {
            if (this.left || shadeWatchFailed) return true;
            try {
                for (View layer : this.lowered.keySet()) {
                    Drawable background = layer == null ? null : layer.getBackground();
                    if (background instanceof BarShade && ((BarShade) background).changed()) layer.invalidate();
                }
            } catch (Throwable failure) {
                shadeWatchFailed = true;
                HookStatus.threw(HOOK_FAMILY, "shade watch", failure);
                Logger.printException(() -> "Could not follow the LIVE room's top shade", failure);
            }
            return true;
        };
        /**
         * TikTok's covers over the top of a room's page, hidden while its strip is away. Clear
         * display (a swipe right in the room) shows a black view a bar tall across the top of the
         * page, so the band under the clock came back over the stream (#137, read off the S22).
         */
        private final WeakHashMap<View, Boolean> pageCovers = new WeakHashMap<>();
        private boolean frameWatchFailed;
        /**
         * Set when this moved a padding or margin since the last frame. A strip taken on a layout
         * pass only moves the stream up on the next one, so the frame drawn in between showed the
         * strip for a moment (#137, BynchPOP on 0.73.0); that frame is held back instead.
         */
        private boolean relaid;
        private int heldFrames;
        /** The bar heights the last layout pass went by, for the strips taken before a frame. */
        private int[] lastBars;
        /**
         * Runs before each frame is drawn. It takes a strip TikTok put back with no layout pass of
         * the window, as a pager does when it binds a room to a page mid-swipe, and hides a cover
         * TikTok showed again, then holds the frame back until what changed is laid out (#137).
         */
        final ViewTreeObserver.OnPreDrawListener frameWatch = this::beforeFrame;
        private long fightStart;
        private boolean fought;
        /**
         * Set once everything is given back. A layout pass calls the listeners it started with, so
         * a room the detail watcher let go during that pass would otherwise take the strip again.
         */
        private boolean left;
        private WeakReference<View> cover = new WeakReference<>(null);
        /** The controls layers held a bar lower, with the top padding each had of its own. */
        private final WeakHashMap<View, Integer> lowered = new WeakHashMap<>();
        private int loweredBy;
        /** The stream's view id, -1 until it's been asked for and 0 when TikTok has none. */
        private int videoId = -1;

        Room(Activity activity, View decor, int cutout, boolean barHidden, int statusBarColor, View stream) {
            this.activity = new WeakReference<>(activity);
            this.decor = new WeakReference<>(decor);
            this.cutout = cutout;
            this.barHidden = barHidden;
            this.statusBarColor = statusBarColor;
            this.stream = stream == null ? null : new WeakReference<>(stream);
        }

        @Override public void onGlobalLayout() {
            if (left) return;
            try {
                keepCoverAway();
                keepStripAway();
                keepPageCoversAway();
                if (!barHidden) keepControlsBelowBar();
            } catch (Throwable failure) {
                searches = MAX_SEARCHES;
                coverSearches = MAX_SEARCHES;
                videoId = 0;
                HookStatus.threw(HOOK_FAMILY, "top strip", failure);
                Logger.printException(() -> "Could not take away the LIVE room's top strip", failure);
            }
        }

        private void keepStripAway() {
            if (stream == null && videoId() != 0) {
                keepRoomStripsAway();
                return;
            }
            if (lifted != null) {
                View target = lifted.view.get();
                // TikTok put the strip back, on a relayout of its own: a panel opening or
                // closing in the room does it.
                if (target == null || fought || lifted.current(target) != lifted.value || !mayRelift()) return;
                lifted.set(target, 0);
                relaid = true;
                return;
            }
            Activity room = activity.get();
            View window = decor.get();
            if (room == null || window == null || searches >= MAX_SEARCHES) return;
            View content = room.findViewById(android.R.id.content);
            if (content == null || content.getHeight() == 0) return;
            searches++;
            Offset offset = stream == null ? findOffset(content, barHeights(window))
                    : stripAbove(stream.get(), content, barHeights(window));
            if (offset == null) return;
            View target = offset.view.get();
            if (target == null) return;
            lifted = offset;
            offset.set(target, 0);
            relaid = true;
            HookStatus.bound(HOOK_FAMILY, "LIVE room top strip taken away");
        }

        /**
         * A room of its own: the strip is looked for just above each room's stream, on every page
         * of the room pager and on every pass, since a page gets its strip as a room is bound to
         * it. The search over the window took the first view held down a bar's height, which on
         * 47.1.4 is a panel shown while a room opens, and left every room page as it was.
         */
        private void keepRoomStripsAway() {
            Activity room = activity.get();
            View window = decor.get();
            if (fought || room == null || window == null) return;
            View content = room.findViewById(android.R.id.content);
            if (content == null || content.getHeight() == 0) return;
            ViewGroup rooms = pager.get();
            if (rooms == null || !rooms.isAttachedToWindow()) {
                View first = content.findViewById(videoId);
                if (first == null) return;
                rooms = findPager(first, content, videoId);
                pager = new WeakReference<>(rooms);
                if (rooms == null) {
                    lastBars = barHeights(window);
                    liftAbove(first, content, lastBars);
                    return;
                }
                ViewGroup found = rooms;
                Logger.printInfo(() -> "LIVE room pager " + found.getClass().getName()
                        + " holds " + found.getChildCount() + " pages");
            }
            int[] bars = barHeights(window);
            lastBars = bars;
            for (int i = 0; i < rooms.getChildCount() && !fought; i++) {
                View page = rooms.getChildAt(i);
                View shown = page.findViewById(videoId);
                if (shown == null) continue;
                forgetPage(page);
                liftAbove(shown, content, bars);
                logRoomPage(page, i, shown);
            }
            if (barHidden || fought) return;
            for (int i = 0; i < rooms.getChildCount(); i++) {
                View page = rooms.getChildAt(i);
                if (page.findViewById(videoId) != null) continue;
                if (stripHeight > 0) {
                    keepPageBelowBar(page, i, bars);
                } else {
                    leftAlone(page, i, "no room's strip taken yet", bars);
                }
            }
        }

        /** Takes the strip above one room's stream, once, or again when TikTok has put it back. */
        private void liftAbove(View shown, View content, int[] bars) {
            Offset strip = stripAbove(shown, content, bars);
            View target = strip == null ? null : strip.view.get();
            if (target == null) return;
            if (strips.containsKey(target) && !mayRelift()) return;
            strips.put(target, strip);
            strip.set(target, 0);
            relaid = true;
            stripHeight = strip.value;
            HookStatus.bound(HOOK_FAMILY, "LIVE room top strip taken away");
        }

        /**
         * Under a bar left showing, a page of the room pager with no stream in it, like the LIVE
         * feed's Discover panel, is held a strip's height lower so its top row stays clear of the
         * clock (#137). A page already held down a bar's height of its own is left alone, and one
         * TikTok gives a padding of its own is let be.
         */
        private void keepPageBelowBar(View page, int index, int[] bars) {
            int[] held = loweredPages.get(page);
            if (held == null) {
                if (isBar(page.getPaddingTop(), bars) || isBar(topMarginOf(page), bars)) {
                    leftAlone(page, index, "already a bar lower", bars);
                    return;
                }
                held = new int[] {page.getPaddingTop(), stripHeight};
                loweredPages.put(page, held);
                if (loweredPages.size() == 1) HookStatus.bound(HOOK_FAMILY, "LIVE feed panel kept below the bar");
                int own = held[0], by = held[1];
                Logger.printInfo(() -> "LIVE under the status bar: padded room pager page " + index + " ("
                        + page.getClass().getName() + ", no stream, " + page.getWidth() + "x" + page.getHeight()
                        + ") from " + own + " to " + (own + by) + " px at the top");
            } else if (page.getPaddingTop() != held[0]) {
                // Already a bar lower, or TikTok set a padding of its own.
                return;
            }
            page.setPadding(page.getPaddingLeft(), held[0] + held[1], page.getPaddingRight(), page.getPaddingBottom());
            relaid = true;
        }

        /**
         * Logs, once per page, where a room's page and its stream start on screen. A stream far
         * below its page's top means something else, like the Discover panel, is in the page above it.
         */
        private void logRoomPage(View page, int index, View shown) {
            if (page.getHeight() == 0 || pagesLogged.put(page, Boolean.TRUE) != null) return;
            int[] pageAt = new int[2];
            int[] streamAt = new int[2];
            page.getLocationOnScreen(pageAt);
            shown.getLocationOnScreen(streamAt);
            Logger.printInfo(() -> "LIVE under the status bar: room pager page " + index + " ("
                    + page.getClass().getName() + ", " + page.getWidth() + "x" + page.getHeight() + " at y "
                    + pageAt[1] + ", padding " + page.getPaddingTop() + ") has its stream at y " + streamAt[1]);
        }

        /** Logs, once per page, a page without a stream that stays where TikTok put it, and why. */
        private void leftAlone(View page, int index, String why, int[] bars) {
            if (pagesLogged.put(page, Boolean.TRUE) != null) return;
            int padding = page.getPaddingTop(), margin = topMarginOf(page);
            int[] screen = new int[2];
            page.getLocationOnScreen(screen);
            Logger.printInfo(() -> "LIVE under the status bar: left room pager page " + index + " ("
                    + page.getClass().getName() + ", no stream, " + page.getWidth() + "x" + page.getHeight()
                    + " at y " + screen[1] + ") as it was, " + why + ": padding " + padding + ", margin "
                    + margin + ", bars " + java.util.Arrays.toString(bars));
        }

        /**
         * Lets go of a page held lower that the pager has since bound a room to. A room's page is
         * padded a bar by TikTok as it's bound, and the strip above its stream is taken from there,
         * so only a padding of the page's own that isn't a bar is given back.
         */
        private void forgetPage(View page) {
            int[] held = loweredPages.remove(page);
            if (held == null || held[0] == 0 || page.getPaddingTop() != held[0] + held[1]) return;
            page.setPadding(page.getPaddingLeft(), held[0], page.getPaddingRight(), page.getPaddingBottom());
            relaid = true;
        }

        /** Gives every page held lower its own padding back unless TikTok has set another. */
        private void raisePages() {
            for (Map.Entry<View, int[]> entry : loweredPages.entrySet()) {
                View page = entry.getKey();
                int[] held = entry.getValue();
                if (page != null && page.getPaddingTop() == held[0] + held[1]) {
                    page.setPadding(page.getPaddingLeft(), held[0], page.getPaddingRight(), page.getPaddingBottom());
                }
            }
            loweredPages.clear();
        }

        /** Counts a strip TikTok put back; false once it has done so too often to keep up with. */
        private boolean mayRelift() {
            long now = SystemClock.uptimeMillis();
            if (now - fightStart > FIGHT_WINDOW_MS) {
                fightStart = now;
                relifts = 0;
            }
            if (++relifts > MAX_RELIFTS) {
                fought = true;
                Logger.printDebug(() -> "TikTok keeps putting the LIVE room's top strip back; leaving it");
                return false;
            }
            return true;
        }

        /** {@link #frameWatch}: false holds the frame back. */
        private boolean beforeFrame() {
            if (left || frameWatchFailed) return true;
            try {
                boolean hold = hidePageCovers();
                hold |= hideCover();
                if (keepStripsAwayBeforeDraw() && !barHidden) keepControlsBelowBar();
                hold |= relaid;
                relaid = false;
                if (hold && heldFrames < MAX_HELD_FRAMES) {
                    heldFrames++;
                    return false;
                }
                heldFrames = 0;
                return true;
            } catch (Throwable failure) {
                frameWatchFailed = true;
                HookStatus.threw(HOOK_FAMILY, "frame watch", failure);
                Logger.printException(() -> "Could not keep the LIVE room's top strip out of a frame", failure);
                return true;
            }
        }

        /**
         * Takes, before a frame, a strip TikTok put back since the last layout pass: one this took
         * before, and the strip of a page the room pager added or bound as it scrolled, which it
         * lays out itself with no layout pass of the window. True when a strip was taken.
         */
        private boolean keepStripsAwayBeforeDraw() {
            if (fought) return false;
            boolean laidOutBefore = relaid;
            relaid = false;
            View target = lifted == null ? null : lifted.view.get();
            if (target != null && lifted.current(target) == lifted.value && mayRelift()) {
                lifted.set(target, 0);
                relaid = true;
            }
            for (Map.Entry<View, Offset> entry : strips.entrySet()) {
                View held = entry.getKey();
                Offset strip = entry.getValue();
                if (held == null || fought || strip.current(held) != strip.value || !mayRelift()) continue;
                strip.set(held, 0);
                relaid = true;
            }
            ViewGroup rooms = pager.get();
            Activity room = activity.get();
            int[] bars = lastBars;
            if (!fought && rooms != null && room != null && bars != null && videoId > 0 && rooms.isAttachedToWindow()) {
                View content = room.findViewById(android.R.id.content);
                for (int i = 0; content != null && i < rooms.getChildCount() && !fought; i++) {
                    View shown = rooms.getChildAt(i).findViewById(videoId);
                    if (shown != null) liftAbove(shown, content, bars);
                }
            }
            boolean took = relaid;
            relaid |= laidOutBefore;
            return took;
        }

        /** Hides the room's cover over the bar if TikTok showed it again; true when it did. */
        private boolean hideCover() {
            View known = cover.get();
            if (known == null || known.getVisibility() != View.VISIBLE || !known.isAttachedToWindow()) return false;
            known.setVisibility(View.INVISIBLE);
            return true;
        }

        /** Finds the cover on each page whose strip is away and hides every cover found so far. */
        private void keepPageCoversAway() {
            View window = decor.get();
            if (window == null) return;
            int[] bars = null;
            for (View page : liftedTargets()) {
                if (!page.isAttachedToWindow()) continue;
                if (bars == null) bars = barHeights(window);
                View found = findPageCover(page, bars);
                if (found != null && pageCovers.put(found, Boolean.TRUE) == null) {
                    HookStatus.bound(HOOK_FAMILY, "LIVE room Clear display cover taken away");
                }
            }
            hidePageCovers();
        }

        /** Hides the page covers TikTok is showing; true when one was. */
        private boolean hidePageCovers() {
            boolean hid = false;
            for (View known : pageCovers.keySet()) {
                if (known != null && known.getVisibility() == View.VISIBLE && known.isAttachedToWindow()) {
                    known.setVisibility(View.INVISIBLE);
                    hid = true;
                }
            }
            return hid;
        }

        /** The views that hold a strip this took away and haven't had it put back. */
        private List<View> liftedTargets() {
            List<View> targets = new ArrayList<>();
            View target = lifted == null ? null : lifted.view.get();
            if (target != null && lifted.current(target) == 0) targets.add(target);
            for (Map.Entry<View, Offset> entry : strips.entrySet()) {
                View held = entry.getKey();
                if (held != null && entry.getValue().current(held) == 0) targets.add(held);
            }
            return targets;
        }

        /** The stream's view id, looked up once; 0 when TikTok has none. */
        private int videoId() {
            if (videoId < 0) {
                Activity room = activity.get();
                if (room == null) return 0;
                videoId = videoContainerForTests != 0 ? videoContainerForTests
                        : room.getResources().getIdentifier(VIDEO_CONTAINER, "id", room.getPackageName());
                if (videoId == 0) HookStatus.missingViewId(HOOK_FAMILY, VIDEO_CONTAINER);
            }
            return videoId;
        }

        /**
         * Hides TikTok's cover once it's found, and again whenever it shows. Invisible rather than
         * see through, so it can't take the taps meant for the creator row under it; a cover TikTok
         * has swapped for a new one is let go and the new one looked for.
         */
        private void keepCoverAway() {
            View known = cover.get();
            if (known != null && !known.isAttachedToWindow()) {
                known = null;
                cover = new WeakReference<>(null);
                coverSearches = 0;
            }
            if (known == null) {
                Activity room = activity.get();
                View window = decor.get();
                if (room == null || window == null || coverSearches >= MAX_SEARCHES) return;
                View content = room.findViewById(android.R.id.content);
                if (content == null || content.getHeight() == 0) return;
                coverSearches++;
                known = findCover(content, barHeights(window));
                if (known == null) return;
                cover = new WeakReference<>(known);
                HookStatus.bound(HOOK_FAMILY, "LIVE room bar cover taken away");
            }
            if (known.getVisibility() == View.VISIBLE) known.setVisibility(View.INVISIBLE);
        }

        /**
         * Under a bar left showing, only the stream goes up to the top edge: the room's controls
         * are held a bar lower again, where TikTok had them, so the creator row and the close
         * button stay clear of the clock (#137). With the strip back, they go back up with it.
         */
        private void keepControlsBelowBar() {
            List<View> targets = liftedTargets();
            if (targets.isEmpty()) {
                raiseControls();
                return;
            }
            if (videoId() == 0) return;
            for (View target : targets) {
                Offset strip = strips.get(target);
                int by = strip != null ? strip.value : lifted.value;
                for (View layer : findControls(target, videoId)) {
                    Integer own = lowered.get(layer);
                    if (own == null) {
                        own = layer.getPaddingTop();
                        lowered.put(layer, own);
                        if (lowered.size() == 1) HookStatus.bound(HOOK_FAMILY, "LIVE room controls kept below the bar");
                    } else if (layer.getPaddingTop() != own) {
                        // Already a bar lower, or TikTok set a padding of its own.
                        continue;
                    }
                    loweredBy = by;
                    layer.setPadding(layer.getPaddingLeft(), own + loweredBy, layer.getPaddingRight(), layer.getPaddingBottom());
                    relaid = true;
                }
            }
            shadeUnderBar();
        }

        /**
         * TikTok's top shade is part of the controls, so it went a bar lower with them and the
         * strip under the clock showed lighter than the rest of the top (#137). Each layer held
         * lower gets a fill across that strip that follows the shade once it's found, and is
         * looked for again when TikTok puts that shade out of the layout for another; a layer
         * with a background of TikTok's own is left alone.
         */
        private void shadeUnderBar() {
            for (Map.Entry<View, Integer> entry : lowered.entrySet()) {
                View layer = entry.getKey();
                if (!(layer instanceof ViewGroup) || layer.getPaddingTop() != entry.getValue() + loweredBy) continue;
                Drawable background = layer.getBackground();
                if (background instanceof BarShade) {
                    BarShade fill = (BarShade) background;
                    if (fill.height == loweredBy && fill.following()) continue;
                } else if (background != null) {
                    continue;
                }
                // Until it's laid out a bar lower nothing in it is where the shade is looked for,
                // and a room scrolled away can't show a strip.
                if (layer.isLayoutRequested() || !layer.isAttachedToWindow()) continue;
                ShadeSearches searches = shadeSearches.get(layer);
                long now = SystemClock.uptimeMillis();
                if (searches == null) {
                    searches = new ShadeSearches();
                    shadeSearches.put(layer, searches);
                } else if (searches.spent >= MAX_SEARCHES || now - searches.last < SHADE_RETRY_MS) {
                    continue;
                }
                searches.spent++;
                searches.last = now;
                View shade = findShade((ViewGroup) layer);
                if (shade == null || Color.alpha(firstColor((GradientDrawable) shade.getBackground())) == 0) continue;
                layer.setBackground(new BarShade(shade, loweredBy));
                HookStatus.bound(HOOK_FAMILY, "LIVE room shade kept under the bar");
            }
        }

        /** Gives the controls their own padding back unless TikTok has set another. */
        private void raiseControls() {
            for (Map.Entry<View, Integer> entry : lowered.entrySet()) {
                View layer = entry.getKey();
                int own = entry.getValue();
                if (layer != null && layer.getBackground() instanceof BarShade) layer.setBackground(null);
                if (layer != null && layer.getPaddingTop() == own + loweredBy) {
                    layer.setPadding(layer.getPaddingLeft(), own, layer.getPaddingRight(), layer.getPaddingBottom());
                }
            }
            lowered.clear();
        }

        /** Gives the strip, the cover and the controls back unless TikTok has already set its own. */
        void putBack() {
            left = true;
            View known = cover.get();
            if (known != null && known.getVisibility() == View.INVISIBLE) known.setVisibility(View.VISIBLE);
            cover = new WeakReference<>(null);
            for (View pageCover : pageCovers.keySet()) {
                if (pageCover != null && pageCover.getVisibility() == View.INVISIBLE) pageCover.setVisibility(View.VISIBLE);
            }
            pageCovers.clear();
            raiseControls();
            raisePages();
            for (Map.Entry<View, Offset> entry : strips.entrySet()) {
                View held = entry.getKey();
                if (held != null && entry.getValue().current(held) == 0) entry.getValue().set(held, entry.getValue().value);
            }
            strips.clear();
            pager = new WeakReference<>(null);
            if (lifted == null) return;
            View target = lifted.view.get();
            if (target != null && lifted.current(target) == 0) lifted.set(target, lifted.value);
            lifted = null;
        }
    }

    /**
     * A detail page in front, watched for a LIVE page on screen. The LIVE page is handled like a
     * room while it's in view, and given back when it's swiped away or another LIVE takes its place.
     */
    private static final class Detail implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<Activity> activity;
        private final boolean hideBar;
        /** The stream's view id, -1 until it's been asked for and 0 when TikTok has none. */
        private int videoId = -1;

        Detail(Activity activity, boolean hideBar) {
            this.activity = new WeakReference<>(activity);
            this.hideBar = hideBar;
        }

        @Override public void onGlobalLayout() {
            try {
                Activity page = activity.get();
                if (page == null) return;
                if (videoId < 0) {
                    videoId = videoContainerForTests != 0 ? videoContainerForTests
                            : page.getResources().getIdentifier(VIDEO_CONTAINER, "id", page.getPackageName());
                    if (videoId == 0) HookStatus.missingViewId(HOOK_FAMILY, VIDEO_CONTAINER);
                }
                if (videoId == 0) return;
                View content = page.findViewById(android.R.id.content);
                View shown = content == null ? null : shownStream(content, videoId);
                Room room = HIDDEN.get(page);
                if (room != null && shown != null && room.stream != null && room.stream.get() == shown) return;
                if (room != null) show(page);
                if (shown == null) return;
                hide(page, hideBar, shown);
                room = HIDDEN.get(page);
                // The room's own listener joins from the next layout pass; this one is now.
                if (room != null) room.onGlobalLayout();
            } catch (Throwable failure) {
                videoId = 0;
                HookStatus.threw(HOOK_FAMILY, "detail LIVE", failure);
                Logger.printException(() -> "Could not handle a LIVE in the detail page", failure);
            }
        }
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.interaction.StoryControls;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Seek bar on video stories (#72): a line along the bottom of a video story that can be dragged
 * to go back or skip ahead.
 *
 * <p>Three hooks feed it. The video story cell's root component reports the view it built
 * ({@link #onCellView}) and each story it is bound to ({@link #onCellBind}). PlayerController,
 * the class every story panel plays through, reports each progress tick with the story's id,
 * the position and the length ({@link #onProgress}). The tick whose id is the cell's story puts
 * the line into that cell's rounded video frame, and every later tick moves it.
 *
 * <p>A drag ends in TikTok's own seek: the controller's player manager, whose {@code seek(F)}
 * takes a percent from 0 to 100, the same call the feed's progress bar makes when you let go of
 * it. It only goes ahead while the cell still shows the story the line was built for, that
 * controller's last tick was for it, and the controller's current id says the same.
 *
 * <p>Off, or with Hushfeed paused (every switch then answers off), the hooks only remember which
 * view and story each cell has, and the next tick takes out any line already there.
 */
@SuppressWarnings("unused")
public final class StorySeekbar {
    static final String FAMILY = "story seek bar";
    /** TikTok's rounded video frame, a real class name, the direct child of the story cell's root. */
    static final String VIDEO_FRAME = "com.bytedance.tux.widget.RadiusLayout";
    /** How far below the cell's root the frame is looked for. 47.1.4 puts it one level down. */
    static final int FRAME_DEPTH = 3;
    /** Seeking to the very end finishes the story and moves on, so a drag stops just short of it. */
    static final float LAST_PERCENT = 99f;

    private static final Map<Object, Cell> CELLS = new WeakHashMap<>();
    /** The story each player controller last reported, so a seek reaches only the player showing it. */
    private static final Map<Object, String> PLAYING = new WeakHashMap<>();
    private static boolean anyLine;

    private StorySeekbar() {
    }

    /** What one video story cell holds: its view, its story, and the line when it has one. */
    static final class Cell {
        WeakReference<View> root = new WeakReference<>(null);
        WeakReference<Object> params = new WeakReference<>(null);
        /**
         * Held weakly, like the rest: the line's frame is in the cell's views, and TikTok's views
         * lead back to the component that keys {@link #CELLS}, so a line held here kept a closed
         * story viewer's component, views and Activity for as long as the switch stayed on.
         */
        WeakReference<StorySeekbarView> line = new WeakReference<>(null);
        /** The root was searched and holds no single video frame, so it is not searched again. */
        boolean noFrame;

        private WeakReference<Object> story = new WeakReference<>(null);
        @Nullable private String aid;
        private boolean photo;

        /** The id of the video story the cell is bound to, or null for a photo story or none. */
        @Nullable String videoAid() {
            Object params = this.params.get();
            if (params == null) return null;
            Object aweme = Reflect.required(params, "getAweme", FAMILY);
            if (aweme == null) return null;
            if (story.get() != aweme) {
                story = new WeakReference<>(aweme);
                aid = Reflect.string(aweme, "getAid", "aid");
                photo = StoryControls.isPhoto(aweme);
            }
            return photo ? null : aid;
        }
    }

    public static boolean isEnabled() {
        return Settings.STORY_SEEKBAR.get();
    }

    /** The entry of the story cell's onViewCreated, with the view the cell built. */
    public static void onCellView(@Nullable Object component, @Nullable View root) {
        if (component == null || root == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Utils.runOnMainThread(() -> onCellView(component, root));
            return;
        }
        try {
            Cell cell = cellOf(component);
            if (cell.root.get() == root) return;
            detach(cell);
            cell.root = new WeakReference<>(root);
            cell.noFrame = false;
        } catch (RuntimeException failure) {
            HookStatus.threw(FAMILY, "story cell view", failure);
        }
    }

    /** The entry of the story cell's bind, with the VideoItemParams it is about to show. */
    public static void onCellBind(@Nullable Object component, @Nullable Object params) {
        if (component == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Utils.runOnMainThread(() -> onCellBind(component, params));
            return;
        }
        try {
            Cell cell = cellOf(component);
            if (cell.params.get() == params) return;
            cell.params = new WeakReference<>(params);
            StorySeekbarView line = cell.line.get();
            // The same story bound again keeps its line. Any other story starts without one,
            // until its own first tick.
            if (line != null && !line.aid.equals(cell.videoAid())) detach(cell);
        } catch (RuntimeException failure) {
            HookStatus.threw(FAMILY, "story cell bind", failure);
        }
    }

    /** The entry of PlayerController.onPlayProgressChange: the story's id, the position, the length. */
    public static void onProgress(@Nullable Object controller, @Nullable String aid,
                                  long positionMs, long durationMs) {
        if (controller == null) return;
        // Every player's ticks come through here, the feed's too. Off with no line left there's
        // nothing to do, so a tick from another thread isn't posted to the main one.
        if (!anyLine && !isEnabled()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Utils.runOnMainThread(() -> onProgress(controller, aid, positionMs, durationMs));
            return;
        }
        try {
            if (!isEnabled()) {
                if (anyLine) detachAll();
                return;
            }
            PLAYING.put(controller, aid);
            if (aid == null || CELLS.isEmpty()) return;
            for (Cell cell : new ArrayList<>(CELLS.values())) {
                StorySeekbarView line = cell.line.get();
                if (aid.equals(cell.videoAid()) && durationMs > 0L) {
                    if (line == null) line = attach(cell, aid);
                    if (line != null) line.update(controller, positionMs, durationMs);
                } else if (line != null && line.isDrivenBy(controller)) {
                    // This player has moved on to another story, or the story has no length.
                    detach(cell);
                }
            }
        } catch (RuntimeException failure) {
            HookStatus.threw(FAMILY, "story progress", failure);
        }
    }

    /**
     * Moves the story's player to this percent of the story. Refused, and false, unless the cell
     * still shows the story the line was built for, the controller's last tick was for that story
     * and the controller's current id agrees.
     */
    static boolean seek(Cell cell, @Nullable Object controller, String aid, float percent) {
        if (controller == null || !isEnabled()) return false;
        if (!aid.equals(cell.videoAid())) {
            Logger.printDebug(() -> "Story seek bar: the cell shows another story now");
            return false;
        }
        if (!aid.equals(PLAYING.get(controller))) {
            Logger.printDebug(() -> "Story seek bar: the player has moved on from the story");
            return false;
        }
        Object current = Reflect.requiredField(controller, "mCurrentAid", FAMILY);
        if (current != null && !aid.equals(current)) {
            Logger.printDebug(() -> "Story seek bar: the controller is on another story");
            return false;
        }
        Object manager = Reflect.required(controller, "getPlayerManager", FAMILY);
        if (manager == null) return false;
        Method seek = Reflect.requiredMethod(manager.getClass(), "seek", FAMILY, float.class);
        if (seek == null) return false;
        try {
            seek.invoke(manager, seekPercent(percent));
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "story seek", failure);
            return false;
        }
    }

    /** The percent handed to TikTok's seek: inside 0 to 100 and short of the very end. */
    static float seekPercent(float percent) {
        if (Float.isNaN(percent)) return 0f;
        return Math.max(0f, Math.min(LAST_PERCENT, percent));
    }

    /** Takes the line out of the cell, if it has one. */
    static void detach(Cell cell) {
        StorySeekbarView line = cell.line.get();
        cell.line = new WeakReference<>(null);
        if (line != null) {
            ViewParent parent = line.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(line);
            line.abandon();
        }
        // Counted again even when the line was already collected with its viewer.
        anyLine = false;
        for (Cell other : CELLS.values()) {
            if (other.line.get() != null) {
                anyLine = true;
                break;
            }
        }
    }

    static void detachAll() {
        for (Cell cell : new ArrayList<>(CELLS.values())) detach(cell);
        anyLine = false;
    }

    /** Forgets every cell and player; tests start from nothing. */
    static void reset() {
        detachAll();
        CELLS.clear();
        PLAYING.clear();
    }

    private static Cell cellOf(Object component) {
        Cell cell = CELLS.get(component);
        if (cell == null) {
            cell = new Cell();
            CELLS.put(component, cell);
        }
        return cell;
    }

    @Nullable
    private static StorySeekbarView attach(Cell cell, String aid) {
        View root = cell.root.get();
        if (root == null || cell.noFrame) return null;
        FrameLayout frame = videoFrame(root);
        if (frame == null) {
            cell.noFrame = true;
            HookStatus.missingMember(FAMILY, "view", root.getClass().getName(), "one " + VIDEO_FRAME);
            return null;
        }
        HookStatus.bound(FAMILY, "story video frame");
        StorySeekbarView line = new StorySeekbarView(frame.getContext(), cell, aid);
        frame.addView(line, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, line.stripHeight(), Gravity.BOTTOM));
        cell.line = new WeakReference<>(line);
        anyLine = true;
        return line;
    }

    /**
     * The story's rounded video frame: the one RadiusLayout nearest the cell's root, searched
     * level by level. Two at the same level is a layout this was not written for, so it is none.
     */
    @Nullable
    static FrameLayout videoFrame(View root) {
        List<View> level = new ArrayList<>();
        level.add(root);
        for (int depth = 0; depth <= FRAME_DEPTH && !level.isEmpty(); depth++) {
            FrameLayout found = null;
            int count = 0;
            List<View> next = new ArrayList<>();
            for (View view : level) {
                if (view instanceof FrameLayout && isVideoFrame(view.getClass())) {
                    found = (FrameLayout) view;
                    count++;
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) next.add(group.getChildAt(i));
                }
            }
            if (count == 1) return found;
            if (count > 1) return null;
            level = next;
        }
        return null;
    }

    private static boolean isVideoFrame(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (VIDEO_FRAME.equals(current.getName())) return true;
        }
        return false;
    }
}

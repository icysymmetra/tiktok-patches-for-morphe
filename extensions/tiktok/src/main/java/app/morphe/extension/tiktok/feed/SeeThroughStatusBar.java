/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.graphics.Color;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * The window and video halves of See-through status bar (#145). VideoOverlayHider collapses the
 * spacer TikTok keeps above each feed video for the bar, so the picture reaches the top edge; this
 * clears the bar's own color while the feed is in front, so the picture shows through it. Android
 * 15 and up draw every app's bar see through already and ignore the color, so there it's only the
 * spacer.
 *
 * <p>Nothing else about the window changes: no flags, no insets, no cutout mode. The color TikTok
 * last gave the bar is kept, and given back when the feed leaves the front, the switch goes off or
 * Hushfeed is paused, unless TikTok has set another color since.
 *
 * <p>TikTok sizes each video's surface to the picture and centers it in the cell's player, so on
 * a tall screen a 9:16 video still left a black band under the clock with the spacer gone (S22: a
 * 1107 by 1968 surface in a 1080 by 2181 player, 106 px above and below). {@link #fill} grows a
 * portrait surface from its center until it covers the player, trimming a little off each side.
 */
public final class SeeThroughStatusBar {
    /**
     * The most a picture is grown. 1.11 fills the S22's player and trims about 70 px off each side;
     * past 1.2 a picture loses too much of its sides, so it keeps TikTok's bands instead.
     */
    static final float MAX_FILL_SCALE = 1.2f;
    /** Windows whose bar this cleared, with the color TikTok last gave it. */
    private static final Map<Window, Integer> CLEARED = new WeakHashMap<>();
    /** Video surfaces and players this grew, with the scale it wrote. */
    private static final Map<View, Float> FILLED = new WeakHashMap<>();

    private SeeThroughStatusBar() {
    }

    /** One layout pass: clears the bar while {@code wanted}, and gives its color back otherwise. */
    static void apply(Activity activity, boolean wanted) {
        Window window = activity.getWindow();
        if (window == null) return;
        int color = window.getStatusBarColor();
        if (wanted) {
            if (color == Color.TRANSPARENT) return;
            // TikTok's own color, kept even when it set a new one while the bar was clear.
            CLEARED.put(window, color);
            window.setStatusBarColor(Color.TRANSPARENT);
            return;
        }
        Integer own = CLEARED.remove(window);
        if (own != null && color == Color.TRANSPARENT) window.setStatusBarColor(own);
    }

    /** Whether this has cleared the bar of {@code activity}'s window. */
    static boolean isCleared(Activity activity) {
        Window window = activity.getWindow();
        return window != null && CLEARED.containsKey(window);
    }

    /**
     * One layout pass over a feed cell's player: while {@code wanted}, grows its video so a portrait
     * picture covers the player top to bottom; otherwise puts back the scale this wrote. Every pass
     * measures again, so a new video in a reused cell gets its own scale. A scale TikTok wrote
     * itself is left alone.
     *
     * <p>Only the first video after a cold start plays in a surface of the player's own (#164).
     * TikTok creates that one at launch, sized to the picture inside {@code player_view}. Every
     * later cell takes its video view from a factory that, depending on TikTok's experiments, hands
     * back a SurfaceView or a TextureView inside the player, or a SurfaceControl player (47.1.4's
     * {@code X.1T9e}) whose view is {@code player_view} itself: the size TikTok works out for the
     * picture goes on the player, and the picture is a layer that follows the player's place, size
     * and scale. With no surface inside it, the player is the picture, and it grows inside the space
     * around it instead. Fit the video to the screen wins: a picture it shrank to show whole isn't
     * grown back.
     */
    static void fill(View player, boolean wanted) {
        if (!(player instanceof ViewGroup)) return;
        if (wanted && fitWins()) wanted = false;
        ViewGroup group = (ViewGroup) player;
        boolean surfaces = false;
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View surface = group.getChildAt(i);
            if (!(surface instanceof SurfaceView) && !(surface instanceof TextureView)) continue;
            surfaces = true;
            grow(surface, wanted ? fillScale(group.getHeight(), surface) : 1f);
        }
        // A player this grew keeps getting a pass after a surface shows up in it, so it goes back.
        if (surfaces && !FILLED.containsKey(player)) return;
        ViewParent space = player.getParent();
        boolean grows = wanted && !surfaces && space instanceof View;
        grow(player, grows ? fillScale(((View) space).getHeight(), player, true) : 1f);
    }

    /** Whether Fit the video to the screen is on, so a picture it shrank stays whole. */
    private static boolean fitWins() {
        return SettingsStatus.videoFitEnabled && Settings.FIT_VIDEO_TO_SCREEN.get();
    }

    /** Writes {@code scale} on {@code view} unless TikTok has written a scale of its own there. */
    private static void grow(View view, float scale) {
        Float ours = FILLED.get(view);
        float current = view.getScaleY();
        if (current != (ours == null ? 1f : ours) || view.getScaleX() != current) {
            FILLED.remove(view);
            return;
        }
        if (scale == current) return;
        view.setScaleX(scale);
        view.setScaleY(scale);
        if (scale == 1f) FILLED.remove(view);
        else FILLED.put(view, scale);
    }

    /** {@link #fillScale(int, View, boolean)} for a surface TikTok centered by its layout. */
    static float fillScale(int playerHeight, View surface) {
        return fillScale(playerHeight, surface, false);
    }

    /**
     * How much {@code picture} grows to cover a space {@code spaceHeight} tall, or 1 to leave it.
     * Only a portrait picture TikTok centered, since it grows from its center; a landscape video
     * keeps its bands, and so does a picture that would lose more than {@link #MAX_FILL_SCALE}
     * allows. Two pixels over, so no line of the band shows at either edge.
     *
     * <p>A player TikTok sized to the picture ({@code moved}) is placed by its translation as well
     * as its layout, and TikTok works its place out for the space it had before the spacer above
     * it went, so it can sit a little low. It grows until the wider band is covered too, within
     * the same limit.
     */
    static float fillScale(int spaceHeight, View picture, boolean moved) {
        int width = picture.getWidth();
        int height = picture.getHeight();
        if (width <= 0 || height <= width || spaceHeight <= height) return 1f;
        float shift = moved ? picture.getTranslationY() : 0f;
        float above = picture.getTop() + shift;
        float below = spaceHeight - picture.getBottom() - shift;
        float scale;
        if (moved) {
            if (above < 0 || below < 0) return 1f;
            scale = (height + 2f * Math.max(above, below) + 2f) / height;
        } else {
            if (Math.abs(above - below) > 2) return 1f;
            scale = (spaceHeight + 2f) / height;
        }
        return scale > MAX_FILL_SCALE ? 1f : scale;
    }

    /** Whether a surface still carries a scale this wrote, so the pass runs to put it back. */
    static boolean anyFilled() {
        return !FILLED.isEmpty();
    }
}

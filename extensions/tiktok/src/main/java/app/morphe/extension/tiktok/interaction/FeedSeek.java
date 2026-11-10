/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.interaction;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * Where the playing video is, and how to move it.
 *
 * <p>TikTok's {@code PlayerController} kept its name and so did the two methods that matter.
 * Its progress callback runs several times a second while a video plays and carries the
 * position, the length and the id of the video they belong to, and {@code getPlayerManager()}
 * hands back the player the seekbar drags. The manager's own {@code seek(float percentage)}
 * takes a percentage from 0 to 100, while progress positions and lengths are milliseconds.
 * Its name survived but the interface declaring it did not, so that one call goes through
 * reflection on whichever concrete class the manager turns out to be.
 *
 * <p>The id is the point. A post that never reports progress, a photo post or a paused ad,
 * leaves the last video's controller sitting here, and a press would move something that is
 * no longer on screen. So the seek says which video it means and is refused if that is not
 * the one the ticks describe.
 */
public final class FeedSeek {
    private static volatile WeakReference<Object> player = new WeakReference<>(null);
    private static volatile String source;
    private static volatile long positionMs;
    private static volatile long durationMs;
    private static Class<?> seekOwner;
    private static Method seekMethod;

    private FeedSeek() {}

    /**
     * {@code PlayerController.onPlayProgressChange(sourceId, position, duration)}. The source
     * id is the video's own id, which is how the block author patch names the same parameter.
     * A tick with no id belongs to a player holding nothing, and a length of zero leaves a
     * seek with nothing to clamp against, so neither is worth keeping.
     */
    public static void recordProgress(Object controller, String sourceId, long position, long duration) {
        if (controller == null || sourceId == null || sourceId.isEmpty() || duration <= 0) return;
        player = new WeakReference<>(controller);
        source = sourceId;
        positionMs = position;
        durationMs = duration;
    }

    /**
     * Where {@code videoId} was at its last progress tick, in milliseconds, or -1 when the ticks
     * describe another video or none at all. Ticks come several times a second, so it can trail
     * the picture by a fraction of one.
     */
    static long positionOf(String videoId) {
        String playing = source;
        if (videoId == null || playing == null || !playing.equals(videoId) || durationMs <= 0) return -1;
        return positionMs;
    }

    /**
     * Moves {@code videoId} by {@code deltaMs}, kept inside its length. False when that is not
     * the video the player has been reporting, which is the case for anything that scrolled
     * past without ever playing.
     */
    static boolean seekBy(String videoId, long deltaMs) {
        Object controller = player.get();
        String playing = source;
        if (controller == null || durationMs <= 0) return false;
        if (videoId == null || playing == null || !playing.equals(videoId)) return false;

        long target = positionMs + deltaMs;
        if (target < 0) target = 0;
        // A millisecond short of the end, because landing on the end finishes the video.
        if (target >= durationMs) target = durationMs - 1;

        Object manager = Reflect.invoke(controller, "getPlayerManager");
        if (manager == null || !seek(manager, target)) return false;

        // A second press counts from where the first one landed. The progress tick that would
        // say so has not arrived yet.
        positionMs = target;
        return true;
    }

    private static boolean seek(Object manager, long targetMs) {
        try {
            Method method = seekMethod(manager.getClass());
            if (method == null) return false;
            // Native 0MhE's seekbar uses target / duration * 100 before 0MI0.seek(F).
            method.invoke(manager, (float) targetMs / durationMs * 100f);
            return true;
        } catch (Exception exception) {
            Logger.printException(() -> "Could not seek the playing video", exception);
            return false;
        }
    }

    /**
     * Remembered for the one class the manager is, which does not change while the app runs.
     * Only ever reached from the gesture, so from the main thread.
     */
    private static Method seekMethod(Class<?> type) {
        if (type == seekOwner) return seekMethod;
        seekOwner = type;
        seekMethod = null;
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod("seek", float.class);
                method.setAccessible(true);
                seekMethod = method;
                break;
            } catch (NoSuchMethodException ignored) {
                // keep climbing
            } catch (Throwable ignored) {
                break;
            }
        }
        return seekMethod;
    }
}

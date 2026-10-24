/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.StoryDownloads;
import app.morphe.extension.tiktok.settings.Settings;
import java.util.Collection;

/**
 * The story viewer's own end-of-story behavior. TikTok keeps a play mode on the story pager (a
 * real-named enum, AUTO_PLAY_NEXT_USER and LOOP_CURRENT_VIDEO among its constants) and reads it
 * when a story finishes, so Loop a story answers with the loop constant instead of adding a replay
 * of its own. Hold a photo story answers the pager's play-completed callback, which is what moves
 * a story on by itself, for photo stories only.
 */
public final class StoryControls {
    static final String LOOP_CONSTANT = "LOOP_CURRENT_VIDEO";
    private static final String FAMILY = "story controls";

    private StoryControls() {}

    /** The play mode the pager just read, or the loop mode in its place when looping is on. */
    public static Enum<?> playMode(Enum<?> mode) {
        HookStatus.bound(FAMILY, "story play mode");
        if (mode == null || !Settings.STORY_LOOP.get()) return mode;
        Object[] constants = mode.getDeclaringClass().getEnumConstants();
        if (constants == null) return mode;
        for (Object constant : constants) {
            if (LOOP_CONSTANT.equals(((Enum<?>) constant).name())) return (Enum<?>) constant;
        }
        return mode;
    }

    /** True when the finished story is a photo and should stay on screen until you move on. */
    public static boolean holdPhotoStory(Object pager) {
        HookStatus.bound(FAMILY, "story play completed");
        if (pager == null || !Settings.STORY_PHOTO_HOLD.get()) return false;
        Object params = StoryDownloads.boundParams(pager);
        if (params == null) return false;
        return isPhoto(Reflect.invoke(params, "getAweme"));
    }

    /** Whether the story is a photo story: one that carries images. The story seek bar asks too. */
    public static boolean isPhoto(Object aweme) {
        if (aweme == null) return false;
        Object images = Reflect.property(aweme, "getImageInfos", "imageInfos");
        return images instanceof Collection && !((Collection<?>) images).isEmpty();
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Keeps TikTok's full-screen viewer on the video that just ended. The viewer has an auto-next of
 * its own, which neither Auto-advance nor Stop video looping reaches: when a video completes, its
 * autoplay hint asks one gate whether to move to the next cell, and the same gate decides whether
 * the "next video in N" countdown shows. The hook sits at the start of that gate.
 */
public final class FullScreenHold {
    private static final String FAMILY = "full screen hold";

    /** True when the gate should answer no, so the viewer stays on the current video. */
    public static boolean hold() {
        HookStatus.bound(FAMILY, "landscape autoplay gate");
        return Settings.FULL_SCREEN_HOLD.get();
    }

    private FullScreenHold() {}
}

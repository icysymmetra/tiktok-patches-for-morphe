/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The switch in front of Enable voice comments.
 *
 * <p>The hook is TikTok's own gate for building the voice comment entry points, the lazily read
 * {@code audio_comment_publish} value. With the switch on it answers yes; with it off, and while
 * Hushfeed is paused, TikTok reads its own value as it ships.
 */
@SuppressWarnings("unused")
public final class VoiceComments {
    /** {@link Settings#ENABLE_VOICE_COMMENTS}'s key, for a read before the settings context. */
    static final String SWITCH_KEY = "enable_voice_comments";

    private VoiceComments() {
    }

    public static boolean isOn() {
        return Utils.getContext() != null ? Settings.ENABLE_VOICE_COMMENTS.get() : EarlySwitch.isOn(SWITCH_KEY);
    }
}

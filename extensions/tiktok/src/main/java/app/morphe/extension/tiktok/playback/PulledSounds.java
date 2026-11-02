/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.playback;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Whether TikTok's player keeps a video silent because its sound was pulled. The audio is still
 * in the video; TikTok mutes the player when the sound's status is 0 or the post's mute info
 * says muted, and search mutes a post flagged for a copyright claim. Each answer is read as a
 * video starts, so the switch needs no restart. Paused, the switch answers off.
 */
@SuppressWarnings("unused")
public final class PulledSounds {
    /** The status TikTok's sound model gives a sound that plays normally. */
    static final int AVAILABLE = 1;

    private PulledSounds() {
    }

    static boolean keep() {
        return SettingsStatus.keepPulledSoundsEnabled && Settings.KEEP_PULLED_SOUNDS.get();
    }

    /** The player's mute decision reads the sound's status here; 0 is a pulled sound. */
    public static int keepStatus(int status) {
        return status == 0 && keep() ? AVAILABLE : status;
    }

    /** The player's mute decision reads the post's own mute flag here. */
    public static boolean keepMuted(boolean muted) {
        return muted && !keep();
    }

    /** True when search should treat a copyright-muted post as playing with its sound. */
    public static boolean keepInSearch() {
        return keep();
    }
}

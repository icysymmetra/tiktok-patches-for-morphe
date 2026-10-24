/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import android.view.View;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * TikTok's Thoughts on a profile (#122): the short note people post, drawn as a speech bubble
 * above their profile picture, and on your own profile the "Share your thoughts..." prompt in the
 * same place.
 *
 * <p>The profile picture builds its bubble once, when its view is made, and keeps it in a field
 * every later step checks for null first. Hiding takes the bubble's view off the screen and leaves
 * that field empty, which is how a profile picture without a bubble starts. The note itself still
 * arrives and nothing is sent. TikTok also opens a spacer above the picture whenever it's told the
 * bubble shows, so the callback that hears that is told it doesn't.
 */
public final class ProfileThoughts {
    private ProfileThoughts() {
    }

    /**
     * Asked right after the profile picture keeps its Thoughts bubble. True hides it: the
     * bubble's view is set gone here and the patch empties the field the picture keeps it in.
     */
    public static boolean hideBubble(Object bubble) {
        boolean hide = Settings.HIDE_PROFILE_THOUGHTS.get();
        HookStatus.bound("profile thoughts bubble", hide ? "bubble hidden" : "bubble left");
        if (hide && bubble instanceof View) ((View) bubble).setVisibility(View.GONE);
        return hide;
    }

    /**
     * Asked when TikTok tells the profile picture whether its bubble shows. True makes the answer
     * no, so the space above the picture stays closed.
     */
    public static boolean hideOnProfiles() {
        boolean hide = Settings.HIDE_PROFILE_THOUGHTS.get();
        HookStatus.bound("profile thoughts space", hide ? "space closed" : "space left");
        return hide;
    }
}

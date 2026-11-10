/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Raises the length TikTok's app stops typing at in the comment box, the repost note boxes and
 * the bio editor.
 *
 * <p>Each box reads its limit once, as an int: the comment keyboard's limit getter, the max its
 * emoji-aware length filter is built with in the repost note boxes, and the constant the bio
 * editor compares the bio with (160 on 47.x) for its overflow state, its counter and its mention
 * check. The patch hands each of those ints here. With the switch on, the limit goes up to
 * {@link #LIFTED}, never down. What the server accepts is up to the server: a comment it turns
 * down comes back through TikTok's own error path, which this leaves alone.
 */
public final class LengthLimits {
    /** The limit with the switch on. Far past what anyone types, small enough for TikTok's arithmetic. */
    static final int LIFTED = 10_000;

    private LengthLimits() {}

    /** The limit TikTok uses for a box whose own limit is {@code original}. */
    public static int limit(int original) {
        return Settings.LIFT_LENGTH_LIMITS.get() ? Math.max(original, LIFTED) : original;
    }
}

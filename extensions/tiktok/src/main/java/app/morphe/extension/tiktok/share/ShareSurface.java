/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import java.util.Locale;

/**
 * Which share sheet is being built, read off the package TikTok builds it from.
 *
 * <p>{@code BaseSharePackage.itemType} is set by the builder of each kind of share. A dex scan of
 * 46.2.3 found {@code "user"} written only by the profile package builder and the profile link
 * service, and {@code "live"} only by the two LIVE builders. Everything else, videos
 * ({@code "aweme"}), photo posts and the two dozen smaller kinds, stays on the video list, so a
 * kind nobody has looked at behaves exactly as it did before the lists were split.
 */
public enum ShareSurface {
    VIDEO('v'),
    PROFILE('p'),
    LIVE('l');

    /** The letter this surface is stored as in the action catalogue. */
    final char flag;

    ShareSurface(char flag) {
        this.flag = flag;
    }

    static ShareSurface of(String itemType) {
        if (itemType == null) return VIDEO;
        switch (itemType.trim().toLowerCase(Locale.ROOT)) {
            case "user":
                return PROFILE;
            case "live":
                return LIVE;
            default:
                return VIDEO;
        }
    }

    static ShareSurface ofFlag(char flag) {
        for (ShareSurface surface : values()) {
            if (surface.flag == flag) return surface;
        }
        return null;
    }
}

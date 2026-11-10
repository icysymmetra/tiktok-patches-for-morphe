/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Takes the story ring and the pulsing LIVE ring off profile pictures.
 *
 * <p>Story rings key on {@code User.getStoryStatus}: a picture gets one, and a tap that opens the
 * story, only when the status says there is one. With the switch on the getter answers 0, the
 * value a user with no story has, so the feed avatar never attaches FeedAvatarSocialPublishAssem
 * and comment avatars draw no ring.
 *
 * <p>The feed avatar attaches FeedAvatarLiveAssem, which draws the LIVE ring and sends a tap into
 * the room, only when TikTok's author live check answers yes. That check is a static
 * {@code (Aweme, User)Z} method R8 renames each build; the patch finds it through the feed
 * avatar's bind method and filters what it returns, so the default avatar binds and a tap opens
 * the profile. Comment and list avatars ask AvatarLiveDataAdapter, whose {@code User.isLive}
 * reads are filtered the same way.
 */
public final class AvatarRings {
    private AvatarRings() {}

    /** What {@code User.getStoryStatus} answers. */
    public static int storyStatus(int status) {
        return Settings.HIDE_STORY_RINGS.get() ? 0 : status;
    }

    /** What TikTok's feed avatar live check answers. */
    public static boolean authorLive(boolean live) {
        return live && !Settings.HIDE_LIVE_RING.get();
    }

    /** What AvatarLiveDataAdapter reads from {@code User.isLive}. */
    public static boolean avatarLive(boolean live) {
        return live && !Settings.HIDE_LIVE_RING.get();
    }
}

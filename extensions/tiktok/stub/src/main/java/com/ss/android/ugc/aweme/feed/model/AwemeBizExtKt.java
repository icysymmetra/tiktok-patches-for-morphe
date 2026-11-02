package com.ss.android.ugc.aweme.feed.model;

public final class AwemeBizExtKt {
    public static int getCacheSourceType(Aweme aweme) {
        return aweme == null ? 0 : aweme.cacheSourceType;
    }

    /**
     * TikTok's returns {@code com.ss.android.ugc.aweme.profile.model.User}, which has no stub,
     * so extension code reaches it by reflection only; a direct call would link to this
     * Object-returning shape and fail on the device.
     */
    public static Object getRepostUser(Aweme aweme) {
        return aweme == null ? null : aweme.repostUser;
    }
}

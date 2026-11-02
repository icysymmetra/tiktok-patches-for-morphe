package com.ss.android.ugc.aweme.feed.model;

/** Test stand-in for TikTok's real-named cell params; only getAweme() is read. */
public class VideoItemParams {
    private final Object aweme;

    public VideoItemParams(Object aweme) {
        this.aweme = aweme;
    }

    public Object getAweme() {
        return aweme;
    }
}

package com.ss.android.ugc.aweme.share.base.model;

/** Test stand-in for TikTok's server-named share target, with its real field names and constructor. */
public final class ShareChannelInfo {
    public final String channelKey;
    public final String packageName;
    public final String labelName;
    public final String iconRes;
    public final String squareIconRes;
    public final TargetComponentInfo targetComponentInfo;

    public ShareChannelInfo(String channelKey, String packageName, String labelName, String iconRes,
                            String squareIconRes, TargetComponentInfo targetComponentInfo) {
        this.channelKey = channelKey;
        this.packageName = packageName;
        this.labelName = labelName;
        this.iconRes = iconRes;
        this.squareIconRes = squareIconRes;
        this.targetComponentInfo = targetComponentInfo;
    }
}

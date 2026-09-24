package com.ss.android.ugc.aweme.feed.model;

import com.ss.android.ugc.aweme.feed.AIGCInfo;

public class Aweme {
    public AIGCInfo aigcInfo;
    public ModerationAigcInfo moderationInfo;
    public boolean throwAigcGetter;
    public boolean throwModerationGetter;

    public AIGCInfo getAigcInfo() {
        if (throwAigcGetter) throw new LinkageError("aigc fixture");
        return aigcInfo;
    }

    public ModerationAigcInfo getModerationAigcInfo() {
        if (throwModerationGetter) throw new LinkageError("moderation fixture");
        return moderationInfo;
    }
}

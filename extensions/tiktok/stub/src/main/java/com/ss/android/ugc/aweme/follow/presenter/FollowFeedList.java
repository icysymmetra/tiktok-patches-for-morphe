/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/stub/src/main/java/com/ss/android/ugc/aweme/follow/presenter/FollowFeedList.java
 */

package com.ss.android.ugc.aweme.follow.presenter;

import java.util.List;

@SuppressWarnings("unused")
public class FollowFeedList {
    // Backing field mItems (46.2.3) became items (47.1.4); the accessors are stable.
    public List getItems() {
        throw new UnsupportedOperationException("Stub");
    }

    public void setItems(List items) {
        throw new UnsupportedOperationException("Stub");
    }
    public int feedType;
    public int hasMore;
    public long cursor;
    public String requestId;
}


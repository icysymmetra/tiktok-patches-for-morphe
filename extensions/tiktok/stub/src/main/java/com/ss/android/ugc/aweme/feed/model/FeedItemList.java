/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/stub/src/main/java/com/ss/android/ugc/aweme/feed/model/FeedItemList.java
 */
package com.ss.android.ugc.aweme.feed.model;

import java.util.List;

@SuppressWarnings("unused")
public class FeedItemList {
    public List items;
    public int fetchType;
    public int hasMore;
    public long cursor;
    public String requestId;
    public boolean hasAd;
    public List preloadAds;
}

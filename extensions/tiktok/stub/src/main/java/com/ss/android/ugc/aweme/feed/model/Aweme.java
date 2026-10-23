/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/stub/src/main/java/com/ss/android/ugc/aweme/feed/model/Aweme.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/stub/src/main/java/com/ss/android/ugc/aweme/feed/model/Aweme.java
 */

package com.ss.android.ugc.aweme.feed.model;

import java.util.List;

@SuppressWarnings("unused")
public class Aweme {
    /** Test fixture value used by the cache-delivery boundary tests. */
    public int cacheSourceType;
    /** Test fixture value: the reposter {@link AwemeBizExtKt#getRepostUser} hands back. */
    public Object repostUser;

    public String getAid() {
        throw new UnsupportedOperationException("Stub");
    }

    public int getAwemeType() {
        throw new UnsupportedOperationException("Stub");
    }

    public boolean isAd() {
        throw new UnsupportedOperationException("Stub");
    }

    public boolean isSoftAd() {
        throw new UnsupportedOperationException("Stub");
    }

    public AwemeRawAd getAwemeRawAd() {
        throw new UnsupportedOperationException("Stub");
    }

    public boolean isLiveReplay() {
        throw new UnsupportedOperationException("Stub");
    }

    public long getLiveId() {
        throw new UnsupportedOperationException("Stub");
    }

    public String getLiveType() {
        throw new UnsupportedOperationException("Stub");
    }

    public boolean isWithPromotionalMusic() {
        throw new UnsupportedOperationException("Stub");
    }

    public boolean getIsTikTokStory() {
        throw new UnsupportedOperationException("Stub");
    }

    public List getImageInfos() {
        throw new UnsupportedOperationException("Stub");
    }

    /** The bottom banners TikTok draws under the video, each a {@code BannerCommonStruct}. */
    public List getBanners() {
        throw new UnsupportedOperationException("Stub");
    }

    public void setBanners(List banners) {
        throw new UnsupportedOperationException("Stub");
    }

    public PhotoModeImageInfo getPhotoModeImageInfo() {
        throw new UnsupportedOperationException("Stub");
    }

    public PhotoModeTextInfo getPhotoModeTextInfo() {
        throw new UnsupportedOperationException("Stub");
    }

    public AwemeStatistics getStatistics() {
        throw new UnsupportedOperationException("Stub");
    }

    /** When the video was posted, in Unix seconds, or 0 when TikTok didn't say. */
    public long getCreateTime() {
        throw new UnsupportedOperationException("Stub");
    }

    public String getShareUrl() {
        throw new UnsupportedOperationException("Stub");
    }

    /** Where TikTok's server says the video came from; {@code for_you_page_999} is its fill-in pool. */
    public String getItemDistributeSource() {
        throw new UnsupportedOperationException("Stub");
    }

    /** Why TikTok picked the video for this reader, or null when it gives no reason. */
    public RecReasonsStruct getRecReasonsStruct() {
        throw new UnsupportedOperationException("Stub");
    }
}

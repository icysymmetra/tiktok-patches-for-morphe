package com.ss.android.ugc.aweme.feed.model;

import com.ss.android.ugc.aweme.base.model.UrlModel;

import java.util.List;

@SuppressWarnings("unused")
public class Video {
    public UrlModel downloadNoWatermarkAddr;

    // The backing fields were renamed in TikTok 47.1.4 (bitRate -> bitRateList, playAddr -> playAddrValue,
    // h264PlayAddr -> h264PlayAddrValue); the getters are stable across 46.2.3 and 47.1.4.
    public List<BitRate> getBitRate() {
        throw new UnsupportedOperationException("Stub");
    }

    public VideoUrlModel getH264PlayAddr() {
        throw new UnsupportedOperationException("Stub");
    }

    public VideoUrlModel getPlayAddr() {
        throw new UnsupportedOperationException("Stub");
    }

    public List<BitRate> getRawBitRate() {
        throw new UnsupportedOperationException("Stub");
    }
}

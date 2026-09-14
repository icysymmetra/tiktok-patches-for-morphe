package com.ss.android.ugc.aweme.feed.model;

import com.ss.android.ugc.aweme.base.model.UrlModel;

import java.util.List;

@SuppressWarnings("unused")
public class Video {
    public List<BitRate> bitRate;
    public UrlModel downloadNoWatermarkAddr;
    public VideoUrlModel h264PlayAddr;
    public VideoUrlModel playAddr;

    public List<BitRate> getRawBitRate() {
        throw new UnsupportedOperationException("Stub");
    }
}

package com.ss.android.ugc.aweme.feed.assem.desc;

/**
 * TikTok's caption view model as the caption-in-comments hook reads it: found by its class name
 * on the caption handler, then getItem() and the item's aweme.
 */
public class VideoDescVM {
    private final Object item;

    public VideoDescVM(Object item) {
        this.item = item;
    }

    public Object getItem() {
        return item;
    }
}

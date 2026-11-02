package com.bytedance.android.live.design.view.sheet;

import android.content.Context;

/** Test stand-in for LIVE's sheet page; only its name and getThemeOverlay are read. */
public class LiveSheetFragment {
    public int overlay;

    public int getThemeOverlay(Context context) {
        return overlay;
    }
}

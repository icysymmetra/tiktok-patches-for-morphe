package com.bytedance.android.live.design.resource.theme;

import android.content.Context;

/** Test stand-in for LIVE's themed dialog base; only its name and getThemeOverlay are read. */
public class LiveThemeAwareDialogFragment {
    public int overlay;

    public int getThemeOverlay(Context context) {
        return overlay;
    }
}

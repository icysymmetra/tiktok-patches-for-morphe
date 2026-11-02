package androidx.fragment.app;

import android.app.Activity;

/**
 * A stand-in for AndroidX's Fragment, which TikTok ships under this name and the extension only
 * reaches by reflection. Just enough for a test to hand a view model a fragment with a screen.
 */
public class Fragment {
    private final Activity activity;

    public Fragment(Activity activity) {
        this.activity = activity;
    }

    public final Activity getActivity() {
        return activity;
    }
}

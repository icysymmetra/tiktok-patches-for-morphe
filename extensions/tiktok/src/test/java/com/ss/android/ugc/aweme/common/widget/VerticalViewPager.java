package com.ss.android.ugc.aweme.common.widget;

import android.content.Context;
import android.widget.FrameLayout;

/** Test fixture for the public native pager contract, not part of the extension. */
public class VerticalViewPager extends FrameLayout {
    public int current = 3;
    public int scrollState;
    public int advances;
    public boolean hasNext = true;
    public VerticalViewPager(Context context) { super(context); }
    public int getCurrentItem() { return current; }
    public int getScrollState() { return scrollState; }
    public void setCurrentItem(int index) { current = index; advances++; }
    @Override public boolean canScrollVertically(int direction) { return direction == 1 && hasNext; }
}

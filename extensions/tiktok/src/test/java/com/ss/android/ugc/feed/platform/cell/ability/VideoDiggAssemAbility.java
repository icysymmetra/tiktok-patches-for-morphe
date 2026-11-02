package com.ss.android.ugc.feed.platform.cell.ability;

import android.graphics.Rect;

/**
 * The heart assem's ability as it stands on 47.1.4, for the Like the video tests.
 *
 * <p>{@code MG0} is TikTok's long press like, which likes a video that isn't liked and leaves a
 * liked one be. {@code Dw0} is the heart's click, a toggle that takes a like back off. The
 * extension picks the one method that takes nothing and returns nothing, so the click and the
 * other four are here to prove that the choice is made by shape and not by name.
 */
public interface VideoDiggAssemAbility {
    Long CI0();
    void Dw0(String enterMethod);
    void MG0();
    Rect Oq1();
    boolean nY2();
    boolean vp0(float x, float y);
}

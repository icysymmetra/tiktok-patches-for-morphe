package com.ss.android.ugc.aweme.feed.assem.ability;

import android.graphics.Rect;

/**
 * The comment assem's ability as it stands on 46.2.3, for the double-tap comments tests.
 *
 * <p>The names are obfuscated and differ on every build (46.7.3 calls the press {@code Ef0},
 * 46.8.3 {@code ig0}); the shape is what stays. The extension picks the one method that takes
 * nothing and returns nothing, so the other five are here to prove that the choice is made by
 * shape and not by position or by name.
 */
public interface IVideoCommentAbility {
    void Id0();
    void XZ1(String enterMethod);
    boolean c00(float x, float y);
    void jo2(int jumpType);
    Rect nw2();
    void qg1(CharSequence text, String source);
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import static org.junit.Assert.*;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

/** Owned share actions keep TikTok's icon frame: its circle in the grid, its size and ink in both lists. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeShareIconTest {
    /** Stands in for TikTok's TuxIconDrawable: a fixed size icon drawn in one ink colour. */
    private static final class Icon extends Drawable {
        private final int size;
        private final Paint paint = new Paint();

        Icon(int size, int ink) {
            this.size = size;
            paint.setColor(ink);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawRect(b.left + b.width() / 4f, b.top + b.height() / 4f,
                    b.right - b.width() / 4f, b.bottom - b.height() / 4f, paint);
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private final List<Integer> inks = new ArrayList<>();
    private final List<Float> strokes = new ArrayList<>();

    private Drawable glyph(int color, float strokeWidthPx) {
        inks.add(color);
        strokes.add(strokeWidthPx);
        return new ColorDrawable(color);
    }

    @Test public void theGridKeepsTikToksCircleAndTheGlyphTakesTheIconsSizeAndInk() {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFFF1F1F2);
        Icon icon = new Icon(48, 0xFF545454);
        icon.setBounds(3, 4, 5, 6);
        LayerDrawable native_ = new LayerDrawable(new Drawable[]{circle, icon});
        native_.setId(0, 7);
        native_.setLayerInset(0, 1, 2, 3, 4);

        LayerDrawable styled = (LayerDrawable) NativeShareIcon.restyle(native_, this::glyph);

        assertEquals(2, styled.getNumberOfLayers());
        assertSame(circle, styled.getDrawable(0));
        assertEquals("the circle keeps its id", 7, styled.getId(0));
        assertEquals("and its insets", List.of(1, 2, 3, 4), List.of(styled.getLayerInsetLeft(0),
                styled.getLayerInsetTop(0), styled.getLayerInsetRight(0), styled.getLayerInsetBottom(0)));
        assertEquals(List.of(0xFF545454), inks);
        assertEquals(List.of(4f), strokes);
        assertEquals(60, styled.getLayerWidth(1));
        assertEquals(60, styled.getLayerHeight(1));
        assertEquals(Gravity.CENTER, styled.getLayerGravity(1));
        assertEquals("the sheet lays it out at TikTok's size", native_.getIntrinsicWidth(), styled.getIntrinsicWidth());
        assertEquals(native_.getIntrinsicHeight(), styled.getIntrinsicHeight());
        assertEquals("the native icon's bounds are put back", new Rect(3, 4, 5, 6), icon.getBounds());
    }

    /**
     * The list row sizes its icon view to the drawable. A drawable a quarter larger than TikTok's
     * grew the row, or a fixed view scaled it back down and undid the larger glyph (review of
     * 5f943bf55), so the glyph reaches past the drawable's bounds instead.
     */
    @Test public void theLongPressListsLoneIconIsSwappedForTheGlyphInItsInk() {
        LayerDrawable native_ = new LayerDrawable(new Drawable[]{new Icon(40, 0xFFFFFFFF)});
        LayerDrawable styled = (LayerDrawable) NativeShareIcon.restyle(native_, this::glyph);

        assertEquals(1, styled.getNumberOfLayers());
        assertEquals(List.of(0xFFFFFFFF), inks);
        assertEquals(50, styled.getLayerWidth(0));
        assertEquals(40, styled.getIntrinsicWidth());
        assertEquals(40, styled.getIntrinsicHeight());
        styled.setBounds(0, 0, 40, 40);
        assertEquals("centred on the icon's box", new Rect(-5, -5, 45, 45), styled.getDrawable(0).getBounds());
    }

    /** An icon layer TikTok sized itself is measured by that size, not the artwork's. */
    @Test public void anIconLayerWithASizeOfItsOwnSetsTheGlyphsSize() {
        LayerDrawable native_ = new LayerDrawable(new Drawable[]{new Icon(48, 0xFF161823)});
        native_.setLayerSize(0, 36, 36);
        LayerDrawable styled = (LayerDrawable) NativeShareIcon.restyle(native_, this::glyph);

        assertEquals(List.of(3f), strokes);
        assertEquals(45, styled.getLayerWidth(0));
        assertEquals(36, styled.getIntrinsicWidth());
    }

    @Test public void anythingButTikToksLayeredIconFallsBackToTheGlyphAlone() {
        assertNull(NativeShareIcon.restyle(null, this::glyph));
        assertNull("not layered", NativeShareIcon.restyle(new Icon(48, 0xFF545454), this::glyph));
        assertNull("no intrinsic size", NativeShareIcon.restyle(
                new LayerDrawable(new Drawable[]{new ColorDrawable(0xFF545454)}), this::glyph));
        assertNull("an icon that draws nothing", NativeShareIcon.restyle(
                new LayerDrawable(new Drawable[]{new Icon(48, 0x00000000)}), this::glyph));
        assertTrue(inks.isEmpty());
    }
}

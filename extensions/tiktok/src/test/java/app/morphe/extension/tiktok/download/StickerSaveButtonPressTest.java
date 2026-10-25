package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Method;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The Save media button's pressed look, held against TikTok's own buttons on the sticker sheet. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StickerSaveButtonPressTest {
    private static final int TIKTOK_PINK = 0xfffe2c55;
    /** TikTok's Save while held, read off the S25 on 47.1.3. */
    private static final int TIKTOK_PINK_HELD = 0xfffe476b;

    private static final int[] AT_REST = {android.R.attr.state_enabled};
    private static final int[] HELD = {android.R.attr.state_enabled, android.R.attr.state_pressed};
    private static final int[] FOCUSED = {android.R.attr.state_enabled, android.R.attr.state_focused};

    /**
     * TikTok's Save and Share lighten while held, by a wash over the pill and not through their
     * background, which is one flat colour. The copy took the background and nothing else, so
     * on the S25 (47.1.3) Save media was the one button on the sheet that showed no press:
     * #fe2c55 held and at rest, where TikTok's Save went to #fe476b.
     */
    @Test public void aHeldSaveButtonLightensAsTikToksOwnDo() throws Exception {
        TextView button = copyOf(pinkTemplate());

        assertNear("the Save button is not TikTok's pink at rest",
                TIKTOK_PINK, colourOf(button.getBackground(), AT_REST));
        assertNear("a held Save button doesn't lighten the way TikTok's Save does",
                TIKTOK_PINK_HELD, colourOf(button.getBackground(), HELD));
    }

    /**
     * TikTok's buttons carry no focused look of their own, so Android draws its default
     * highlight on all three. A focused look in the copy would make Save media the odd one out
     * under a keyboard.
     */
    @Test public void focusIsLeftToThePlatformAsOnTikToksButtons() throws Exception {
        TextView button = copyOf(pinkTemplate());

        assertNear("the copy draws a focused look TikTok's buttons don't have",
                TIKTOK_PINK, colourOf(button.getBackground(), FOCUSED));
    }

    /** A background that already answers a press keeps its own look, with no wash on top. */
    @Test public void aBackgroundWithItsOwnPressIsCopiedAsItIs() throws Exception {
        StateListDrawable own = new StateListDrawable();
        own.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.BLUE));
        own.addState(new int[0], new ColorDrawable(TIKTOK_PINK));
        TextView template = new TextView(RuntimeEnvironment.getApplication());
        template.setTextColor(Color.WHITE);
        template.setBackground(own);

        TextView button = copyOf(template);

        assertNear("a host press was washed over", Color.BLUE, colourOf(button.getBackground(), HELD));
        assertNear("a host background lost its resting colour",
                TIKTOK_PINK, colourOf(button.getBackground(), AT_REST));
    }

    private static TextView pinkTemplate() {
        TextView template = new TextView(RuntimeEnvironment.getApplication());
        template.setTextColor(Color.WHITE);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(TIKTOK_PINK);
        pill.setCornerRadius(48f);
        template.setBackground(pill);
        return template;
    }

    private static TextView copyOf(TextView template) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Method create = StickerGallerySaver.class.getDeclaredMethod(
                "createActionButton", View.class, View.class);
        create.setAccessible(true);
        return (TextView) create.invoke(null, template, new View(context));
    }

    /** The colour at the middle of the background drawn in the given state, text left out. */
    private static int colourOf(Drawable background, int[] state) {
        Bitmap bitmap = Bitmap.createBitmap(300, 96, Bitmap.Config.ARGB_8888);
        background.setBounds(0, 0, 300, 96);
        background.setState(state);
        background.draw(new Canvas(bitmap));
        return bitmap.getPixel(150, 48);
    }

    private static void assertNear(String message, int expected, int actual) {
        boolean near = Color.alpha(actual) == 0xff
                && Math.abs(Color.red(expected) - Color.red(actual)) <= 2
                && Math.abs(Color.green(expected) - Color.green(actual)) <= 2
                && Math.abs(Color.blue(expected) - Color.blue(actual)) <= 2;
        assertTrue(String.format("%s: expected #%08x, drew #%08x", message, expected, actual), near);
    }
}

package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;

/**
 * See-through bottom bar (#145): the bar's background goes clear and the spacer under each video
 * goes, and turning the switch off puts both back exactly. If collapsing the spacer pushes the
 * caption under the bar, the spacer is put back and only the bar stays clear.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SeeThroughBottomBarTest {
    private static final int CELL_ID = 0x7f0a0201;
    private static final int SPACER_ID = 0x7f0a0c21;
    private static final int BAR_ID = 0x7f0a0c22;
    private static final int ANCHOR_ID = 0x7f0a0c23;

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        VideoOverlayHider.resolveForTests("47.4.3:duc", SPACER_ID);
        VideoOverlayHider.resolveForTests("47.4.3:onp", BAR_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", ANCHOR_ID);
        SeeThroughBottomBar.reset();
    }

    @After
    public void tearDown() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
        Settings.FLOATING_BOTTOM_BAR.save(false);
        SeeThroughBottomBar.reset();
        VideoOverlayHider.resolveForTests("view_rootview", 0);
    }

    /** The scene: a cell holding the video area, the anchor frame and the spacer, and the bar. */
    private static final class Scene {
        final FrameLayout root;
        final View spacer;
        final View bar;
        final View anchor;

        Scene(Activity activity, int spacerVisibility, int barColor) {
            root = new FrameLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            LinearLayout area = new LinearLayout(activity);
            area.setOrientation(LinearLayout.VERTICAL);
            area.addView(new View(activity), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            spacer = new View(activity);
            spacer.setId(SPACER_ID);
            spacer.setVisibility(spacerVisibility);
            area.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 136));
            cell.addView(area);
            anchor = new FrameLayout(activity);
            anchor.setId(ANCHOR_ID);
            cell.addView(anchor);
            root.addView(cell);
            bar = new FrameLayout(activity);
            bar.setId(BAR_ID);
            bar.setBackgroundColor(barColor);
            root.addView(bar);
            activity.setContentView(root);
        }
    }

    @Test
    public void theBarGoesClearAndTheSpacerGoesThenBothComeBack() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            // TikTok keeps the spacer INVISIBLE (its space, no picture): it must come back so.
            Scene scene = new Scene(activity, View.INVISIBLE, Color.BLACK);
            android.graphics.drawable.Drawable original = scene.bar.getBackground();

            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());
            assertTrue(scene.bar.getBackground() instanceof ColorDrawable);
            assertNotSame(original, scene.bar.getBackground());
            assertEquals(Color.TRANSPARENT, ((ColorDrawable) scene.bar.getBackground()).getColor());

            // A pass is repeated on every layout; a second one changes nothing.
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());
            assertEquals(Color.TRANSPARENT, ((ColorDrawable) scene.bar.getBackground()).getColor());

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, scene.spacer.getVisibility());
            assertSame("the bar's own background must be the very drawable TikTok set",
                    original, scene.bar.getBackground());
        }
    }

    /**
     * Floating bottom bar (#124): with See-through bottom bar on, the bar's background is a
     * rounded see-through pill drawn in from its sides and bottom. The bar keeps its padding, so
     * the tabs stay where TikTok put them, and every way out gives TikTok's own background back.
     */
    @Test
    public void aFloatingBarIsAPillInsideTheBarAndEveryWayOutGivesTikToksBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.VISIBLE, Color.BLACK);
            scene.bar.setPadding(5, 6, 7, 8);
            android.graphics.drawable.Drawable original = scene.bar.getBackground();

            Settings.FLOATING_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertSame("the pill showed without See-through bottom bar", original, scene.bar.getBackground());

            Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.FloatingBackground);
            SeeThroughBottomBar.FloatingBackground floating = (SeeThroughBottomBar.FloatingBackground) scene.bar.getBackground();
            assertEquals("the pill moved the tabs", Arrays.asList(5, 6, 7, 8), padding(scene.bar));
            GradientDrawable pill = (GradientDrawable) floating.getDrawable();
            assertEquals(SeeThroughBottomBar.PILL_COLOR, pill.getColor().getDefaultColor());
            float density = activity.getResources().getDisplayMetrics().density;
            floating.setBounds(0, 0, 1080, 150);
            assertEquals(new Rect(Math.round(12 * density), Math.round(2 * density),
                    1080 - Math.round(12 * density), 150 - Math.round(6 * density)), pill.getBounds());

            VideoOverlayHider.applyTo(activity);
            assertSame("a pass swapped the pill for a new one", floating, scene.bar.getBackground());

            Settings.FLOATING_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof ColorDrawable);
            assertEquals(Color.TRANSPARENT, ((ColorDrawable) scene.bar.getBackground()).getColor());

            Settings.FLOATING_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.FloatingBackground);
            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertSame("Pause kept the pill", original, scene.bar.getBackground());
            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.FloatingBackground);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertSame("the bar's own background must be the very drawable TikTok set",
                    original, scene.bar.getBackground());
            assertEquals(Arrays.asList(5, 6, 7, 8), padding(scene.bar));
        } finally {
            PausedProcess.set(false);
            Settings.FLOATING_BOTTOM_BAR.save(false);
        }
    }

    private static java.util.List<Integer> padding(View view) {
        return Arrays.asList(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom());
    }

    @Test
    public void aBackgroundTikTokSetInTheMeantimeIsNotOverwrittenOnTheWayBack() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.VISIBLE, Color.BLACK);
            VideoOverlayHider.applyTo(activity);
            ColorDrawable theirs = new ColorDrawable(Color.RED);
            scene.bar.setBackground(theirs);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertSame(theirs, scene.bar.getBackground());
            assertEquals(View.VISIBLE, scene.spacer.getVisibility());
        }
    }

    @Test
    public void aSpacerTikTokHadAlreadyRemovedStaysRemovedWhenTheSwitchGoesOff() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.GONE, Color.BLACK);
            VideoOverlayHider.applyTo(activity);
            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());
        }
    }

    @Test
    public void theSpacerIsPutBackWhenTheCollapseMovedTheCaptionUnderTheBar() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.VISIBLE, Color.BLACK);
            scene.bar.layout(0, 900, 1080, 1000);
            // With the spacer in place the caption frame ends above the bar.
            scene.anchor.layout(0, 700, 1080, 880);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());

            // The collapse let the cell grow and TikTok's layout carried the caption down with it.
            scene.anchor.layout(0, 800, 1080, 1000);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the spacer must come back when the caption ended up under the bar",
                    View.VISIBLE, scene.spacer.getVisibility());
            assertFalse(SeeThroughBottomBar.collapseAllowed());
            assertEquals("the bar stays clear",
                    Color.TRANSPARENT, ((ColorDrawable) scene.bar.getBackground()).getColor());

            // Later passes leave the spacer be, until the switch is turned off and on again.
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, scene.spacer.getVisibility());
            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(SeeThroughBottomBar.collapseAllowed());
            Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());
        }
    }

    @Test
    public void aCaptionThatStaysAboveTheBarKeepsTheCollapse() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.VISIBLE, Color.BLACK);
            scene.bar.layout(0, 900, 1080, 1000);
            scene.anchor.layout(0, 700, 1080, 880);
            VideoOverlayHider.applyTo(activity);
            VideoOverlayHider.applyTo(activity);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());
            assertTrue(SeeThroughBottomBar.collapseAllowed());
        }
    }

    private static final int STRIP_ID = 0x7f0a0c24;
    private static final int COLUMN_ID = 0x7f0a0c25;
    private static final int LAYER_ID = 0x7f0a0c26;

    /**
     * The main feed as the S22 lays it out (2026-10-13): the cell's own spacer is already gone,
     * and the room kept for the bar is the 138 px strip under the pager in a 2181 px column, with
     * the bar over it. The strip goes, the pager gets its room once the column is laid out again,
     * and the cell's widget layer goes up by the same 138 so the caption stays where it was. The
     * guard, measuring after that lift, keeps the collapse. Off puts the strip and the layer back.
     */
    @Test
    public void theMainFeedStripGoesAndTheCaptionStaysAboveTheBar() {
        VideoOverlayHider.resolveForTests("47.4.3:cnc", STRIP_ID);
        VideoOverlayHider.resolveForTests("viewpager_container", COLUMN_ID);
        VideoOverlayHider.resolveForTests("widget_container", LAYER_ID);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setId(COLUMN_ID);
            column.setOrientation(LinearLayout.VERTICAL);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            column.addView(cell, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            View strip = new View(activity);
            strip.setId(STRIP_ID);
            column.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 138));
            FrameLayout layer = new FrameLayout(activity);
            layer.setId(LAYER_ID);
            cell.addView(layer);
            View anchor = new FrameLayout(activity);
            anchor.setId(ANCHOR_ID);
            layer.addView(anchor, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 161, android.view.Gravity.BOTTOM));
            root.addView(column);
            View bar = new FrameLayout(activity);
            bar.setId(BAR_ID);
            bar.setBackgroundColor(Color.BLACK);
            root.addView(bar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 138, android.view.Gravity.BOTTOM));
            activity.setContentView(root);
            layOut(root);
            assertEquals(1882, anchor.getTop() + cell.getTop());

            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, strip.getVisibility());
            assertEquals("nothing moves before the pager has the room", 0f, layer.getTranslationY(), 0f);

            layOut(root);
            assertEquals(2181, cell.getHeight());
            VideoOverlayHider.applyTo(activity);
            assertEquals(-138f, layer.getTranslationY(), 0f);
            assertEquals("the caption ends where it did, on the bar's top edge",
                    2043f, anchor.getBottom() + layer.getTranslationY(), 0f);
            assertTrue("the guard kept the collapse", SeeThroughBottomBar.collapseAllowed());
            assertEquals(View.GONE, strip.getVisibility());
            assertEquals(Color.TRANSPARENT, ((ColorDrawable) bar.getBackground()).getColor());

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, strip.getVisibility());
            assertEquals(0f, layer.getTranslationY(), 0f);
        } finally {
            VideoOverlayHider.resolveForTests("47.4.3:cnc", 0);
            VideoOverlayHider.resolveForTests("viewpager_container", 0);
            VideoOverlayHider.resolveForTests("widget_container", 0);
        }
    }

    private static void layOut(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2181, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2181);
    }

    @Test
    public void onlyAnOverlayThatWasClearBeforeTheCollapseIsBlamedAfterIt() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            View bar = new View(activity);
            View tall = new View(activity);
            View fresh = new View(activity);
            root.addView(bar);
            root.addView(tall);
            root.addView(fresh);
            activity.setContentView(root);
            bar.layout(0, 900, 1080, 1000);
            // Already reaches under the bar with the spacer in place: nothing to learn from it.
            tall.layout(0, 0, 1080, 1000);
            SeeThroughBottomBar.judge(bar, Arrays.asList(tall), false);
            assertFalse(SeeThroughBottomBar.judge(bar, Arrays.asList(tall), true));
            // Never measured before, so never blamed.
            fresh.layout(0, 800, 1080, 1000);
            assertFalse(SeeThroughBottomBar.judge(bar, Arrays.asList(fresh), true));
            assertTrue(SeeThroughBottomBar.collapseAllowed());
        }
    }

    @Test
    public void anOverlayInAnotherCellIsNotJudged() {
        assertFalse(SeeThroughBottomBar.reachesUnder(1200f, 1900f, 900f));
        assertFalse(SeeThroughBottomBar.reachesUnder(-800f, -100f, 900f));
        assertFalse(SeeThroughBottomBar.reachesUnder(700f, 901f, 900f));
        assertTrue(SeeThroughBottomBar.reachesUnder(700f, 960f, 900f));
    }
}

package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.Before;
import org.junit.Test;
import org.robolectric.Robolectric;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The caption and music line come back when their switch goes off, unlike the prompts,
 * which only need hiding until the next video.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoOverlayHiderTest {
    /** Stands in for the clear display event TikTok posts. */
    public static final class ClearEvent {
        public boolean LIZ;
        public int LIZIZ;

        ClearEvent(boolean clear, int type) {
            LIZ = clear;
            LIZIZ = type;
        }
    }

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        // The id cache is static. A cell id left by an earlier test limits the walk to a cell this
        // test's layout may not have, and its rail buttons were never found.
        VideoOverlayHider.resolveForTests("view_rootview", 0);
    }

    @Test
    public void hiddenSeekbarDefeatsNativeAlphaWritesWithoutAnyOtherFade() {
        assertHiddenSeekbarDefeatsNativeAlphaWrites(100, false);
    }

    @Test
    public void hiddenSeekbarDefeatsNativeAlphaWritesWhileOtherControlsAreFaded() {
        assertHiddenSeekbarDefeatsNativeAlphaWrites(50, false);
    }

    @Test
    public void hiddenSeekbarDefeatsNativeAlphaWritesAfterCommentsRebindTheRow() {
        assertHiddenSeekbarDefeatsNativeAlphaWrites(50, true);
    }

    private void assertHiddenSeekbarDefeatsNativeAlphaWrites(int fade, boolean rebind) {
        int exitId = 0x7f0a0f41, seekId = 0x7f0a0f42, railId = 0x7f0a0f43;
        VideoOverlayHider.resolveForTests("47.4.3:e_3", exitId);
        VideoOverlayHider.resolveForTests("video_seek_bar", seekId);
        VideoOverlayHider.resolveForTests("47.4.3:mud", railId);
        boolean previousHide = Settings.HIDE_CLEAR_DISPLAY_CONTROLS.get();
        int previousFade = Settings.FADE_CONTROLS_OPACITY.get();
        Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(true);
        Settings.FADE_CONTROLS_OPACITY.save(fade);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout clearBar = new FrameLayout(activity);
            View exit = new View(activity);
            exit.setId(exitId);
            clearBar.addView(exit);
            root.addView(clearBar);
            View rail = new View(activity);
            rail.setId(railId);
            root.addView(rail);
            View row = new View(activity);
            row.setId(seekId);
            row.setAlpha(0.6f);
            root.addView(row);
            activity.setContentView(root);
            View content = activity.findViewById(android.R.id.content);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0f, row.getAlpha(), 0f);
            View previousRow = row;
            if (rebind) {
                root.removeView(row);
                row = new View(activity);
                row.setId(seekId);
                row.setAlpha(0.8f);
                root.addView(row);
                VideoOverlayHider.applyTo(activity);
                previousRow.setAlpha(0.4f);
            }

            // TikTok shows a paused/rebound seekbar by writing alpha without another layout.
            row.setAlpha(1f);
            content.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("native alpha escaped the hidden seekbar hold", 0f, row.getAlpha(), 0f);
            assertEquals("the transparent seekbar must still take drags", View.VISIBLE, row.getVisibility());
            if (rebind) assertEquals("a detached row was changed", 0.4f, previousRow.getAlpha(), 0f);

            Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(false);
            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(activity);
            assertEquals("disabling lost the latest native animation opacity", 1f, row.getAlpha(), 0f);
            Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(true);
            Settings.FADE_CONTROLS_OPACITY.save(fade);
            VideoOverlayHider.applyTo(activity);

            // A later native write, this time followed by layout, becomes the value to restore.
            row.setAlpha(0.7f);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0f, row.getAlpha(), 0f);
            Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(false);
            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(activity);
            assertEquals("disabling restored a stale native opacity", 0.7f, row.getAlpha(), 0f);
            row.setAlpha(0.9f);
            content.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("a disabled hold still changed the row", 0.9f, row.getAlpha(), 0f);
        } finally {
            Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(previousHide);
            Settings.FADE_CONTROLS_OPACITY.save(previousFade);
            VideoOverlayHider.resolveForTests("47.4.3:e_3", 0);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:mud", 0);
        }
    }

    /**
     * Leaving a faded Clear display holds the whole level until TikTok's own opacity is back at
     * full (#84). A control TikTok rests lower never gets there, so the hold lets go after a second
     * and the control goes back to a share of TikTok's opacity.
     */
    @Test
    public void theWholeLevelLetsGoOfAControlTikTokRestsBelowFull() {
        View control = new View(context);
        VideoOverlayHider.setFaded(control, 50, true);
        control.setAlpha(0f);
        VideoOverlayHider.setFaded(control, 50, true);
        assertEquals("kept in sight while TikTok clears it", 0.5f, control.getAlpha(), 0f);

        control.setAlpha(0.6f);
        VideoOverlayHider.setFaded(control, 50, false);
        assertEquals("held while TikTok's restore runs", 0.5f, control.getAlpha(), 0f);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500));
        VideoOverlayHider.setFaded(control, 50, false);
        assertEquals(0.5f, control.getAlpha(), 0f);

        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(600));
        VideoOverlayHider.setFaded(control, 50, false);
        assertEquals("half of the 0.6 TikTok rests it at", 0.3f, control.getAlpha(), 1e-6f);
        VideoOverlayHider.setFaded(control, 100);
        assertEquals(0.6f, control.getAlpha(), 1e-6f);
    }

    @Test
    public void turningTheSwitchOffPutsTheViewBack() {
        View caption = new View(context);

        VideoOverlayHider.setHidden(caption, true);
        assertEquals(View.GONE, caption.getVisibility());

        // Runs on every layout pass, so it has to stay put.
        VideoOverlayHider.setHidden(caption, true);
        assertEquals(View.GONE, caption.getVisibility());

        VideoOverlayHider.setHidden(caption, false);
        assertEquals(View.VISIBLE, caption.getVisibility());
    }

    @Test
    public void aViewTikTokHidItselfIsNotForcedBackOn() {
        View hiddenByTikTok = new View(context);
        hiddenByTikTok.setVisibility(View.GONE);

        // The switch was never on for this view, so nothing here hid it.
        VideoOverlayHider.setHidden(hiddenByTikTok, false);
        assertEquals(View.GONE, hiddenByTikTok.getVisibility());
    }

    @Test
    public void aMissingViewIsHarmless() {
        VideoOverlayHider.setHidden(null, true);
        VideoOverlayHider.setHidden(null, false);
    }

    @Test
    public void anInvisibleViewComesBackInvisible() {
        // TikTok keeps the music disc invisible on a photo post to hold its space.
        View disc = new View(context);
        disc.setVisibility(View.INVISIBLE);

        VideoOverlayHider.setHidden(disc, true);
        assertEquals(View.GONE, disc.getVisibility());
        VideoOverlayHider.setHidden(disc, false);
        assertEquals(View.INVISIBLE, disc.getVisibility());
    }

    @Test
    public void feedFurnitureOutsideAFeedCellIsLeftAlone() {
        // The profile's Favorites page is a LinearLayout carrying id/f7u, the survey card's
        // id, and Hide feed surveys took the whole tab with it. Furniture is only hidden
        // under a feed cell root now.
        // Ids apart from every other fixture in this class: the id cache is static, and a
        // view matching two targets is hidden by one and put back by the other.
        int surveyId = 0x7f0a0a11;
        int cellId = 0x7f0a0a12;
        int captionId = 0x7f0a0a13;
        VideoOverlayHider.resolveForTests("47.4.3:f92", surveyId);
        VideoOverlayHider.resolveForTests("desc", captionId);
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout root = new LinearLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(cellId);
            View survey = new View(activity);
            survey.setId(surveyId);
            View caption = new View(activity);
            caption.setId(captionId);
            cell.addView(survey);
            cell.addView(caption);
            FrameLayout profile = new FrameLayout(activity);
            LinearLayout favoritesPage = new LinearLayout(activity);
            favoritesPage.setId(surveyId);
            profile.addView(favoritesPage);
            root.addView(cell);
            root.addView(profile);
            activity.setContentView(root);

            Settings.HIDE_FEED_SURVEYS.save(true);
            Settings.HIDE_FEED_CAPTION.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, survey.getVisibility());
            assertEquals(View.GONE, caption.getVisibility());
            assertEquals("the Favorites page shares the survey card's id and has to stay",
                    View.VISIBLE, favoritesPage.getVisibility());
        } finally {
            Settings.HIDE_FEED_SURVEYS.save(false);
            Settings.HIDE_FEED_CAPTION.save(false);
            VideoOverlayHider.resolveForTests("view_rootview", 0);
        }
    }

    /**
     * Caption above comments puts the whole caption at the top of the comments, so the caption
     * over the video goes too, without Hide the caption on, and comes back when it goes off.
     * Asked for from the S25 on 2026-09-25: with the caption in the comments it showed twice.
     */
    @Test
    public void captionAboveCommentsTakesTheCaptionOffTheVideo() {
        int cellId = 0x7f0a0a51;
        int captionId = 0x7f0a0a52;
        VideoOverlayHider.resolveForTests("desc", captionId);
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout root = new LinearLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(cellId);
            View caption = new View(activity);
            caption.setId(captionId);
            cell.addView(caption);
            root.addView(cell);
            activity.setContentView(root);

            Settings.HIDE_FEED_CAPTION.save(false);
            Settings.CAPTION_ABOVE_COMMENTS.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the caption stayed over the video", View.GONE, caption.getVisibility());

            Settings.CAPTION_ABOVE_COMMENTS.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the caption did not come back", View.VISIBLE, caption.getVisibility());
        } finally {
            Settings.CAPTION_ABOVE_COMMENTS.save(false);
            Settings.HIDE_FEED_CAPTION.save(false);
            VideoOverlayHider.resolveForTests("view_rootview", 0);
        }
    }

    @Test
    public void theChosenButtonSizeGrowsTheGlyphAndSurvivesTikToksOwnAnimation() {
        // The rail on the S22 is a column of 180 by 169 slots with a 126 px icon frame and the
        // count in the rest, and the frame clips its children. So the size goes on the glyph
        // inside the button, grown from its bottom edge, with the frames above it unclipped,
        // and never beyond a quarter, which is all the column has room for. TikTok animates
        // scale on these glyphs itself and writes 1 back when it is done, so the size has to
        // hold through a reset and stop holding once Normal is chosen again. The music row
        // spans the width and is left alone. Before 2026-09-17 none of this reached a phone:
        // the cell root the walk scoped to was a sibling of the rail, so it found nothing.
        String[] names = {"47.4.3:i_t", "47.4.3:g8a", "47.4.3:eq2", "47.4.3:i9a", "47.4.3:pop", "47.4.3:w_s"};
        int cellId = 0x7f0a0a31;
        int actionBarId = 0x7f0a0a32;
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        VideoOverlayHider.resolveForTests("47.4.3:mud", actionBarId);
        for (int i = 0; i < names.length; i++) {
            VideoOverlayHider.resolveForTests(names[i], 0x7f0a0a40 + i);
        }
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(cellId);
            FrameLayout column = new FrameLayout(activity);
            column.setId(actionBarId);
            cell.addView(column);
            View[] buttons = new View[names.length];
            View[] glyphs = new View[names.length];
            for (int i = 0; i < names.length; i++) {
                FrameLayout button = new FrameLayout(activity);
                button.setId(0x7f0a0a40 + i);
                // A glyph-sized leaf (40 px at density 1 sits inside the 22 to 52 dp window) and
                // a count row too wide to be one. The last two glyphs have no size yet, the way an
                // unlaid-out cell arrives.
                View glyph = new View(activity);
                boolean sized = i < 4;
                button.addView(glyph, new FrameLayout.LayoutParams(sized ? 40 : 0, sized ? 40 : 0));
                View count = new View(activity);
                button.addView(count, new FrameLayout.LayoutParams(120, 12));
                column.addView(button, new FrameLayout.LayoutParams(120, 80));
                buttons[i] = button;
                glyphs[i] = glyph;
            }
            activity.setContentView(cell);
            View content = activity.findViewById(android.R.id.content);
            content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, 1080, 1920);

            Settings.TOUCH_TARGET_SCALE.save("1.25");
            VideoOverlayHider.applyTo(activity);
            for (int i = 0; i < 4; i++) {
                assertEquals(names[i] + " glyph", 1.25f, glyphs[i].getScaleX(), 0f);
                assertEquals(names[i] + " glyph", 1.25f, glyphs[i].getScaleY(), 0f);
                assertEquals("the button itself is never scaled", 1f, buttons[i].getScaleX(), 0f);
            }
            assertEquals("the music row is left alone", 1f, glyphs[4].getScaleX(), 0f);
            assertEquals("a glyph with no size yet cannot be told from padding", 1f, glyphs[5].getScaleX(), 0f);
            assertEquals("a sized glyph grows from its bottom centre", 20f, glyphs[1].getPivotX(), 0f);
            assertEquals(40f, glyphs[1].getPivotY(), 0f);
            assertFalse("the frame around a grown glyph must stop clipping it",
                    ((FrameLayout) buttons[1]).getClipChildren());
            assertTrue("the column itself keeps clipping", column.getClipChildren());

            // The cell lays out, the glyph has a size, and the next pass takes it.
            glyphs[5].setLayoutParams(new FrameLayout.LayoutParams(40, 40));
            content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, 1080, 1920);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a glyph laid out after the first pass is scaled on the next", 1.25f, glyphs[5].getScaleX(), 0f);

            // A stored size from the days the row offered more is read as the most that fits.
            Settings.TOUCH_TARGET_SCALE.save("2");
            VideoOverlayHider.applyTo(activity);
            assertEquals(1.25f, glyphs[1].getScaleX(), 0f);

            // TikTok's animation ends and writes 1 back; the next frame corrects it.
            glyphs[1].setScaleX(1f);
            glyphs[1].setScaleY(1f);
            glyphs[3].setScaleX(1.1f);
            content.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("the reset was not corrected before the frame", 1.25f, glyphs[1].getScaleX(), 0f);
            assertEquals(1.25f, glyphs[1].getScaleY(), 0f);
            assertEquals(1.25f, glyphs[3].getScaleX(), 0f);

            // Back to Normal: the walk writes 1, and the frame pass leaves the glyphs alone.
            Settings.TOUCH_TARGET_SCALE.save("1");
            VideoOverlayHider.applyTo(activity);
            for (View glyph : glyphs) {
                assertEquals(1f, glyph.getScaleX(), 0f);
            }
            glyphs[1].setScaleX(1.3f);
            content.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Normal must not keep rewriting TikTok's own scale", 1.3f, glyphs[1].getScaleX(), 0f);
        } finally {
            Settings.TOUCH_TARGET_SCALE.resetToDefault();
            VideoOverlayHider.resolveForTests("view_rootview", 0);
            VideoOverlayHider.resolveForTests("47.4.3:mud", 0);
            for (String name : names) {
                VideoOverlayHider.resolveForTests(name, 0);
            }
        }
    }

    @Test
    public void withoutACellRootTheWalkTakesTheWholeWindowAsBefore() {
        // A build that renames the cell root must not turn every hide switch off; the walk
        // falls back to the whole window and the hook status names the miss.
        int surveyId = 0x7f0a0a21;
        VideoOverlayHider.resolveForTests("47.4.3:f92", surveyId);
        VideoOverlayHider.resolveForTests("view_rootview", 0);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout root = new LinearLayout(activity);
            View survey = new View(activity);
            survey.setId(surveyId);
            root.addView(survey);
            activity.setContentView(root);

            Settings.HIDE_FEED_SURVEYS.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, survey.getVisibility());
        } finally {
            Settings.HIDE_FEED_SURVEYS.save(false);
        }
    }

    @Test
    public void theSurveyIsFoundByItsFortySevenNameAndTheOldNameHidesNothing() {
        // Every layout the feed cell's survey stubs inflate has the root f7u on 47.0.3, which
        // 46.2.3 called ezp. On 47.0.3 ezp is a label in the paid series panel instead, so a
        // view under a cell that carries it is not a survey.
        int cellId = 0x7f0a0a51;
        int surveyId = 0x7f0a0a52;
        int oldNameId = 0x7f0a0a53;
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        VideoOverlayHider.resolveForTests("47.4.3:f92", surveyId);
        VideoOverlayHider.resolveForTests("ezp", oldNameId);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(cellId);
            TextView label = new TextView(activity);
            label.setId(oldNameId);
            cell.addView(label);
            activity.setContentView(cell);

            // An ordinary post: no survey, so nothing under the current name to prefer.
            Settings.HIDE_FEED_SURVEYS.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a 46.2.3 name hid a 47.0.3 view", View.VISIBLE, label.getVisibility());

            View survey = new View(activity);
            survey.setId(surveyId);
            cell.addView(survey);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, survey.getVisibility());
            assertEquals(View.VISIBLE, label.getVisibility());
        } finally {
            Settings.HIDE_FEED_SURVEYS.save(false);
            VideoOverlayHider.resolveForTests("view_rootview", 0);
            VideoOverlayHider.resolveForTests("47.4.3:f92", 0);
            VideoOverlayHider.resolveForTests("ezp", 0);
        }
    }

    @Test
    public void visualSearchHidesItsFortySevenLayerAndPillAndNothingTheOldNamesNameNow() {
        // SearchVisualSearchContainerComponentV2 loads fo on 47.0.3 where it loaded fb, and the
        // VTag processors inflate a pill whose root is d4 where it was cn. On 47.0.3 fb is a row
        // of the visual search camera page and cn a row of the floating card in search results.
        int layerId = 0x7e0a0a61;
        int pillId = 0x7e0a0a62;
        int cameraRowId = 0x7e0a0a63;
        int floatingCardRowId = 0x7e0a0a64;
        VideoOverlayHider.resolveSearchModuleForTests("47.4.3:fr", layerId);
        VideoOverlayHider.resolveSearchModuleForTests("d4", pillId);
        VideoOverlayHider.resolveSearchModuleForTests("fb", cameraRowId);
        VideoOverlayHider.resolveSearchModuleForTests("cn", floatingCardRowId);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            LinearLayout cameraRow = new LinearLayout(activity);
            cameraRow.setId(cameraRowId);
            LinearLayout floatingCardRow = new LinearLayout(activity);
            floatingCardRow.setId(floatingCardRowId);
            root.addView(cameraRow);
            root.addView(floatingCardRow);
            activity.setContentView(root);

            // No prompt on screen, so there is nothing under the current names to prefer.
            Settings.HIDE_VISUAL_SEARCH.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a 46.2.3 name hid the camera page's row", View.VISIBLE, cameraRow.getVisibility());
            assertEquals("a 46.2.3 name hid the floating card's row",
                    View.VISIBLE, floatingCardRow.getVisibility());

            FrameLayout layer = new FrameLayout(activity);
            layer.setId(layerId);
            LinearLayout pill = new LinearLayout(activity);
            pill.setId(pillId);
            root.addView(layer);
            root.addView(pill);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, layer.getVisibility());
            assertEquals(View.GONE, pill.getVisibility());
            assertEquals(View.VISIBLE, cameraRow.getVisibility());
            assertEquals(View.VISIBLE, floatingCardRow.getVisibility());
        } finally {
            Settings.HIDE_VISUAL_SEARCH.save(false);
            for (String name : new String[]{"47.4.3:fr", "d4", "fb", "cn"}) {
                VideoOverlayHider.resolveSearchModuleForTests(name, 0);
            }
        }
    }

    @Test
    public void anOrdinaryPostWithoutASurveyDoesNotReportTheBuildBroken() {
        int cellId = 0x7f0a0a22;
        int surveyId = 0x7f0a0a23;
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        VideoOverlayHider.resolveForTests("47.4.3:f92", surveyId);
        HookStatus.clear();
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(cellId);
            activity.setContentView(cell);

            Settings.HIDE_FEED_SURVEYS.save(true);
            VideoOverlayHider.applyTo(activity);

            assertTrue("a survey is optional content, not a required build anchor: "
                            + HookStatus.missing("overlay"),
                    HookStatus.missing("overlay").stream().noneMatch(line -> line.contains("47.4.3:f92")));
        } finally {
            Settings.HIDE_FEED_SURVEYS.save(false);
            VideoOverlayHider.resolveForTests("view_rootview", 0);
            VideoOverlayHider.resolveForTests("47.4.3:f92", 0);
            HookStatus.clear();
        }
    }

    @Test
    public void aPassHidesEveryCellsCaptionAndColumnAndPutsThemBack() {
        // The feed keeps the previous and next cells inflated beside the one on screen,
        // each with its own caption and action column under the same ids.
        int captionId = 0x7f0a0001;
        int columnId = 0x7f0a0002;
        VideoOverlayHider.resolveForTests("desc", captionId);
        VideoOverlayHider.resolveForTests("47.4.3:mud", columnId);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout root = new LinearLayout(activity);
            View[] captions = new View[3];
            View[] columns = new View[3];
            for (int i = 0; i < 3; i++) {
                FrameLayout cell = new FrameLayout(activity);
                captions[i] = new View(activity);
                captions[i].setId(captionId);
                columns[i] = new View(activity);
                columns[i].setId(columnId);
                cell.addView(new View(activity));
                cell.addView(captions[i]);
                cell.addView(columns[i]);
                root.addView(cell);
            }
            activity.setContentView(root);

            Settings.HIDE_FEED_CAPTION.save(true);
            Settings.HIDE_FEED_ACTION_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            for (int i = 0; i < 3; i++) {
                assertEquals("caption " + i, View.GONE, captions[i].getVisibility());
                assertEquals("column " + i, View.VISIBLE, columns[i].getVisibility());
            }

            Settings.HIDE_FEED_CAPTION.save(false);
            Settings.HIDE_FEED_ACTION_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            for (int i = 0; i < 3; i++) {
                assertEquals("caption " + i, View.VISIBLE, captions[i].getVisibility());
                assertEquals("column " + i, View.GONE, columns[i].getVisibility());
            }

            Settings.HIDE_FEED_ACTION_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            for (int i = 0; i < 3; i++) {
                assertEquals("column " + i, View.VISIBLE, columns[i].getVisibility());
            }
        } finally {
            Settings.HIDE_FEED_CAPTION.save(false);
            Settings.HIDE_FEED_ACTION_BAR.save(false);
        }
        assertEquals(0, VideoOverlayHider.viewsWithId(new View(context), 0).size());
        assertEquals(0, VideoOverlayHider.viewsWithId(null, captionId).size());
    }

    @Test
    public void olderBuildNamesAreNeverTriedEvenInACellWithoutTheCurrentOnes() {
        // TikTok hands its short names out again on every build, and on 47.0.3 each 46.x name
        // here is some other view. They used to be tried whenever the current name found
        // nothing, so a cell without a like button lost whatever 47.0.3 calls fws.
        int cellId = 0x7f0a0500;
        int currentColumnId = 0x7f0a0501;
        int oldColumnId = 0x7f0a0502;
        int currentLikeId = 0x7f0a0503;
        int oldLikeId = 0x7f0a0504;
        int currentCountRowId = 0x7f0a0505;
        int oldCountRowId = 0x7f0a0506;
        int currentCountTextId = 0x7f0a0507;
        int oldCountTextId = 0x7f0a0508;
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        VideoOverlayHider.resolveForTests("47.4.3:mud", currentColumnId);
        VideoOverlayHider.resolveForTests("kzj", oldColumnId);
        VideoOverlayHider.resolveForTests("47.4.3:g8a", currentLikeId);
        VideoOverlayHider.resolveForTests("fws", oldLikeId);
        VideoOverlayHider.resolveForTests("47.4.3:g8c", currentCountRowId);
        VideoOverlayHider.resolveForTests("fwu", oldCountRowId);
        VideoOverlayHider.resolveForTests("47.4.3:g8b", currentCountTextId);
        VideoOverlayHider.resolveForTests("fwt", oldCountTextId);

        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(cellId);
            // Views under the 46.x names, inside the cell, standing in for whatever 47.0.3
            // gives those names.
            View oldColumn = new View(activity);
            oldColumn.setId(oldColumnId);
            View oldLike = new View(activity);
            oldLike.setId(oldLikeId);
            LinearLayout oldCountRow = new LinearLayout(activity);
            oldCountRow.setId(oldCountRowId);
            TextView oldCountText = new TextView(activity);
            oldCountText.setId(oldCountTextId);
            oldCountRow.addView(oldCountText);
            cell.addView(oldColumn);
            cell.addView(oldLike);
            cell.addView(oldCountRow);
            activity.setContentView(cell);

            // Nothing under a current name yet, the case the fallback used to answer.
            Settings.HIDE_FEED_ACTION_BAR.save(true);
            Settings.HIDE_RAIL_LIKE.save(true);
            Settings.HIDE_RAIL_COUNTS.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, oldColumn.getVisibility());
            assertEquals(View.VISIBLE, oldLike.getVisibility());
            assertEquals(View.VISIBLE, oldCountRow.getVisibility());
            assertEquals(View.VISIBLE, oldCountText.getVisibility());

            View currentColumn = new View(activity);
            currentColumn.setId(currentColumnId);
            View currentLike = new View(activity);
            currentLike.setId(currentLikeId);
            LinearLayout currentCountRow = new LinearLayout(activity);
            currentCountRow.setId(currentCountRowId);
            TextView currentCountText = new TextView(activity);
            currentCountText.setId(currentCountTextId);
            currentCountRow.addView(currentCountText);
            cell.addView(currentColumn);
            cell.addView(currentLike);
            cell.addView(currentCountRow);
            VideoOverlayHider.applyTo(activity);

            assertEquals(View.GONE, currentColumn.getVisibility());
            assertEquals(View.GONE, currentLike.getVisibility());
            assertEquals(View.GONE, currentCountRow.getVisibility());
            assertEquals(View.GONE, currentCountText.getVisibility());
            assertEquals(View.VISIBLE, oldColumn.getVisibility());
            assertEquals(View.VISIBLE, oldLike.getVisibility());
            assertEquals(View.VISIBLE, oldCountRow.getVisibility());
            assertEquals(View.VISIBLE, oldCountText.getVisibility());

            Settings.HIDE_FEED_ACTION_BAR.save(false);
            Settings.HIDE_RAIL_LIKE.save(false);
            Settings.HIDE_RAIL_COUNTS.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, currentColumn.getVisibility());
            assertEquals(View.VISIBLE, currentLike.getVisibility());
            assertEquals(View.VISIBLE, currentCountRow.getVisibility());
            assertEquals(View.VISIBLE, currentCountText.getVisibility());
        } finally {
            Settings.HIDE_FEED_ACTION_BAR.save(false);
            Settings.HIDE_RAIL_LIKE.save(false);
            Settings.HIDE_RAIL_COUNTS.save(false);
            String[] names = {"view_rootview", "47.4.3:mud", "kzj", "47.4.3:g8a", "fws",
                    "47.4.3:g8c", "fwu", "47.4.3:g8b", "fwt"};
            for (String name : names) VideoOverlayHider.resolveForTests(name, 0);
        }
    }

    @Test
    public void aPeekInsideTheGraceWindowIsNotHiddenAgain() {
        // On Android 11 and up a swipe from the top shows the bar transiently and the
        // insets report it visible, so the layout pass must not answer with another hide.
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            View decor = activity.getWindow().getDecorView();
            assertTrue(VideoOverlayHider.rehideAllowed(decor, android.os.SystemClock.uptimeMillis()));

            VideoOverlayHider.setStatusBarHidden(activity, true);
            // Robolectric's clock moves a few milliseconds during the hide, so the two
            // probes sit well inside and just past the window rather than on its edge.
            long hiddenAt = android.os.SystemClock.uptimeMillis();
            assertFalse(VideoOverlayHider.rehideAllowed(decor, hiddenAt + VideoOverlayHider.STATUS_BAR_PEEK_MS / 2));
            assertTrue(VideoOverlayHider.rehideAllowed(decor, hiddenAt + VideoOverlayHider.STATUS_BAR_PEEK_MS));

            VideoOverlayHider.setStatusBarHidden(activity, false);
            assertTrue(VideoOverlayHider.rehideAllowed(decor, hiddenAt + 1));
        }
    }

    @Test
    public void eachRailButtonHasItsOwnSwitchInEveryCell() {
        // The column keeps its six buttons under fixed ids, and the feed keeps the cells on
        // either side inflated with the same ones.
        String[] names = {"47.4.3:i_t", "47.4.3:g8a", "47.4.3:eq2", "47.4.3:i9a", "47.4.3:pop", "47.4.3:w_s"};
        int[] ids = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            ids[i] = 0x7f0a0100 + i;
            VideoOverlayHider.resolveForTests(names[i], ids[i]);
        }
        var switches = new app.morphe.extension.shared.settings.BooleanSetting[]{
                Settings.HIDE_RAIL_FOLLOW, Settings.HIDE_RAIL_LIKE, Settings.HIDE_RAIL_COMMENTS,
                Settings.HIDE_RAIL_FAVOURITE, Settings.HIDE_RAIL_MUSIC, Settings.HIDE_RAIL_SHARE,
        };

        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout root = new LinearLayout(activity);
            View[][] buttons = new View[2][names.length];
            for (int cell = 0; cell < 2; cell++) {
                LinearLayout column = new LinearLayout(activity);
                for (int i = 0; i < names.length; i++) {
                    buttons[cell][i] = new View(activity);
                    buttons[cell][i].setId(ids[i]);
                    column.addView(buttons[cell][i]);
                }
                root.addView(column);
            }
            activity.setContentView(root);

            for (var setting : switches) setting.save(false);

            for (int target = 0; target < names.length; target++) {
                switches[target].save(true);
                VideoOverlayHider.applyTo(activity);
                for (int cell = 0; cell < 2; cell++) {
                    for (int i = 0; i < names.length; i++) {
                        assertEquals(names[i] + " in cell " + cell,
                                i == target ? View.GONE : View.VISIBLE,
                                buttons[cell][i].getVisibility());
                    }
                }
                switches[target].save(false);
            }

            // Everything off puts every button back.
            VideoOverlayHider.applyTo(activity);
            for (int cell = 0; cell < 2; cell++) {
                for (int i = 0; i < names.length; i++) {
                    assertEquals(names[i], View.VISIBLE, buttons[cell][i].getVisibility());
                }
            }
        }
    }

    /**
     * A hidden button takes its own count with it.
     *
     * <p>"Hide like button" hid the heart and left "282" standing under an empty space, half
     * under the avatar (S22, 2026-09-15), which read as the switch doing nothing and was
     * reported as exactly that. The other counts are not touched: they belong to buttons
     * that are still there.
     */
    @Test
    public void aHiddenButtonTakesItsOwnCountWithIt() {
        String[] rowNames = {"47.4.3:g8c", "47.4.3:ek4", "47.4.3:i8a", "47.4.3:w70"};
        String[] textNames = {"47.4.3:g8b", "47.4.3:ek3", "47.4.3:i8_", "47.4.3:w6z"};
        String[] buttonNames = {"47.4.3:g8a", "47.4.3:eq2", "47.4.3:i9a", "47.4.3:w_s"};
        for (int i = 0; i < rowNames.length; i++) {
            VideoOverlayHider.resolveForTests(rowNames[i], 0x7f0a0300 + i);
            VideoOverlayHider.resolveForTests(textNames[i], 0x7f0a0310 + i);
            VideoOverlayHider.resolveForTests(buttonNames[i], 0x7f0a0320 + i);
        }
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout root = new LinearLayout(activity);
            View[] buttons = new View[buttonNames.length];
            LinearLayout[] rows = new LinearLayout[rowNames.length];
            TextView[] counts = new TextView[textNames.length];
            for (int i = 0; i < rowNames.length; i++) {
                buttons[i] = new View(activity);
                buttons[i].setId(0x7f0a0320 + i);
                root.addView(buttons[i]);
                rows[i] = new LinearLayout(activity);
                rows[i].setId(0x7f0a0300 + i);
                counts[i] = new TextView(activity);
                counts[i].setId(0x7f0a0310 + i);
                counts[i].setText(Integer.toString(200 + i));
                rows[i].addView(counts[i]);
                root.addView(rows[i]);
            }
            activity.setContentView(root);

            Settings.HIDE_RAIL_COUNTS.save(false);
            Settings.HIDE_RAIL_LIKE.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the like button stayed", View.GONE, buttons[0].getVisibility());
            assertEquals("the like count stood under an empty space", View.GONE, rows[0].getVisibility());
            assertEquals("the like count's text stood on its own", View.GONE, counts[0].getVisibility());
            for (int i = 1; i < rowNames.length; i++) {
                assertEquals(buttonNames[i] + " went with the like button", View.VISIBLE, buttons[i].getVisibility());
                assertEquals(rowNames[i] + " went with the like count", View.VISIBLE, rows[i].getVisibility());
                assertEquals(textNames[i], View.VISIBLE, counts[i].getVisibility());
            }

            Settings.HIDE_RAIL_LIKE.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, buttons[0].getVisibility());
            assertEquals("the like count did not come back with its button", View.VISIBLE, rows[0].getVisibility());
            assertEquals(View.VISIBLE, counts[0].getVisibility());

            // The far end of the map as well: share is the sixth button and the fourth count.
            Settings.HIDE_RAIL_SHARE.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the share count stood under an empty space", View.GONE, rows[3].getVisibility());
            assertEquals(View.GONE, counts[3].getVisibility());
            assertEquals("the like count went with the share button", View.VISIBLE, rows[0].getVisibility());
        } finally {
            Settings.HIDE_RAIL_LIKE.save(false);
            Settings.HIDE_RAIL_SHARE.save(false);
            Settings.HIDE_RAIL_COUNTS.save(false);
        }
    }

    /**
     * The like button's row goes with it, so the rail closes up (#190).
     *
     * <p>47.4.3 stacks each button in a slot, and the like button alone sits inside a 120 px row
     * (g8m) with its count. Hiding the heart and the count left that row standing, a hole
     * between the avatar and the comments (Login2, 2026-10-18). A button that is its slot's own
     * child needs nothing more, and the slot itself is never touched.
     */
    @Test
    public void theLikeButtonsRowGoesWithItSoTheRailClosesUp() {
        VideoOverlayHider.resolveForTests("47.4.3:exj", 0x7f0a0340);
        VideoOverlayHider.resolveForTests("47.4.3:g8a", 0x7f0a0341);
        VideoOverlayHider.resolveForTests("47.4.3:g8c", 0x7f0a0342);
        VideoOverlayHider.resolveForTests("47.4.3:eq2", 0x7f0a0343);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);

            FrameLayout likeSlot = new FrameLayout(activity);
            likeSlot.setId(0x7f0a0340);
            LinearLayout likeRow = new LinearLayout(activity);
            View heart = new View(activity);
            heart.setId(0x7f0a0341);
            LinearLayout count = new LinearLayout(activity);
            count.setId(0x7f0a0342);
            count.addView(new TextView(activity));
            likeRow.addView(heart);
            likeRow.addView(count);
            likeSlot.addView(likeRow, new FrameLayout.LayoutParams(128, 120));
            column.addView(likeSlot);

            FrameLayout commentSlot = new FrameLayout(activity);
            commentSlot.setId(0x7f0a0340);
            View comment = new View(activity);
            comment.setId(0x7f0a0343);
            commentSlot.addView(comment, new FrameLayout.LayoutParams(128, 120));
            column.addView(commentSlot);
            activity.setContentView(column);

            Settings.HIDE_RAIL_LIKE.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, heart.getVisibility());
            assertEquals(View.GONE, count.getVisibility());
            assertEquals("the empty row kept its 120 px in the rail", View.GONE, likeRow.getVisibility());
            assertEquals("the slot is TikTok's to show", View.VISIBLE, likeSlot.getVisibility());
            assertEquals(View.VISIBLE, comment.getVisibility());

            Settings.HIDE_RAIL_LIKE.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, heart.getVisibility());
            assertEquals("the row didn't come back with the heart", View.VISIBLE, likeRow.getVisibility());

            Settings.HIDE_RAIL_COMMENTS.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, comment.getVisibility());
            assertEquals("a button's own slot was hidden", View.VISIBLE, commentSlot.getVisibility());
            assertEquals(View.VISIBLE, likeRow.getVisibility());

            // A row that still holds something showing stays.
            View other = new View(activity);
            likeRow.addView(other);
            heart.setVisibility(View.GONE);
            count.setVisibility(View.GONE);
            VideoOverlayHider.collapseWrappers(heart, true, 0x7f0a0340);
            assertEquals(View.VISIBLE, likeRow.getVisibility());
        } finally {
            Settings.HIDE_RAIL_LIKE.save(false);
            Settings.HIDE_RAIL_COMMENTS.save(false);
            VideoOverlayHider.resolveForTests("47.4.3:exj", 0);
            VideoOverlayHider.resolveForTests("47.4.3:g8a", 0);
            VideoOverlayHider.resolveForTests("47.4.3:g8c", 0);
            VideoOverlayHider.resolveForTests("47.4.3:eq2", 0);
        }
    }

    @Test
    public void countRowsAndTheirInnerTextAnchorsGoWithoutTheButtons() {
        String[] rowNames = {"47.4.3:g8c", "47.4.3:ek4", "47.4.3:i8a", "47.4.3:w70"};
        String[] textNames = {"47.4.3:g8b", "47.4.3:ek3", "47.4.3:i8_", "47.4.3:w6z"};
        String[] buttonNames = {"47.4.3:g8a", "47.4.3:eq2", "47.4.3:i9a", "47.4.3:w_s"};
        int[] rowIds = new int[rowNames.length];
        int[] textIds = new int[textNames.length];
        int[] buttonIds = new int[buttonNames.length];
        for (int i = 0; i < rowNames.length; i++) {
            rowIds[i] = 0x7f0a0200 + i;
            textIds[i] = 0x7f0a0210 + i;
            buttonIds[i] = 0x7f0a0220 + i;
            VideoOverlayHider.resolveForTests(rowNames[i], rowIds[i]);
            VideoOverlayHider.resolveForTests(textNames[i], textIds[i]);
            VideoOverlayHider.resolveForTests(buttonNames[i], buttonIds[i]);
        }

        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout root = new LinearLayout(activity);
            View[] buttons = new View[buttonNames.length];
            View[] knownRows = new View[rowNames.length];
            LinearLayout[] changedRows = new LinearLayout[rowNames.length];
            TextView[] changedRowCounts = new TextView[textNames.length];
            for (int i = 0; i < rowNames.length; i++) {
                buttons[i] = new View(activity);
                buttons[i].setId(buttonIds[i]);
                buttons[i].setClickable(true);
                root.addView(buttons[i]);

                // TikTok 46.2.3 uses the first ID on the count row. Some feed layouts
                // replace that wrapper, but retain the second ID on the numeric TextView.
                knownRows[i] = new LinearLayout(activity);
                knownRows[i].setId(rowIds[i]);
                knownRows[i].setClickable(false);
                knownRows[i].setContentDescription("known count row " + i);
                root.addView(knownRows[i]);

                changedRows[i] = new LinearLayout(activity);
                changedRows[i].setId(0x7f0a0230 + i);
                changedRowCounts[i] = new TextView(activity);
                changedRowCounts[i].setId(textIds[i]);
                changedRowCounts[i].setText(Integer.toString(100 + i));
                changedRows[i].addView(changedRowCounts[i]);
                root.addView(changedRows[i]);
            }
            activity.setContentView(root);

            Settings.HIDE_RAIL_LIKE.save(false);
            Settings.HIDE_RAIL_COUNTS.save(true);
            VideoOverlayHider.applyTo(activity);
            for (int i = 0; i < rowNames.length; i++) {
                assertEquals(rowNames[i], View.GONE, knownRows[i].getVisibility());
                assertEquals(textNames[i], View.GONE, changedRowCounts[i].getVisibility());
                assertEquals("an unknown row stays", View.VISIBLE, changedRows[i].getVisibility());
                assertEquals(buttonNames[i], View.VISIBLE, buttons[i].getVisibility());
                assertTrue(buttonNames[i] + " remains clickable", buttons[i].isClickable());
            }

            // A newly bound feed cell must be covered on the next layout pass too.
            root.removeView(changedRows[0]);
            LinearLayout reboundRow = new LinearLayout(activity);
            TextView reboundCount = new TextView(activity);
            reboundCount.setId(textIds[0]);
            reboundCount.setText("999");
            reboundRow.addView(reboundCount);
            root.addView(reboundRow);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, reboundCount.getVisibility());

            Settings.HIDE_RAIL_COUNTS.save(false);
            VideoOverlayHider.applyTo(activity);
            for (int i = 0; i < rowNames.length; i++) {
                assertEquals(rowNames[i], View.VISIBLE, knownRows[i].getVisibility());
                if (i != 0) {
                    assertEquals(textNames[i], View.VISIBLE, changedRowCounts[i].getVisibility());
                }
            }
            assertEquals("the rebound count comes back", View.VISIBLE, reboundCount.getVisibility());
        } finally {
            Settings.HIDE_RAIL_COUNTS.save(false);
        }
    }

    @Test
    public void clearDisplayKeepsTheTabStripAwayUntilItEnds() {
        int tabStripId = 0x7f0a0011;
        VideoOverlayHider.resolveForTests("47.4.3:uwq", tabStripId);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout root = new LinearLayout(activity);
            View tabStrip = new View(activity);
            tabStrip.setId(tabStripId);
            root.addView(tabStrip);
            activity.setContentView(root);

            // The hider follows the live clear display state, not the stored setting: the
            // automatic path never writes that one. AutomaticClearDisplayTest covers the
            // automatic transition itself.
            Settings.CLEAR_DISPLAY.save(false);
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));

            // The two disagree on purpose: the live state says the controls are hidden, the
            // stored setting says nothing. Reading the setting here would leave the strip up.
            Settings.CLEAR_DISPLAY.save(false);

            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, tabStrip.getVisibility());

            // TikTok puts the strip back on the first swipe; the next layout pass takes it
            // away again, which is the whole point of the mode.
            tabStrip.setVisibility(View.VISIBLE);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, tabStrip.getVisibility());

            // The tap that leaves clear display brings it back.
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, tabStrip.getVisibility());
        }
    }

    /**
     * The automatic path can turn Clear display on before the bottom tabs are laid out after a
     * cold start, and TikTok then leaves them over the first video (#84). They sit outside the
     * cells, go while the mode is on, and come back when it ends. An opened video has no such bar.
     */
    @Test
    public void clearDisplayTakesTheBottomTabsAwayOnTheMainFeedOnly() {
        int tabsId = 0x7f0a0b20;
        int cellId = 0x7f0a0b21;
        VideoOverlayHider.resolveForTests("47.4.3:onp", tabsId);
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        try (var main = Robolectric.buildActivity(Activity.class).setup();
             var detail = Robolectric.buildActivity(
                     com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).setup()) {
            Utils.setContext(main.get());
            FrameLayout root = new FrameLayout(main.get());
            FrameLayout cell = new FrameLayout(main.get());
            cell.setId(cellId);
            root.addView(cell);
            View tabs = new View(main.get());
            tabs.setId(tabsId);
            root.addView(tabs);
            main.get().setContentView(root);
            View detailTabs = new View(detail.get());
            detailTabs.setId(tabsId);
            detail.get().setContentView(detailTabs);

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(main.get());
            assertEquals(View.GONE, tabs.getVisibility());
            VideoOverlayHider.applyTo(detail.get());
            assertEquals(View.VISIBLE, detailTabs.getVisibility());

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            VideoOverlayHider.applyTo(main.get());
            assertEquals(View.VISIBLE, tabs.getVisibility());
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
        }
    }

    /**
     * alexcsi on 0.71.2 (#84): swipe to a creator's profile and back a few times in Clear display
     * and the bottom tabs stay up until the next scroll. The profile is a page of the feed's own
     * pager and the tabs ride on the feed's page, so a pass while the profile showed put them
     * back, and the swipe back only scrolls: no layout, no pass. They now stay away while the
     * feed's page is scrolled aside, and another tab still gets them back.
     */
    @Test
    public void clearDisplayKeepsTheBottomTabsAwayAcrossASwipeToTheProfileAndBack() {
        int tabsId = 0x7f0a0b25;
        int homeId = 0x7f0a0b26;
        int cellId = 0x7f0a0b27;
        VideoOverlayHider.resolveForTests("47.4.3:onp", tabsId);
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        try (var main = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = main.get();
            Utils.setContext(activity);
            FrameLayout pager = new FrameLayout(activity);
            FrameLayout feedPage = new FrameLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(cellId);
            feedPage.addView(cell);
            FrameLayout tabs = new FrameLayout(activity);
            tabs.setId(tabsId);
            View home = new View(activity);
            home.setId(homeId);
            home.setSelected(true);
            tabs.addView(home, new FrameLayout.LayoutParams(60, 40));
            feedPage.addView(tabs, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 40, android.view.Gravity.BOTTOM));
            pager.addView(feedPage, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            activity.setContentView(pager);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("the window never laid out", home.getWidth() > 0 && pager.getWidth() > 0);
            app.morphe.extension.tiktok.blockauthor.FeedVisibility.resolveForTests(
                    activity.getPackageName(), "47.4.3:onh", homeId);

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, tabs.getVisibility());

            // The swipe to the profile, and a layout pass while it's in front.
            pager.scrollTo(pager.getWidth(), 0);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the profile pass put the tabs back", View.GONE, tabs.getVisibility());

            // The swipe back only scrolls, so no pass runs; the tabs are still away.
            pager.scrollTo(0, 0);
            assertEquals(View.GONE, tabs.getVisibility());
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, tabs.getVisibility());

            // Another tab selected is another page: the tabs come back there.
            home.setSelected(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, tabs.getVisibility());
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            app.morphe.extension.tiktok.blockauthor.FeedVisibility.resolveForTests(
                    RuntimeEnvironment.getApplication().getPackageName(), "47.4.3:onh", 0);
        }
    }

    /**
     * The hairline along the tab bar's top edge is the bar's sibling, so with only the bar taken
     * a thin line stayed across the bottom of each new video until TikTok's own clear landed
     * (#84). It goes and comes back with the bar, and one TikTok put away itself stays away.
     */
    @Test
    public void theTabBarsHairlineGoesWithTheTabs() {
        int tabsId = 0x7f0a0b22;
        int lineId = 0x7f0a0b23;
        int cellId = 0x7f0a0b24;
        VideoOverlayHider.resolveForTests("47.4.3:onp", tabsId);
        VideoOverlayHider.resolveForTests("47.4.3:cnj", lineId);
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        try (var main = Robolectric.buildActivity(Activity.class).setup()) {
            Utils.setContext(main.get());
            FrameLayout root = new FrameLayout(main.get());
            FrameLayout cell = new FrameLayout(main.get());
            cell.setId(cellId);
            root.addView(cell);
            View line = new View(main.get());
            line.setId(lineId);
            root.addView(line);
            View tabs = new View(main.get());
            tabs.setId(tabsId);
            root.addView(tabs);
            main.get().setContentView(root);

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(main.get());
            assertEquals(View.GONE, tabs.getVisibility());
            assertEquals("the hairline stayed over the video", View.GONE, line.getVisibility());

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            VideoOverlayHider.applyTo(main.get());
            assertEquals(View.VISIBLE, tabs.getVisibility());
            assertEquals("the hairline didn't come back with the tabs", View.VISIBLE, line.getVisibility());

            // TikTok hides the hairline on its own on some accounts; ending the mode leaves that.
            line.setVisibility(View.GONE);
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(main.get());
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            VideoOverlayHider.applyTo(main.get());
            assertEquals("a hairline TikTok put away was brought back", View.GONE, line.getVisibility());
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
        }
    }

    @Test
    public void clearDisplayHidesFollowingStoriesOutsideTheCellsAndRestoresNativeVisibility() {
        int storyId = 0x7f0a0b10;
        int cellId = 0x7f0a0b11;
        VideoOverlayHider.resolveForTests("47.4.3:wr5", storyId);
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(cellId);
            root.addView(cell);
            FrameLayout stories = new FrameLayout(activity);
            stories.setId(storyId);
            stories.setAlpha(0.6f);
            stories.setClickable(true);
            root.addView(stories);
            activity.setContentView(root);

            for (int visibility : new int[]{View.VISIBLE, View.INVISIBLE, View.GONE}) {
                stories.setVisibility(visibility);
                app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                        .rememberClearDisplayEvent(new ClearEvent(true, 0));
                Settings.CLEAR_DISPLAY.save(false);
                VideoOverlayHider.applyTo(activity);
                assertEquals(View.GONE, stories.getVisibility());
                if (visibility == View.VISIBLE) {
                    stories.setVisibility(View.VISIBLE);
                    VideoOverlayHider.applyTo(activity);
                    assertEquals(View.GONE, stories.getVisibility());
                }
                app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                        .rememberClearDisplayEvent(new ClearEvent(false, 2));
                VideoOverlayHider.applyTo(activity);
                assertEquals(visibility, stories.getVisibility());
                assertEquals(0.6f, stories.getAlpha(), 0f);
                assertTrue(stories.isClickable());
            }
        } finally {
            VideoOverlayHider.resolveForTests("view_rootview", 0);
        }
    }

    @Test
    public void nativeClearExitRestoresFollowingStoriesWithoutAnotherLayout() {
        int storyId = 0x7f0a0b10;
        VideoOverlayHider.resolveForTests("47.4.3:wr5", storyId);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            View stories = new View(activity);
            stories.setId(storyId);
            root.addView(stories);
            activity.setContentView(root);
            VideoOverlayHider.install(activity);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, stories.getVisibility());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(View.VISIBLE, stories.getVisibility());
        }
    }

    @Test
    public void aStoryViewerCoveringTheMainActivityRestoresTheStoryCount() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            View stories = new View(activity);
            stories.setId(0x7f0a0b10);
            root.addView(stories);
            View viewer = new View(activity);
            viewer.setId(0x7f0a0b13);
            viewer.setVisibility(View.GONE);
            root.addView(viewer, new FrameLayout.LayoutParams(400, 600));
            activity.setContentView(root);
            VideoOverlayHider.resolveForTests("47.4.3:wr5", stories.getId());
            app.morphe.extension.tiktok.blockauthor.FeedVisibility.resolveForTests(
                    activity.getPackageName(), "vp_story_collection", viewer.getId());
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));

            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, stories.getVisibility());
            viewer.setVisibility(View.VISIBLE);
            assertTrue(app.morphe.extension.tiktok.blockauthor.FeedVisibility.isStoryVisible(activity));
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, stories.getVisibility());
            assertEquals(View.VISIBLE, viewer.getVisibility());

            viewer.setVisibility(View.GONE);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, stories.getVisibility());
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, stories.getVisibility());
        }
    }

    @Test
    public void pauseRestoresTheClearDisplayChromeAndDetailPagesKeepTheirStoryControls() {
        int storyId = 0x7f0a0b10;
        int tabStripId = 0x7f0a0b12;
        VideoOverlayHider.resolveForTests("47.4.3:wr5", storyId);
        VideoOverlayHider.resolveForTests("47.4.3:uwq", tabStripId);
        try (var main = Robolectric.buildActivity(Activity.class).setup();
             var detail = Robolectric.buildActivity(
                     com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).setup()) {
            Utils.setContext(main.get());
            FrameLayout root = new FrameLayout(main.get());
            View stories = new View(main.get());
            stories.setId(storyId);
            root.addView(stories);
            View tabs = new View(main.get());
            tabs.setId(tabStripId);
            root.addView(tabs);
            main.get().setContentView(root);
            View detailStories = new View(detail.get());
            detailStories.setId(storyId);
            detail.get().setContentView(detailStories);
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));

            VideoOverlayHider.applyTo(main.get());
            app.morphe.extension.shared.settings.PausedProcess.set(true);
            VideoOverlayHider.applyTo(main.get());
            assertEquals(View.VISIBLE, stories.getVisibility());
            assertEquals(View.VISIBLE, tabs.getVisibility());
            app.morphe.extension.shared.settings.PausedProcess.set(false);
            VideoOverlayHider.applyTo(detail.get());
            assertEquals(View.VISIBLE, detailStories.getVisibility());
        } finally {
            app.morphe.extension.shared.settings.PausedProcess.set(false);
        }
    }

    /**
     * A video opened from a creator's grid plays in TikTok's detail pager, a second activity with
     * the same cell and the same right column ids (#47). The hides follow it there as it comes to
     * the front, the status bar included (#50).
     */
    @Test
    public void theHidesFollowAVideoOpenedFromAProfile() {
        int likeId = 0x7f0a0200;
        int cellId = 0x7f0a0201;
        VideoOverlayHider.resolveForTests("47.4.3:g8a", likeId);
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        Settings.HIDE_RAIL_LIKE.save(true);
        Settings.HIDE_STATUS_BAR.save(true);
        try (var main = Robolectric.buildActivity(Activity.class).setup();
             var detailController = Robolectric.buildActivity(
                     com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            VideoOverlayHider.install(main.get());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            Activity detail = detailController.get();
            FrameLayout cell = new FrameLayout(detail);
            cell.setId(cellId);
            View like = new View(detail);
            like.setId(likeId);
            cell.addView(like);
            detail.setContentView(cell);
            detailController.resume();

            detail.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnGlobalLayout();

            assertEquals(View.GONE, like.getVisibility());
            assertNotEquals(0, detail.getWindow().getDecorView().getSystemUiVisibility()
                    & View.SYSTEM_UI_FLAG_FULLSCREEN);

            Settings.HIDE_RAIL_LIKE.save(false);
            Settings.HIDE_STATUS_BAR.save(false);
            detail.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals(View.VISIBLE, like.getVisibility());
            assertEquals(0, detail.getWindow().getDecorView().getSystemUiVisibility()
                    & View.SYSTEM_UI_FLAG_FULLSCREEN);
        } finally {
            Settings.HIDE_RAIL_LIKE.save(false);
            Settings.HIDE_STATUS_BAR.save(false);
        }
    }

    /**
     * TikTok's launcher entry is a second MainActivity under the feed's, and a theme change can
     * resume it for a moment while the feed stays in front and gets no new resume. The layout
     * listener went with that resume and with the install its onCreate posted, and the feed in
     * front got its LIVE entrance back on the next layout.
     */
    @Test
    public void aBuriedMainActivityResumedForAMomentHandsTheHidesBack() {
        int liveId = 0x7f0a0e41;
        VideoOverlayHider.resolveForTests("47.4.3:k_d", liveId);
        Settings.HIDE_LIVE_ENTRANCE.save(true);
        try (var feed = Robolectric.buildActivity(
                     com.ss.android.ugc.aweme.main.MainActivity.class).create();
             var launcher = Robolectric.buildActivity(
                     com.ss.android.ugc.aweme.main.MainActivity.class).create()) {
            View live = liveEntrance(feed.get(), liveId);
            VideoOverlayHider.install(feed.get());
            feed.start().resume();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            layOut(feed.get());
            assertEquals(View.GONE, live.getVisibility());

            liveEntrance(launcher.get(), liveId);
            VideoOverlayHider.install(launcher.get());
            // Recreated, the buried copy is resumed for a moment and stopped again, and the
            // install its onCreate posted runs after that.
            launcher.start().resume().pause().stop();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            // TikTok shows the entrance again, and the feed's next layout takes it away.
            live.setVisibility(View.VISIBLE);
            layOut(feed.get());
            assertEquals("the feed in front got its LIVE entrance back",
                    View.GONE, live.getVisibility());
        } finally {
            Settings.HIDE_LIVE_ENTRANCE.resetToDefault();
        }
    }

    private static View liveEntrance(Activity activity, int id) {
        FrameLayout root = new FrameLayout(activity);
        View live = new View(activity);
        live.setId(id);
        root.addView(live);
        activity.setContentView(root);
        return live;
    }

    @Test
    public void theStatusBarComesBackOnlyIfThisClassHidIt() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            View decor = activity.getWindow().getDecorView();
            int fullscreen = View.SYSTEM_UI_FLAG_FULLSCREEN;

            VideoOverlayHider.setStatusBarHidden(activity, true);
            assertNotEquals(0, decor.getSystemUiVisibility() & fullscreen);
            // Every layout pass re-runs this; it must not toggle or stack.
            VideoOverlayHider.setStatusBarHidden(activity, true);
            assertNotEquals(0, decor.getSystemUiVisibility() & fullscreen);

            VideoOverlayHider.setStatusBarHidden(activity, false);
            assertEquals(0, decor.getSystemUiVisibility() & fullscreen);

            // TikTok hid the bar itself on some page. Turning the switch off leaves that alone.
            decor.setSystemUiVisibility(fullscreen);
            VideoOverlayHider.setStatusBarHidden(activity, true);
            VideoOverlayHider.setStatusBarHidden(activity, false);
            assertNotEquals(0, decor.getSystemUiVisibility() & fullscreen);
        }
    }

    /**
     * The main feed and a video opened from a profile are two windows (#50). Each one's bar is
     * given back only where this class hid it, and each has its own peek window.
     */
    @Test
    public void eachWindowGivesBackOnlyTheStatusBarItHid() {
        try (var main = Robolectric.buildActivity(Activity.class).setup();
             var detail = Robolectric.buildActivity(Activity.class).setup()) {
            View mainDecor = main.get().getWindow().getDecorView();
            View detailDecor = detail.get().getWindow().getDecorView();
            int fullscreen = View.SYSTEM_UI_FLAG_FULLSCREEN;

            VideoOverlayHider.setStatusBarHidden(main.get(), true);
            long now = android.os.SystemClock.uptimeMillis();
            assertFalse(VideoOverlayHider.rehideAllowed(mainDecor, now));
            assertTrue("The other window has no hide of its own",
                    VideoOverlayHider.rehideAllowed(detailDecor, now));

            // Nothing was hidden in the detail window, so giving back there leaves the feed alone.
            VideoOverlayHider.setStatusBarHidden(detail.get(), false);
            assertNotEquals(0, mainDecor.getSystemUiVisibility() & fullscreen);

            VideoOverlayHider.setStatusBarHidden(detail.get(), true);
            VideoOverlayHider.setStatusBarHidden(main.get(), false);
            assertEquals(0, mainDecor.getSystemUiVisibility() & fullscreen);
            assertNotEquals("Giving back the feed's bar leaves the detail page's away",
                    0, detailDecor.getSystemUiVisibility() & fullscreen);

            VideoOverlayHider.setStatusBarHidden(detail.get(), false);
            assertEquals(0, detailDecor.getSystemUiVisibility() & fullscreen);
        }
    }

    private static final int BAR_ID = 0x7f0a0210;
    private static final int STRIP_ID = 0x7f0a0211;
    private static final int COLUMN_ID = 0x7f0a0212;
    private static final int CELL_ID = 0x7f0a0201;

    private static void resolveCommentBarIds() {
        VideoOverlayHider.resolveForTests("47.4.3:qox", BAR_ID);
        VideoOverlayHider.resolveForTests("47.4.3:cnc", STRIP_ID);
        VideoOverlayHider.resolveForTests("viewpager_container", COLUMN_ID);
        // With the cell root known, furniture is looked for inside cells only; the bar and its
        // strip sit outside every cell, under the pager, as on the phone.
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
    }

    /** The pager column as TikTok inflates it: a cell over the strip that keeps its height. */
    private static LinearLayout pagerColumn(Context context, View strip) {
        LinearLayout column = new LinearLayout(context);
        column.setId(COLUMN_ID);
        column.setOrientation(LinearLayout.VERTICAL);
        FrameLayout cell = new FrameLayout(context);
        cell.setId(CELL_ID);
        column.addView(cell, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        strip.setId(STRIP_ID);
        column.addView(strip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 138));
        return column;
    }

    private static void layOut(Activity activity) {
        activity.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnGlobalLayout();
    }

    /**
     * A video opened from a profile has an Add comment bar laid over a strip that holds its
     * height under the pager (#50). The switch takes both away there and gives them back when
     * it goes off. The main feed inflates the same column with the same ids and has the tabs
     * in that place, so it's left alone. The strip's id also names a Space in twenty other
     * layouts; one of those in the detail window stays put.
     */
    @Test
    public void theCommentBarLeavesAVideoOpenedFromAProfile() {
        resolveCommentBarIds();
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var main = Robolectric.buildActivity(Activity.class).setup();
             var detailController = Robolectric.buildActivity(
                     com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            View feedStrip = new View(main.get());
            main.get().setContentView(pagerColumn(main.get(), feedStrip));
            VideoOverlayHider.install(main.get());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            layOut(main.get());
            assertEquals(View.VISIBLE, feedStrip.getVisibility());

            Activity detail = detailController.get();
            View strip = new View(detail);
            FrameLayout root = new FrameLayout(detail);
            root.addView(pagerColumn(detail, strip));
            View bar = new View(detail);
            bar.setId(BAR_ID);
            root.addView(bar);
            FrameLayout otherPage = new FrameLayout(detail);
            // A Space makes itself INVISIBLE as it's built; GONE would be this class's doing.
            android.widget.Space spacer = new android.widget.Space(detail);
            int spacerVisibility = spacer.getVisibility();
            spacer.setId(STRIP_ID);
            otherPage.addView(spacer);
            root.addView(otherPage);
            detail.setContentView(root);
            detailController.resume();

            layOut(detail);
            assertEquals(View.GONE, bar.getVisibility());
            assertEquals(View.GONE, strip.getVisibility());
            assertEquals("a Space sharing the strip's id was taken",
                    spacerVisibility, spacer.getVisibility());

            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            layOut(detail);
            assertEquals(View.VISIBLE, bar.getVisibility());
            assertEquals(View.VISIBLE, strip.getVisibility());
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
        }
    }

    /**
     * Swiping through an opened grid on the S22 left each post 138 px short over a black band
     * (#153): the strip read GONE, but the column was never laid out again, so the pager kept the
     * height it had beside the strip. The column is measured again inside its own bounds and the
     * pager gets the whole of it.
     */
    @Test
    public void aHiddenStripsRoomGoesBackToThePagerWithoutALayoutRequest() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            View strip = new View(activity);
            LinearLayout column = pagerColumn(activity, strip);
            FrameLayout root = new FrameLayout(activity);
            root.addView(column);
            activity.setContentView(root);
            View cell = column.findViewById(CELL_ID);
            column.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2181, View.MeasureSpec.EXACTLY));
            column.layout(0, 0, 1080, 2181);
            assertEquals(2043, cell.getHeight());
            assertFalse("a shown strip claims its own room", VideoOverlayHider.leavesRoom(column));

            strip.setVisibility(View.GONE);
            assertEquals("the stale layout the phone showed", 2043, cell.getHeight());
            assertTrue(VideoOverlayHider.leavesRoom(column));

            VideoOverlayHider.relayoutColumn(column);
            assertEquals(2181, cell.getHeight());
            assertEquals("the column keeps its own bounds", 2181, column.getHeight());
            assertFalse(VideoOverlayHider.leavesRoom(column));
        }
    }

    /**
     * On the S22 (2026-10-14, a video opened from search) the strip went GONE while its column
     * was being laid out, so the request it made stopped at the column and was cleared with the
     * column's own. Measuring the column again at the size it already had was then answered from
     * the cache, the pager stayed 2043 over a black band, and "Gave the pager back" was logged
     * every half second without anything moving.
     */
    @Test
    public void aStripHiddenDuringItsColumnsLayoutStillGivesThePagerItsRoom() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            View strip = new View(activity);
            LinearLayout column = pagerColumn(activity, strip);
            FrameLayout root = new FrameLayout(activity);
            root.addView(column);
            activity.setContentView(root);
            View cell = column.findViewById(CELL_ID);
            int width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
            int height = View.MeasureSpec.makeMeasureSpec(2181, View.MeasureSpec.EXACTLY);
            column.measure(width, height);
            column.layout(0, 0, 1080, 2181);

            View.OnLayoutChangeListener hideStrip = (view, l, t, r, b, oldL, oldT, oldR, oldB) ->
                    strip.setVisibility(View.GONE);
            column.addOnLayoutChangeListener(hideStrip);
            column.requestLayout();
            column.measure(width, height);
            column.layout(0, 0, 1080, 2181);
            column.removeOnLayoutChangeListener(hideStrip);
            assertEquals(View.GONE, strip.getVisibility());
            assertFalse("the strip's request went out with the column's", column.isLayoutRequested());
            assertEquals("the stale layout the phone showed", 2043, cell.getHeight());

            VideoOverlayHider.relayoutColumn(column);
            assertEquals(2181, cell.getHeight());
            assertEquals("the column keeps its own bounds", 2181, column.getHeight());
            assertFalse(VideoOverlayHider.leavesRoom(column));
        }
    }

    /**
     * On photo posts TikTok shows the strip again about twice a second (S22, 2026-10-14). The
     * relayout was posted at most once every half second, so each show left the band up until
     * then and the photo spent most of its time over it. Every pass that hides the strip again
     * now gives the pager its room before the frame is drawn.
     */
    @Test
    public void aStripTikTokShowsAgainGoesBackOnTheSamePass() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            View strip = new View(activity);
            LinearLayout column = pagerColumn(activity, strip);
            FrameLayout root = new FrameLayout(activity);
            root.addView(column);
            activity.setContentView(root);
            View cell = column.findViewById(CELL_ID);
            int width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
            int height = View.MeasureSpec.makeMeasureSpec(2181, View.MeasureSpec.EXACTLY);
            try {
                for (int show = 0; show < 3; show++) {
                    strip.setVisibility(View.VISIBLE);
                    column.measure(width, height);
                    column.layout(0, 0, 1080, 2181);
                    assertEquals(2043, cell.getHeight());

                    VideoOverlayHider.setHidden(strip, true);
                    VideoOverlayHider.settleStripColumns(java.util.List.of(strip));
                    assertEquals("show " + show + " left the band", 2181, cell.getHeight());
                }
            } finally {
                VideoOverlayHider.setHidden(strip, false);
            }
        }
    }

    /** A column that doesn't settle is measured once and then left for half a second. */
    @Test
    public void aColumnThatWontSettleIsTriedAgainOnlyAfterHalfASecond() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            int[] measures = {0};
            LinearLayout column = new LinearLayout(activity) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    measures[0]++;
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }
            };
            column.setOrientation(LinearLayout.VERTICAL);
            // A cell of its own fixed height never grows into the strip's room.
            column.addView(new FrameLayout(activity), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 2043));
            View strip = new View(activity);
            column.addView(strip, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 138));
            FrameLayout root = new FrameLayout(activity);
            root.addView(column);
            activity.setContentView(root);
            column.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2181, View.MeasureSpec.EXACTLY));
            column.layout(0, 0, 1080, 2181);
            try {
                VideoOverlayHider.setHidden(strip, true);
                measures[0] = 0;
                VideoOverlayHider.settleStripColumns(java.util.List.of(strip));
                assertEquals(1, measures[0]);
                assertTrue(VideoOverlayHider.leavesRoom(column));

                VideoOverlayHider.settleStripColumns(java.util.List.of(strip));
                assertEquals("tried again inside the half second", 1, measures[0]);

                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500));
                VideoOverlayHider.settleStripColumns(java.util.List.of(strip));
                assertEquals(2, measures[0]);
            } finally {
                VideoOverlayHider.setHidden(strip, false);
            }
        }
    }

    /**
     * TikTok leaves the Add comment bar up in Clear display on a photo or video opened from
     * search or a profile (#84). It goes with the other controls, with Hide the comment bar on
     * opened videos off, and comes back on Restore display.
     */
    @Test
    public void clearDisplayTakesTheCommentBarOffAnOpenedVideo() {
        resolveCommentBarIds();
        Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View strip = new View(detail);
            FrameLayout root = new FrameLayout(detail);
            root.addView(pagerColumn(detail, strip));
            View bar = new View(detail);
            bar.setId(BAR_ID);
            root.addView(bar);
            detail.setContentView(root);
            detailController.resume();

            VideoOverlayHider.applyTo(detail);
            assertEquals(View.VISIBLE, bar.getVisibility());
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(detail);
            assertEquals("the comment bar stayed in Clear display", View.GONE, bar.getVisibility());
            assertEquals(View.GONE, strip.getVisibility());

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            VideoOverlayHider.applyTo(detail);
            assertEquals("Restore display left the comment bar hidden", View.VISIBLE, bar.getVisibility());
            assertEquals(View.VISIBLE, strip.getVisibility());
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            Settings.CLEAR_DISPLAY.save(false);
        }
    }

    private static final int SEEK_ROW_ID = 0x7f0a0213;
    private static final int CAPTION_FRAME_ID = 0x7f0a0214;

    /**
     * An opened video as the S25 lays it out (2026-10-11), at 1080 by 2205: the pager column with
     * a cell over the 138 px strip, the caption's frame at the bottom of the cell, the comment bar,
     * and TikTok's 79 px progress bar row at the top of its own 180 px slot at the bottom. With
     * {@code searchBarUnderCaption} the cell keeps 101 px under the caption, as one does with
     * TikTok's search bar there. Returns the root, the row, the strip and the caption's frame.
     * The activity is never resumed, so no window lays the views out again in the middle of a
     * pass; {@link #layOutAt} stands in for the layout that follows each one on a phone.
     */
    private static View[] openedVideo(Activity detail, boolean searchBarUnderCaption) {
        FrameLayout root = new FrameLayout(detail);
        View strip = new View(detail);
        LinearLayout column = pagerColumn(detail, strip);
        FrameLayout cell = column.findViewById(CELL_ID);
        View frame = new View(detail);
        frame.setId(CAPTION_FRAME_ID);
        FrameLayout.LayoutParams frameParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 161, android.view.Gravity.BOTTOM);
        frameParams.bottomMargin = searchBarUnderCaption ? 101 : 0;
        cell.addView(frame, frameParams);
        root.addView(column);
        View bar = new View(detail);
        bar.setId(BAR_ID);
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 87, android.view.Gravity.BOTTOM));
        FrameLayout slot = new FrameLayout(detail);
        View row = new View(detail);
        row.setId(SEEK_ROW_ID);
        slot.addView(row, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 79));
        root.addView(slot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 180, android.view.Gravity.BOTTOM));
        detail.setContentView(root);
        return new View[] {root, row, strip, frame};
    }

    private static void layOutAt(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2205, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2205);
    }

    /**
     * Where {@code view}'s top is drawn, offsets included. The activity is never resumed, so its
     * window isn't attached and getLocationInWindow would answer 0.
     */
    private static float drawnTop(View view) {
        float top = view.getTop() + view.getTranslationY();
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            View group = (View) parent;
            top += group.getTop() - group.getScrollY() + group.getTranslationY();
        }
        return top;
    }

    /** Where TikTok's line runs on screen: through the middle of the row. */
    private static float lineOf(View row) {
        return drawnTop(row) + row.getHeight() / 2f;
    }

    private static float bottomOf(View frame) {
        return drawnTop(frame) + frame.getHeight();
    }

    /**
     * With the comment bar and its strip gone the cell grows to the bottom and the caption comes
     * down 138 px, but TikTok's bar row keeps its place, so a long video's bar drew across the
     * creator's name (S25, 2026-10-11). The row comes down the 101 px its slot has. TikTok's line
     * runs through the row's middle, so that still left it 40 px inside the caption, across its last
     * line (#153): the caption and the music disc beside it go up those 40 px, and the caption ends
     * on the line as it does with the bar up. All of it goes back with the comment bar.
     */
    @Test
    public void theProgressBarComesDownWithTheCaptionWhenTheCommentBarGoes() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_ROW_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", CAPTION_FRAME_ID);
        VideoOverlayHider.resolveForTests("videomusiccoverblock", DISC_ID);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View[] views = openedVideo(detail, false);
            View row = views[1];
            View frame = views[3];
            View disc = discIn((FrameLayout) frame.getParent());
            layOutAt(views[0]);

            VideoOverlayHider.applyTo(detail);
            assertEquals(View.GONE, views[2].getVisibility());
            // The cell grows into the strip's room on the next layout, and that layout's pass moves the row.
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            assertEquals("the caption grew past the bar", 2205, frame.getBottom());
            assertEquals("the bar stayed above the caption's new place", 101f, row.getTranslationY(), 0.5f);
            assertEquals("the line runs across the caption", bottomOf(frame), lineOf(row), 1f);
            assertEquals("the caption didn't make room for the line", -40f, frame.getTranslationY(), 0.5f);
            // The disc ends above the row, clear of the line and the time beside it (#153, 0.73.0).
            assertEquals("the disc stayed on the line", -79f, disc.getTranslationY(), 0.5f);
            assertEquals("the disc doesn't end at the row's top", drawnTop(row), bottomOf(disc), 1f);

            // Another layout pass changes nothing: the caption's own lift isn't measured as TikTok's.
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            assertEquals(-40f, frame.getTranslationY(), 0.5f);
            assertEquals(101f, row.getTranslationY(), 0.5f);

            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.applyTo(detail);
            assertEquals(View.VISIBLE, views[2].getVisibility());
            assertEquals("the bar didn't go back up with the comment bar", 0f, row.getTranslationY(), 0f);
            assertEquals("the caption didn't go back down", 0f, frame.getTranslationY(), 0f);
            assertEquals("the disc didn't go back down", 0f, disc.getTranslationY(), 0f);
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
            VideoOverlayHider.resolveForTests("videomusiccoverblock", 0);
        }
    }

    private static final int DISC_ID = 0x7f0a0215;

    /** TikTok's spinning disc at the bottom right of {@code cell}, the way the cell lays it out. */
    private static View discIn(FrameLayout cell) {
        View disc = new View(cell.getContext());
        disc.setId(DISC_ID);
        cell.addView(disc, new FrameLayout.LayoutParams(126, 126,
                android.view.Gravity.BOTTOM | android.view.Gravity.END));
        return disc;
    }

    /**
     * The next video's caption is lifted as its cell is laid out below the screen, from where it
     * sits in its own cell, so it doesn't arrive low and jump up after the swipe (#153).
     */
    @Test
    public void theNextVideosCaptionIsLiftedBeforeItArrives() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_ROW_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", CAPTION_FRAME_ID);
        VideoOverlayHider.resolveForTests("videomusiccoverblock", DISC_ID);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View[] views = openedVideo(detail, false);
            // The column's one cell becomes the pager, holding this video's cell and the next one,
            // laid out a screen further down.
            FrameLayout pager = (FrameLayout) views[3].getParent();
            pager.setId(View.NO_ID);
            pager.removeView(views[3]);
            FrameLayout current = new FrameLayout(detail);
            current.setId(CELL_ID);
            current.addView(views[3], new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 161, android.view.Gravity.BOTTOM));
            pager.addView(current, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            FrameLayout next = new FrameLayout(detail);
            next.setId(CELL_ID);
            View nextFrame = new View(detail);
            nextFrame.setId(CAPTION_FRAME_ID);
            next.addView(nextFrame, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 161, android.view.Gravity.BOTTOM));
            View nextDisc = discIn(next);
            pager.addView(next, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            next.setTranslationY(2205f);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);

            assertEquals(-40f, views[3].getTranslationY(), 0.5f);
            assertEquals("the next caption waits to be lifted until it's on screen",
                    -40f, nextFrame.getTranslationY(), 0.5f);
            assertEquals(-79f, nextDisc.getTranslationY(), 0.5f);
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
            VideoOverlayHider.resolveForTests("videomusiccoverblock", 0);
        }
    }

    /**
     * A video opened from search plays in the main activity, with the For You pager still in the
     * tree behind it, gone. That feed's caption runs to the bottom and covers more of the row's
     * slot than the opened video's caption over TikTok's search bar, so the row came down its
     * whole slot into the search bar (S22, 2026-10-14: line through "Search · ..."). Only what's
     * on screen counts, and the feed behind keeps its caption where it was.
     */
    @Test
    public void aFeedGoneBehindAnOpenedVideoDoesNotPlaceItsProgressBar() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_ROW_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", CAPTION_FRAME_ID);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View[] views = openedVideo(detail, true);
            FrameLayout feed = new FrameLayout(detail);
            FrameLayout feedCell = new FrameLayout(detail);
            feedCell.setId(CELL_ID);
            View feedFrame = new View(detail);
            feedFrame.setId(CAPTION_FRAME_ID);
            feedCell.addView(feedFrame, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 161, android.view.Gravity.BOTTOM));
            feed.addView(feedCell);
            ((FrameLayout) views[0]).addView(feed, 0);
            layOutAt(views[0]);
            // Laid out while For You showed, then gone when the search result opened over it.
            feed.setVisibility(View.GONE);

            VideoOverlayHider.applyTo(detail);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            assertEquals("the line went past the caption into the search bar",
                    bottomOf(views[3]), lineOf(views[1]), 1f);
            assertEquals("the caption over the search bar moved", 0f, views[3].getTranslationY(), 0f);
            assertEquals("the feed behind had its caption lifted", 0f, feedFrame.getTranslationY(), 0f);
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
        }
    }

    /**
     * TikTok 47.1.4 holds the music disc up with an offset of its own. The disc's lift goes on top
     * of it instead of skipping the disc, follows TikTok when it writes the offset again on a
     * rebind, and puts TikTok's offset back with the comment bar (#153).
     */
    @Test
    public void theDiscIsLiftedOverTikToksOwnOffset() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_ROW_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", CAPTION_FRAME_ID);
        VideoOverlayHider.resolveForTests("videomusiccoverblock", DISC_ID);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View[] views = openedVideo(detail, false);
            View frame = views[3];
            View disc = discIn((FrameLayout) frame.getParent());
            disc.setTranslationY(-25f);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            assertEquals(-40f, frame.getTranslationY(), 0.5f);
            // TikTok's 25 px count toward ending above the row, so the disc lands where it would without them.
            assertEquals("the disc wasn't lifted over TikTok's offset", -79f, disc.getTranslationY(), 0.5f);

            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            assertEquals("a second pass lifted the disc again", -79f, disc.getTranslationY(), 0.5f);

            // TikTok binds the cell again and writes its offset back.
            disc.setTranslationY(-25f);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            assertEquals("the disc stayed where TikTok put it back", -79f, disc.getTranslationY(), 0.5f);

            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.applyTo(detail);
            assertEquals("TikTok's own offset didn't come back", -25f, disc.getTranslationY(), 0f);
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
            VideoOverlayHider.resolveForTests("videomusiccoverblock", 0);
        }
    }

    /**
     * A video with no disc leaves Share as the rail's lowest button. With the comment bar gone the
     * cell grows to the bottom and Share's label sat over the end of the line and the time beside
     * it (#153, BynchPOP on 0.74.0, videos opened from search). The rail's column goes up until
     * Share ends above the row, the buttons keep their spacing, and it all comes back down with the
     * comment bar.
     */
    @Test
    public void theRailClearsTheRowWhenShareIsItsLowestButton() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_ROW_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", CAPTION_FRAME_ID);
        VideoOverlayHider.resolveForTests("47.4.3:g8a", 0x7f0a0216);
        VideoOverlayHider.resolveForTests("47.4.3:w_s", 0x7f0a0217);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View[] views = openedVideo(detail, false);
            View row = views[1];
            View frame = views[3];
            FrameLayout cell = (FrameLayout) frame.getParent();
            LinearLayout column = new LinearLayout(detail);
            column.setOrientation(LinearLayout.VERTICAL);
            View like = new View(detail);
            like.setId(0x7f0a0216);
            View share = new View(detail);
            share.setId(0x7f0a0217);
            column.addView(like, new LinearLayout.LayoutParams(180, 169));
            column.addView(share, new LinearLayout.LayoutParams(180, 169));
            cell.addView(column, new FrameLayout.LayoutParams(180, FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM | android.view.Gravity.END));
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);

            assertTrue("Share runs into the row: " + bottomOf(share) + " under " + drawnTop(row),
                    bottomOf(share) <= drawnTop(row) + 1f);
            assertTrue("the column wasn't lifted", column.getTranslationY() < 0f);
            assertEquals("the buttons lost their spacing", bottomOf(like), drawnTop(share), 0.5f);
            float lifted = column.getTranslationY();

            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            assertEquals("a second pass lifted the column again", lifted, column.getTranslationY(), 0.5f);

            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.applyTo(detail);
            assertEquals("the column didn't go back down", 0f, column.getTranslationY(), 0f);
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
            VideoOverlayHider.resolveForTests("47.4.3:g8a", 0);
            VideoOverlayHider.resolveForTests("47.4.3:w_s", 0);
        }
    }

    /**
     * Comment, Save and Share carry their counts inside the button, but the heart's count is a
     * row of its own under the heart (S22, 2026-10-16: the heart 126 px tall, its count the 24 px
     * under it). With the other buttons hidden the heart was the lowest, the column went up until
     * the heart ended on the row, and its count sat on the time (#145, FBI-PRC on 0.74.0).
     */
    @Test
    public void theRailClearsTheRowWithTheLikeCountWhenLikeIsItsLowestButton() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_ROW_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", CAPTION_FRAME_ID);
        VideoOverlayHider.resolveForTests("47.4.3:g8a", 0x7f0a0216);
        VideoOverlayHider.resolveForTests("47.4.3:w_s", 0x7f0a0217);
        VideoOverlayHider.resolveForTests("47.4.3:g8c", 0x7f0a0218);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View[] views = openedVideo(detail, false);
            View row = views[1];
            View frame = views[3];
            FrameLayout cell = (FrameLayout) frame.getParent();
            LinearLayout column = new LinearLayout(detail);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout likeSlot = new LinearLayout(detail);
            likeSlot.setOrientation(LinearLayout.VERTICAL);
            View like = new View(detail);
            like.setId(0x7f0a0216);
            View count = new View(detail);
            count.setId(0x7f0a0218);
            likeSlot.addView(like, new LinearLayout.LayoutParams(180, 126));
            likeSlot.addView(count, new LinearLayout.LayoutParams(180, 24));
            // Hidden by its rail switch, as the reporter had Share, Save and Comment.
            View share = new View(detail);
            share.setId(0x7f0a0217);
            share.setVisibility(View.GONE);
            column.addView(likeSlot, new LinearLayout.LayoutParams(180, LinearLayout.LayoutParams.WRAP_CONTENT));
            column.addView(share, new LinearLayout.LayoutParams(180, 169));
            cell.addView(column, new FrameLayout.LayoutParams(180, FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM | android.view.Gravity.END));
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);

            assertTrue("the like count runs into the row: " + bottomOf(count) + " under " + drawnTop(row),
                    bottomOf(count) <= drawnTop(row) + 1f);
            assertTrue("the column wasn't lifted", column.getTranslationY() < 0f);
            assertEquals("the count left the heart", bottomOf(like), drawnTop(count), 0.5f);

            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.applyTo(detail);
            assertEquals("the column didn't go back down", 0f, column.getTranslationY(), 0f);
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
            VideoOverlayHider.resolveForTests("47.4.3:g8a", 0);
            VideoOverlayHider.resolveForTests("47.4.3:w_s", 0);
            VideoOverlayHider.resolveForTests("47.4.3:g8c", 0);
        }
    }

    /**
     * On the reporter's phone the disc is the column's last button: a block the rail's width,
     * with the notes that float off the disc reaching its bottom left of the other buttons, and
     * the disc above that in the buttons' lane. The column went up only for Share, so the disc sat
     * across the line and the time (#153, BynchPOP on 0.75.0). The column now goes up until the
     * disc ends on the row's top, and not by the block's bottom, which would leave a gap under it.
     */
    @Test
    public void theRailClearsTheRowWhenTheDiscIsItsLowestButton() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_ROW_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", CAPTION_FRAME_ID);
        VideoOverlayHider.resolveForTests("47.4.3:g8a", 0x7f0a0216);
        VideoOverlayHider.resolveForTests("47.4.3:w_s", 0x7f0a0217);
        VideoOverlayHider.resolveForTests("47.4.3:pop", 0x7f0a0219);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View[] views = openedVideo(detail, false);
            View row = views[1];
            View frame = views[3];
            FrameLayout cell = (FrameLayout) frame.getParent();
            LinearLayout column = new LinearLayout(detail);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(android.view.Gravity.END);
            View like = new View(detail);
            like.setId(0x7f0a0216);
            View share = new View(detail);
            share.setId(0x7f0a0217);
            // The disc's block as the reporter's layout recording has it, 1080 by 267: the notes,
            // 267 px square from x 754 down to its bottom, and the 113 px disc 28 px down a 180 by
            // 174 frame at the right.
            FrameLayout music = new FrameLayout(detail);
            music.setId(0x7f0a0219);
            FrameLayout.LayoutParams notesParams = new FrameLayout.LayoutParams(267, 267,
                    android.view.Gravity.BOTTOM | android.view.Gravity.START);
            notesParams.leftMargin = 754;
            View notes = new View(detail);
            music.addView(notes, notesParams);
            FrameLayout discFrame = new FrameLayout(detail);
            FrameLayout.LayoutParams discParams = new FrameLayout.LayoutParams(113, 113,
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            discParams.topMargin = 28;
            View disc = new View(detail);
            discFrame.addView(disc, discParams);
            music.addView(discFrame, new FrameLayout.LayoutParams(180, 174,
                    android.view.Gravity.BOTTOM | android.view.Gravity.END));
            column.addView(like, new LinearLayout.LayoutParams(180, 169));
            column.addView(share, new LinearLayout.LayoutParams(180, 169));
            column.addView(music, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 267));
            cell.addView(column, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM));
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);

            assertEquals("the disc doesn't end on the row's top", drawnTop(row), bottomOf(disc), 1f);
            assertTrue("Share runs into the row", bottomOf(share) <= drawnTop(row) + 1f);
            assertTrue("the notes lifted the column: " + bottomOf(notes) + " over " + drawnTop(row),
                    bottomOf(notes) > drawnTop(row) + 20f);

            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.applyTo(detail);
            assertEquals("the column didn't go back down", 0f, column.getTranslationY(), 0f);
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
            VideoOverlayHider.resolveForTests("47.4.3:g8a", 0);
            VideoOverlayHider.resolveForTests("47.4.3:w_s", 0);
            VideoOverlayHider.resolveForTests("47.4.3:pop", 0);
        }
    }

    private static final int MESSAGE_BAR_ID = 0x7f0a021a;

    /**
     * A mutual friend's opened video has no comment bar. A "Message..." bar sits over the strip
     * in its place, and with the strip gone it covered the bottom of the caption and the rail
     * (#153, BynchPOP's layout recording on 0.75.0). It now goes with the strip, the progress bar
     * comes down as it does without the comment bar, and both come back with the switch.
     */
    @Test
    public void theMessageBarGoesWithTheCommentBarOnAFriendsVideo() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("47.4.3:kyn", MESSAGE_BAR_ID);
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_ROW_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", CAPTION_FRAME_ID);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View[] views = openedVideo(detail, false);
            FrameLayout root = (FrameLayout) views[0];
            root.removeView(root.findViewById(BAR_ID));
            FrameLayout messageBar = new FrameLayout(detail);
            messageBar.setId(MESSAGE_BAR_ID);
            messageBar.addView(new View(detail), new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 124));
            root.addView(messageBar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 138, android.view.Gravity.BOTTOM));
            layOutAt(root);
            VideoOverlayHider.applyTo(detail);
            layOutAt(root);
            VideoOverlayHider.applyTo(detail);

            assertEquals("the Message bar stayed", View.GONE, messageBar.getVisibility());
            assertEquals(View.GONE, views[2].getVisibility());
            assertEquals("the progress bar didn't come down with the caption",
                    101f, views[1].getTranslationY(), 0.5f);

            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.applyTo(detail);
            assertEquals(View.VISIBLE, messageBar.getVisibility());
            assertEquals(View.VISIBLE, views[2].getVisibility());
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.resolveForTests("47.4.3:kyn", 0);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
        }
    }

    /**
     * A short caption ends above the lowest line, so it needs no lift and the line comes down to
     * meet it. The disc only ever took the caption's lift, so it got none and sat across the end of
     * the line and the time beside it (#153, a video opened from search on 0.73.0). It now goes up
     * on its own to end above the row, and the caption stays where it is.
     */
    @Test
    public void theDiscClearsTheRowOverAShortCaption() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_ROW_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", CAPTION_FRAME_ID);
        VideoOverlayHider.resolveForTests("videomusiccoverblock", DISC_ID);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View[] views = openedVideo(detail, false);
            View row = views[1];
            View frame = views[3];
            ((FrameLayout.LayoutParams) frame.getLayoutParams()).bottomMargin = 60;
            View disc = discIn((FrameLayout) frame.getParent());
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);

            assertEquals("the short caption was lifted", 0f, frame.getTranslationY(), 0f);
            assertEquals("the line doesn't run along the caption", bottomOf(frame), lineOf(row), 1f);
            assertTrue("the disc runs into the row: " + bottomOf(disc) + " under " + drawnTop(row),
                    bottomOf(disc) <= drawnTop(row) + 1f);

            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.applyTo(detail);
            assertEquals("the disc didn't go back down", 0f, disc.getTranslationY(), 0f);
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
            VideoOverlayHider.resolveForTests("videomusiccoverblock", 0);
        }
    }

    /**
     * With no progress bar on screen (a photo, or the bar turned off) there's no line to make room
     * for, so the caption goes back to the bottom (#153).
     */
    @Test
    public void theCaptionStaysDownWithNoProgressBarOnScreen() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_ROW_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", CAPTION_FRAME_ID);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View[] views = openedVideo(detail, false);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            assertEquals(-40f, views[3].getTranslationY(), 0.5f);

            ((View) views[1].getParent()).setVisibility(View.GONE);
            VideoOverlayHider.applyTo(detail);
            assertEquals("the caption kept room for a bar that isn't there", 0f, views[3].getTranslationY(), 0f);
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
        }
    }

    /**
     * The main feed has no comment bar, so with the switch on it isn't reported missing there, as
     * it was on every pass of #153's report. An opened video always has one, so a rename still is.
     */
    @Test
    public void theMainFeedDoesNotReportTheCommentBarMissing() {
        resolveCommentBarIds();
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        HookStatus.clear();
        try (var main = Robolectric.buildActivity(Activity.class).setup();
             var detailController = Robolectric.buildActivity(
                     com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            main.get().setContentView(pagerColumn(main.get(), new View(main.get())));
            VideoOverlayHider.applyTo(main.get());
            assertTrue("the main feed called the comment bar missing: " + HookStatus.missing("overlay"),
                    HookStatus.missing("overlay").stream().noneMatch(line -> line.contains("47.4.3:qox")));

            Activity detail = detailController.get();
            detail.setContentView(pagerColumn(detail, new View(detail)));
            VideoOverlayHider.applyTo(detail);
            assertTrue("an opened video without the bar went unreported",
                    HookStatus.missing("overlay").stream().anyMatch(line -> line.contains("47.4.3:qox")));
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            HookStatus.clear();
        }
    }

    /**
     * A cell with TikTok's search bar under the caption ends higher, so the row comes down only
     * until its line meets the caption's bottom and nothing is lifted; and a row TikTok moved
     * itself is left where TikTok put it.
     */
    @Test
    public void theProgressBarComesDownOnlyToACaptionThatEndsHigher() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_ROW_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", CAPTION_FRAME_ID);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View[] views = openedVideo(detail, true);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            assertEquals(View.GONE, views[2].getVisibility());
            assertEquals("the line stopped short of the caption's bottom",
                    bottomOf(views[3]), lineOf(views[1]), 1f);
            assertEquals("a caption that ends high enough moved", 0f, views[3].getTranslationY(), 0f);

            views = openedVideo(detail, false);
            views[1].setTranslationY(-20f);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            layOutAt(views[0]);
            VideoOverlayHider.applyTo(detail);
            assertEquals("TikTok's own move was overwritten", -20f, views[1].getTranslationY(), 0f);
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
        }
    }

    /**
     * On 47.1.4 a photo opened from search plays in the main activity (DetailSafRootFragment),
     * not the detail pager, with the same comment bar. Clear display takes it off there too, and
     * the main feed's own strip, which has no bar over it, is left alone (#84).
     */
    @Test
    public void clearDisplayTakesTheCommentBarOffAPhotoOpenedInTheMainActivity() {
        resolveCommentBarIds();
        Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
        try (var mainController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.main.MainActivity.class).create().start()) {
            Activity main = mainController.get();
            View strip = new View(main);
            FrameLayout root = new FrameLayout(main);
            root.addView(pagerColumn(main, strip));
            View bar = new View(main);
            bar.setId(BAR_ID);
            root.addView(bar);
            main.setContentView(root);
            mainController.resume();

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(main);
            assertEquals("the photo's comment bar stayed in Clear display", View.GONE, bar.getVisibility());
            assertEquals(View.GONE, strip.getVisibility());

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            VideoOverlayHider.applyTo(main);
            assertEquals(View.VISIBLE, bar.getVisibility());
            assertEquals(View.VISIBLE, strip.getVisibility());

            // The feed itself: the strip with no bar over it stays in Clear display.
            root.removeView(bar);
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(main);
            assertEquals("the feed's strip went without a comment bar", View.VISIBLE, strip.getVisibility());
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            Settings.CLEAR_DISPLAY.save(false);
        }
    }

    /**
     * The switch follows the bar the way Clear display does. A photo opened from search plays in
     * the main activity on 47.1.4, and asked only in the detail pager the switch left that
     * photo's bar up. The feed's own strip, with no bar over it, stays.
     */
    @Test
    public void theSwitchTakesTheBarOffAPhotoOpenedInTheMainActivity() {
        resolveCommentBarIds();
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var mainController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.main.MainActivity.class).create().start()) {
            Activity main = mainController.get();
            View strip = new View(main);
            FrameLayout root = new FrameLayout(main);
            root.addView(pagerColumn(main, strip));
            View bar = new View(main);
            bar.setId(BAR_ID);
            root.addView(bar);
            main.setContentView(root);
            mainController.resume();

            VideoOverlayHider.applyTo(main);
            assertEquals("the photo's comment bar stayed up", View.GONE, bar.getVisibility());
            assertEquals(View.GONE, strip.getVisibility());

            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.applyTo(main);
            assertEquals(View.VISIBLE, bar.getVisibility());
            assertEquals(View.VISIBLE, strip.getVisibility());

            Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
            root.removeView(bar);
            VideoOverlayHider.applyTo(main);
            assertEquals("the feed's strip went without a comment bar", View.VISIBLE, strip.getVisibility());
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
        }
    }

    /**
     * Clear display only fades the anchor row under the caption, and a tap on the faded row still
     * opened its search (#84). It goes invisible, keeping its space so the caption doesn't move,
     * and comes back when Clear display ends. A row TikTok had put away stays as it was.
     */
    @Test
    public void clearDisplayTakesTheAnchorRowOutOfReach() {
        int cellId = 0x7f0a0c71;
        int anchorId = 0x7f0a0c72;
        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", anchorId);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout root = new LinearLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(cellId);
            View anchor = new View(activity);
            anchor.setId(anchorId);
            cell.addView(anchor);
            FrameLayout emptyCell = new FrameLayout(activity);
            emptyCell.setId(cellId);
            View putAway = new View(activity);
            putAway.setId(anchorId);
            putAway.setVisibility(View.GONE);
            emptyCell.addView(putAway);
            root.addView(cell);
            root.addView(emptyCell);
            activity.setContentView(root);

            VideoOverlayHider.applyTo(activity);
            assertEquals("the anchor went with Clear display off", View.VISIBLE, anchor.getVisibility());

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(activity);
            assertEquals("the faded anchor still takes taps", View.INVISIBLE, anchor.getVisibility());
            assertEquals(View.GONE, putAway.getVisibility());

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, anchor.getVisibility());
            assertEquals(View.GONE, putAway.getVisibility());
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            Settings.CLEAR_DISPLAY.save(false);
            VideoOverlayHider.resolveForTests("view_rootview", 0);
        }
    }

    /**
     * The bar and the strip go together. With the bar renamed by a build its id no longer
     * resolves, and a collapsed strip would grow the pager under that bar, which then covers the
     * caption. With the strip missing, hiding the bar alone leaves the black strip.
     */
    @Test
    public void halfTheCommentBarIsNeverHidden() {
        resolveCommentBarIds();
        VideoOverlayHider.resolveForTests("47.4.3:qox", 0);
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var noBar = Robolectric.buildActivity(
                     com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start();
             var noStrip = Robolectric.buildActivity(
                     com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            View strip = new View(noBar.get());
            noBar.get().setContentView(pagerColumn(noBar.get(), strip));
            noBar.resume();
            VideoOverlayHider.applyTo(noBar.get());
            assertEquals(View.VISIBLE, strip.getVisibility());
            resolveCommentBarIds();

            FrameLayout root = new FrameLayout(noStrip.get());
            View bar = new View(noStrip.get());
            bar.setId(BAR_ID);
            root.addView(bar);
            noStrip.get().setContentView(root);
            noStrip.resume();
            VideoOverlayHider.applyTo(noStrip.get());
            assertEquals(View.VISIBLE, bar.getVisibility());

            // The strip turns up later in the same window: now both go.
            View lateStrip = new View(noStrip.get());
            root.addView(pagerColumn(noStrip.get(), lateStrip));
            VideoOverlayHider.applyTo(noStrip.get());
            assertEquals(View.GONE, bar.getVisibility());
            assertEquals(View.GONE, lateStrip.getVisibility());
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
        }
    }

    /**
     * An opened grid that starts on photos, or runs through several, has no comment bar in its
     * tree on 47.1.4 until a video binds one. The bar's id still resolves there, so the strip
     * stays away instead of coming back as a black band under the photo (#153), and the switch
     * still gives it back.
     */
    @Test
    public void anOpenedPhotoWithNoBarYetKeepsTheStripAway() {
        resolveCommentBarIds();
        Settings.HIDE_DETAIL_COMMENT_BAR.save(true);
        try (var photos = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = photos.get();
            View strip = new View(detail);
            FrameLayout root = new FrameLayout(detail);
            root.addView(pagerColumn(detail, strip));
            detail.setContentView(root);
            photos.resume();
            VideoOverlayHider.applyTo(detail);
            assertEquals("the strip came back under a photo", View.GONE, strip.getVisibility());

            // A video binds the bar: both stay away.
            View bar = new View(detail);
            bar.setId(BAR_ID);
            root.addView(bar);
            VideoOverlayHider.applyTo(detail);
            assertEquals(View.GONE, bar.getVisibility());
            assertEquals(View.GONE, strip.getVisibility());

            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
            VideoOverlayHider.applyTo(detail);
            assertEquals(View.VISIBLE, strip.getVisibility());
            assertEquals(View.VISIBLE, bar.getVisibility());
        } finally {
            Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
        }
    }


    /**
     * The phones take the Android 11 and later branch, which hides through each window's own
     * insets controller. That branch keeps one record per window too (#50).
     */
    @Test
    @Config(sdk = 34)
    public void eachWindowKeepsItsOwnStatusBarOnAndroid11AndUp() {
        try (var main = Robolectric.buildActivity(Activity.class).setup();
             var detail = Robolectric.buildActivity(Activity.class).setup()) {
            View mainDecor = main.get().getWindow().getDecorView();
            View detailDecor = detail.get().getWindow().getDecorView();
            int bySwipe = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;

            VideoOverlayHider.setStatusBarHidden(detail.get(), true);
            long now = android.os.SystemClock.uptimeMillis();
            assertFalse(VideoOverlayHider.rehideAllowed(detailDecor, now));
            assertTrue(VideoOverlayHider.rehideAllowed(mainDecor, now));
            assertEquals(bySwipe, detailDecor.getWindowInsetsController().getSystemBarsBehavior());
            assertNotEquals("the feed's window was asked to hide its bar",
                    bySwipe, mainDecor.getWindowInsetsController().getSystemBarsBehavior());

            // Nothing was hidden in the feed's window, so giving back there leaves this one.
            VideoOverlayHider.setStatusBarHidden(main.get(), false);
            assertFalse(VideoOverlayHider.rehideAllowed(detailDecor, now));

            VideoOverlayHider.setStatusBarHidden(detail.get(), false);
            assertTrue(VideoOverlayHider.rehideAllowed(detailDecor, now));
        }
    }

    /**
     * Clear display has its own bar at the bottom: a close button and a pause and speed pill
     * over a progress bar (#97). They go only while TikTok shows that bar, because the progress
     * bar shares its id with the one TikTok draws outside Clear display, and they come back the
     * moment TikTok takes the bar down, under Pause, or with the switch off.
     */
    @Test
    public void theClearDisplayControlsGoOnlyWhileTikTokShowsThem() {
        int exitId = 0x7f0a0c01;
        int playbackId = 0x7f0a0c02;
        int seekBarId = 0x7f0a0c03;
        VideoOverlayHider.resolveForTests("47.4.3:e_3", exitId);
        VideoOverlayHider.resolveForTests("47.4.3:l6j", playbackId);
        VideoOverlayHider.resolveForTests("video_seek_bar", seekBarId);
        Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout clearBar = new FrameLayout(activity);
            View exit = new View(activity);
            exit.setId(exitId);
            clearBar.addView(exit);
            LinearLayout playback = new LinearLayout(activity);
            playback.setId(playbackId);
            clearBar.addView(playback);
            root.addView(clearBar);
            View seekBar = new View(activity);
            seekBar.setId(seekBarId);
            root.addView(seekBar);
            activity.setContentView(root);

            clearBar.setVisibility(View.GONE);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the progress bar outside Clear display was taken",
                    View.VISIBLE, seekBar.getVisibility());

            clearBar.setVisibility(View.VISIBLE);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, exit.getVisibility());
            assertEquals(View.GONE, playback.getVisibility());
            assertEquals("the progress bar left the screen and can't be dragged",
                    View.VISIBLE, seekBar.getVisibility());
            assertEquals(0f, seekBar.getAlpha(), 0f);

            // Restore display: TikTok takes its bar down and everything is TikTok's again.
            clearBar.setVisibility(View.GONE);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, exit.getVisibility());
            assertEquals(View.VISIBLE, playback.getVisibility());
            assertEquals(View.VISIBLE, seekBar.getVisibility());
            assertEquals(1f, seekBar.getAlpha(), 0f);

            clearBar.setVisibility(View.VISIBLE);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the progress bar left the screen and can't be dragged",
                    View.VISIBLE, seekBar.getVisibility());
            assertEquals(0f, seekBar.getAlpha(), 0f);
            app.morphe.extension.shared.settings.PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, exit.getVisibility());
            assertEquals(View.VISIBLE, seekBar.getVisibility());
            assertEquals(1f, seekBar.getAlpha(), 0f);
            app.morphe.extension.shared.settings.PausedProcess.set(false);

            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, playback.getVisibility());
            Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, exit.getVisibility());
            assertEquals(View.VISIBLE, playback.getVisibility());
            assertEquals(View.VISIBLE, seekBar.getVisibility());
            assertEquals(1f, seekBar.getAlpha(), 0f);
        } finally {
            app.morphe.extension.shared.settings.PausedProcess.set(false);
            Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(false);
        }
    }

    /**
     * TikTok's loading line grows along the bottom of a video while it buffers, and it was the
     * one bar left on a cleared screen (#84). Its frame goes see-through while TikTok's clear-mode
     * bar is up, whatever TikTok does to the line itself, and comes back with the bar.
     */
    @Test
    public void theLoadingLineGoesWithTheClearDisplayControls() {
        int exitId = 0x7f0a0c11;
        int lineId = 0x7f0a0c12;
        VideoOverlayHider.resolveForTests("47.4.3:e_3", exitId);
        VideoOverlayHider.resolveForTests("47.4.3:nf6", lineId);
        Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout clearBar = new FrameLayout(activity);
            View exit = new View(activity);
            exit.setId(exitId);
            clearBar.addView(exit);
            root.addView(clearBar);
            FrameLayout lineFrame = new FrameLayout(activity);
            View line = new View(activity);
            line.setId(lineId);
            lineFrame.addView(line);
            root.addView(lineFrame);
            activity.setContentView(root);

            clearBar.setVisibility(View.GONE);
            VideoOverlayHider.applyTo(activity);
            assertEquals("outside Clear display the line is TikTok's", 1f, lineFrame.getAlpha(), 0f);

            clearBar.setVisibility(View.VISIBLE);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0f, lineFrame.getAlpha(), 0f);
            assertEquals("the frame stays laid out", View.VISIBLE, lineFrame.getVisibility());
            assertEquals("the line itself is left to TikTok", 1f, line.getAlpha(), 0f);

            // TikTok shows the line again for the next buffer; the frame still covers it.
            line.setVisibility(View.GONE);
            line.setVisibility(View.VISIBLE);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0f, lineFrame.getAlpha(), 0f);

            clearBar.setVisibility(View.GONE);
            VideoOverlayHider.applyTo(activity);
            assertEquals("back with TikTok's bar", 1f, lineFrame.getAlpha(), 0f);

            clearBar.setVisibility(View.VISIBLE);
            VideoOverlayHider.applyTo(activity);
            Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("back with the switch off", 1f, lineFrame.getAlpha(), 0f);
        } finally {
            Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(false);
        }
    }

    /**
     * A photo post in Clear display has only a close button at the bottom right, inside the
     * cell, and TikTok takes it and its parent away as Clear display ends. It goes with the other
     * controls, and when it's put back after TikTok's exit it stays inside the hidden parent,
     * where it can't be seen or tapped.
     */
    @Test
    public void aPhotoPostsClearDisplayCloseButtonGoesToo() {
        int photoExitId = 0x7f0a0c04;
        VideoOverlayHider.resolveForTests("47.4.3:uym", photoExitId);
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            FrameLayout exitArea = new FrameLayout(activity);
            RelativeLayout photoExit = new RelativeLayout(activity);
            photoExit.setId(photoExitId);
            exitArea.addView(photoExit);
            cell.addView(exitArea);
            root.addView(cell);
            activity.setContentView(root);

            exitArea.setVisibility(View.GONE);
            photoExit.setVisibility(View.GONE);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, photoExit.getVisibility());

            exitArea.setVisibility(View.VISIBLE);
            photoExit.setVisibility(View.VISIBLE);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, photoExit.getVisibility());

            // Restore display: TikTok hides both. Ours comes back inside a parent that stays gone.
            exitArea.setVisibility(View.GONE);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, photoExit.getVisibility());
            assertFalse(photoExit.isShown());

            exitArea.setVisibility(View.VISIBLE);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, photoExit.getVisibility());
            Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(photoExit.isShown());
        } finally {
            Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(false);
            VideoOverlayHider.resolveForTests("view_rootview", 0);
        }
    }

    /**
     * A window in the default cutout mode is kept below the punch hole while its status bar is
     * hidden, which left a black strip over opened videos on Android 14 and older (#97). The
     * window draws behind the cutout while this class has the bar away and gets its own mode
     * back after. A mode TikTok picked is left as it is.
     */
    @Test
    @Config(sdk = 34)
    public void aHiddenStatusBarLetsTheVideoUnderTheCutout() {
        int defaultMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        int shortEdges = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        int never = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        try (var main = Robolectric.buildActivity(Activity.class).setup();
             var detail = Robolectric.buildActivity(Activity.class).setup()) {
            android.view.Window window = detail.get().getWindow();
            assertEquals(defaultMode, window.getAttributes().layoutInDisplayCutoutMode);

            VideoOverlayHider.setStatusBarHidden(detail.get(), true);
            assertEquals(shortEdges, window.getAttributes().layoutInDisplayCutoutMode);
            VideoOverlayHider.setStatusBarHidden(detail.get(), true);
            assertEquals(shortEdges, window.getAttributes().layoutInDisplayCutoutMode);
            assertEquals("the other window was changed", defaultMode,
                    main.get().getWindow().getAttributes().layoutInDisplayCutoutMode);

            // A page change puts the default back while the bar is still away.
            android.view.WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = defaultMode;
            window.setAttributes(attributes);
            VideoOverlayHider.setStatusBarHidden(detail.get(), true);
            assertEquals(shortEdges, window.getAttributes().layoutInDisplayCutoutMode);

            VideoOverlayHider.setStatusBarHidden(detail.get(), false);
            assertEquals(defaultMode, window.getAttributes().layoutInDisplayCutoutMode);

            attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = never;
            window.setAttributes(attributes);
            VideoOverlayHider.setStatusBarHidden(detail.get(), true);
            assertEquals(never, window.getAttributes().layoutInDisplayCutoutMode);
            VideoOverlayHider.setStatusBarHidden(detail.get(), false);
            assertEquals(never, window.getAttributes().layoutInDisplayCutoutMode);
        }
    }

    /**
     * On a tall screen TikTok keeps a blank as tall as the status bar above the video. With the
     * bar hidden that blank was a black strip over every video (#97), so it goes with the bar and
     * comes back, invisible as TikTok left it, when the switch is off.
     */
    @Test
    public void theBlankAboveTheVideoGoesWithTheStatusBar() {
        int spacerId = 0x7f0a0c11;
        VideoOverlayHider.resolveForTests("47.4.3:dus", spacerId);
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        Settings.HIDE_STATUS_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            LinearLayout area = new LinearLayout(activity);
            area.setOrientation(LinearLayout.VERTICAL);
            View spacer = new View(activity);
            spacer.setId(spacerId);
            spacer.setVisibility(View.INVISIBLE);
            area.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 136));
            area.addView(new View(activity), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            cell.addView(area);
            root.addView(cell);
            activity.setContentView(root);

            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());

            Settings.HIDE_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, spacer.getVisibility());
        } finally {
            Settings.HIDE_STATUS_BAR.save(false);
            VideoOverlayHider.resolveForTests("view_rootview", 0);
        }
    }

    /** Android 15 already draws TikTok's default-mode windows behind the cutout. */
    @Test
    @Config(sdk = 35)
    public void theCutoutModeIsLeftAloneOnAndroid15AndUp() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            android.view.Window window = controller.get().getWindow();
            int before = window.getAttributes().layoutInDisplayCutoutMode;
            VideoOverlayHider.setStatusBarHidden(controller.get(), true);
            assertEquals(before, window.getAttributes().layoutInDisplayCutoutMode);
            VideoOverlayHider.setStatusBarHidden(controller.get(), false);
            assertEquals(before, window.getAttributes().layoutInDisplayCutoutMode);
        }
    }

    /**
     * Fade the video controls (#84): the column holding the rail, caption and music row and the
     * tab strip go to the chosen opacity and keep taking taps, 100 leaves everything stock, 0
     * hides the column the way Clear display does, and Pause or a repatch without the overlay
     * patch answers the stock look.
     */
    private static final int FADE_CELL_ID = 0x7f0a0d01;
    private static final int FADE_COLUMN_ID = 0x7f0a0d02;
    private static final int FADE_TABS_ID = 0x7f0a0d03;

    private static void resolveFadeIds() {
        VideoOverlayHider.resolveForTests("view_rootview", FADE_CELL_ID);
        VideoOverlayHider.resolveForTests("47.4.3:mud", FADE_COLUMN_ID);
        VideoOverlayHider.resolveForTests("47.4.3:uwq", FADE_TABS_ID);
    }

    private static void clearFadeIds() {
        VideoOverlayHider.resolveForTests("view_rootview", 0);
        VideoOverlayHider.resolveForTests("47.4.3:mud", 0);
        VideoOverlayHider.resolveForTests("47.4.3:uwq", 0);
    }

    @Test
    public void fadingTheControlsKeepsThemTouchableAndSetsTheirOpacity() {
        resolveFadeIds();
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(FADE_CELL_ID);
            FrameLayout column = new FrameLayout(activity);
            column.setId(FADE_COLUMN_ID);
            column.setClickable(true);
            cell.addView(column);
            View tabs = new View(activity);
            tabs.setId(FADE_TABS_ID);
            root.addView(cell);
            root.addView(tabs);
            activity.setContentView(root);

            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(activity);
            assertEquals("100 is stock", 1f, column.getAlpha(), 0f);
            assertEquals(1f, tabs.getAlpha(), 0f);

            Settings.FADE_CONTROLS_OPACITY.save(50);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, column.getAlpha(), 0f);
            assertEquals(0.5f, tabs.getAlpha(), 0f);
            assertEquals("a faded column still takes taps", View.VISIBLE, column.getVisibility());
            assertTrue(column.isClickable());
            assertEquals(View.VISIBLE, tabs.getVisibility());

            Settings.FADE_CONTROLS_OPACITY.save(0);
            VideoOverlayHider.applyTo(activity);
            assertEquals("0 hides the column the way Clear display does",
                    View.GONE, column.getVisibility());
            assertEquals("the tabs never fade below the floor",
                    VideoOverlayHider.NAVIGATION_FADE_FLOOR / 100f, tabs.getAlpha(), 0.0001f);
            assertEquals(View.VISIBLE, tabs.getVisibility());

            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, column.getVisibility());
            assertEquals("back to 100 puts the opacity back", 1f, column.getAlpha(), 0f);
            assertEquals(1f, tabs.getAlpha(), 0f);
        } finally {
            Settings.FADE_CONTROLS_OPACITY.save(100);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
            clearFadeIds();
        }
    }

    /**
     * 47.1.4 keeps the caption frame and the music disc beside the rail's column, so fading the
     * column alone left the caption and the disc at full opacity (S22, 2026-10-08).
     */
    @Test
    public void theCaptionFrameAndMusicDiscFadeBesideTheColumn() {
        int column471 = 0x7f0a0d11, frame471 = 0x7f0a0d12, disc = 0x7f0a0d13;
        resolveFadeIds();
        VideoOverlayHider.resolveForTests("47.4.3:mud", column471);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", frame471);
        VideoOverlayHider.resolveForTests("videomusiccoverblock", disc);
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            // 47.1.4: three siblings in the cell.
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(FADE_CELL_ID);
            View column = new View(activity);
            column.setId(column471);
            View frame = new View(activity);
            frame.setId(frame471);
            frame.setClickable(true);
            View cover = new View(activity);
            cover.setId(disc);
            cell.addView(column);
            cell.addView(frame);
            cell.addView(cover);
            FrameLayout root = new FrameLayout(activity);
            root.addView(cell);
            activity.setContentView(root);

            Settings.FADE_CONTROLS_OPACITY.save(50);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the column", 0.5f, column.getAlpha(), 0f);
            assertEquals("the caption frame beside it", 0.5f, frame.getAlpha(), 0f);
            assertEquals("the music disc beside it", 0.5f, cover.getAlpha(), 0f);
            assertEquals("a faded caption still takes taps", View.VISIBLE, frame.getVisibility());

            Settings.FADE_CONTROLS_OPACITY.save(0);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, column.getVisibility());
            assertEquals("0 takes the caption away without moving it", View.INVISIBLE, frame.getVisibility());
            assertEquals(View.INVISIBLE, cover.getVisibility());

            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(activity);
            for (View view : new View[]{column, frame, cover}) {
                assertEquals("100 puts everything back", View.VISIBLE, view.getVisibility());
                assertEquals(1f, view.getAlpha(), 0f);
            }
        } finally {
            Settings.FADE_CONTROLS_OPACITY.save(100);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
            clearFadeIds();
            VideoOverlayHider.resolveForTests("47.4.3:mud", 0);
            VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
            VideoOverlayHider.resolveForTests("videomusiccoverblock", 0);
        }
    }

    /**
     * 47.1.4's full-width search bar under the caption sits in a container of its own, so it
     * stayed at full opacity under a faded caption and stayed tappable in Clear display (S22,
     * 2026-10-08). It fades with the controls, and Clear display or 0 take it out of reach
     * without moving the caption above it.
     */
    @Test
    public void theSearchBarUnderTheCaptionFadesAndLeavesWithClearDisplay() {
        int barId = 0x7f0a0d15;
        resolveFadeIds();
        VideoOverlayHider.resolveForTests("47.4.3:ljb", barId);
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(FADE_CELL_ID);
            View bar = new View(activity);
            bar.setId(barId);
            bar.setClickable(true);
            cell.addView(bar);
            FrameLayout root = new FrameLayout(activity);
            root.addView(cell);
            activity.setContentView(root);

            Settings.FADE_CONTROLS_OPACITY.save(50);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, bar.getAlpha(), 0f);
            assertEquals("a faded bar still takes taps", View.VISIBLE, bar.getVisibility());

            Settings.FADE_CONTROLS_OPACITY.save(0);
            VideoOverlayHider.applyTo(activity);
            assertEquals("0 takes it away without moving the caption", View.INVISIBLE, bar.getVisibility());

            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, bar.getVisibility());
            assertEquals(1f, bar.getAlpha(), 0f);

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(activity);
            assertEquals("Clear display leaves no bar to tap by accident", View.INVISIBLE, bar.getVisibility());

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, bar.getVisibility());
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            Settings.CLEAR_DISPLAY.save(false);
            Settings.FADE_CONTROLS_OPACITY.save(100);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
            clearFadeIds();
            VideoOverlayHider.resolveForTests("47.4.3:ljb", 0);
        }
    }

    /**
     * Clear display ending animates the controls back to full opacity, and no layout follows,
     * so the fade was gone until the next video (S22, 2026-10-08). The next frame fades what
     * TikTok wrote, and back at 100 the frame pass leaves TikTok's own values alone.
     */
    @Test
    public void theFadeComesBackOnTheNextFrameAfterTikTokWritesItsOwnOpacity() {
        int barId = 0x7f0a0d16;
        resolveFadeIds();
        VideoOverlayHider.resolveForTests("47.4.3:ljb", barId);
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(FADE_CELL_ID);
            View bar = new View(activity);
            bar.setId(barId);
            cell.addView(bar);
            FrameLayout root = new FrameLayout(activity);
            root.addView(cell);
            activity.setContentView(root);
            View content = activity.findViewById(android.R.id.content);

            Settings.FADE_CONTROLS_OPACITY.save(50);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, bar.getAlpha(), 0f);

            // Restore display's animation ends on 1, then midway through the next one on 0.6.
            bar.setAlpha(1f);
            content.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("faded again before the frame", 0.5f, bar.getAlpha(), 0f);
            bar.setAlpha(0.6f);
            content.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(0.3f, bar.getAlpha(), 0.0001f);
            content.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("its own value is left as it is", 0.3f, bar.getAlpha(), 0.0001f);

            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(activity);
            bar.setAlpha(0.8f);
            content.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("100 must not keep rewriting TikTok's own opacity", 0.8f, bar.getAlpha(), 0f);
        } finally {
            Settings.FADE_CONTROLS_OPACITY.save(100);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
            clearFadeIds();
            VideoOverlayHider.resolveForTests("47.4.3:ljb", 0);
        }
    }

    @Test
    public void theFadeFollowsRecycledViewsAndTikToksOwnAlphaWrites() {
        resolveFadeIds();
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(FADE_CELL_ID);
            View column = new View(activity);
            column.setId(FADE_COLUMN_ID);
            cell.addView(column);
            root.addView(cell);
            activity.setContentView(root);

            Settings.FADE_CONTROLS_OPACITY.save(50);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, column.getAlpha(), 0f);

            // An animation ends by writing 1 back; the next pass fades it again.
            column.setAlpha(1f);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, column.getAlpha(), 0f);

            // The feed re-binds a cell: a new column under the same id is faded on the next pass.
            cell.removeView(column);
            View rebound = new View(activity);
            rebound.setId(FADE_COLUMN_ID);
            cell.addView(rebound);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, rebound.getAlpha(), 0f);

            // A new level is taken from the opacity the view had before, not compounded.
            Settings.FADE_CONTROLS_OPACITY.save(25);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.25f, rebound.getAlpha(), 0f);
        } finally {
            Settings.FADE_CONTROLS_OPACITY.save(100);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
            clearFadeIds();
        }
    }

    @Test
    public void thePauseAndARepatchWithoutTheOverlayPatchLeaveTheControlsAlone() {
        resolveFadeIds();
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(FADE_CELL_ID);
            View column = new View(activity);
            column.setId(FADE_COLUMN_ID);
            cell.addView(column);
            root.addView(cell);
            activity.setContentView(root);

            Settings.FADE_CONTROLS_OPACITY.save(50);

            // A saved value does nothing when the patch that installs the hooks is not there.
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = false;
            VideoOverlayHider.applyTo(activity);
            assertEquals(1f, column.getAlpha(), 0f);

            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, column.getAlpha(), 0f);

            // Pause gives the stock look back, and keeps it while paused.
            app.morphe.extension.shared.settings.PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(1f, column.getAlpha(), 0f);
            VideoOverlayHider.applyTo(activity);
            assertEquals(1f, column.getAlpha(), 0f);
            assertEquals(View.VISIBLE, column.getVisibility());

            app.morphe.extension.shared.settings.PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, column.getAlpha(), 0f);
        } finally {
            app.morphe.extension.shared.settings.PausedProcess.set(false);
            Settings.FADE_CONTROLS_OPACITY.save(100);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
            clearFadeIds();
        }
    }

    /**
     * The progress bar on a paused video stayed at full strength under a faded setting (#84).
     * TikTok writes its show type's alpha on the SeekBar inside the row, which is what undid a
     * fade put there; the row is never written by it, so the fade goes on the row, and the time
     * label that rides in the row fades with it. A value TikTok writes on the row is faded again
     * before the frame.
     */
    @Test
    public void theProgressBarRowFadesAndTikTokPausingItDoesNotUndoIt() {
        int rowId = 0x7f0a0e01;
        VideoOverlayHider.resolveForTests("video_seek_bar", rowId);
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout row = new FrameLayout(activity);
            row.setId(rowId);
            View seekBar = new View(activity);
            row.addView(seekBar);
            FrameLayout root = new FrameLayout(activity);
            root.addView(row);
            activity.setContentView(root);
            View content = activity.findViewById(android.R.id.content);

            Settings.FADE_CONTROLS_OPACITY.save(30);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.3f, row.getAlpha(), 0.0001f);
            assertEquals("the bar inside is left to TikTok", 1f, seekBar.getAlpha(), 0f);

            // The show type of a paused video: alpha 1 on the SeekBar, then on the row by an animation.
            seekBar.setAlpha(1f);
            row.setAlpha(1f);
            content.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(0.3f, row.getAlpha(), 0.0001f);
            assertEquals(1f, seekBar.getAlpha(), 0f);
            assertEquals("the bar still takes a drag", View.VISIBLE, row.getVisibility());

            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(activity);
            assertEquals(1f, row.getAlpha(), 0f);
        } finally {
            Settings.FADE_CONTROLS_OPACITY.save(100);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
        }
    }

    /**
     * A video opened from search has a search bar across the top that sits outside the cells, so
     * the fade never reached it or the gradient behind it. They fade like the tabs, never past the
     * floor, and in the faded Clear display they show at the chosen level.
     */
    @Test
    public void theSearchBarAcrossTheTopOfAnOpenedVideoFades() {
        int barId = 0x7f0a0e02;
        int scrimId = 0x7f0a0e03;
        VideoOverlayHider.resolveForTests("47.4.3:j1z", barId);
        VideoOverlayHider.resolveForTests("47.4.3:g5g", scrimId);
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            View scrim = new View(activity);
            scrim.setId(scrimId);
            View bar = new FrameLayout(activity);
            bar.setId(barId);
            bar.setClickable(true);
            root.addView(scrim);
            root.addView(bar);
            activity.setContentView(root);

            Settings.FADE_CONTROLS_OPACITY.save(30);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.3f, bar.getAlpha(), 0.0001f);
            assertEquals(0.3f, scrim.getAlpha(), 0.0001f);
            assertEquals("the way back out stays in reach", View.VISIBLE, bar.getVisibility());

            Settings.FADE_CONTROLS_OPACITY.save(0);
            VideoOverlayHider.applyTo(activity);
            assertEquals("never past the floor", 0.1f, bar.getAlpha(), 0.0001f);
            assertEquals(View.VISIBLE, bar.getVisibility());

            // Faded Clear display: TikTok left the bar at 0.5, and it shows at the level.
            Settings.FADE_CONTROLS_OPACITY.save(30);
            VideoOverlayHider.applyTo(activity);
            bar.setAlpha(0.5f);
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.3f, bar.getAlpha(), 0.0001f);

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(activity);
            assertEquals("TikTok's own last value comes back", 0.5f, bar.getAlpha(), 0f);
            assertEquals(1f, scrim.getAlpha(), 0f);
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            Settings.CLEAR_DISPLAY.save(false);
            Settings.FADE_CONTROLS_OPACITY.save(100);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
            VideoOverlayHider.resolveForTests("47.4.3:j1z", 0);
            VideoOverlayHider.resolveForTests("47.4.3:g5g", 0);
        }
    }

    /**
     * A video opened from search with Automatic Clear Display at no delay: until its clear lands,
     * the top bar and its "find related content" box showed over the held-away controls (#84,
     * Login2 emulator). They're out of sight while the page's window holds, and TikTok's own
     * value comes back once the clear is TikTok's.
     */
    @Test
    public void theTopBarOfAnOpenedVideoWaitsForItsClear() {
        int barId = 0x7f0a0e22;
        int scrimId = 0x7f0a0e23;
        VideoOverlayHider.resolveForTests("47.4.3:j1z", barId);
        VideoOverlayHider.resolveForTests("47.4.3:g5g", scrimId);
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        boolean automatic = app.morphe.extension.tiktok.settings.SettingsStatus.automaticClearDisplayEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
        app.morphe.extension.tiktok.settings.SettingsStatus.automaticClearDisplayEnabled = true;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            View scrim = new View(activity);
            scrim.setId(scrimId);
            View bar = new FrameLayout(activity);
            bar.setId(barId);
            root.addView(scrim);
            root.addView(bar);
            activity.setContentView(root);
            Settings.AUTOMATIC_CLEAR_DISPLAY.save(true);
            Settings.AUTOMATIC_CLEAR_DISPLAY_DELAY.save(0);

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch.onPageOpened(new Object());
            VideoOverlayHider.applyTo(activity);
            assertEquals("the bar showed before the page's clear", 0f, bar.getAlpha(), 0f);
            assertEquals(0f, scrim.getAlpha(), 0f);

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(activity);
            assertEquals("TikTok's own value didn't come back", 1f, bar.getAlpha(), 0f);
            assertEquals(1f, scrim.getAlpha(), 0f);
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            org.robolectric.util.ReflectionHelpers.callStaticMethod(
                    app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch.class, "resetForTests");
            Settings.CLEAR_DISPLAY.save(false);
            Settings.AUTOMATIC_CLEAR_DISPLAY.save(false);
            Settings.AUTOMATIC_CLEAR_DISPLAY_DELAY.save(1000);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
            app.morphe.extension.tiktok.settings.SettingsStatus.automaticClearDisplayEnabled = automatic;
            VideoOverlayHider.resolveForTests("47.4.3:j1z", 0);
            VideoOverlayHider.resolveForTests("47.4.3:g5g", 0);
        }
    }

    /**
     * Also on opened videos (#158): with the feed's search button switch and its own row on, the
     * search box in an opened video's top bar goes invisible and Back stays. A box of the same name
     * outside the bar is another page's and is left alone. TikTok showing it again is answered on
     * the next pass, and either switch off puts it back.
     */
    @Test
    public void theSearchBoxAtTheTopOfAnOpenedVideoHidesAndComesBack() {
        int barId = 0x7f0a0e12;
        int boxId = 0x7f0a0e13;
        VideoOverlayHider.resolveForTests("47.4.3:j1z", barId);
        VideoOverlayHider.resolveForTests("47.4.3:o94", boxId);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout bar = new FrameLayout(activity);
            bar.setId(barId);
            View back = new View(activity);
            back.setClickable(true);
            LinearLayout box = new LinearLayout(activity);
            box.setId(boxId);
            bar.addView(back);
            bar.addView(box);
            LinearLayout elsewhere = new LinearLayout(activity);
            elsewhere.setId(boxId);
            root.addView(bar);
            root.addView(elsewhere);
            activity.setContentView(root);

            Settings.HIDE_FEED_SEARCH_BUTTON.save(true);
            Settings.HIDE_OPENED_VIDEO_SEARCH.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the feed's switch alone leaves it", View.VISIBLE, box.getVisibility());

            Settings.HIDE_OPENED_VIDEO_SEARCH.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, box.getVisibility());
            assertEquals("Back stays", View.VISIBLE, back.getVisibility());
            assertEquals("another page's box is left alone", View.VISIBLE, elsewhere.getVisibility());

            box.setVisibility(View.VISIBLE);
            VideoOverlayHider.applyTo(activity);
            assertEquals("shown again by TikTok, taken again", View.INVISIBLE, box.getVisibility());

            Settings.HIDE_FEED_SEARCH_BUTTON.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the row rides on the feed's switch", View.VISIBLE, box.getVisibility());

            Settings.HIDE_FEED_SEARCH_BUTTON.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, box.getVisibility());
            Settings.HIDE_OPENED_VIDEO_SEARCH.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off puts it back", View.VISIBLE, box.getVisibility());
            assertEquals(View.VISIBLE, back.getVisibility());
        } finally {
            Settings.HIDE_FEED_SEARCH_BUTTON.resetToDefault();
            Settings.HIDE_OPENED_VIDEO_SEARCH.resetToDefault();
            VideoOverlayHider.resolveForTests("47.4.3:j1z", 0);
            VideoOverlayHider.resolveForTests("47.4.3:o94", 0);
        }
    }

    /**
     * The Full screen button has no id to look up, so the patch hands the view over as TikTok binds
     * it. It fades with the controls, lets a finger through it in the faded Clear display, goes out
     * of sight at 0 without moving what is beside it, and comes back at 100.
     */
    @Test
    public void theFullScreenButtonFadesWithTheControls() {
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            View entry = new View(activity);
            entry.setClickable(true);
            root.addView(entry);
            activity.setContentView(root);
            FeedOverlayControls.bindFullscreen(entry);

            Settings.FADE_CONTROLS_OPACITY.save(30);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.3f, entry.getAlpha(), 0.0001f);
            assertEquals(View.VISIBLE, entry.getVisibility());
            assertFalse("outside Clear display it takes taps", TapThroughControls.isMarked(entry));

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.3f, entry.getAlpha(), 0.0001f);
            assertTrue("a finger goes through it in the faded Clear display",
                    TapThroughControls.isMarked(entry));

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            Settings.FADE_CONTROLS_OPACITY.save(0);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, entry.getVisibility());
            assertFalse(TapThroughControls.isMarked(entry));

            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, entry.getVisibility());
            assertEquals(1f, entry.getAlpha(), 0f);
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            Settings.CLEAR_DISPLAY.save(false);
            Settings.FADE_CONTROLS_OPACITY.save(100);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
        }
    }

    /**
     * Classic Clear display takes the Add comment bar and its strip off an opened video. The faded
     * one keeps the bar in sight at the chosen level instead (the strip stays, so the pager keeps
     * its height), the fade fades it outside Clear display too, and a fade of 0 takes both away.
     */
    @Test
    public void theCommentBarFadesOnAnOpenedVideoInsteadOfStayingSolid() {
        resolveCommentBarIds();
        Settings.HIDE_DETAIL_COMMENT_BAR.save(false);
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
        try (var detailController = Robolectric.buildActivity(
                com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            Activity detail = detailController.get();
            View strip = new View(detail);
            FrameLayout root = new FrameLayout(detail);
            root.addView(pagerColumn(detail, strip));
            View bar = new View(detail);
            bar.setId(BAR_ID);
            bar.setClickable(true);
            root.addView(bar);
            detail.setContentView(root);
            detailController.resume();

            Settings.FADE_CONTROLS_OPACITY.save(40);
            VideoOverlayHider.applyTo(detail);
            assertEquals(0.4f, bar.getAlpha(), 0.0001f);
            assertEquals(View.VISIBLE, bar.getVisibility());
            assertFalse(TapThroughControls.isMarked(bar));

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(detail);
            assertEquals("the faded Clear display keeps the bar in sight", View.VISIBLE, bar.getVisibility());
            assertEquals(0.4f, bar.getAlpha(), 0.0001f);
            assertEquals("and keeps the pager its height", View.VISIBLE, strip.getVisibility());
            assertTrue(TapThroughControls.isMarked(bar));

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            Settings.FADE_CONTROLS_OPACITY.save(0);
            VideoOverlayHider.applyTo(detail);
            assertEquals(View.GONE, bar.getVisibility());
            assertEquals(View.GONE, strip.getVisibility());
            assertFalse(TapThroughControls.isMarked(bar));

            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(detail);
            assertEquals(View.VISIBLE, bar.getVisibility());
            assertEquals(View.VISIBLE, strip.getVisibility());
            assertEquals(1f, bar.getAlpha(), 0f);
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            Settings.CLEAR_DISPLAY.save(false);
            Settings.FADE_CONTROLS_OPACITY.save(100);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
        }
    }

    /**
     * The Following tab's story pill was only ever hidden by Clear display, so with a fade chosen
     * it stayed at full strength the rest of the time (#84). It fades with the tabs now, and Clear
     * display still takes it away.
     */
    @Test
    public void theFollowingStoriesPillFadesWithTheTabs() {
        int storyId = 0x7f0a0e04;
        VideoOverlayHider.resolveForTests("47.4.3:wr5", storyId);
        boolean overlays = app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = true;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout stories = new FrameLayout(activity);
            stories.setId(storyId);
            stories.setClickable(true);
            root.addView(stories);
            activity.setContentView(root);

            Settings.FADE_CONTROLS_OPACITY.save(30);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.3f, stories.getAlpha(), 0.0001f);
            assertEquals(View.VISIBLE, stories.getVisibility());

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(true, 0));
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, stories.getVisibility());

            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, stories.getVisibility());
            assertEquals(0.3f, stories.getAlpha(), 0.0001f);

            Settings.FADE_CONTROLS_OPACITY.save(100);
            VideoOverlayHider.applyTo(activity);
            assertEquals(1f, stories.getAlpha(), 0f);
        } finally {
            app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch
                    .rememberClearDisplayEvent(new ClearEvent(false, 2));
            Settings.CLEAR_DISPLAY.save(false);
            Settings.FADE_CONTROLS_OPACITY.save(100);
            app.morphe.extension.tiktok.settings.SettingsStatus.videoOverlaysEnabled = overlays;
            VideoOverlayHider.resolveForTests("47.4.3:wr5", 0);
        }
    }

    /**
     * The debug line a diagnostic export carries from an opened video (#153): the column's shown
     * children and the strip's, so a "Message..." bar the switch leaves can be named by its id.
     */
    @Test
    public void anOpenedVideosColumnIsNamedForTheDiagnosticExport() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        View pager = new FrameLayout(context);
        pager.setId(android.R.id.content);
        LinearLayout strip = new LinearLayout(context);
        View gone = new View(context);
        gone.setVisibility(View.GONE);
        strip.addView(new TextView(context));
        strip.addView(gone);
        View message = new TextView(context);
        // Not a resource, the way an id this build doesn't name reads.
        int unnamed = View.generateViewId();
        message.setId(unnamed);
        column.addView(pager);
        column.addView(strip);
        column.addView(message);
        strip.setVisibility(View.GONE);

        assertEquals("content FrameLayout 0px, 0x" + Integer.toHexString(unnamed)
                        + " TextView 0px; strip hidden [no id TextView 0px]",
                VideoOverlayHider.columnNote(column, strip));
    }
}

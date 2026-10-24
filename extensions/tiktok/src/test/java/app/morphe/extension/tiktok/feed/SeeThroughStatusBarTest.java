package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import com.ss.android.ugc.aweme.detail.ui.DetailActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * See-through status bar (#145): on the main feed the spacer TikTok keeps above each video for
 * the status bar goes and the bar's color is cleared, so the video reaches the top edge under the
 * clock. Off and Pause give both back exactly, Hide the status bar wins, and a video opened from
 * a profile or search is left as TikTok draws it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SeeThroughStatusBarTest {
    private static final int CELL_ID = 0x7f0a0211;
    private static final int SPACER_ID = 0x7f0a0d31;
    private static final int BAND_ID = 0x7f0a0d32;
    private static final int PLAYER_ID = 0x7f0a5f5e;
    private static final int OWN_BAR_COLOR = 0xFF121212;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        VideoOverlayHider.resolveForTests("47.4.3:dus", SPACER_ID);
        VideoOverlayHider.resolveForTests("47.4.3:wiz", BAND_ID);
        VideoOverlayHider.resolveForTests("player_view", PLAYER_ID);
    }

    @After
    public void tearDown() {
        Settings.SEE_THROUGH_STATUS_BAR.resetToDefault();
        Settings.HIDE_STATUS_BAR.resetToDefault();
        Settings.FIT_VIDEO_TO_SCREEN.resetToDefault();
        SettingsStatus.videoFitEnabled = false;
        PausedProcess.set(false);
        VideoOverlayHider.resolveForTests("view_rootview", 0);
        VideoOverlayHider.resolveForTests("47.4.3:dus", 0);
        VideoOverlayHider.resolveForTests("47.4.3:wiz", 0);
        VideoOverlayHider.resolveForTests("player_view", 0);
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    private static void layOut(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    /** TikTok's player sizes the surface to the picture and centers it, the S22's numbers by default. */
    private static void showVideo(FrameLayout player, View surface, int width, int height) {
        surface.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        layOut(player, 1080, 2181);
    }

    /** A feed cell as 47.1.4 lays it out on a tall screen: the bar's spacer, then the video. */
    private static View feedCell(Activity activity, int spacerVisibility) {
        FrameLayout root = new FrameLayout(activity);
        FrameLayout cell = new FrameLayout(activity);
        cell.setId(CELL_ID);
        LinearLayout area = new LinearLayout(activity);
        area.setOrientation(LinearLayout.VERTICAL);
        View spacer = new View(activity);
        spacer.setId(SPACER_ID);
        spacer.setVisibility(spacerVisibility);
        area.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 75));
        area.addView(new View(activity), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        cell.addView(area);
        root.addView(cell);
        activity.setContentView(root);
        return spacer;
    }

    @Test
    public void theVideoGoesUpUnderTheBarAndOffGivesEverythingBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.INVISIBLE);
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            int flags = activity.getWindow().getAttributes().flags;
            int cutout = activity.getWindow().getAttributes().layoutInDisplayCutoutMode;
            int systemUi = activity.getWindow().getDecorView().getSystemUiVisibility();

            VideoOverlayHider.applyTo(activity);
            assertEquals("the switch is off by default", View.INVISIBLE, spacer.getVisibility());
            assertEquals(OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the strip above the video stayed", View.GONE, spacer.getVisibility());
            assertEquals("the bar wasn't made see through", Color.TRANSPARENT, activity.getWindow().getStatusBarColor());
            assertEquals("the window's flags changed", flags, activity.getWindow().getAttributes().flags);
            assertEquals(cutout, activity.getWindow().getAttributes().layoutInDisplayCutoutMode);
            assertEquals("the bar was hidden", systemUi, activity.getWindow().getDecorView().getSystemUiVisibility());

            // Every layout runs a pass; another changes nothing.
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals(Color.TRANSPARENT, activity.getWindow().getStatusBarColor());

            // TikTok showing the spacer or coloring the bar again on a page change.
            spacer.setVisibility(View.VISIBLE);
            activity.getWindow().setStatusBarColor(Color.BLACK);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals(Color.TRANSPARENT, activity.getWindow().getStatusBarColor());

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't give the spacer back", View.VISIBLE, spacer.getVisibility());
            assertEquals("off didn't give TikTok's latest color back", Color.BLACK, activity.getWindow().getStatusBarColor());
            assertFalse(SeeThroughStatusBar.isCleared(activity));
            assertEquals(flags, activity.getWindow().getAttributes().flags);
            assertEquals(systemUi, activity.getWindow().getDecorView().getSystemUiVisibility());
        }
    }

    /**
     * The S22 kept a black strip under the clock with the switch on: the spacer was gone and the
     * video was up there, but TikTok's own band, as tall as the bar, lies over the top of each
     * cell. It goes with the spacer, invisible so it keeps its place, and comes back with it.
     */
    @Test
    public void theBlackBandOverTheTopOfTheCellGoesWithTheSpacer() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            View band = new View(activity);
            band.setId(BAND_ID);
            band.setBackgroundColor(Color.BLACK);
            cell.addView(band, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 75));

            VideoOverlayHider.applyTo(activity);
            assertEquals("the switch is off by default", View.VISIBLE, band.getVisibility());

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals("the band still covered the top of the video", View.INVISIBLE, band.getVisibility());

            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause kept the band away", View.VISIBLE, band.getVisibility());
            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, band.getVisibility());

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't give the band back", View.VISIBLE, band.getVisibility());

            // Hide the status bar takes the band too: there's no bar left for it to sit under.
            Settings.HIDE_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, band.getVisibility());
        }
    }

    /**
     * With the spacer and the band gone the S22 still showed black under the clock: TikTok sizes
     * a 9:16 video's surface to the picture (1107 by 1968) and centers it in a 2181 px player. The
     * surface grows from its center until it covers the player. A landscape video and a squarer
     * portrait one keep their bands, and off, Pause and a zoom TikTok set itself are respected.
     */
    @Test
    public void aPortraitVideoGrowsToFillThePlayerUnderTheClock() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            FrameLayout player = new FrameLayout(activity);
            player.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            player.addView(surface);
            cell.addView(player, new FrameLayout.LayoutParams(1080, 2181));
            showVideo(player, surface, 1107, 1968);
            assertEquals("not TikTok's centered surface", 106, surface.getTop());

            VideoOverlayHider.applyTo(activity);
            assertEquals("the switch is off by default", 1f, surface.getScaleY(), 0f);

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            float scale = surface.getScaleY();
            assertEquals(scale, surface.getScaleX(), 0f);
            assertTrue("the picture still stopped short of the top", 1968 * scale >= 2181);
            assertTrue("grown past what the player needs", 1968 * scale <= 2181 + 3);

            // Each pass measures again, so the next video in the same cell gets its own answer.
            showVideo(player, surface, 1080, 608);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a landscape video was cropped", 1f, surface.getScaleY(), 0f);
            showVideo(player, surface, 1080, 1440);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a 3:4 video lost a third of its width", 1f, surface.getScaleY(), 0f);
            showVideo(player, surface, 1107, 1968);
            VideoOverlayHider.applyTo(activity);
            assertEquals(scale, surface.getScaleY(), 0f);

            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause kept the picture grown", 1f, surface.getScaleY(), 0f);
            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(scale, surface.getScaleY(), 0f);

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't put the picture back", 1f, surface.getScaleX(), 0f);
            assertEquals(1f, surface.getScaleY(), 0f);
            assertFalse(SeeThroughStatusBar.anyFilled());

            surface.setScaleX(1.5f);
            surface.setScaleY(1.5f);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("TikTok's own zoom was overwritten", 1.5f, surface.getScaleY(), 0f);
        }
    }

    /**
     * #164: only the first video after a cold start filled. That one plays in the surface TikTok
     * creates at launch inside the player. Later cells can get TikTok's SurfaceControl player,
     * whose view is the player itself: TikTok sizes the player to the picture and places it with
     * its translation, and nothing inside it is a surface. The player grows in its space then, and
     * goes back with the switch.
     */
    @Test
    public void aLaterVideoInTikToksSurfaceControlPlayerFillsToo() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            // The first video: TikTok's launch surface inside the player.
            FrameLayout firstCell = new FrameLayout(activity);
            firstCell.setId(CELL_ID);
            FrameLayout firstPlayer = new FrameLayout(activity);
            firstPlayer.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            firstPlayer.addView(surface);
            firstCell.addView(firstPlayer, new FrameLayout.LayoutParams(1080, 2181));
            root.addView(firstCell);
            // The next one: the player is the picture, 1107 by 1968, moved down by TikTok.
            FrameLayout nextCell = new FrameLayout(activity);
            nextCell.setId(CELL_ID);
            FrameLayout space = new FrameLayout(activity);
            FrameLayout nextPlayer = new FrameLayout(activity);
            nextPlayer.setId(PLAYER_ID);
            nextPlayer.addView(new View(activity), new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            space.addView(nextPlayer, new FrameLayout.LayoutParams(1107, 1968));
            nextCell.addView(space, new FrameLayout.LayoutParams(1080, 2181));
            root.addView(nextCell);
            activity.setContentView(root);
            showVideo(firstPlayer, surface, 1107, 1968);
            layOut(space, 1080, 2181);
            nextPlayer.setTranslationX(-13f);
            nextPlayer.setTranslationY(106f);

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertTrue("the first video stopped short of the top", 1968 * surface.getScaleY() >= 2181);
            float scale = nextPlayer.getScaleY();
            assertEquals(scale, nextPlayer.getScaleX(), 0f);
            float center = 106f + 1968 / 2f;
            assertTrue("the next video stopped short of the top: " + scale, center - 1968 * scale / 2f <= 0f);
            assertTrue("the next video stopped short of the bottom: " + scale, center + 1968 * scale / 2f >= 2181f);
            assertTrue("grown past what the space needs: " + scale, scale <= SeeThroughStatusBar.MAX_FILL_SCALE);

            // A wide picture in that player keeps its bands.
            nextPlayer.setLayoutParams(new FrameLayout.LayoutParams(1080, 608));
            layOut(space, 1080, 2181);
            nextPlayer.setTranslationY(786f);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a landscape video was cropped", 1f, nextPlayer.getScaleY(), 0f);

            nextPlayer.setLayoutParams(new FrameLayout.LayoutParams(1107, 1968));
            layOut(space, 1080, 2181);
            nextPlayer.setTranslationY(106f);
            VideoOverlayHider.applyTo(activity);
            assertEquals(scale, nextPlayer.getScaleY(), 0f);

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't put the player back", 1f, nextPlayer.getScaleX(), 0f);
            assertEquals(1f, nextPlayer.getScaleY(), 0f);
            assertEquals(1f, surface.getScaleY(), 0f);
            assertFalse(SeeThroughStatusBar.anyFilled());
        }
    }

    /**
     * Fit the video to the screen shrinks a picture until all of it shows, and growing it back to
     * cover the player would undo that, so Fit wins while both are on.
     */
    @Test
    public void fitTheVideoToTheScreenWinsOverTheFill() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            FrameLayout player = new FrameLayout(activity);
            player.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            player.addView(surface);
            cell.addView(player, new FrameLayout.LayoutParams(1080, 2181));
            // What Fit leaves a 9:16 video at in this player: the whole width, 1920 tall.
            showVideo(player, surface, 1080, 1920);
            SettingsStatus.videoFitEnabled = true;
            Settings.FIT_VIDEO_TO_SCREEN.save(true);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);

            VideoOverlayHider.applyTo(activity);
            assertEquals("the fitted video was grown back over its sides", 1f, surface.getScaleY(), 0f);

            Settings.FIT_VIDEO_TO_SCREEN.save(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue("with Fit off the video didn't fill", 1920 * surface.getScaleY() >= 2181);

            Settings.FIT_VIDEO_TO_SCREEN.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Fit coming back on didn't put the video back", 1f, surface.getScaleY(), 0f);
        }
    }

    @Test
    public void pauseShowsTheFeedAsTikTokDrawsIt() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.INVISIBLE);
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertTrue(SeeThroughStatusBar.isCleared(activity));

            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause kept the strip away", View.INVISIBLE, spacer.getVisibility());
            assertEquals("Pause kept the bar clear", OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());

            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals(Color.TRANSPARENT, activity.getWindow().getStatusBarColor());
        }
    }

    @Test
    public void hidingTheStatusBarWinsAndLeavesTheColorAlone() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.VISIBLE);
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            Settings.HIDE_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the hidden bar's spacer stayed", View.GONE, spacer.getVisibility());
            assertEquals("the hidden bar's color was changed", OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());
            assertFalse(SeeThroughStatusBar.isCleared(activity));
            Settings.HIDE_STATUS_BAR.save(false);
            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, spacer.getVisibility());
            WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT, attributes.layoutInDisplayCutoutMode);
        }
    }

    @Test
    public void aVideoOpenedFromAProfileOrSearchIsLeftAlone() {
        try (var controller = Robolectric.buildActivity(DetailActivity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.VISIBLE);
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("an opened video lost its strip", View.VISIBLE, spacer.getVisibility());
            assertEquals(OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());
        }
    }
}

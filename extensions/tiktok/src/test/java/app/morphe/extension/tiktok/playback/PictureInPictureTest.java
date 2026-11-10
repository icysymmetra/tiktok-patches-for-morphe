package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.util.Rational;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PictureInPictureTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> owner;
    private View decor;
    private FrameLayout cell;
    private TextureView video;
    private TextView caption;
    private TextView tabs;
    private View alreadyGone;
    private TextureView thumbnail;

    @Before public void setUp() {
        SettingsStatus.pictureInPictureEnabled = true;
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = owner.get();
        FrameLayout content = new FrameLayout(activity);
        cell = new FrameLayout(activity);
        video = new TextureView(activity);
        caption = new TextView(activity);
        cell.addView(video, new FrameLayout.LayoutParams(400, 700));
        cell.addView(caption, new FrameLayout.LayoutParams(400, 100));
        content.addView(cell, new FrameLayout.LayoutParams(400, 700));
        tabs = new TextView(activity);
        content.addView(tabs, new FrameLayout.LayoutParams(400, 100));
        alreadyGone = new View(activity);
        alreadyGone.setVisibility(View.GONE);
        content.addView(alreadyGone, new FrameLayout.LayoutParams(400, 100));
        // A small surface, like a LIVE preview's or an ad's, that isn't the video on screen.
        thumbnail = new TextureView(activity);
        content.addView(thumbnail, new FrameLayout.LayoutParams(60, 60));
        activity.setContentView(content);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 400, 800);
    }

    @After public void tearDown() {
        PictureInPicture.bringBack();
        setPaused(false);
        SettingsStatus.pictureInPictureEnabled = false;
        Settings.PICTURE_IN_PICTURE.save(Settings.PICTURE_IN_PICTURE.defaultValue);
        owner.pause().stop().destroy();
    }

    @Test public void onlyTheWindowsOwnButtonCanPauseOrPlay() {
        Activity activity = owner.get();
        Intent sent = Shadows.shadowOf(
                PictureInPicture.actions(activity, true).get(0).getActionIntent()).getSavedIntent();
        assertTrue(PictureInPicture.fromTheButton(sent));

        // Another app sending the action, before Android 13 let a receiver refuse it.
        Intent bare = new Intent(PictureInPicture.ACTION_TOGGLE)
                .setPackage(activity.getPackageName());
        assertFalse(PictureInPicture.fromTheButton(bare));
        assertFalse(PictureInPicture.fromTheButton(
                new Intent(bare).putExtra(PictureInPicture.EXTRA_TOKEN, "guess")));
        assertFalse(PictureInPicture.fromTheButton(null));
    }

    @Test public void offByDefault() {
        assertFalse(Settings.PICTURE_IN_PICTURE.defaultValue);
        assertFalse(PictureInPicture.enabled());
    }

    @Test public void onWhenTheSwitchIs() {
        Settings.PICTURE_IN_PICTURE.save(true);
        assertTrue(PictureInPicture.enabled());
    }

    @Test public void pausedLeavesTikTokAsBefore() {
        Settings.PICTURE_IN_PICTURE.save(true);
        setPaused(true);
        assertFalse(PictureInPicture.enabled());
    }

    /** A switch saved on by an earlier build does nothing after a repatch without the patch. */
    @Test public void aSavedSwitchDoesNothingWithoutItsPatch() {
        Settings.PICTURE_IN_PICTURE.save(true);
        SettingsStatus.pictureInPictureEnabled = false;
        assertFalse(PictureInPicture.enabled());
    }

    /** The video's own shape, held inside what Android takes for a picture-in-picture window. */
    @Test public void theWindowTakesTheVideosShapeWithinAndroidsLimits() {
        assertEquals(new Rational(9, 16), PictureInPicture.shape(1080, 1920));
        assertEquals(new Rational(16, 9), PictureInPicture.shape(1920, 1080));
        assertEquals(new Rational(1, 1), PictureInPicture.shape(720, 720));
        assertSame(PictureInPicture.NARROWEST, PictureInPicture.shape(100, 1000));
        assertSame(PictureInPicture.WIDEST, PictureInPicture.shape(3000, 100));
        assertNull(PictureInPicture.shape(0, 1920));
        assertNull(PictureInPicture.shape(1080, -1));
    }

    @Test public void theVideoIsTheLargestSurfaceShowing() {
        assertSame(video, PictureInPicture.videoView(decor));
        video.setVisibility(View.INVISIBLE);
        assertSame("a hidden surface isn't the video", thumbnail, PictureInPicture.videoView(decor));
        thumbnail.setVisibility(View.GONE);
        assertNull(PictureInPicture.videoView(decor));
    }

    @Test public void theWindowShowsOnlyTheVideoAndEverythingComesBack() {
        PictureInPicture.setAside(decor);
        assertEquals(View.VISIBLE, video.getVisibility());
        assertEquals(View.VISIBLE, cell.getVisibility());
        assertEquals(View.INVISIBLE, caption.getVisibility());
        assertEquals(View.INVISIBLE, tabs.getVisibility());
        assertEquals(View.INVISIBLE, thumbnail.getVisibility());
        assertEquals("a view TikTok had hidden stays as it was", View.GONE, alreadyGone.getVisibility());

        PictureInPicture.bringBack();
        assertEquals(View.VISIBLE, caption.getVisibility());
        assertEquals(View.VISIBLE, tabs.getVisibility());
        assertEquals(View.VISIBLE, thumbnail.getVisibility());
        assertEquals(View.GONE, alreadyGone.getVisibility());
    }

    /** What TikTok changed while the window was up is TikTok's: only views still set aside come back. */
    @Test public void bringingBackLeavesTikToksOwnChangesAlone() {
        PictureInPicture.setAside(decor);
        caption.setVisibility(View.GONE);
        PictureInPicture.bringBack();
        assertEquals(View.GONE, caption.getVisibility());
        assertEquals(View.VISIBLE, tabs.getVisibility());
    }

    /**
     * TikTok shows the cell's controls again after the window opens (S22, 47.1.4, 2026-10-08).
     * The next frame sets them aside again, and once the window closes frames leave them alone.
     */
    @Test public void whatTikTokShowsAgainInTheWindowIsSetAsideOnTheNextFrame() {
        PictureInPicture.keepAsideWhileUp(decor);
        assertEquals(View.INVISIBLE, caption.getVisibility());
        caption.setVisibility(View.VISIBLE);
        alreadyGone.setVisibility(View.VISIBLE);
        decor.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("shown again, set aside again", View.INVISIBLE, caption.getVisibility());
        assertEquals("one TikTok shows later too", View.INVISIBLE, alreadyGone.getVisibility());
        assertEquals(View.VISIBLE, video.getVisibility());

        PictureInPicture.bringBack();
        assertEquals(View.VISIBLE, caption.getVisibility());
        assertEquals(View.VISIBLE, alreadyGone.getVisibility());
        tabs.setVisibility(View.VISIBLE);
        decor.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("the window closed, so frames leave TikTok's views alone",
                View.VISIBLE, caption.getVisibility());
    }

    @Test public void nothingIsSetAsideWithoutAVideo() {
        video.setVisibility(View.GONE);
        thumbnail.setVisibility(View.GONE);
        PictureInPicture.setAside(decor);
        assertEquals(View.VISIBLE, caption.getVisibility());
        assertEquals(View.VISIBLE, tabs.getVisibility());
    }

    /** A plain activity isn't a feed window, so leaving it opens nothing, switch on or off. */
    @Test public void leavingAnotherScreenOpensNothing() {
        Settings.PICTURE_IN_PICTURE.save(true);
        PictureInPicture.onUserLeaveHint(owner.get());
        assertFalse(owner.get().isInPictureInPictureMode());
        assertEquals(View.VISIBLE, tabs.getVisibility());
    }

    /** A window the extension didn't open, TikTok's LIVE one for instance, is left to TikTok. */
    @Test public void aWindowItDidntOpenIsLeftAlone() {
        Settings.PICTURE_IN_PICTURE.save(true);
        PictureInPicture.onModeChanged(owner.get(), true);
        assertEquals(View.VISIBLE, caption.getVisibility());
        assertEquals(View.VISIBLE, tabs.getVisibility());
        PictureInPicture.onModeChanged(null, true);
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }
}

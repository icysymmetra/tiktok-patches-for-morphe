package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.detail.ui.DetailActivity;
import com.ss.android.ugc.aweme.live.LivePlayActivity;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * #38: a LIVE room hides its status bar, reaches into the cutout and loses the strip TikTok keeps
 * for the bar while it is in front, and gives all three back when it leaves; nothing else is
 * touched, and the switch decides.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LiveStatusBarTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(false);
        Settings.LIVE_UNDER_STATUS_BAR.save(false);
    }

    @After public void tearDown() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.resetToDefault();
        Settings.LIVE_UNDER_STATUS_BAR.resetToDefault();
        LiveStatusBar.videoContainerForTests = 0;
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void aLiveRoomHidesItsBarWhileInFrontAndGivesItBack() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).setup();
        LivePlayActivity live = room.get();
        assertTrue("the room's bar wasn't hidden", LiveStatusBar.isHidden(live));
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);

        room.pause();
        assertFalse("leaving kept the bar hidden", LiveStatusBar.isHidden(live));
        assertEquals("the cutout mode wasn't given back",
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);

        room.resume();
        assertTrue("coming back to the room didn't hide it again", LiveStatusBar.isHidden(live));
        room.pause().stop().destroy();
    }

    /** 47.1.3's LIVE window already reaches into the cutout on every edge; that stays. */
    @Test public void aRoomAlreadyInTheCutoutKeepsItsOwnMode() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        WindowManager.LayoutParams attributes = live.getWindow().getAttributes();
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        live.getWindow().setAttributes(attributes);
        room.start().resume().visible();
        assertTrue("the room's bar wasn't hidden", LiveStatusBar.isHidden(live));
        assertEquals("ALWAYS was narrowed", WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);
        room.pause();
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);
        room.stop().destroy();
    }

    @Test public void theSwitchOffOrAnotherScreenLeavesTheBarAlone() {
        followFromTheMainActivity();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).setup();
        assertFalse("hidden with the switch off", LiveStatusBar.isHidden(room.get()));
        room.pause().stop().destroy();

        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        ActivityController<Activity> other = Robolectric.buildActivity(Activity.class).setup();
        assertFalse("a screen that isn't a LIVE room was touched", LiveStatusBar.isHidden(other.get()));
        other.pause().stop().destroy();
    }

    /**
     * On the S22 the bar hid and a black strip stayed: TikTok's room sits a status bar lower than
     * its container and keeps that offset with the bar gone. The shape is 47.1.3's: AppCompat's
     * empty full-screen frame comes first, TikTok's room container second, and the room inside it
     * is held down by a top margin under a full-size layer that starts at the top. A first build
     * followed only the first full-screen child and never reached the room.
     */
    @Test public void theStripKeptForTheBarGoesWhileTheRoomIsInFront() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout root = new FrameLayout(live);
        FrameLayout appCompat = new FrameLayout(live);
        appCompat.addView(new FrameLayout(live), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(appCompat, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout container = new FrameLayout(live);
        root.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout stream = new FrameLayout(live);
        FrameLayout.LayoutParams held = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        held.topMargin = bar;
        held.bottomMargin = 135;
        container.addView(stream, held);
        container.addView(new FrameLayout(live), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        live.setContentView(root);
        room.start().resume().visible();

        layOut(live);
        assertEquals("the strip stayed", 0, topMargin(stream));
        assertEquals("the bottom was touched", 135, ((ViewGroup.MarginLayoutParams) stream.getLayoutParams()).bottomMargin);

        // TikTok setting its offset again on a relayout of its own gets the same answer.
        held.topMargin = bar;
        stream.setLayoutParams(held);
        layOut(live);
        assertEquals("the strip came back on a relayout", 0, topMargin(stream));

        room.pause();
        assertEquals("leaving didn't give the strip back", bar, topMargin(stream));
        layOut(live);
        assertEquals("a layout after leaving took it again", bar, topMargin(stream));

        room.resume();
        layOut(live);
        assertEquals("coming back kept the strip", 0, topMargin(stream));
        room.pause().stop().destroy();
    }

    /**
     * The same strip as a container's own padding, under an outer padding of another height that
     * has to stay; the lifted container keeps its other three sides.
     */
    @Test public void aPaddedContainerLosesOnlyTheBarsHeight() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout outer = new FrameLayout(live);
        outer.setPadding(0, 3, 0, 0);
        FrameLayout container = new FrameLayout(live);
        container.setPadding(7, bar, 9, 135);
        outer.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        live.setContentView(outer);
        room.start().resume().visible();

        layOut(live);
        assertEquals("a padding that isn't the bar's was taken", 3, outer.getPaddingTop());
        assertEquals("the strip stayed", 0, container.getPaddingTop());
        assertEquals(7, container.getPaddingLeft());
        assertEquals(9, container.getPaddingRight());
        assertEquals(135, container.getPaddingBottom());

        room.pause();
        assertEquals("leaving didn't give the strip back", bar, container.getPaddingTop());
        room.stop().destroy();
    }

    /**
     * The room's side drawer (`qwo` on 47.1.4) waits INVISIBLE off to the side, padded a bar tall,
     * one level above the container that holds the stream's strip. The search passes it by and
     * lifts the strip; the drawer keeps its padding for when it slides in.
     */
    @Test public void aHiddenDrawerPaddedForTheBarKeepsItsPaddingAndTheStripGoes() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout layer = new FrameLayout(live);
        RelativeLayout drawer = new RelativeLayout(live);
        drawer.setPadding(0, bar, 0, 0);
        drawer.setVisibility(View.INVISIBLE);
        layer.addView(drawer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout page = new FrameLayout(live);
        layer.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout container = new FrameLayout(live);
        container.setPadding(0, bar, 0, 0);
        page.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        live.setContentView(layer);
        room.start().resume().visible();

        layOut(live);
        assertEquals("the hidden drawer lost its padding", bar, drawer.getPaddingTop());
        assertEquals("the strip stayed", 0, container.getPaddingTop());

        room.pause();
        assertEquals("leaving didn't give the strip back", bar, container.getPaddingTop());
        assertEquals(bar, drawer.getPaddingTop());
        room.stop().destroy();
    }

    @Test public void withTheSwitchOffTheStripStays() {
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout container = new FrameLayout(live);
        container.setPadding(0, bar, 0, 0);
        live.setContentView(container);
        View cover = cover(live, (FrameLayout) container.getParent(), bar);
        room.start().resume().visible();
        layOut(live);
        assertEquals(bar, container.getPaddingTop());
        assertEquals("the cover went with the switch off", View.VISIBLE, cover.getVisibility());
        room.pause().stop().destroy();
    }

    /**
     * #137 on Android 16: TikTok also draws a plain view the bar's height over the top of the room,
     * so under the bar the room's top row sat under black once the strip was gone.
     */
    @Test public void tikToksCoverOverTheBarGoesWhileTheRoomIsInFront() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout root = new FrameLayout(live);
        FrameLayout stream = new FrameLayout(live);
        FrameLayout.LayoutParams held = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        held.topMargin = bar;
        root.addView(stream, held);
        View shade = new View(live);
        shade.setBackgroundColor(Color.DKGRAY);
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar * 2));
        live.setContentView(root);
        View cover = cover(live, root, bar);
        room.start().resume().visible();

        layOut(live);
        assertEquals("the cover stayed", View.INVISIBLE, cover.getVisibility());
        assertEquals("a view that isn't the bar's height went", View.VISIBLE, shade.getVisibility());
        assertEquals("the strip stayed", 0, topMargin(stream));

        cover.setVisibility(View.VISIBLE);
        layOut(live);
        assertEquals("the cover came back on a relayout", View.INVISIBLE, cover.getVisibility());

        room.pause();
        assertEquals("leaving didn't give the cover back", View.VISIBLE, cover.getVisibility());
        room.resume();
        layOut(live);
        assertEquals("coming back kept the cover", View.INVISIBLE, cover.getVisibility());

        // TikTok swapping its cover for a new one, as a rotation or a new room can.
        root.removeView(cover);
        View swapped = cover(live, root, bar);
        layOut(live);
        assertEquals("the new cover stayed", View.INVISIBLE, swapped.getVisibility());
        room.pause().stop().destroy();
    }

    /**
     * #137: under a bar left showing, the stream reaches the top edge while the room's controls
     * stay a bar lower, where TikTok had them, so the creator row isn't under the clock. Every
     * page of the room's pager gets it, and it all goes back as the room leaves.
     */
    @Test public void underTheBarTheControlsStayBelowTheClock() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout controls = page(live, stream);
        FrameLayout nextRoom = page(live, stream);
        room.start().resume().visible();
        layOut(live);

        assertEquals("the strip stayed", 0, topMargin(stream));
        assertEquals("the controls went up under the clock", 7 + bar, controls.getPaddingTop());
        assertEquals("the next room's controls went up", 7 + bar, nextRoom.getPaddingTop());
        layOut(live);
        assertEquals("a relayout lowered the controls twice", 7 + bar, controls.getPaddingTop());

        controls.setPadding(0, 7, 0, 0);
        layOut(live);
        assertEquals("TikTok's own padding came back and stayed", 7 + bar, controls.getPaddingTop());

        room.pause();
        assertEquals("leaving didn't give the controls back", 7, controls.getPaddingTop());
        assertEquals(7, nextRoom.getPaddingTop());
        room.resume();
        layOut(live);
        assertEquals("coming back left the controls up", 7 + bar, controls.getPaddingTop());
        room.pause().stop().destroy();
    }

    /**
     * #137: TikTok's dark shade across the top is part of the controls, so it went a bar lower
     * with them and the strip under the clock showed lighter than the rest of the top. That strip
     * gets the shade's first color now, only on a layer that has a shade, and it goes as the room
     * leaves.
     */
    @Test public void underTheBarTikToksTopShadeCarriesOnUnderTheClock() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout controls = page(live, stream);
        View shade = shade(live, controls);
        FrameLayout plain = page(live, stream);
        room.start().resume().visible();
        layOut(live);
        layOut(live);

        assertEquals("the controls went up under the clock", 7 + bar, controls.getPaddingTop());
        assertTrue("the strip under the clock wasn't shaded", controls.getBackground() instanceof LiveStatusBar.BarShade);
        LiveStatusBar.BarShade fill = (LiveStatusBar.BarShade) controls.getBackground();
        assertEquals("the strip should be as tall as the bar", bar, fill.height);
        assertEquals("the strip should start where the shade does, as drawn", 0x33000000, fill.color());
        assertEquals("a layer without a shade got one", null, plain.getBackground());
        assertEquals("the shade was moved", 0f, shade.getTranslationY(), 0f);

        room.pause();
        assertEquals("leaving kept the strip shaded", null, controls.getBackground());
        assertEquals(7, controls.getPaddingTop());
        room.stop().destroy();
    }

    /**
     * #137: scrolling on through LIVE rooms, the strip under the clock went light again after the
     * first few. The shade search had one budget for the whole visit, and TikTok keeps a room's
     * shade hidden for a while, so a busy LIVE's layout passes used it up. A hidden shade is taken
     * now and its fill shows only while the shade does, and each layer has a budget of its own.
     */
    @Test public void roomsFurtherOnStillGetTheShadeUnderTheClock() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout first = page(live, stream);
        View firstShade = shade(live, first);
        firstShade.setVisibility(View.INVISIBLE);
        page(live, stream);
        room.start().resume().visible();
        for (int pass = 0; pass < 60; pass++) layOut(live);

        assertTrue("a hidden shade got no fill to follow it", first.getBackground() instanceof LiveStatusBar.BarShade);
        LiveStatusBar.BarShade fill = (LiveStatusBar.BarShade) first.getBackground();
        assertEquals("the strip was shaded while TikTok's shade was hidden", 0, fill.color());
        firstShade.setVisibility(View.VISIBLE);
        assertEquals("the strip didn't follow the shade in", 0x33000000, fill.color());

        FrameLayout later = page(live, stream);
        shade(live, later);
        layOut(live);
        layOut(live);
        assertTrue("a room further on got no shade under the clock", later.getBackground() instanceof LiveStatusBar.BarShade);
        room.pause().stop().destroy();
    }

    /**
     * #137, seen on the S22 after the strip fix: a LIVE room lays out many times a second while it
     * loads, and TikTok's shade isn't there to find yet, so the layer's 40 searches were gone in a
     * second and the room kept a light strip under the clock once the shade showed.
     */
    @Test public void aBusyRoomStillFindsItsShadeOnceTikTokShowsIt() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout controls = page(live, stream);
        View shade = shade(live, controls);
        shade.setVisibility(View.GONE);
        room.start().resume().visible();
        for (int pass = 0; pass < 100; pass++) layOut(live);
        assertEquals("a shade not there yet got a fill", null, controls.getBackground());

        shade.setVisibility(View.VISIBLE);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        layOut(live);
        assertTrue("the busy room's searches ran out before the shade showed",
                controls.getBackground() instanceof LiveStatusBar.BarShade);
        room.pause().stop().destroy();
    }

    /**
     * #137, seen on the S22: TikTok shows one shade as a room opens and a moment later puts it out
     * of the layout for another. The fill stayed on the first, so it drew nothing and the strip
     * under the clock was clear over a shaded room.
     */
    @Test public void whenTikTokSwapsItsShadeTheFillFollowsTheNewOne() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout controls = page(live, stream);
        View opening = shade(live, controls);
        room.start().resume().visible();
        layOut(live);
        layOut(live);
        LiveStatusBar.BarShade first = (LiveStatusBar.BarShade) controls.getBackground();
        assertEquals(0x33000000, first.color());

        opening.setVisibility(View.GONE);
        View swapped = new View(live);
        swapped.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x4D000000, Color.TRANSPARENT}));
        ((FrameLayout) opening.getParent()).addView(swapped, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        layOut(live);
        layOut(live);

        assertTrue(controls.getBackground() instanceof LiveStatusBar.BarShade);
        assertEquals("the fill stayed on the shade TikTok put away", 0x4D000000,
                ((LiveStatusBar.BarShade) controls.getBackground()).color());
        room.pause().stop().destroy();
    }

    /**
     * #137, from BynchPOP: Clear display in a LIVE (swipe right) fades the controls with the shade
     * in them, but the strip under the clock stayed shaded, a dark band over a clear room.
     */
    @Test public void clearDisplayTakesTheStripsShadeAwayWithTheControls() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout controls = page(live, stream);
        View shade = shade(live, controls);
        room.start().resume().visible();
        layOut(live);
        layOut(live);
        LiveStatusBar.BarShade fill = (LiveStatusBar.BarShade) controls.getBackground();
        assertEquals(0x33000000, fill.color());

        View row = (View) shade.getParent();
        row.setAlpha(0f);
        assertEquals("the controls faded out and the strip stayed shaded", 0, fill.color());
        row.setAlpha(0.5f);
        assertEquals("the strip didn't fade with the controls", 0x1A000000, fill.color());
        row.setAlpha(1f);
        assertEquals(0x33000000, fill.color());
        room.pause().stop().destroy();
    }

    /**
     * #137, from BynchPOP and read off the S22: Clear display in a LIVE shows a black view a bar
     * tall across the top of the room's page, so the band under the clock came back over the
     * stream. It stays hidden while the page's strip is away, and goes back as the room closes.
     */
    @Test public void clearDisplaysBlackBandStaysAwayWithTheStrip() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout pager = new FrameLayout(live);
        live.setContentView(pager);
        FrameLayout first = roomPage(live, pager, bar);
        roomPage(live, pager, bar);
        View band = new View(live);
        band.setBackgroundColor(Color.BLACK);
        band.setVisibility(View.GONE);
        ((FrameLayout) first.getChildAt(0)).addView(band,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar));
        room.start().resume().visible();
        layOut(live);
        layOut(live);
        assertEquals(0, first.getPaddingTop());
        assertEquals(View.GONE, band.getVisibility());

        band.setVisibility(View.VISIBLE);
        layOut(live);
        assertEquals("Clear display's band covered the stream under the clock", View.INVISIBLE, band.getVisibility());

        band.setVisibility(View.VISIBLE);
        assertTrue("a band shown again without a layout pass wasn't kept out of the frame",
                live.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw());
        assertEquals(View.INVISIBLE, band.getVisibility());

        room.pause();
        assertEquals("leaving kept TikTok's band hidden", View.VISIBLE, band.getVisibility());
        room.stop().destroy();
    }

    /**
     * #137, BynchPOP on 0.73.0: the black strip under the clock showed for a moment, then went. A
     * strip taken on a layout pass only moves the room up on the next one, so the frame drawn in
     * between showed it, and a page the pager binds or adds mid-swipe has no layout pass of the
     * window to be taken on at all. No frame is drawn with a strip or with the controls under the
     * clock now, a cover TikTok shows again is hidden before the frame, and a room TikTok fights
     * for its strip keeps drawing.
     */
    @Test public void noFrameIsDrawnWithTheStripUnderTheClock() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout root = new FrameLayout(live);
        FrameLayout pager = new FrameLayout(live);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        live.setContentView(root);
        FrameLayout first = roomPage(live, pager, bar);
        FrameLayout second = roomPage(live, pager, bar);
        View firstRow = shade(live, controlsOf(first));
        View secondRow = shade(live, controlsOf(second));
        View cover = cover(live, root, bar);
        room.start().resume().visible();

        int drawn = 0;
        for (int i = 0; i < 4; i++) {
            if (!frame(live)) continue;
            drawn++;
            assertDrawnBelowTheClock("opening", bar, first, firstRow);
            assertDrawnBelowTheClock("opening", bar, second, secondRow);
        }
        assertTrue("the room never drew", drawn > 0);
        assertEquals(View.INVISIBLE, cover.getVisibility());

        // The pager binding a room to a page on a layout pass of its own.
        first.setPadding(0, bar, 0, 0);
        drawn = 0;
        for (int i = 0; i < 4; i++) {
            if (!frame(live)) continue;
            drawn++;
            assertDrawnBelowTheClock("a room bound on a layout pass", bar, first, firstRow);
        }
        assertTrue(drawn > 0);

        // The pager binding a room to a page mid-swipe and laying it out itself: the window has
        // no layout pass, so only the frame is left to take the strip before it's drawn.
        second.setPadding(0, bar, 0, 0);
        layOutAlone(second);
        assertTrue("a page bound mid-swipe was drawn with its strip", preDraw(live));
        assertEquals(0, second.getPaddingTop());
        drawn = 0;
        for (int i = 0; i < 4; i++) {
            if (!frame(live)) continue;
            drawn++;
            assertDrawnBelowTheClock("a room bound mid-swipe", bar, second, secondRow);
        }
        assertTrue(drawn > 0);

        // A page the pager adds mid-swipe, never seen on a layout pass.
        FrameLayout later = roomPage(live, pager, bar);
        View laterRow = shade(live, controlsOf(later));
        layOutAlone(later);
        assertTrue("a page added mid-swipe was drawn with its strip", preDraw(live));
        assertEquals(0, later.getPaddingTop());
        drawn = 0;
        for (int i = 0; i < 4; i++) {
            if (!frame(live)) continue;
            drawn++;
            assertDrawnBelowTheClock("a room added mid-swipe", bar, later, laterRow);
        }
        assertTrue(drawn > 0);

        // TikTok's cover over the bar shown again with no layout pass.
        cover.setVisibility(View.VISIBLE);
        assertTrue("the cover shown again was drawn", preDraw(live));
        assertEquals(View.INVISIBLE, cover.getVisibility());
        assertTrue(frame(live) || frame(live));

        // TikTok putting the strip back on every layout: the room keeps drawing.
        int held = 0;
        for (int i = 0; i < 16; i++) {
            first.setPadding(0, bar, 0, 0);
            if (frame(live)) {
                held = 0;
            } else {
                held++;
                assertTrue("a fight over the strip stopped the room drawing", held <= 3);
            }
        }
        assertTrue("a lost fight still holds frames back", frame(live));

        room.pause();
        assertEquals("leaving didn't give the strip back", bar, second.getPaddingTop());
        assertEquals(bar, later.getPaddingTop());
        assertEquals(View.VISIBLE, cover.getVisibility());
        room.stop().destroy();
    }

    /** The same for a strip found by the search over the window, with the bar hidden. */
    @Test public void aStripTakenWithTheBarHiddenIsLaidOutBeforeItsDrawn() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        room.start().resume().visible();

        int drawn = 0;
        for (int i = 0; i < 4; i++) {
            if (!frame(live)) continue;
            drawn++;
            assertEquals("a frame was drawn with the strip", 0, stream.getTop());
        }
        assertTrue(drawn > 0);

        putBack(stream, bar);
        layOutAlone((View) stream.getParent());
        assertTrue("a strip put back with no layout pass of the window was drawn", preDraw(live));
        assertEquals(0, topMargin(stream));
        assertTrue(frame(live) || frame(live));
        assertEquals(0, stream.getTop());
        room.pause().stop().destroy();
    }

    /**
     * The room in {@code page} as the frame draws it: its stream at the top, and the row holding
     * {@code shade} at the top of its controls, a bar below the clock.
     */
    private static void assertDrawnBelowTheClock(String when, int bar, FrameLayout page, View shade) {
        assertEquals(when + ": a frame was drawn with the strip", 0, page.getChildAt(0).getTop());
        assertEquals(when + ": a frame was drawn with the controls under the clock", 7 + bar,
                ((View) shade.getParent()).getTop());
    }

    /**
     * #137, seen on the S22 with 0.71.3: every room is a page of the room pager, and the pager
     * pads a page as it binds a room to it. The search over the window took the panel TikTok
     * shows while a room opens, the first view held down a bar's height, and left every page
     * alone, so some rooms kept a black band under the clock. The strip is looked for above each
     * room's stream now, on every page. TikTok's side panel (swipe left in a room) is held down a
     * bar's height too, and BynchPOP saw its top row under the clock, so it's left alone as well.
     */
    @Test public void everyRoomPageLosesItsStripAndTikToksPanelsAreLeftAlone() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout root = new FrameLayout(live);
        FrameLayout opening = new FrameLayout(live);
        opening.setPadding(0, bar, 0, 0);
        root.addView(opening, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 626));
        FrameLayout side = new FrameLayout(live);
        side.setPadding(0, bar, 0, 0);
        root.addView(side, new FrameLayout.LayoutParams(939, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        FrameLayout pager = new FrameLayout(live);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        live.setContentView(root);
        FrameLayout first = roomPage(live, pager, 0);
        FrameLayout second = roomPage(live, pager, bar);
        shade(live, controlsOf(second));
        room.start().resume().visible();
        layOut(live);
        layOut(live);

        assertEquals("the opening panel was taken for the strip", bar, opening.getPaddingTop());
        assertEquals("the side panel's top row went up under the clock", bar, side.getPaddingTop());
        assertEquals("a room without a strip was moved", 0, first.getPaddingTop());
        assertEquals("a room without a strip had its controls lowered", 7, controlsOf(first).getPaddingTop());
        assertEquals("the next room kept its strip", 0, second.getPaddingTop());
        assertEquals("the next room's controls went up under the clock", 7 + bar, controlsOf(second).getPaddingTop());
        assertTrue("the next room got no shade under the clock",
                controlsOf(second).getBackground() instanceof LiveStatusBar.BarShade);

        FrameLayout later = roomPage(live, pager, bar);
        layOut(live);
        assertEquals("a page the pager made later kept its strip", 0, later.getPaddingTop());
        assertEquals(7 + bar, controlsOf(later).getPaddingTop());

        // The pager binding another room to a page pads it again.
        first.setPadding(0, bar, 0, 0);
        layOut(live);
        assertEquals("a page padded as a room was bound kept its strip", 0, first.getPaddingTop());
        assertEquals(7 + bar, controlsOf(first).getPaddingTop());

        room.pause();
        assertEquals("leaving didn't give the rooms their strip back", bar, second.getPaddingTop());
        assertEquals(bar, later.getPaddingTop());
        assertEquals(bar, first.getPaddingTop());
        assertEquals("leaving didn't give the controls back", 7, controlsOf(second).getPaddingTop());
        assertEquals(null, controlsOf(second).getBackground());
        assertEquals(bar, opening.getPaddingTop());
        assertEquals(bar, side.getPaddingTop());
        room.stop().destroy();
    }

    /**
     * #137, BynchPOP on 0.72.0: in the LIVE feed opened from the LIVE button, the Discover panel
     * above the first room had its top row under the clock. It's a page of the room pager with no
     * stream, so nothing held it lower while the rooms went up. It's held a strip lower now, a page
     * already held down a bar of its own is left alone, and both go back as the room leaves.
     */
    @Test public void underTheBarTheLiveFeedsTopPanelStaysBelowTheClock() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout pager = new FrameLayout(live);
        live.setContentView(pager);
        FrameLayout panel = new FrameLayout(live);
        panel.addView(new View(live), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300));
        pager.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout heldPanel = new FrameLayout(live);
        heldPanel.setPadding(0, bar, 0, 0);
        pager.addView(heldPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout first = roomPage(live, pager, bar);
        FrameLayout second = roomPage(live, pager, bar);
        room.start().resume().visible();
        layOut(live);
        layOut(live);

        assertEquals("the first room kept its strip", 0, first.getPaddingTop());
        assertEquals(0, second.getPaddingTop());
        assertEquals("the panel's top row stayed under the clock", bar, panel.getPaddingTop());
        assertEquals("a panel held down a bar of its own went lower again", bar, heldPanel.getPaddingTop());
        layOut(live);
        assertEquals("a relayout lowered the panel twice", bar, panel.getPaddingTop());

        panel.setPadding(0, 0, 0, 0);
        layOut(live);
        assertEquals("the panel stayed up after TikTok laid it out again", bar, panel.getPaddingTop());

        room.pause();
        assertEquals("leaving didn't give the panel back", 0, panel.getPaddingTop());
        assertEquals(bar, heldPanel.getPaddingTop());
        assertEquals(bar, first.getPaddingTop());
        room.resume();
        layOut(live);
        assertEquals("coming back left the panel under the clock", bar, panel.getPaddingTop());
        room.pause().stop().destroy();
    }

    /**
     * #137: a page held lower that the pager then binds a room to gets its own padding back and is
     * let go, so the room's strip is taken as usual. With the bar hidden there's no clock to keep
     * clear of, so a page without a stream is left where TikTok put it.
     */
    @Test public void aPanelPageThatGetsARoomIsLetGoAndTheHiddenBarLeavesPanelsAlone() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout pager = new FrameLayout(live);
        live.setContentView(pager);
        FrameLayout loading = new FrameLayout(live);
        loading.setPadding(0, 7, 0, 0);
        pager.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        roomPage(live, pager, bar);
        roomPage(live, pager, bar);
        room.start().resume().visible();
        layOut(live);
        layOut(live);
        assertEquals("the page without a stream wasn't held lower", 7 + bar, loading.getPaddingTop());

        page(live, loading);
        layOut(live);
        assertEquals("a page bound to a room kept the extra padding", 7, loading.getPaddingTop());
        room.pause();
        assertEquals("leaving changed a page that was let go", 7, loading.getPaddingTop());
        room.stop().destroy();

        Settings.LIVE_UNDER_STATUS_BAR.save(false);
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        ActivityController<LivePlayActivity> hidden = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity hiddenLive = hidden.get();
        FrameLayout hiddenPager = new FrameLayout(hiddenLive);
        hiddenLive.setContentView(hiddenPager);
        FrameLayout panel = new FrameLayout(hiddenLive);
        hiddenPager.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout first = roomPage(hiddenLive, hiddenPager, bar);
        roomPage(hiddenLive, hiddenPager, bar);
        hidden.start().resume().visible();
        layOut(hiddenLive);
        layOut(hiddenLive);
        assertEquals("the room kept its strip with the bar hidden", 0, first.getPaddingTop());
        assertEquals("a panel was held lower with the bar hidden", 0, panel.getPaddingTop());
        hidden.pause().stop().destroy();
    }

    /** A room page as 47.1.4 has it on the S22: held down by {@code strip}, the stream and controls inside. */
    private static FrameLayout roomPage(Activity live, FrameLayout pager, int strip) {
        FrameLayout page = new FrameLayout(live);
        page.setPadding(0, strip, 0, 0);
        pager.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page(live, page);
        return page;
    }

    private static FrameLayout controlsOf(FrameLayout roomPage) {
        return (FrameLayout) ((ViewGroup) roomPage.getChildAt(0)).getChildAt(1);
    }

    /** TikTok's top shade as 47.1.4 has it: a gradient across the top of a row in the controls. */
    private static View shade(Activity live, FrameLayout controls) {
        FrameLayout row = new FrameLayout(live);
        controls.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150));
        View shade = new View(live);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x66000000, Color.TRANSPARENT});
        gradient.setAlpha(128);
        shade.setBackground(gradient);
        row.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
        return shade;
    }

    /** With the bar hidden there's no clock to keep clear of, so the controls go up with the room. */
    @Test public void withTheBarHiddenTheControlsGoUpWithTheRoom() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout controls = page(live, stream);
        room.start().resume().visible();
        layOut(live);

        assertEquals("the strip stayed", 0, topMargin(stream));
        assertEquals("the controls were held down", 7, controls.getPaddingTop());
        room.pause().stop().destroy();
    }

    /** A room page as 47.1.4 has it: the stream's view, and beside it the controls, padded 7. */
    private static FrameLayout page(Activity live, FrameLayout pager) {
        FrameLayout page = new FrameLayout(live);
        FrameLayout video = new FrameLayout(live);
        video.setId(LiveStatusBar.videoContainerForTests);
        page.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout controls = new FrameLayout(live);
        controls.setPadding(0, 7, 0, 0);
        page.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pager.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return controls;
    }

    private static View cover(Activity live, FrameLayout parent, int bar) {
        View cover = new View(live);
        cover.setBackgroundColor(Color.BLACK);
        parent.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar));
        return cover;
    }

    /**
     * #137: every panel TikTok opened over the room put its strip back once, and the old limit
     * counted those for the whole visit, so the seventh panel left the strip there for good.
     */
    @Test public void aStripPutBackNowAndThenIsTakenEveryTime() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        room.start().resume().visible();
        layOut(live);
        assertEquals("the strip stayed", 0, topMargin(stream));

        for (int panel = 1; panel <= 10; panel++) {
            putBack(stream, bar);
            layOut(live);
            assertEquals("panel " + panel + " left the strip", 0, topMargin(stream));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        }
        room.pause().stop().destroy();
    }

    /** TikTok putting the strip back on every frame is a fight the room is left to win. */
    @Test public void aStripPutBackOnEveryLayoutIsLeftAfterAFewTries() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        room.start().resume().visible();
        layOut(live);

        for (int frame = 0; frame < 12; frame++) {
            putBack(stream, bar);
            layOut(live);
        }
        assertEquals("the fight went on", bar, topMargin(stream));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        putBack(stream, bar);
        layOut(live);
        assertEquals("a lost fight was taken up again", bar, topMargin(stream));

        // A new visit starts over.
        room.pause();
        room.resume();
        layOut(live);
        assertEquals("coming back kept the strip", 0, topMargin(stream));
        room.pause().stop().destroy();
    }

    /** #137: the other way out of the black strip keeps the clock and icons over the stream. */
    @Test public void underTheBarTheStripGoesAndTheBarStays() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        live.getWindow().setStatusBarColor(Color.BLACK);
        room.start().resume().visible();
        layOut(live);

        assertEquals("the strip stayed", 0, topMargin(stream));
        assertEquals("the bar wasn't made see through", Color.TRANSPARENT, live.getWindow().getStatusBarColor());
        assertEquals("the cutout mode was changed with the bar showing",
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);

        room.pause();
        assertEquals("leaving didn't give the strip back", bar, topMargin(stream));
        assertEquals("leaving didn't give the bar's color back", Color.BLACK, live.getWindow().getStatusBarColor());
        room.stop().destroy();
    }

    @Test public void hidingTheBarWinsOverShowingTheRoomUnderIt() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).setup();
        LivePlayActivity live = room.get();
        assertEquals("the bar wasn't hidden", WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);
        room.pause().stop().destroy();
    }

    /**
     * #137: a LIVE opened from the preview under a creator's card in search is a page of TikTok's
     * detail pager, held a bar lower by the page's own padding (read off the S22 on 47.1.4), and
     * the switches did nothing there. It's handled like a room while it's on screen now, and
     * swiping on to a video gives everything back.
     */
    @Test public void aLiveOpenedFromSearchIsUnderTheBarWhileItsPageIsOnScreen() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<DetailActivity> opened = Robolectric.buildActivity(DetailActivity.class).create();
        DetailActivity detail = opened.get();
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout pager = new FrameLayout(detail);
        FrameLayout livePage = detailPage(detail, pager, bar);
        FrameLayout controls = page(detail, livePage);
        detail.setContentView(pager);
        detail.getWindow().setStatusBarColor(Color.BLACK);
        opened.start().resume().visible();
        layOut(detail);

        assertEquals("the page's strip stayed", 0, livePage.getPaddingTop());
        assertEquals("the controls went up under the clock", 7 + bar, controls.getPaddingTop());
        assertEquals("the bar wasn't made see through", Color.TRANSPARENT, detail.getWindow().getStatusBarColor());

        livePage.setTranslationY(-2316);
        layOut(detail);
        assertEquals("swiping on didn't give the strip back", bar, livePage.getPaddingTop());
        assertEquals(7, controls.getPaddingTop());
        assertEquals("swiping on didn't give the bar's color back", Color.BLACK, detail.getWindow().getStatusBarColor());

        livePage.setTranslationY(0);
        layOut(detail);
        assertEquals("swiping back left the strip", 0, livePage.getPaddingTop());

        opened.pause();
        assertEquals("leaving didn't give the strip back", bar, livePage.getPaddingTop());
        opened.stop().destroy();
    }

    /** The detail pager is mostly videos: the bar is hidden only while a LIVE page is in view. */
    @Test public void aDetailPageHidesTheBarOnlyWhileALiveIsOnScreen() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<DetailActivity> opened = Robolectric.buildActivity(DetailActivity.class).create();
        DetailActivity detail = opened.get();
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout pager = new FrameLayout(detail);
        FrameLayout video = detailPage(detail, pager, bar);
        FrameLayout livePage = detailPage(detail, pager, bar);
        page(detail, livePage);
        livePage.setTranslationY(2316);
        detail.setContentView(pager);
        opened.start().resume().visible();
        layOut(detail);

        assertFalse("a video page hid the bar", LiveStatusBar.isHidden(detail));
        assertEquals("a video page lost its padding", bar, video.getPaddingTop());
        assertEquals("the LIVE below the screen lost its strip", bar, livePage.getPaddingTop());

        video.setTranslationY(-2316);
        livePage.setTranslationY(0);
        layOut(detail);
        assertTrue("the LIVE in view kept its bar", LiveStatusBar.isHidden(detail));
        assertEquals("the LIVE in view kept its strip", 0, livePage.getPaddingTop());
        assertEquals(bar, video.getPaddingTop());

        opened.pause();
        assertFalse("leaving kept the bar hidden", LiveStatusBar.isHidden(detail));
        assertEquals(bar, livePage.getPaddingTop());
        opened.stop().destroy();
    }

    /** A page of the detail pager as 47.1.4 has it for a LIVE: held a bar lower by its own padding. */
    private static FrameLayout detailPage(Activity detail, FrameLayout pager, int bar) {
        FrameLayout page = new FrameLayout(detail);
        page.setPadding(0, bar, 0, 0);
        pager.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return page;
    }

    private static FrameLayout heldStream(Activity live, int bar) {
        FrameLayout container = new FrameLayout(live);
        FrameLayout stream = new FrameLayout(live);
        FrameLayout.LayoutParams held = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        held.topMargin = bar;
        container.addView(stream, held);
        live.setContentView(container);
        return stream;
    }

    private static void putBack(View stream, int bar) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) stream.getLayoutParams();
        params.topMargin = bar;
        stream.setLayoutParams(params);
    }

    private static int platformBar() {
        Resources system = Resources.getSystem();
        int bar = system.getDimensionPixelSize(system.getIdentifier("status_bar_height", "dimen", "android"));
        assertTrue("no platform status bar height to test with", bar > 0);
        return bar;
    }

    private static int topMargin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).topMargin;
    }

    private static void layOut(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2316, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 2316);
        decor.getViewTreeObserver().dispatchOnGlobalLayout();
    }

    /**
     * One frame the way the window runs it: a layout pass, its layout listeners, then the pre-draw
     * listeners, which can hold the frame back. True when the frame is drawn.
     */
    private static boolean frame(Activity activity) {
        layOut(activity);
        return !preDraw(activity);
    }

    /** The pre-draw listeners alone, as on a frame with no layout pass; true when one held it back. */
    private static boolean preDraw(Activity activity) {
        return activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
    }

    /**
     * Lays out one view in its place, as a pager does with a page it binds or adds as it scrolls,
     * with no layout pass of the window. A view not laid out yet takes its parent's size.
     */
    private static void layOutAlone(View view) {
        View parent = (View) view.getParent();
        int width = view.getWidth() > 0 ? view.getWidth() : parent.getWidth();
        int height = view.getHeight() > 0 ? view.getHeight() : parent.getHeight();
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(view.getLeft(), view.getTop(), view.getLeft() + width, view.getTop() + height);
    }

    private static void followFromTheMainActivity() {
        ActivityController<Activity> main = Robolectric.buildActivity(Activity.class).setup();
        LiveStatusBar.follow(main.get());
    }
}

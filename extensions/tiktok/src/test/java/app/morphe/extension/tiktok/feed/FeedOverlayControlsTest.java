package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class FeedOverlayControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    boolean originalFullscreen, originalLocation;

    @Before public void prepare() {
        originalFullscreen=Settings.HIDE_FULLSCREEN_BUTTON.get();
        originalLocation=Settings.HIDE_LOCATION_LABELS.get();
        Settings.HIDE_FULLSCREEN_BUTTON.save(true);
        Settings.HIDE_LOCATION_LABELS.save(true);
        FeedOverlayControls.resolveForTests("view_rootview",1001);
        FeedOverlayControls.resolveForTests("feed_multi_tag_layout",1002);
    }
    @After public void restore() {
        Settings.HIDE_FULLSCREEN_BUTTON.save(originalFullscreen);
        Settings.HIDE_LOCATION_LABELS.save(originalLocation);
    }

    /** Issue #21: TikTok's regional Report button gate is closed only by its own switch. */
    @Test public void theReportButtonGateIsLeftAloneUntilItsSwitchIsOn() {
        try {
            assertEquals(Boolean.FALSE, Settings.HIDE_FEED_REPORT_BUTTON.defaultValue);
            assertFalse("the Full screen and location switches closed the Report gate",
                    FeedOverlayControls.shouldHideReportButton());
            Settings.HIDE_FEED_REPORT_BUTTON.save(true);
            assertTrue(FeedOverlayControls.shouldHideReportButton());
        } finally {
            Settings.HIDE_FEED_REPORT_BUTTON.resetToDefault();
        }
    }

    /**
     * The Footnotes banner's gate is closed only by its own switch, is left to TikTok while
     * Hushfeed is paused, and says it ran so the build report can tell a bound hook from a
     * missing one.
     */
    @Test public void theFootnotesGateIsClosedOnlyByItsOwnSwitchAndHonoursPause() {
        try {
            assertEquals(Boolean.FALSE, Settings.HIDE_FOOTNOTES.defaultValue);
            Settings.HIDE_FEED_REPORT_BUTTON.save(true);
            Settings.HIDE_FEED_SURVEYS.save(true);
            assertFalse("another overlay switch closed the Footnotes gate",
                    FeedOverlayControls.shouldHideFootnotes());
            Settings.HIDE_FEED_REPORT_BUTTON.save(false);
            Settings.HIDE_FEED_SURVEYS.save(false);
            Settings.HIDE_FOOTNOTES.save(true);
            assertTrue(FeedOverlayControls.shouldHideFootnotes());
            assertFalse("the Footnotes switch closed the Report gate",
                    FeedOverlayControls.shouldHideReportButton());
            PausedProcess.set(true);
            assertFalse("a paused Metra still closed the gate", FeedOverlayControls.shouldHideFootnotes());
            PausedProcess.set(false);
            assertTrue(String.join(" ", HookStatus.report()).contains("footnote banner"));
        } finally {
            PausedProcess.set(false);
            HookStatus.clear();
            Settings.HIDE_FOOTNOTES.resetToDefault();
            Settings.HIDE_FEED_REPORT_BUTTON.resetToDefault();
            Settings.HIDE_FEED_SURVEYS.resetToDefault();
        }
    }

    @Test public void fullscreenOnlyHidesItsDedicatedRootAndKeepsNativeClickOnRestore() {
        try(Fixture f=new Fixture()) {
            int[] clicks={0};
            f.fullscreen.setOnClickListener(v -> clicks[0]++);
            FeedOverlayControls.bindFullscreen(f.fullscreen); idle();
            assertEquals(View.GONE,f.fullscreen.getVisibility());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,f.fullscreen.getImportantForAccessibility());
            assertEquals(View.VISIBLE,f.card.getVisibility());
            assertEquals(View.VISIBLE,f.other.getVisibility());
            assertEquals(500,f.cell.getLayoutParams().height);
            Settings.HIDE_FULLSCREEN_BUTTON.save(false); f.layout();
            assertEquals(View.VISIBLE,f.fullscreen.getVisibility());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,f.fullscreen.getImportantForAccessibility());
            f.fullscreen.performClick(); assertEquals(1,clicks[0]);
        }
    }

    @Test public void locationOnlyHidesTheCardAndItsExclusiveWrapperWithoutLeavingHeight() {
        try(Fixture f=new Fixture()) {
            FeedOverlayControls.bindLocation(f.card); idle();
            assertEquals(View.GONE,f.card.getVisibility());
            assertEquals(View.GONE,f.tags.getVisibility());
            assertEquals(View.VISIBLE,f.fullscreen.getVisibility());
            assertEquals(View.VISIBLE,f.other.getVisibility());
            Settings.HIDE_LOCATION_LABELS.save(false); f.layout();
            assertEquals(View.VISIBLE,f.card.getVisibility());
            assertEquals(View.VISIBLE,f.tags.getVisibility());
        }
    }

    @Test public void mixedTagsAreNeverHiddenAndANewSiblingRestoresTheSharedWrapper() {
        try(Fixture f=new Fixture()) {
            FeedOverlayControls.bindLocation(f.card); idle();
            assertEquals(View.GONE,f.tags.getVisibility());
            View otherTag=new View(f.activity);
            f.tags.addView(otherTag);
            f.layout();
            assertEquals(View.VISIBLE,f.tags.getVisibility());
            assertEquals(View.VISIBLE,otherTag.getVisibility());
            assertEquals(View.GONE,f.card.getVisibility());
        }
        try(Fixture f=new Fixture()) {
            f.tags.addView(new View(f.activity));
            FeedOverlayControls.bindLocation(f.card); idle();
            assertEquals(View.VISIBLE,f.tags.getVisibility());
            assertEquals(View.GONE,f.card.getVisibility());
        }
    }

    @Test public void outsideFeedAndUnknownLayoutsFailOpen() {
        try(Fixture f=new Fixture()) {
            f.cell.setId(77);
            FeedOverlayControls.bindLocation(f.card);
            FeedOverlayControls.bindFullscreen(f.fullscreen); idle();
            assertEquals(View.VISIBLE,f.card.getVisibility());
            assertEquals(View.VISIBLE,f.fullscreen.getVisibility());
            assertEquals(View.VISIBLE,f.tags.getVisibility());
        }
        try(Fixture f=new Fixture()) {
            FeedOverlayControls.resolveForTests("view_rootview",0);
            FeedOverlayControls.bindFullscreen(f.fullscreen); idle();
            assertEquals(View.VISIBLE,f.fullscreen.getVisibility());
        }
    }

    @Test public void nativeReappearanceIsHiddenAgainAndDetachRestoresForReuse() {
        try(Fixture f=new Fixture()) {
            FeedOverlayControls.bindLocation(f.card); idle();
            f.card.setVisibility(View.VISIBLE); f.layout();
            assertEquals(View.GONE,f.card.getVisibility());
            f.tags.removeView(f.card);
            assertEquals(View.VISIBLE,f.card.getVisibility());
            assertEquals(View.VISIBLE,f.tags.getVisibility());
            f.tags.addView(f.card); idle();
            assertEquals(View.GONE,f.card.getVisibility());
            f.tags.removeView(f.card);
            FrameLayout outside=new FrameLayout(f.activity);
            f.activity.setContentView(outside); outside.addView(f.card); idle();
            assertEquals(View.VISIBLE,f.card.getVisibility());
        }
    }

    @Test public void offIsANoopAndOriginalInvisibleOrGoneStatesArePreserved() {
        Settings.HIDE_FULLSCREEN_BUTTON.save(false);
        Settings.HIDE_LOCATION_LABELS.save(false);
        try(Fixture f=new Fixture()) {
            FeedOverlayControls.bindFullscreen(f.fullscreen);
            FeedOverlayControls.bindLocation(f.card); idle();
            assertEquals(View.VISIBLE,f.fullscreen.getVisibility());
            assertEquals(View.VISIBLE,f.card.getVisibility());
            assertEquals(Boolean.FALSE,Settings.HIDE_FULLSCREEN_BUTTON.defaultValue);
            assertEquals(Boolean.FALSE,Settings.HIDE_LOCATION_LABELS.defaultValue);
        }
        Settings.HIDE_FULLSCREEN_BUTTON.save(true);
        for(int visibility:new int[]{View.INVISIBLE,View.GONE}) try(Fixture f=new Fixture()) {
            f.fullscreen.setVisibility(visibility);
            FeedOverlayControls.bindFullscreen(f.fullscreen); idle();
            Settings.HIDE_FULLSCREEN_BUTTON.save(false); f.layout();
            assertEquals(visibility,f.fullscreen.getVisibility());
            Settings.HIDE_FULLSCREEN_BUTTON.save(true);
        }
    }

    private static void idle(){shadowOf(Looper.getMainLooper()).idle();}
    private static final class Fixture implements AutoCloseable {
        final ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup().visible();
        final Activity activity=owner.get();
        final FrameLayout cell=new FrameLayout(activity), tags=new FrameLayout(activity), card=new FrameLayout(activity);
        final View fullscreen=new View(activity),other=new View(activity);
        Fixture(){
            cell.setId(1001);tags.setId(1002);
            FrameLayout content=new FrameLayout(activity);
            content.addView(cell,new FrameLayout.LayoutParams(400,500));
            cell.addView(fullscreen,new FrameLayout.LayoutParams(150,40));
            cell.addView(tags,new FrameLayout.LayoutParams(300,60));
            cell.addView(other,new FrameLayout.LayoutParams(40,40));
            tags.addView(card,new FrameLayout.LayoutParams(300,60));
            activity.setContentView(content); idle();
        }
        void layout(){cell.getViewTreeObserver().dispatchOnGlobalLayout();idle();}
        @Override public void close(){owner.close();}
    }
}

package app.morphe.extension.tiktok.foldable;

import static org.junit.Assert.*;
import android.content.res.Configuration;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 30}, qualifiers = "w700dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FoldableSplitViewTest {
    /**
     * The newest API above, and the only run that publishes a picture.
     *
     * <p>Both runs used to write foldable-settings.png, so which one ended up in assets depended
     * on which finished last. Guarded at the call site rather than by pinning the method to one
     * API, which would give up the API 28 run of everything else the case asserts.
     */
    private static final int PUBLISHED_CAPTURE_SDK = 30;

    public static class TestActivity extends android.preference.PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
        boolean multiWindow, pictureInPicture;
        @Override public boolean isInMultiWindowMode() { return multiWindow; }
        @Override public boolean isInPictureInPictureMode() { return pictureInPicture; }
    }
    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Utils.setActivity(null);
        Settings.FOLDABLE_SPLIT_VIEW.save(false);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(600);
    }
    @After public void tearDown() {
        SettingsStatus.foldableSplitViewEnabled = false;
        FoldableSplitView.forgetForTests();
        Utils.setActivity(null);
    }

    private static Configuration width(TestActivity activity, int dp) {
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.screenWidthDp = dp;
        return configuration;
    }

    /** A report says whether TikTok asked at all, which a phone that never splits can't show. */
    @Test public void everyAnswerTikTokGetsGoesIntoHookStatus() {
        HookStatus.clear();
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(320);
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            assertTrue(FoldableSplitView.shouldForceContainer());
            assertTrue(FoldableSplitView.shouldForce(activity, width(activity, 700)));
            assertTrue(String.join(" ", HookStatus.report()).contains(FoldableSplitView.FAMILY));
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * TikTok's server sends 1, and the feed then registers its split container only on a
     * tablet, so a wide phone window never split. With the switch off TikTok's value stands.
     */
    @Test public void theFeedRegistersItsContainerWhateverTheServerSaysOnlyWithTheSwitchOn() {
        HookStatus.clear();
        try {
            assertEquals(1L, FoldableSplitView.splitContainerInit(1L));
            assertEquals(-1L, FoldableSplitView.splitContainerInit(-1L));
            Settings.FOLDABLE_SPLIT_VIEW.save(true);
            assertEquals(-1L, FoldableSplitView.splitContainerInit(1L));
            assertEquals(-1L, FoldableSplitView.splitContainerInit(0L));
            assertTrue(String.join(" ", HookStatus.report()).contains(FoldableSplitView.FAMILY));
        } finally {
            HookStatus.clear();
        }
    }

    /** Opened folded, then unfolded with TikTok still open: issue #26. */
    @Test public void aWindowThatCrossesTheThresholdBuildsTheFeedAgainOncePerCrossing() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            // TikTok builds the comment containers once, for the 700 dp window it starts in.
            assertFalse(FoldableSplitView.shouldForceContainer());

            FoldableSplitView.onConfigurationChanged(activity, width(activity, 760));
            assertEquals("a change on the same side built the feed again", 0, rebuilt.size());

            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
            // The same crossing reported again, as an unfold passes through several sizes.
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 950));
            assertEquals("one crossing built the feed twice", 1, rebuilt.size());
        }
    }

    /**
     * The rebuild let Hide seen videos take the video on screen out of the lists TikTok reads
     * again, so the feed came back on another video (issue #26, second report).
     */
    @Test public void theRebuildKeepsTheVideoThatWasPlaying() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
        Settings.HIDE_SEEN_VIDEOS.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            assertFalse(FoldableSplitView.shouldForceContainer());
            SeenVideoHistory.onPlayProgressChange("42", 5000, 10000);
            assertTrue("watched long enough to be seen", SeenVideoHistory.shouldHide("42"));

            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals(1, rebuilt.size());
            assertFalse("the video on screen was filtered out of the rebuilt feed", SeenVideoHistory.shouldHide("42"));
        } finally {
            SeenVideoHistory.clear();
        }
    }

    @Test public void theContainersWereBuiltOnTheFirstAnswerWhateverTikTokAsksLater() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            assertFalse(FoldableSplitView.shouldForceContainer());
            // TikTok asks again from its layout and playback paths once the window is wide; the
            // containers it built at the start are still the folded ones.
            Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(600);
            assertTrue(FoldableSplitView.shouldForceContainer());
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }

    @Test public void foldingBackIsACrossingToo() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            // Started unfolded, so the split containers were built.
            assertTrue(FoldableSplitView.shouldForceContainer());
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 380));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }

    @Test public void aSwitchTurnedOnMidSessionAppliesAtTheNextStartNotTheNextResize() {
        Settings.FOLDABLE_SPLIT_VIEW.save(false);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            // TikTok built its containers with the switch off, which is no split decision.
            assertFalse(FoldableSplitView.shouldForceContainer());

            Settings.FOLDABLE_SPLIT_VIEW.save(true);
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals("the feed was built again for a switch that applies at the next start", 0, rebuilt.size());

            // The control: asked again with the switch on, the 700 dp window is recorded as the
            // narrow side of 800 dp, and the next crossing counts.
            assertFalse(FoldableSplitView.shouldForceContainer());
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }

    @Test public void aThresholdEditedMidSessionAppliesAtTheNextStartNotTheNextResize() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            assertFalse(FoldableSplitView.shouldForceContainer());

            // The containers were built for an 800 dp threshold; 760 dp crosses only the new one.
            Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(600);
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 760));
            assertEquals("the feed was built again for a threshold that applies at the next start", 0, rebuilt.size());

            // The control: a crossing of the recorded threshold still counts.
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }

    @Test public void nothingIsBuiltAgainWithTheSwitchOffBesideOtherAppsOrForAnActivityThatNeverAsked() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup();
             var other = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            // Built wide, so every change below lands on the other side; only what stands in
            // the way keeps each from building the feed again.
            assertTrue(FoldableSplitView.shouldForceContainer());

            // The switch applies at the next start, as its row says, not at the next resize.
            Settings.FOLDABLE_SPLIT_VIEW.save(false);
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 380));
            Settings.FOLDABLE_SPLIT_VIEW.save(true);

            // A window beside another app is TikTok's own business.
            activity.multiWindow = true;
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 380));
            activity.multiWindow = false;
            activity.pictureInPicture = true;
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 380));
            activity.pictureInPicture = false;

            // An activity TikTok never asked about built no comment containers.
            FoldableSplitView.onConfigurationChanged(other.get(), width(other.get(), 380));
            FoldableSplitView.onConfigurationChanged(null, width(activity, 380));
            assertEquals(0, rebuilt.size());

            // The control: the same change on the activity that asked, with nothing in the way.
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 380));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }
    @Test public void explicitWidthControlsBothSidesOfThresholdAndRespectsWindowModes() {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.screenWidthDp = 700;
            assertFalse(FoldableSplitView.shouldForce(activity, configuration));
            Settings.FOLDABLE_SPLIT_VIEW.save(true);
            assertTrue(FoldableSplitView.shouldForce(activity, configuration));
            configuration.screenWidthDp = 599;
            assertFalse(FoldableSplitView.shouldForce(activity, configuration));
            configuration.screenWidthDp = 600;
            assertTrue(FoldableSplitView.shouldForce(activity, configuration));
            activity.multiWindow = true;
            assertFalse(FoldableSplitView.shouldForce(activity, configuration));
            activity.multiWindow = false;
            activity.pictureInPicture = true;
            assertFalse(FoldableSplitView.shouldForce(activity, configuration));
            activity.pictureInPicture = false;
            activity.finish();
            assertFalse(FoldableSplitView.shouldForce(activity, configuration));
        }
    }
    @Test public void containerUsesCurrentActivityAndNeverRecreatesIt() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        assertFalse(FoldableSplitView.shouldForceContainer());
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = owner.get();
            Utils.setActivity(activity);
            assertTrue(FoldableSplitView.shouldForceContainer());
            Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
            assertFalse(FoldableSplitView.shouldForceContainer());
            Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(600);
            activity.multiWindow = true;
            assertFalse(FoldableSplitView.shouldForceContainer());
            assertFalse(activity.isChangingConfigurations());
        }
        Utils.setActivity(null);
    }
    @Test public void controlsAreReachableAndWidthInputIsBounded() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = owner.get();
            Utils.setContext(activity);
            Utils.setIsDarkModeEnabled(true);
            SettingsStatus.foldableSplitViewEnabled = true;
            var screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new ExtensionPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("foldable_split_view"));
            var width = (app.morphe.extension.tiktok.settings.preference.NumberInputPreference) screen.findPreference("foldable_split_view_min_width_dp");
            width.setValue("-1");
            assertEquals("320", width.getValue());
            width.setValue("600");
            activity.setPreferenceScreen(screen);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (android.os.Build.VERSION.SDK_INT == PUBLISHED_CAPTURE_SDK) {
                app.morphe.extension.tiktok.UiCapture.save(
                        activity.getWindow().getDecorView(), "foldable-settings.png");
            }
        }
    }
}

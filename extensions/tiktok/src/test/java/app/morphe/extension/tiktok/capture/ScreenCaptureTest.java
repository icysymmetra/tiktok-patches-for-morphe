package app.morphe.extension.tiktok.capture;

import static org.junit.Assert.*;
import android.app.Activity;
import android.view.WindowManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenCaptureTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @After public void tearDown() {
        SettingsStatus.screenCaptureEnabled = false;
        Settings.ALLOW_SCREEN_CAPTURE.resetToDefault();
    }
    private static final int SECURE = WindowManager.LayoutParams.FLAG_SECURE;
    private static final int KEEP = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
    @Test public void disabledPassesFlagsThroughAndEnabledRemovesOnlySecure() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            var activity = owner.get(); Utils.setContext(activity);
            var window = activity.getWindow();
            Settings.ALLOW_SCREEN_CAPTURE.save(false);
            ScreenCapture.addFlags(window, SECURE | KEEP);
            assertEquals(SECURE | KEEP, window.getAttributes().flags & (SECURE | KEEP));
            Settings.ALLOW_SCREEN_CAPTURE.save(true);
            ScreenCapture.addFlags(window, SECURE);
            assertEquals(KEEP, window.getAttributes().flags & (SECURE | KEEP));
            window.addFlags(SECURE);
            ScreenCapture.setFlags(window, KEEP, KEEP);
            assertEquals(KEEP, window.getAttributes().flags & (SECURE | KEEP));
            Settings.ALLOW_SCREEN_CAPTURE.save(false);
            ScreenCapture.setFlags(window, SECURE, SECURE);
            assertEquals(SECURE | KEEP, window.getAttributes().flags & (SECURE | KEEP));
        }
    }
    @Test public void layoutAttributesAreCopiedAndOtherAttributesSurvive() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            var activity = owner.get(); Utils.setContext(activity);
            Settings.ALLOW_SCREEN_CAPTURE.save(true);
            var attributes = new WindowManager.LayoutParams();
            attributes.flags = SECURE | KEEP;
            attributes.screenBrightness = 0.65f;
            ScreenCapture.setAttributes(activity.getWindow(), attributes);
            assertEquals(SECURE | KEEP, attributes.flags);
            assertEquals(KEEP, activity.getWindow().getAttributes().flags & (SECURE | KEEP));
            assertEquals(0.65f, activity.getWindow().getAttributes().screenBrightness, 0f);
        }
    }
    @Test public void layoutFlagWritesLoseOnlySecureWhileOn() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Utils.setContext(owner.get());
            var popup = new WindowManager.LayoutParams();
            Settings.ALLOW_SCREEN_CAPTURE.save(false);
            ScreenCapture.setLayoutFlags(popup, SECURE | KEEP);
            assertEquals(SECURE | KEEP, popup.flags);
            Settings.ALLOW_SCREEN_CAPTURE.save(true);
            ScreenCapture.setLayoutFlags(popup, SECURE | KEEP);
            assertEquals(KEEP, popup.flags);
            ScreenCapture.setLayoutFlags(popup, 0);
            assertEquals(0, popup.flags);
        }
    }
    @Test public void circleGateFollowsSetting() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            var activity = owner.get(); Utils.setContext(activity);
            Settings.ALLOW_SCREEN_CAPTURE.save(false);
            Object original = Integer.valueOf(2);
            assertSame(original, ScreenCapture.circleBlock(original));
            Settings.ALLOW_SCREEN_CAPTURE.save(true);
            assertEquals(Integer.valueOf(0), ScreenCapture.circleBlock(original));
        }
    }
    @Test public void standaloneToggleShowsRestartRequirement() throws Exception {
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.interaction.GestureActionsTest.TestActivity.class).setup()) {
            var activity = owner.get(); Utils.setContext(activity); Utils.setIsDarkModeEnabled(true);
            SettingsStatus.screenCaptureEnabled = true;
            var screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new ExtensionPreferenceCategory(activity, screen);
            var toggle = screen.findPreference("allow_screen_capture");
            assertNotNull(toggle);
            assertTrue(toggle.getSummary().toString().contains("Restart"));
            activity.setPreferenceScreen(screen);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            app.morphe.extension.tiktok.UiCapture.save(activity.getWindow().getDecorView(), "capture-settings.png");
        }
    }
}

package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** What the window is told to ask the screen for. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RefreshRateTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void onlyARequestSlowerThanTheScreenIsDeclined() {
        try {
            RefreshRate.setHighestSupported(120f);

            // Off: whatever TikTok worked out from the video goes through untouched.
            Settings.UNCAP_REFRESH_RATE.save(false);
            for (float rate : new float[]{24f, 25f, 30f, 60f, 120f}) {
                assertEquals(rate, RefreshRate.preferredRefreshRate(rate), 0.001f);
            }

            // On: a frame rate below what the screen can do is the cap this is here for, and
            // zero is what Android reads as the app having no preference.
            Settings.UNCAP_REFRESH_RATE.save(true);
            for (float rate : new float[]{24f, 25f, 30f, 60f, 90f, 118.9f}) {
                assertEquals(0f, RefreshRate.preferredRefreshRate(rate), 0.001f);
            }
            // A request for the screen's own rate, or more, is asking for something else and
            // is not ours to answer. Six writes were taken over and not all of them cap.
            assertEquals(120f, RefreshRate.preferredRefreshRate(120f), 0.001f);
            assertEquals(144f, RefreshRate.preferredRefreshRate(144f), 0.001f);
            assertEquals(119.5f, RefreshRate.preferredRefreshRate(119.5f), 0.001f);
            // Nothing meaningful asked for is nothing to change.
            assertEquals(0f, RefreshRate.preferredRefreshRate(0f), 0.001f);
            assertEquals(-1f, RefreshRate.preferredRefreshRate(-1f), 0.001f);

            // A 60 Hz phone: the same video is not a cap there.
            RefreshRate.setHighestSupported(60f);
            assertEquals(60f, RefreshRate.preferredRefreshRate(60f), 0.001f);
            assertEquals(0f, RefreshRate.preferredRefreshRate(30f), 0.001f);

            // A screen that will not say: every request is treated as the cap it usually is.
            RefreshRate.setHighestSupported(-1f);
            assertEquals(0f, RefreshRate.preferredRefreshRate(120f), 0.001f);
        } finally {
            Settings.UNCAP_REFRESH_RATE.save(false);
            RefreshRate.setHighestSupported(-1f);
        }
    }

    @Test public void theStoreIsMadeOnTheParametersThemselves() {
        try {
            RefreshRate.setHighestSupported(120f);
            android.view.WindowManager.LayoutParams params =
                    new android.view.WindowManager.LayoutParams();

            Settings.UNCAP_REFRESH_RATE.save(false);
            RefreshRate.apply(params, 30f);
            assertEquals(30f, params.preferredRefreshRate, 0.001f);

            Settings.UNCAP_REFRESH_RATE.save(true);
            RefreshRate.apply(params, 30f);
            assertEquals(0f, params.preferredRefreshRate, 0.001f);

            // Nothing to write to is not a crash.
            RefreshRate.apply(null, 30f);
        } finally {
            Settings.UNCAP_REFRESH_RATE.save(false);
            RefreshRate.setHighestSupported(-1f);
        }
    }

    @Test public void theSwitchIsReachable() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.refreshRateEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new ExtensionPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("uncap_refresh_rate"));
        } finally {
            SettingsStatus.refreshRateEnabled = false;
        }
    }
}

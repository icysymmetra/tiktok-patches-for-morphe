package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** When a video's risk model reads as absent, which takes the Check sources banner and the share warnings with it. */
@RunWith(RobolectricTestRunner.class)
public class UnverifiedNoticesTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.unverifiedNoticesEnabled = true;
    }

    @After public void tearDown() {
        setPaused(false);
        SettingsStatus.unverifiedNoticesEnabled = false;
        Settings.HIDE_UNVERIFIED_NOTICES.save(Settings.HIDE_UNVERIFIED_NOTICES.defaultValue);
        Settings.HIDE_SENSITIVE_WARNINGS.save(Settings.HIDE_SENSITIVE_WARNINGS.defaultValue);
    }

    @Test public void offByDefaultTheNoticesShow() {
        assertFalse(SensitiveWarnings.hideUnverifiedNotices());
    }

    @Test public void onTheRiskModelReadsAsAbsent() {
        Settings.HIDE_UNVERIFIED_NOTICES.save(true);
        assertTrue(SensitiveWarnings.hideUnverifiedNotices());
    }

    /** Its own switch: skipping the tap-through overlay leaves the banner where it is. */
    @Test public void skippingContentWarningsAloneKeepsTheNotices() {
        Settings.HIDE_SENSITIVE_WARNINGS.save(true);
        assertFalse(SensitiveWarnings.hideUnverifiedNotices());
    }

    @Test public void pausedTheNoticesShow() {
        Settings.HIDE_UNVERIFIED_NOTICES.save(true);
        setPaused(true);
        assertFalse(SensitiveWarnings.hideUnverifiedNotices());
    }

    @Test public void aSavedSwitchDoesNothingWithoutItsPatch() {
        Settings.HIDE_UNVERIFIED_NOTICES.save(true);
        SettingsStatus.unverifiedNoticesEnabled = false;
        assertFalse(SensitiveWarnings.hideUnverifiedNotices());
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }
}

package app.morphe.extension.tiktok.misc;

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

@RunWith(RobolectricTestRunner.class)
public class FirstLaunchSetupTest {
    /** Every step id TikTok 47.0.3, 47.1.3 and 47.1.4 build their setup from that must keep showing. */
    private static final String[] KEPT = {
            "ad_choice", "ad_subscription", "age_gate", "consent_box_page", "consent_box_page_hu",
            "deep_link", "free_trial", "login", "m2_one_tap_login", "privacy_for_teens",
            "private_account", "push_popup_background", "skippable_login", "slogan_page",
    };

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.firstLaunchSetupEnabled = true;
    }

    @After public void tearDown() {
        setPaused(false);
        SettingsStatus.firstLaunchSetupEnabled = false;
        Settings.SKIP_FIRST_LAUNCH_SETUP.save(Settings.SKIP_FIRST_LAUNCH_SETUP.defaultValue);
    }

    /** The setup runs before the switch can be reached, so picking the patch is enough. */
    @Test public void onByDefaultTheTastePagesArePassedOver() {
        for (String id : FirstLaunchSetup.SKIPPED) assertTrue(id, FirstLaunchSetup.skipStep(id));
    }

    @Test public void consentAgeAndSignInAlwaysShow() {
        for (String id : KEPT) assertFalse(id, FirstLaunchSetup.skipStep(id));
    }

    @Test public void aStepTheListDoesNotKnowShows() {
        assertFalse(FirstLaunchSetup.skipStep("a_step_from_a_later_build"));
        assertFalse(FirstLaunchSetup.skipStep("null"));
        assertFalse(FirstLaunchSetup.skipStep(null));
    }

    @Test public void offEveryStepShows() {
        Settings.SKIP_FIRST_LAUNCH_SETUP.save(false);
        assertFalse(FirstLaunchSetup.skipStep("interest_list"));
    }

    @Test public void pausedEveryStepShows() {
        setPaused(true);
        assertFalse(FirstLaunchSetup.skipStep("interest_list"));
    }

    @Test public void withoutThePatchEveryStepShows() {
        SettingsStatus.firstLaunchSetupEnabled = false;
        assertFalse(FirstLaunchSetup.skipStep("interest_list"));
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }
}

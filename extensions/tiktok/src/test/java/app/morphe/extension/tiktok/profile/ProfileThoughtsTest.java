package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Hide Thoughts on profiles (#122): off by default, hides only with its switch, honours Pause. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProfileThoughtsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() {
        PausedProcess.set(false);
        HookStatus.clear();
        Settings.HIDE_PROFILE_THOUGHTS.resetToDefault();
    }

    @Test public void theBubbleIsLeftAloneUntilTheSwitchIsOn() {
        assertFalse(Settings.HIDE_PROFILE_THOUGHTS.defaultValue);
        View bubble = new View(RuntimeEnvironment.getApplication());
        assertFalse(ProfileThoughts.hideBubble(bubble));
        assertFalse(ProfileThoughts.hideOnProfiles());
        assertEquals(View.VISIBLE, bubble.getVisibility());
    }

    @Test public void withTheSwitchOnTheBubbleIsGoneAndTheSpaceStaysClosed() {
        Settings.HIDE_PROFILE_THOUGHTS.save(true);
        View bubble = new View(RuntimeEnvironment.getApplication());
        assertTrue(ProfileThoughts.hideBubble(bubble));
        assertEquals(View.GONE, bubble.getVisibility());
        assertTrue(ProfileThoughts.hideOnProfiles());
        // No bubble view on this profile: the answer still empties the field, and nothing throws.
        assertTrue(ProfileThoughts.hideBubble(null));
        assertTrue(ProfileThoughts.hideBubble(new Object()));
        String report = String.join(" ", HookStatus.report());
        assertTrue(report, report.contains("profile thoughts bubble") && report.contains("profile thoughts space"));
    }

    @Test public void pausedTheBubbleIsTikToks() {
        Settings.HIDE_PROFILE_THOUGHTS.save(true);
        PausedProcess.set(true);
        View bubble = new View(RuntimeEnvironment.getApplication());
        assertFalse(ProfileThoughts.hideBubble(bubble));
        assertFalse(ProfileThoughts.hideOnProfiles());
        assertEquals(View.VISIBLE, bubble.getVisibility());
    }
}

package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Remove avatar rings: what the story status and the two live checks answer, and the rows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AvatarRingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        Settings.HIDE_STORY_RINGS.save(false);
        Settings.HIDE_LIVE_RING.save(false);
        SettingsStatus.avatarRingsEnabled = false;
    }

    @Test public void offLeavesTikTokItsOwnAnswers() {
        for (int status : new int[]{0, 1, 2, 3}) assertEquals(status, AvatarRings.storyStatus(status));
        assertTrue(AvatarRings.authorLive(true));
        assertFalse(AvatarRings.authorLive(false));
        assertTrue(AvatarRings.avatarLive(true));
        assertFalse(AvatarRings.avatarLive(false));
    }

    @Test public void eachSwitchOnlyTakesItsOwnRing() {
        Settings.HIDE_STORY_RINGS.save(true);
        assertEquals(0, AvatarRings.storyStatus(1));
        assertEquals(0, AvatarRings.storyStatus(2));
        assertTrue("story switch leaves LIVE alone", AvatarRings.authorLive(true));
        assertTrue(AvatarRings.avatarLive(true));

        Settings.HIDE_STORY_RINGS.save(false);
        Settings.HIDE_LIVE_RING.save(true);
        assertEquals("LIVE switch leaves stories alone", 1, AvatarRings.storyStatus(1));
        assertFalse(AvatarRings.authorLive(true));
        assertFalse(AvatarRings.avatarLive(true));
        assertFalse(AvatarRings.authorLive(false));
    }

    @Test public void theSwitchesAreOnTheFeedScreenPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.avatarRingsEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.HIDE_STORY_RINGS.key));
            assertNull(without.findPreference(Settings.HIDE_LIVE_RING.key));

            SettingsStatus.avatarRingsEnabled = true;
            assertTrue(InterfacePreferenceCategory.isAvailable());
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, with);
            assertNotNull(with.findPreference(Settings.HIDE_STORY_RINGS.key));
            assertNotNull(with.findPreference(Settings.HIDE_LIVE_RING.key));
        }
    }
}

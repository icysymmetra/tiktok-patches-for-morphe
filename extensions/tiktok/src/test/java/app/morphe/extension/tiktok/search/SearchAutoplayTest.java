package app.morphe.extension.tiktok.search;

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
public class SearchAutoplayTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.searchAutoplayEnabled = true;
    }

    @After public void tearDown() {
        setPaused(false);
        SettingsStatus.searchAutoplayEnabled = false;
        Settings.STOP_SEARCH_AUTOPLAY.save(Settings.STOP_SEARCH_AUTOPLAY.defaultValue);
    }

    @Test public void offByDefaultResultsPlayAsBefore() {
        assertFalse(SearchAutoplay.shouldSkip());
    }

    @Test public void onTheCheckDoesNothing() {
        Settings.STOP_SEARCH_AUTOPLAY.save(true);
        assertTrue(SearchAutoplay.shouldSkip());
    }

    @Test public void pausedResultsPlayAsBefore() {
        Settings.STOP_SEARCH_AUTOPLAY.save(true);
        setPaused(true);
        assertFalse(SearchAutoplay.shouldSkip());
    }

    /** A switch saved on by an earlier build does nothing after a repatch without the patch. */
    @Test public void aSavedSwitchDoesNothingWithoutItsPatch() {
        Settings.STOP_SEARCH_AUTOPLAY.save(true);
        SettingsStatus.searchAutoplayEnabled = false;
        assertFalse(SearchAutoplay.shouldSkip());
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }
}

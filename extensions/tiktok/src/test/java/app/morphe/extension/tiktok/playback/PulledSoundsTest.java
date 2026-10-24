package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.assertEquals;
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
public class PulledSoundsTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.keepPulledSoundsEnabled = true;
    }

    @After public void tearDown() {
        setPaused(false);
        SettingsStatus.keepPulledSoundsEnabled = false;
        Settings.KEEP_PULLED_SOUNDS.save(Settings.KEEP_PULLED_SOUNDS.defaultValue);
    }

    @Test public void offByDefaultTikTokKeepsItsMute() {
        assertFalse(Settings.KEEP_PULLED_SOUNDS.defaultValue);
        assertEquals(0, PulledSounds.keepStatus(0));
        assertTrue(PulledSounds.keepMuted(true));
        assertFalse(PulledSounds.keepInSearch());
    }

    @Test public void onAPulledSoundReadsAsAvailableAndThePostAsUnmuted() {
        Settings.KEEP_PULLED_SOUNDS.save(true);
        assertEquals(PulledSounds.AVAILABLE, PulledSounds.keepStatus(0));
        assertFalse(PulledSounds.keepMuted(true));
        assertTrue(PulledSounds.keepInSearch());
    }

    /** Only the pulled status changes; any other status, and an unmuted post, pass as TikTok sent them. */
    @Test public void onOtherAnswersPassThrough() {
        Settings.KEEP_PULLED_SOUNDS.save(true);
        assertEquals(1, PulledSounds.keepStatus(1));
        assertEquals(2, PulledSounds.keepStatus(2));
        assertEquals(-1, PulledSounds.keepStatus(-1));
        assertFalse(PulledSounds.keepMuted(false));
    }

    @Test public void pausedTikTokKeepsItsMute() {
        Settings.KEEP_PULLED_SOUNDS.save(true);
        setPaused(true);
        assertEquals(0, PulledSounds.keepStatus(0));
        assertTrue(PulledSounds.keepMuted(true));
        assertFalse(PulledSounds.keepInSearch());
    }

    /** A switch saved on by an earlier build does nothing after a repatch without the patch. */
    @Test public void aSavedSwitchDoesNothingWithoutItsPatch() {
        Settings.KEEP_PULLED_SOUNDS.save(true);
        SettingsStatus.keepPulledSoundsEnabled = false;
        assertEquals(0, PulledSounds.keepStatus(0));
        assertTrue(PulledSounds.keepMuted(true));
        assertFalse(PulledSounds.keepInSearch());
    }

    /** Read as each video starts, so turning it off mutes the next pulled sound again. */
    @Test public void turningItOffTakesEffectAtTheNextVideo() {
        Settings.KEEP_PULLED_SOUNDS.save(true);
        assertEquals(PulledSounds.AVAILABLE, PulledSounds.keepStatus(0));
        Settings.KEEP_PULLED_SOUNDS.save(false);
        assertEquals(0, PulledSounds.keepStatus(0));
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }
}

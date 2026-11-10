package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Each story switch is off by default, answers when on, and stands down under Pause. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StoryControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for TikTok's obfuscated play mode enum, which only shares its constant names. */
    enum Mode { AUTO_PLAY_NEXT_USER, LOOP_CURRENT_USER, LOOP_CURRENT_VIDEO, QUIT_AFTER_FINISH }

    /** Stands in for the photo story's Aweme, whose image list is a real-named getter. */
    public static final class Photo {
        public List<Object> getImageInfos() { return Collections.singletonList(new Object()); }
    }

    public static final class Video {
        public List<Object> getImageInfos() { return Collections.emptyList(); }
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.STORY_LOOP.resetToDefault();
        Settings.STORY_PHOTO_HOLD.resetToDefault();
    }

    @Test public void loopingIsOffByDefaultAndSwapsTheModeWhenOn() {
        assertFalse(Settings.STORY_LOOP.get());
        assertSame(Mode.AUTO_PLAY_NEXT_USER, StoryControls.playMode(Mode.AUTO_PLAY_NEXT_USER));
        Settings.STORY_LOOP.save(true);
        assertSame(Mode.LOOP_CURRENT_VIDEO, StoryControls.playMode(Mode.AUTO_PLAY_NEXT_USER));
        assertSame(Mode.LOOP_CURRENT_VIDEO, StoryControls.playMode(Mode.QUIT_AFTER_FINISH));
        assertNull(StoryControls.playMode(null));
    }

    @Test public void loopingStandsDownUnderPause() {
        Settings.STORY_LOOP.save(true);
        PausedProcess.set(true);
        assertSame(Mode.AUTO_PLAY_NEXT_USER, StoryControls.playMode(Mode.AUTO_PLAY_NEXT_USER));
    }

    @Test public void photoHoldIsOffByDefaultAndOnlyAnswersForPhotos() {
        assertFalse(Settings.STORY_PHOTO_HOLD.get());
        assertFalse(StoryControls.holdPhotoStory(null));
        assertFalse(StoryControls.isPhoto(new Video()));
        assertTrue(StoryControls.isPhoto(new Photo()));
        assertFalse(StoryControls.isPhoto(null));
    }

    @Test public void photoHoldKeepsTheSwitchValueAndPausesToo() {
        Settings.STORY_PHOTO_HOLD.save(true);
        assertTrue(Settings.STORY_PHOTO_HOLD.get());
        // No pager to read a story from, so nothing is held even with the switch on.
        assertFalse(StoryControls.holdPhotoStory(new Object()));
        PausedProcess.set(true);
        assertFalse(Settings.STORY_PHOTO_HOLD.get());
        assertEquals(false, StoryControls.holdPhotoStory(new Object()));
    }
}

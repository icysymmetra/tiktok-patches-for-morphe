/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;

import app.morphe.extension.shared.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The sound the block button offers to block, captured as each video is bound.
 *
 * <p>It is read off the video's music object by reflection, through the same update the
 * author tracker runs when the current video changes, so the cases here go in through that
 * tracker rather than calling the sound class directly. The tracker's rule is that a bind
 * fills an empty selection but never replaces a live one; only the player's own progress
 * callback moves the selection on. So each case binds the video and then plays it, which is
 * the order the host does things in.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CurrentVideoSoundTest {
    public static final class TestActivity extends PreferenceActivity {}

    /** Stands in for VideoItemParams, with a video that may or may not carry music. */
    public static final class Params {
        public final Clip aweme;
        Params(Clip aweme) { this.aweme = aweme; }
    }

    public static final class Clip {
        public final String aid;
        public final Author author;
        public final Music music;

        Clip(String aid, Music music) {
            this.aid = aid;
            this.author = new Author("creator_" + aid);
            this.music = music;
        }
    }

    public static final class Author {
        public final String uid;
        public final String uniqueId;
        Author(String uid) { this.uid = uid; this.uniqueId = uid; }
    }

    /** The fields SoundIdentity reads, by the names TikTok's model uses. */
    public static final class Music {
        public final String mid;
        public final String musicName;
        public final String authorName;
        Music(String mid, String musicName, String authorName) {
            this.mid = mid;
            this.musicName = musicName;
            this.authorName = authorName;
        }
    }

    @Before
    public void setUp() {
        CurrentVideoAuthor.resetForTests();
        CurrentVideoSound.clear();
    }

    @After
    public void tearDown() {
        CurrentVideoAuthor.resetForTests();
        CurrentVideoSound.clear();
    }

    /** Bind, then play: the bind records the video and the play makes it current. */
    private static void show(Clip clip) {
        CurrentVideoAuthor.update(new Params(clip));
        CurrentVideoAuthor.onPlaying(clip.aid);
    }

    @Test
    public void playingAVideoMakesItsSoundTheCurrentOne() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());

            show(new Clip("one", new Music("7001", "Night Drive", "Some Artist")));

            CurrentVideoSound sound = CurrentVideoSound.get();
            assertNotNull("no sound captured on bind", sound);
            assertEquals("7001", sound.id);
            assertEquals("Night Drive", sound.name);
            assertEquals("Some Artist", sound.author);
            // Between the isolate pair, so the report can leave the name out. See VideoAuthor.
            assertEquals("⁨Night Drive (Some Artist)⁩", sound.label());
            assertTrue(sound.isUsable());
        }
    }

    @Test
    public void playingAVideoWithoutASoundClearsTheCurrentOne() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());

            show(new Clip("one", new Music("7001", "Night Drive", "Some Artist")));
            assertNotNull(CurrentVideoSound.get());

            show(new Clip("two", null));
            assertNull("the last video's sound outlived it", CurrentVideoSound.get());
        }
    }

    @Test
    public void aBoundButUnplayedNeighbourDoesNotReplaceTheSound() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());

            show(new Clip("one", new Music("7001", "Night Drive", "Some Artist")));
            // The feed binds the next video ahead of time. Its sound is not the one on
            // screen, and the block button must keep offering the one that is.
            CurrentVideoAuthor.update(new Params(new Clip("two", new Music("7002", "Other", "Y"))));
            assertEquals("7001", CurrentVideoSound.get().id);
        }
    }

    @Test
    public void theLabelSaysWhatItCan() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());

            // A name with no author is the name alone, not "name ()".
            show(new Clip("a", new Music("1", "Just a Name", "")));
            assertEquals("⁨Just a Name⁩", CurrentVideoSound.get().label());

            // An id with no name is named by the id, and is still usable: the id is recorded.
            show(new Clip("b", new Music("42", "", null)));
            assertEquals("⁨sound 42⁩", CurrentVideoSound.get().label());
            assertTrue(CurrentVideoSound.get().isUsable());

            // Neither: there is nothing to record and nothing to match, so the block button
            // has nothing to offer.
            show(new Clip("c", new Music("", "", "")));
            assertEquals("⁨this sound⁩", CurrentVideoSound.get().label());
            assertFalse(CurrentVideoSound.get().isUsable());
        }
    }

    @Test
    public void reboundWithTheSameVideoTheSoundIsLeftAlone() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());

            show(new Clip("one", new Music("7001", "Night Drive", "X")));
            CurrentVideoSound first = CurrentVideoSound.get();
            // The same aid bound and played again, as TikTok does on a relayout, is not a
            // new video, so the sound is not read again even from a bind that lost it.
            show(new Clip("one", null));
            assertEquals("a relayout of the same video dropped its sound", first, CurrentVideoSound.get());
        }
    }
}

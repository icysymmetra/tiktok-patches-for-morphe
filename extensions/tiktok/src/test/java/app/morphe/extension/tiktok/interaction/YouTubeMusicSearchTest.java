/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.L10n;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowToast;

/**
 * The sound handed to YouTube Music as a search: the title and artist off the video model,
 * opened in YouTube Music alone, and a plain word when the app is missing or the sound has no
 * title. Nothing else leaves: the intent carries the search and no extras.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class YouTubeMusicSearchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class Sound {
        final String title;
        final String author;
        boolean original;
        Sound(String title, String author) {
            this.title = title;
            this.author = author;
        }
        public String getMusicName() { return title; }
        public String getAuthorName() { return author; }
        public boolean isOriginal() { return original; }
    }

    public static final class Video {
        public final Sound music;
        Video(Sound music) { this.music = music; }
    }

    /** YouTube Music as the phone would carry it: one activity that takes its search links. */
    private static void installYouTubeMusic(Activity activity) {
        ComponentName search = new ComponentName(YouTubeMusicSearch.PACKAGE,
                YouTubeMusicSearch.PACKAGE + ".SearchActivity");
        ShadowPackageManager packages = Shadows.shadowOf(activity.getPackageManager());
        packages.addActivityIfNotPresent(search);
        IntentFilter filter = new IntentFilter(Intent.ACTION_VIEW);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        filter.addDataScheme("https");
        filter.addDataAuthority("music.youtube.com", null);
        packages.addIntentFilterForActivity(search, filter);
    }

    @Test
    public void theTitleAndArtistOpenInYouTubeMusicsSearchAndNothingElseLeaves() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Shadows.shadowOf(activity.getApplication()).checkActivities(true);
            installYouTubeMusic(activity);
            ShadowToast.reset();

            assertTrue(YouTubeMusicSearch.open(new Video(new Sound("Espresso", "Sabrina Carpenter")), activity));
            Intent sent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(sent);
            assertEquals(Intent.ACTION_VIEW, sent.getAction());
            assertEquals(YouTubeMusicSearch.PACKAGE, sent.getPackage());
            assertEquals("https://music.youtube.com/search?q=Espresso%20Sabrina%20Carpenter", sent.getDataString());
            assertNull("the search carries nothing but the words", sent.getExtras());
            assertNull(ShadowToast.getTextOfLatestToast());

            // A sound with no artist searches by its title alone.
            assertTrue(YouTubeMusicSearch.open(new Video(new Sound("Espresso", null)), activity));
            assertEquals("https://music.youtube.com/search?q=Espresso",
                    Shadows.shadowOf(activity).getNextStartedActivity().getDataString());
        }
    }

    @Test
    public void withoutYouTubeMusicTheReaderIsToldAndNothingOpens() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            // Robolectric only refuses an activity it cannot resolve once it is asked to.
            Shadows.shadowOf(activity.getApplication()).checkActivities(true);
            ShadowToast.reset();

            assertFalse(YouTubeMusicSearch.open(new Video(new Sound("Espresso", "Sabrina Carpenter")), activity));
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            assertEquals(L10n.t("YouTube Music isn't installed"), ShadowToast.getTextOfLatestToast());
        }
    }

    @Test
    public void theVideosOwnSoundIsSaidSoInsteadOfSearched() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            installYouTubeMusic(activity);
            Sound flagged = new Sound("Espresso", "alice");
            flagged.original = true;
            for (Sound sound : new Sound[]{new Sound("original sound - alice", "alice"),
                    new Sound("Original Sound - alice", null), flagged}) {
                ShadowToast.reset();
                assertFalse(sound.title, YouTubeMusicSearch.open(new Video(sound), activity));
                assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
                assertEquals(L10n.t("This is the video's own sound, so YouTube Music won't have it"),
                        ShadowToast.getTextOfLatestToast());
            }
            // A song whose title merely mentions the words is still searched.
            assertTrue(YouTubeMusicSearch.open(new Video(new Sound("My Original Sound", "The Band")), activity));
            assertNotNull(Shadows.shadowOf(activity).getNextStartedActivity());
        }
    }

    @Test
    public void aSoundWithNoTitleIsSaidSoBeforeAnythingOpens() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            installYouTubeMusic(activity);
            for (Object video : new Object[]{new Video(new Sound(null, "Someone")),
                    new Video(new Sound(" ", "Someone")), new Video(null), null}) {
                ShadowToast.reset();
                assertFalse(String.valueOf(video), YouTubeMusicSearch.open(video, activity));
                assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
                assertEquals(L10n.t("This video's sound has no title to look for"),
                        ShadowToast.getTextOfLatestToast());
            }
        }
    }
}

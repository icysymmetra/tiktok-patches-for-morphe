/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.share.ShareModelFilter;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The save button in a story's share sheet (#93): it goes in only on a story's sheet with its
 * switch on, saves the story the sheet was opened for, and leaves every other sheet and every
 * TikTok save action exactly as TikTok built them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class StoryShareSaveTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** An action as TikTok's sheet holds one: something with a key. */
    public static final class Action {
        private final String key;

        Action(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** A post, a story when its flag or its type says so. Its id is unreadable, like a story the server dropped. */
    public static final class Post extends Aweme {
        private final boolean story;
        private final int type;

        Post(boolean story, int type) {
            this.story = story;
            this.type = type;
        }

        @Override public boolean getIsTikTokStory() {
            return story;
        }

        @Override public int getAwemeType() {
            return type;
        }

        @Override public String getAid() {
            return null;
        }
    }

    /** The share package a sheet is built from, holding its post where TikTok's does. */
    public static final class SharePackage {
        public final String itemType = "aweme";
        public final Object aweme;

        SharePackage(Object aweme) {
            this.aweme = aweme;
        }
    }

    private final List<Object[]> built = new ArrayList<>();
    private Activity activity;
    private boolean bridgeThrows;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.SAVE_STORY_FROM_SHARE.save(true);
        ShadowToast.reset();
        StoryShareSave.nativeForTests = (from, story) -> {
            if (bridgeThrows) throw new IllegalStateException("TikTok's save action refused");
            built.add(new Object[] {from, story});
            return new Action(StoryShareSave.SAVE_KEY);
        };
    }

    @After public void tearDown() {
        StoryShareSave.nativeForTests = null;
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ReflectionHelpers.ClassParameter.from(boolean.class, false));
        Settings.SAVE_STORY_FROM_SHARE.save(false);
        Settings.HIDE_SHARE_ACTIONS.save(false);
        Settings.SHARE_HIDDEN_ITEMS.save("");
        SettingsStatus.advancedDownloadsEnabled = false;
        SettingsStatus.shareSheetEnabled = false;
    }

    private static List<Object> row(String... keys) {
        List<Object> row = new ArrayList<>();
        for (String key : keys) row.add(new Action(key));
        return row;
    }

    private static Post story() {
        return new Post(true, 0);
    }

    @Test public void aStorySheetGetsTheSaveInFrontOfTikToksRow() {
        Post story = story();
        List<Object> row = row("report");
        List<Object> before = new ArrayList<>(row);

        List<?> sheet = StoryShareSave.actions(new SharePackage(story), row);

        assertEquals(2, sheet.size());
        assertSame("TikTok's own row was rewritten", before.get(0), sheet.get(1));
        assertEquals("the list TikTok built was changed in place", before, row);
        assertEquals(StoryShareSave.SAVE_KEY, ((Action) sheet.get(0)).key());
        assertEquals("one save action per sheet", 1, built.size());
        assertTrue("built without an activity", built.get(0)[0] instanceof Activity);
        assertSame("built for another story", story, built.get(0)[1]);
        assertSame(story, StoryShareSave.storyOf(sheet.get(0)));
    }

    @Test public void eachStoryTypeCounts() {
        assertTrue(StoryShareSave.isStory(new Post(true, 0)));
        assertTrue(StoryShareSave.isStory(new Post(false, 40)));
        assertTrue(StoryShareSave.isStory(new Post(false, 45)));
        assertFalse(StoryShareSave.isStory(new Post(false, 0)));
        assertFalse(StoryShareSave.isStory(new Post(false, 51)));
        assertFalse(StoryShareSave.isStory(new Aweme()));
        assertFalse(StoryShareSave.isStory("not a post"));
        assertFalse(StoryShareSave.isStory(null));
    }

    @Test public void offPausedOrWithoutAdvancedDownloadsTheRowIsTikToks() {
        List<Object> row = row("report");
        SharePackage sheet = new SharePackage(story());

        Settings.SAVE_STORY_FROM_SHARE.save(false);
        assertSame(row, StoryShareSave.actions(sheet, row));

        Settings.SAVE_STORY_FROM_SHARE.save(true);
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        assertSame("a pause added the button", row, StoryShareSave.actions(sheet, row));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ReflectionHelpers.ClassParameter.from(boolean.class, false));

        SettingsStatus.advancedDownloadsEnabled = false;
        assertSame(row, StoryShareSave.actions(sheet, row));
        assertTrue("an action was built for a sheet that keeps its row", built.isEmpty());
    }

    @Test public void anyOtherSheetIsLeftAlone() {
        List<Object> row = row("save", "report");
        assertSame(row, StoryShareSave.actions(new SharePackage(new Post(false, 0)), row));
        // A profile or LIVE sheet's package holds no post at all.
        assertSame(row, StoryShareSave.actions(new Object(), row));
        assertSame(row, StoryShareSave.actions(null, row));
        assertNull(StoryShareSave.actions(new SharePackage(story()), null));
        assertTrue(built.isEmpty());
    }

    @Test public void aStoryRowWithTikToksOwnSaveGetsNoSecondOne() {
        for (String key : new String[] {"save", "download", "save_video"}) {
            List<Object> row = row("report", key);
            assertSame(key, row, StoryShareSave.actions(new SharePackage(story()), row));
        }
        assertTrue(built.isEmpty());
    }

    @Test public void theSaveStaysOutWhereShareSheetToolsTookTheSaveOut() {
        List<Object> row = row("report");
        SharePackage sheet = new SharePackage(story());
        // Share sheet tools says which sheet is opening before the row is built, a video's here.
        ShareModelFilter.surface(sheet);

        // Without Share sheet tools in the bundle, its saved lists say nothing about this sheet.
        Settings.SHARE_HIDDEN_ITEMS.save("Download");
        assertEquals(2, StoryShareSave.actions(sheet, row).size());

        SettingsStatus.shareSheetEnabled = true;
        assertSame(row, StoryShareSave.actions(sheet, row));
        Settings.SHARE_HIDDEN_ITEMS.save("Report, Copy link");
        assertEquals(2, StoryShareSave.actions(sheet, row).size());
        Settings.HIDE_SHARE_ACTIONS.save(true);
        assertSame(row, StoryShareSave.actions(sheet, row));
    }

    @Test public void aBridgeThatFailsLeavesTheRowAsItWas() {
        List<Object> row = row("report");
        bridgeThrows = true;
        assertSame(row, StoryShareSave.actions(new SharePackage(story()), row));
        bridgeThrows = false;
        StoryShareSave.nativeForTests = (from, story) -> null;
        assertSame(row, StoryShareSave.actions(new SharePackage(story()), row));
    }

    @Test public void onlyTheActionsBuiltHereAreAnsweredForAndTapped() {
        Object ours = StoryShareSave.actions(new SharePackage(story()), row("report")).get(0);
        Object tiktoks = new Action(StoryShareSave.SAVE_KEY);

        assertTrue(StoryShareSave.owns(ours));
        assertFalse("TikTok's own save was answered for", StoryShareSave.owns(tiktoks));
        assertFalse(StoryShareSave.owns(null));

        assertFalse("TikTok's own save was taken", StoryShareSave.click(tiktoks, activity));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull("TikTok's own save said something", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aTapSavesTheStoryTheButtonWasBuiltFor() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        Object ours = StoryShareSave.actions(new SharePackage(story()), row("report")).get(0);

        assertTrue("the tap went on to TikTok's download", StoryShareSave.click(ours, RuntimeEnvironment.getApplication()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        // The story save had the story: with none it asks for the story to be opened again. This
        // one's id can't be read, which is the first thing the save says no to.
        assertEquals("This story isn't available to save", ShadowToast.getTextOfLatestToast());
        assertEquals(0, MediaJobScheduler.queuedJobs());
    }

    @Test public void aButtonStillOnScreenSavesAfterTheSwitchGoesOff() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        Object ours = StoryShareSave.actions(new SharePackage(story()), row()).get(0);
        Settings.SAVE_STORY_FROM_SHARE.save(false);
        // TikTok's download would run on a story if the tap were handed back.
        assertTrue(StoryShareSave.click(ours, RuntimeEnvironment.getApplication()));
        assertTrue(StoryShareSave.owns(ours));
    }

    @Test public void aStoryVideoTakesItsCleanCopiesBeforeTikToksStampedOne() {
        AdvancedDownloadsTest.VideoData video = new AdvancedDownloadsTest.VideoData(Collections.emptyList());
        video.downloadNoWatermarkAddr = new AdvancedDownloadsTest.Address("https://example.com/clean.mp4", 900);
        video.downloadAddr = new AdvancedDownloadsTest.Address("https://example.com/watermarked.mp4", 900);
        video.playAddr = new AdvancedDownloadsTest.Address("https://example.com/play.mp4", 900);
        StoryDownloads.VideoSource source = StoryDownloads.videoSource(video);
        assertEquals(Arrays.asList("https://example.com/clean.mp4"), source.urls);
        assertFalse(source.stamped);

        // No clean address: the copy the player streams has no watermark either.
        video.downloadNoWatermarkAddr = null;
        source = StoryDownloads.videoSource(video);
        assertEquals(Arrays.asList("https://example.com/play.mp4"), source.urls);
        assertFalse(source.stamped);

        // Only TikTok's stamped copy: it's saved, and said to be.
        video.playAddr = new AdvancedDownloadsTest.Address(null, 0);
        source = StoryDownloads.videoSource(video);
        assertEquals(Arrays.asList("https://example.com/watermarked.mp4"), source.urls);
        assertTrue(source.stamped);

        video.downloadAddr = null;
        source = StoryDownloads.videoSource(video);
        assertTrue(source.urls.isEmpty());
        assertFalse("nothing to save was called stamped", source.stamped);
        assertTrue(StoryDownloads.videoSource(new Object()).urls.isEmpty());
        assertTrue(StoryDownloads.videoSource(null).urls.isEmpty());
    }

    /**
     * On Automatic a story took the copy the player streamed, often well below its tallest
     * rendition, while another downloader's HD save got the full one (#155). The tallest rendition
     * other players open comes first now, unless TikTok's own clean file is as tall, and split
     * tracks with no sound to pair leave the story's own save to run without a word.
     */
    @Test public void aStoryOnAutomaticTakesItsTallestRendition() {
        AdvancedDownloadsTest.Gear low = new AdvancedDownloadsTest.Gear("normal_540_0", 100, "https://example.com/540");
        AdvancedDownloadsTest.Gear full = new AdvancedDownloadsTest.Gear("normal_1080_0", 400, "https://example.com/1080");
        AdvancedDownloadsTest.Gear vc2 = new AdvancedDownloadsTest.Gear("adapt_1440_2", 600, "https://example.com/1440").codec(2);
        QualityChoiceTest.VideoPost post = new QualityChoiceTest.VideoPost("155", low, vc2, full);
        // Remove watermark fills the clean address from the streamed copy when TikTok sent none.
        post.video.downloadNoWatermarkAddr = new AdvancedDownloadsTest.Address("https://example.com/play", 900).frame(576, 1024);
        assertSame(full, VideoDownloads.storyGear(post.video));

        post.video.downloadNoWatermarkAddr = new AdvancedDownloadsTest.Address("https://example.com/clean", 900).frame(1080, 1920);
        assertNull("TikTok's own clean file was already that tall", VideoDownloads.storyGear(post.video));
        assertNull(VideoDownloads.storyGear(new AdvancedDownloadsTest.VideoData(Collections.emptyList())));
        assertNull(VideoDownloads.storyGear(null));

        boolean muted = Settings.DOWNLOAD_WITHOUT_SOUND.get();
        try {
            Settings.DOWNLOAD_WITHOUT_SOUND.save(false);
            post.video.dash = true;
            assertFalse(VideoDownloads.startStory(post, activity, full));
            assertFalse(VideoDownloads.startStory(post, activity, null));
            assertNull("a declined rendition said something", ShadowToast.getTextOfLatestToast());
            assertFalse(VideoDownloads.saving("155"));
        } finally {
            Settings.DOWNLOAD_WITHOUT_SOUND.save(muted);
        }
    }
}

package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SettingsRegistryRule;
import com.ss.android.ugc.aweme.detail.ui.DetailActivity;
import com.ss.android.ugc.aweme.main.MainActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.lang.reflect.Method;

/**
 * Hushfeed's controls over the video follow it into the detail pager, where a video opened from
 * a creator's grid, a hashtag or a sound plays, and come back with the reader (#47 on the S22:
 * the block and mute controls stayed on the feed's window, hidden behind the pager).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DetailPagerControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final SettingsRegistryRule settingsRegistry = new SettingsRegistryRule();

    private static final String BLOCK = "Block this creator";

    @Before public void setUp() {
        CurrentVideoAuthor.resetForTests();
        Settings.BLOCK_AUTHOR_BUTTON.save(true);
    }

    @After public void tearDown() throws Exception {
        Method detach = BlockAuthorOverlay.class.getDeclaredMethod("detach");
        detach.setAccessible(true);
        detach.invoke(null);
        Settings.BLOCK_AUTHOR_BUTTON.resetToDefault();
        CurrentVideoAuthor.resetForTests();
        Utils.setActivity(null);
    }

    @Test public void theControlsFollowAGridVideoByTheSameCreatorAndComeBack() {
        ActivityController<MainActivity> feed = Robolectric.buildActivity(MainActivity.class).setup();
        Utils.setContext(feed.get());
        Utils.setActivity(feed.get());
        ViewGroup feedRoot = feed.get().findViewById(android.R.id.content);
        CurrentVideoAuthor.update(new CurrentVideoAuthorTest.Params("clip_one", "creator"));
        CurrentVideoAuthor.onPlaying("clip_one");
        idle();
        assertEquals("the feed's controls", 1, count(feedRoot, BLOCK));

        // A grid video by the same creator names no new author, and still the controls move.
        feed.pause();
        ActivityController<DetailActivity> pager = Robolectric.buildActivity(DetailActivity.class).setup();
        ViewGroup pagerRoot = pager.get().findViewById(android.R.id.content);
        idle();
        assertEquals("the pager's controls", 1, count(pagerRoot, BLOCK));
        assertEquals("a second set was left on the feed", 0, count(feedRoot, BLOCK));

        // Back to the feed: the controls come with the reader, one set of them.
        pager.pause();
        feed.resume();
        pager.stop().destroy();
        idle();
        assertEquals(1, count(feedRoot, BLOCK));
        assertEquals(0, count(pagerRoot, BLOCK));
        feed.pause().stop().destroy();
    }

    @Test public void aScreenThatIsNotAFeedLeavesTheControlsOnTheFeed() {
        ActivityController<Activity> feed = Robolectric.buildActivity(Activity.class).setup();
        Utils.setContext(feed.get());
        Utils.setActivity(feed.get());
        ViewGroup feedRoot = feed.get().findViewById(android.R.id.content);
        CurrentVideoAuthor.update(new CurrentVideoAuthorTest.Params("clip_one", "creator"));
        CurrentVideoAuthor.onPlaying("clip_one");
        idle();

        // Messages or settings in front: nothing to draw the controls on there.
        feed.pause();
        ActivityController<Activity> other = Robolectric.buildActivity(Activity.class).setup();
        CurrentVideoAuthor.update(new CurrentVideoAuthorTest.Params("clip_two", "someone_else"));
        CurrentVideoAuthor.onPlaying("clip_two");
        idle();
        assertEquals(1, count(feedRoot, BLOCK));
        assertEquals(0, count(other.get().findViewById(android.R.id.content), BLOCK));
        other.pause().stop().destroy();
        feed.resume().pause().stop().destroy();
    }

    private static int count(ViewGroup root, String description) {
        int found = 0;
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (description.contentEquals(String.valueOf(child.getContentDescription()))) found++;
        }
        return found;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}

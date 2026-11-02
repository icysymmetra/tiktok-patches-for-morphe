package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import app.morphe.extension.shared.diagnostics.HookStatus;

import com.ss.android.ugc.aweme.feed.model.VideoItemParams;
import com.ss.android.ugc.aweme.story.fake.StoryHoldFakes.Holder;
import com.ss.android.ugc.aweme.story.fake.StoryHoldFakes.Monitor;
import com.ss.android.ugc.aweme.story.fake.StoryHoldFakes.State;
import com.ss.android.ugc.aweme.story.fake.StoryHoldFakes.StoryView;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Which story a press and hold saves. Until 2026-09-27 the saver read view.LLJIJIL, then
 * .LLJIJIL and .LL, 46.2.3's names, and on 47.0.3 and 47.1.3 none of them was there, so the hold
 * never saved a story on either declared build. It goes by TikTok's own names now: the monitor
 * by the ability it implements, the story by the cell params it is bound to.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StoryHoldTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Before public void setUp() {
        HookStatus.clear();
    }

    @After public void tearDown() {
        HookStatus.clear();
    }

    /** The shape on every declared build: the monitor's Assem state, then the bound item. */
    @Test public void theHeldStoryIsTheOneTheMonitorsCellIsBoundTo() {
        Object story = new Object();
        StoryView view = new StoryView(context, new Monitor(new State(new VideoItemParams(story))));

        assertSame(story, StoryDownloads.heldStory(view));
        assertTrue(HookStatus.missing("story saves").toString(), HookStatus.missing("story saves").isEmpty());
    }

    /** Whatever the fields are called on the next build, the search finds them by what they hold. */
    @Test public void theFieldNamesDoNotMatter() {
        Object story = new Object();
        Monitor monitor = new Monitor(null);
        monitor.LLLFF = new State(new VideoItemParams(story));
        StoryView view = new StoryView(context, monitor);

        assertSame(story, StoryDownloads.heldStory(view));
    }

    /** Two cells' params at one depth: saving either would be a guess. */
    @Test public void twoDifferentStoriesAtOneDepthAreNoAnswer() {
        Monitor monitor = new Monitor(new State(new VideoItemParams(new Object())));
        monitor.LLLFF = new Holder(new VideoItemParams(new Object()));
        StoryView view = new StoryView(context, monitor);

        assertNull(StoryDownloads.heldStory(view));
        assertMissing("VideoItemParams");
    }

    /** The same story reached two ways is still one story. */
    @Test public void oneStoryReachedTwiceIsStillAnAnswer() {
        Object story = new Object();
        Monitor monitor = new Monitor(new State(new VideoItemParams(story)));
        monitor.LLLFF = new Holder(new VideoItemParams(story));
        StoryView view = new StoryView(context, monitor);

        assertSame(story, StoryDownloads.heldStory(view));
    }

    @Test public void aViewWithoutAMonitorSaysSoInHookStatus() {
        StoryView view = new StoryView(context, new Holder(new VideoItemParams(new Object())));

        assertNull(StoryDownloads.heldStory(view));
        assertMissing("LongPressMonitorAbility");
    }

    /** Deeper than the declared builds keep it is somewhere else's params, not this cell's. */
    @Test public void paramsBelowTheDeclaredDepthAreNotTaken() {
        StoryView view = new StoryView(context,
                new Monitor(new Holder(new State(new VideoItemParams(new Object())))));

        assertNull(StoryDownloads.heldStory(view));
        assertMissing("VideoItemParams");
    }

    /** Platform objects aren't opened: a list the state keeps is not the cell's binding. */
    @Test public void platformObjectsAreNotSearched() {
        State state = new State(null);
        state.LLJZ.add(new VideoItemParams(new Object()));
        StoryView view = new StoryView(context, new Monitor(state));

        assertNull(StoryDownloads.heldStory(view));
    }

    private static void assertMissing(String what) {
        List<String> missing = HookStatus.missing("story saves");
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains(what));
    }
}

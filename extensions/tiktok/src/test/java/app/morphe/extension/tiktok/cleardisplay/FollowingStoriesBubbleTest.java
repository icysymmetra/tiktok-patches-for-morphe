package app.morphe.extension.tiktok.cleardisplay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The Following stories bubble after clear display: shown again only when it was showing as
 * clear display hid it, is still hidden, and TikTok's list behind it still holds someone, and
 * left to TikTok under Pause.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FollowingStoriesBubbleTest {
    private static final List<Object> SOMEONE = Collections.singletonList(new Object());

    private View bubble;

    @Before
    public void setUp() {
        // The pause check reads a setting, whose class needs a context to load. Without this the
        // class passed only when an earlier test in the same fork had set one.
        Utils.setContext(RuntimeEnvironment.getApplication());
        bubble = new View(RuntimeEnvironment.getApplication());
        FollowingStoriesBubble.resetForTests();
    }

    @After
    public void tearDown() {
        PausedProcess.set(false);
        FollowingStoriesBubble.resetForTests();
    }

    /** What TikTok's own hide does after the hook has looked. */
    private void hiddenForClearDisplay() {
        FollowingStoriesBubble.hiding(bubble);
        bubble.setVisibility(View.GONE);
    }

    @Test
    public void aBubbleThatWasShowingComesBackOnce() {
        hiddenForClearDisplay();
        assertTrue(FollowingStoriesBubble.showAgain(bubble, SOMEONE));
        assertFalse("one clear display, one answer", FollowingStoriesBubble.showAgain(bubble, SOMEONE));
    }

    @Test
    public void aRepeatedStartWhileHiddenKeepsThatItWasShowing() {
        hiddenForClearDisplay();
        // Remember clear display posts the start again for the next video.
        hiddenForClearDisplay();
        assertTrue(FollowingStoriesBubble.showAgain(bubble, SOMEONE));
    }

    @Test
    public void aBubbleTikTokWasntShowingStaysGone() {
        bubble.setVisibility(View.GONE);
        hiddenForClearDisplay();
        assertFalse(FollowingStoriesBubble.showAgain(bubble, SOMEONE));
        assertFalse("no hide was seen", FollowingStoriesBubble.showAgain(new View(RuntimeEnvironment.getApplication()), SOMEONE));
    }

    @Test
    public void anEmptyListOrABubbleAlreadyBackIsLeftAlone() {
        hiddenForClearDisplay();
        assertFalse("nobody left in the list", FollowingStoriesBubble.showAgain(bubble, Collections.emptyList()));

        bubble.setVisibility(View.VISIBLE);
        hiddenForClearDisplay();
        assertFalse(FollowingStoriesBubble.showAgain(bubble, null));

        bubble.setVisibility(View.VISIBLE);
        hiddenForClearDisplay();
        bubble.setVisibility(View.VISIBLE);
        assertFalse("TikTok put it back itself", FollowingStoriesBubble.showAgain(bubble, SOMEONE));
    }

    @Test
    public void pauseLeavesItToTikTok() {
        hiddenForClearDisplay();
        PausedProcess.set(true);
        assertFalse(FollowingStoriesBubble.showAgain(bubble, SOMEONE));
    }
}

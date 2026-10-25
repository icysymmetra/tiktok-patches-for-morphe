package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Locale;

/**
 * Swipe-left controls. TikTok's feed-to-profile pager slides a left swipe on a video to the
 * creator's profile; the setting holds that pager on its feed page while a gesture heads for the
 * profile and, set to comments, opens the video's comments instead, once a swipe. On 47.0.3 the
 * home pager holds three pages, a side panel, the feed and the profile, and the feed is the one
 * before the last (the probe's pagerstate on the S22). Each event here goes the way the pager
 * handles it: its dispatch first, then TikTok's own check answering, then its intercept or its
 * own touch handling.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SwipeLeftTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /**
     * TikTok's pager, by the two methods the extension reads. A real View, so its configuration
     * decides which way the profile is, as TikTok's does.
     */
    public static final class Pager extends FrameLayout {
        int page = FEED;
        int pages = 3;

        public Pager() {
            this(RuntimeEnvironment.getApplication());
        }

        public Pager(Context context) {
            super(context);
        }

        public int getCurrentItem() {
            return page;
        }

        public Adapter getAdapter() {
            return new Adapter(pages);
        }
    }

    /** Its adapter, by its page count. */
    public static final class Adapter {
        private final int count;

        Adapter(int count) {
            this.count = count;
        }

        public int getCount() {
            return count;
        }
    }

    /** The feed page of a three-page pager: a side panel at 0, the feed at 1, the profile at 2. */
    private static final int FEED = 1;
    private static final int PROFILE = 2;

    private Runnable original;
    private int opened;
    private Pager pager;
    /** TikTok's own answer during a gesture: yes on the feed, no where TikTok turns paging off. */
    private boolean tiktokPages = true;

    @Before public void setUp() {
        pager = new Pager();
        original = GestureActions.swipeCommentsOpener;
        GestureActions.swipeCommentsOpener = () -> opened++;
    }

    @After public void tearDown() {
        GestureActions.swipeCommentsOpener = original;
        Settings.SWIPE_LEFT_ACTION.resetToDefault();
    }

    @Test public void tiktoksDefaultPagesAsItAlwaysDid() {
        assertEquals("default", Settings.SWIPE_LEFT_ACTION.get());
        pager.page = FEED;
        heading(900, 600);
        assertTrue(GestureActions.pagingEnabled(pager, true));
        pager.page = PROFILE;
        assertTrue(GestureActions.pagingEnabled(pager, true));
    }

    @Test public void doNothingAndCommentsHoldOnlyTheFeedPage() {
        for (String action : new String[]{"nothing", "comments"}) {
            Settings.SWIPE_LEFT_ACTION.save(action);
            pager.pages = 3;
            pager.page = FEED;
            // Every question below comes during a swipe heading left, for the profile.
            heading(900, 600);
            assertFalse(action + ": the feed page stays put", GestureActions.pagingEnabled(pager, true));
            pager.page = PROFILE;
            assertTrue(action + ": the swipe back from the profile stays TikTok's", GestureActions.pagingEnabled(pager, true));
            pager.page = 0;
            assertTrue(action + ": the side panel pages as usual", GestureActions.pagingEnabled(pager, true));
            assertTrue(action + ": a pager whose page can't be read pages as usual",
                    GestureActions.pagingEnabled(new Object(), true));
            pager.pages = 2;
            pager.page = 0;
            assertFalse(action + ": in a pager of two (a video opened from a profile, search or a link) the video is page 0",
                    GestureActions.pagingEnabled(pager, true));
            pager.page = 1;
            assertTrue(action + ": and the profile page 1", GestureActions.pagingEnabled(pager, true));
            pager.pages = 3;
            pager.page = FEED;
        }
    }

    /**
     * TikTok turns paging off itself where the feed isn't what's showing (the Inbox, your own
     * profile). Its no stays no, and a swipe there opens nothing.
     */
    @Test public void tiktoksOwnNoStaysNo() {
        for (String action : new String[]{"default", "nothing", "comments"}) {
            Settings.SWIPE_LEFT_ACTION.save(action);
            heading(900, 600);
            assertFalse(action, GestureActions.pagingEnabled(pager, false));
        }
        Settings.SWIPE_LEFT_ACTION.save("comments");
        opened = 0;
        tiktokPages = false;
        swipe(900, 1000, 200, 1000);
        assertEquals("comments open only where TikTok itself would page", 0, opened);
    }

    /**
     * TikTok's pager asks after every event, so the hold follows the gesture: the touch down pages
     * as usual (TikTok records where the gesture starts), a swipe to the right still opens the side
     * panel, and one that turns back past where it started is let go.
     */
    @Test public void onlyAGestureHeadingForTheProfileIsHeld() {
        for (String action : new String[]{"nothing", "comments"}) {
            Settings.SWIPE_LEFT_ACTION.save(action);
            pager.page = FEED;
            long start = SystemClock.uptimeMillis();
            MotionEvent down = obtain(MotionEvent.ACTION_DOWN, start, 500, 1000);
            GestureActions.onMainPagerDispatch(pager, down);
            assertTrue(action + ": at the touch down", GestureActions.pagingEnabled(pager, true));
            down.recycle();
            heading(200, 700);
            assertTrue(action + ": a swipe to the right, for the side panel", GestureActions.pagingEnabled(pager, true));
            heading(900, 600);
            assertFalse(action + ": a swipe to the left, for the profile", GestureActions.pagingEnabled(pager, true));
            MotionEvent back = obtain(MotionEvent.ACTION_MOVE, start, 950, 1000);
            GestureActions.onMainPagerDispatch(pager, back);
            assertTrue(action + ": turned back past where it started", GestureActions.pagingEnabled(pager, true));
            back.recycle();
        }
    }

    /**
     * In a right-to-left layout TikTok's pager runs the other way: the profile is to the right.
     * The pager's own configuration says so, the way TikTok reads it; the default locale stays
     * left-to-right, so a check that read the locale instead would fail here.
     */
    @Test public void inARightToLeftLayoutTheProfileIsToTheRight() {
        Configuration rtl = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        rtl.setLayoutDirection(new Locale("ar"));
        pager = new Pager(RuntimeEnvironment.getApplication().createConfigurationContext(rtl));
        assertEquals(View.LAYOUT_DIRECTION_RTL, pager.getContext().getResources().getConfiguration().getLayoutDirection());
        // The hold under "do nothing", so the gestures that ask it open nothing themselves.
        Settings.SWIPE_LEFT_ACTION.save("nothing");
        pager.page = FEED;
        heading(200, 700);
        assertFalse("a swipe to the right is held", GestureActions.pagingEnabled(pager, true));
        heading(900, 600);
        assertTrue("a swipe to the left pages as usual", GestureActions.pagingEnabled(pager, true));
        Settings.SWIPE_LEFT_ACTION.save("comments");
        swipe(900, 1000, 200, 1000);
        assertEquals("a swipe to the left opens nothing", 0, opened);
        swipe(200, 1000, 900, 1000);
        assertEquals("a swipe to the right opens the comments", 1, opened);
    }

    /**
     * TikTok hands the pager the up of every gesture whatever its check says, and the pager picks
     * its page there. A held gesture's up reaches the pager as a cancel, which settles it on the
     * page it is on; any other gesture's up is left alone.
     */
    @Test public void aHeldGestureEndsInACancel() {
        Settings.SWIPE_LEFT_ACTION.save("nothing");
        assertEquals("held", MotionEvent.ACTION_CANCEL, upAfter(900, 600));
        assertEquals("a swipe to the right", MotionEvent.ACTION_UP, upAfter(200, 700));
        Settings.SWIPE_LEFT_ACTION.save("default");
        assertEquals("TikTok's default", MotionEvent.ACTION_UP, upAfter(900, 600));
    }

    @Test public void aLeftSwipeFromTheFeedOpensCommentsOnce() {
        Settings.SWIPE_LEFT_ACTION.save("comments");
        swipe(900, 1000, 890, 1002, 600, 1010, 200, 1020);
        assertEquals("one swipe, one press, however far it goes", 1, opened);
    }

    @Test public void onlyAClearlySidewaysLeftSwipeCounts() {
        Settings.SWIPE_LEFT_ACTION.save("comments");
        swipe(200, 1000, 800, 1000);
        assertEquals("a right swipe", 0, opened);
        swipe(900, 1000, 500, 1400);
        assertEquals("more down than sideways", 0, opened);
        swipe(900, 1000, 890, 1000);
        assertEquals("a nudge", 0, opened);
        pager.page = PROFILE;
        swipe(900, 1000, 200, 1000);
        assertEquals("from the profile page", 0, opened);
        pager.page = 0;
        swipe(900, 1000, 200, 1000);
        assertEquals("from the side panel", 0, opened);
        pager.page = FEED;
    }

    @Test public void theOtherChoicesOpenNothing() {
        for (String action : new String[]{"default", "nothing"}) {
            Settings.SWIPE_LEFT_ACTION.save(action);
            swipe(900, 1000, 200, 1000);
            assertEquals(action, 0, opened);
        }
    }

    @Test public void eachNewSwipeCountsAgain() {
        Settings.SWIPE_LEFT_ACTION.save("comments");
        swipe(900, 1000, 200, 1000);
        swipe(900, 1000, 200, 1000);
        assertEquals(2, opened);
    }

    /** A swipe in progress: DOWN at one x, a MOVE to the other, no UP yet. */
    private void heading(float fromX, float toX) {
        long start = SystemClock.uptimeMillis();
        handle(MotionEvent.ACTION_DOWN, start, fromX, 1000, false).recycle();
        handle(MotionEvent.ACTION_MOVE, start, toX, 1000, false).recycle();
    }

    /** DOWN at the first point, a MOVE at each point after it, then UP at the last. */
    private void swipe(float... points) {
        long start = SystemClock.uptimeMillis();
        handle(MotionEvent.ACTION_DOWN, start, points[0], points[1], false).recycle();
        for (int i = 2; i < points.length; i += 2) {
            handle(MotionEvent.ACTION_MOVE, start, points[i], points[i + 1], false).recycle();
        }
        handle(MotionEvent.ACTION_UP, start, points[points.length - 2], points[points.length - 1], true).recycle();
    }

    /** The action the pager's own touch handling is left with for the up of a horizontal swipe. */
    private int upAfter(float fromX, float toX) {
        long start = SystemClock.uptimeMillis();
        handle(MotionEvent.ACTION_DOWN, start, fromX, 1000, false).recycle();
        handle(MotionEvent.ACTION_MOVE, start, toX, 1000, true).recycle();
        MotionEvent up = handle(MotionEvent.ACTION_UP, start, toX, 1000, true);
        try {
            return up.getActionMasked();
        } finally {
            up.recycle();
        }
    }

    /**
     * One event as the pager handles it: its dispatch, TikTok's check answering {@link #tiktokPages},
     * then its intercept, or its own touch handling once it has the gesture.
     */
    private MotionEvent handle(int action, long start, float x, float y, boolean own) {
        MotionEvent event = obtain(action, start, x, y);
        GestureActions.onMainPagerDispatch(pager, event);
        GestureActions.pagingEnabled(pager, tiktokPages);
        if (own) GestureActions.onMainPagerOwnTouch(pager, event);
        else GestureActions.onMainPagerTouch(pager, event);
        return event;
    }

    private static MotionEvent obtain(int action, long start, float x, float y) {
        return MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0);
    }
}

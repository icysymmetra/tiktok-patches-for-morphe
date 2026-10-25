package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RailHoldTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final int[] menus = new int[1];

    @Before public void setUp() {
        reset();
    }

    @After public void tearDown() {
        reset();
    }

    private static void reset() {
        Settings.RAIL_HOLD_COMMENT.resetToDefault();
        Settings.RAIL_HOLD_SHARE.resetToDefault();
        Settings.RAIL_HOLD_FAVORITES.resetToDefault();
        Settings.LONG_PRESS_ACTION.resetToDefault();
        Settings.EDGE_SEEK.resetToDefault();
        Settings.EDGE_SEEK_SECONDS.resetToDefault();
        RailHold.resetForTests();
        HookStatus.clear();
    }

    /**
     * The Share button in its rail. A long press nobody takes falls through to the context menu,
     * which asks the button's parent, and on the phone there always is one.
     */
    private View button() {
        FrameLayout rail = new FrameLayout(RuntimeEnvironment.getApplication());
        View button = new View(RuntimeEnvironment.getApplication());
        button.setLongClickable(true);
        rail.addView(button);
        return button;
    }

    /** TikTok's own long press on Share, the menu it opens counted. */
    private View.OnLongClickListener menu(boolean handled) {
        return view -> {
            menus[0]++;
            return handled;
        };
    }

    @Test public void tikTokDecidesByDefault() {
        assertTrue("a press on Comment is still no hold", RailHold.commentStopsHold(true));
        assertTrue("nor one on Share", RailHold.shareStopsHold(true));
        assertTrue("nor one on Favorites", RailHold.favoritesStopsHold(true));
        assertFalse("a press elsewhere is left as it was", RailHold.commentStopsHold(false));
        assertFalse(RailHold.shareStopsHold(false));
        assertFalse(RailHold.favoritesStopsHold(false));
        assertFalse("Comment's emoji row opens", RailHold.skipCommentMenu());
        assertFalse("Favorites offers a collection", RailHold.skipFavoritesMenu());
    }

    @Test public void eachSwitchFreesOnlyItsOwnButton() {
        Settings.RAIL_HOLD_COMMENT.save(true);
        assertFalse("Comment holds", RailHold.commentStopsHold(true));
        assertTrue("Share keeps its menu", RailHold.shareStopsHold(true));
        assertTrue("and Favorites its offer", RailHold.favoritesStopsHold(true));
        Settings.RAIL_HOLD_COMMENT.save(false);
        Settings.RAIL_HOLD_SHARE.save(true);
        assertTrue(RailHold.commentStopsHold(true));
        assertFalse(RailHold.shareStopsHold(true));
        assertTrue(RailHold.favoritesStopsHold(true));
        Settings.RAIL_HOLD_SHARE.save(false);
        Settings.RAIL_HOLD_FAVORITES.save(true);
        assertTrue(RailHold.commentStopsHold(true));
        assertTrue(RailHold.shareStopsHold(true));
        assertFalse(RailHold.favoritesStopsHold(true));
    }

    @Test public void aMenuMethodSkipsOnlyForAPressTheCheckLetThrough() {
        Settings.RAIL_HOLD_COMMENT.save(true);
        Settings.RAIL_HOLD_FAVORITES.save(true);
        assertFalse("chosen, but the check turned the press down before asking the buttons",
                RailHold.skipCommentMenu());
        assertFalse(RailHold.skipFavoritesMenu());
        RailHold.commentStopsHold(true);
        assertTrue("held, so the emoji row stays shut", RailHold.skipCommentMenu());
        assertFalse("a pass on Comment leaves Favorites' offer alone", RailHold.skipFavoritesMenu());
        RailHold.favoritesStopsHold(true);
        assertTrue("held, so no collection is offered", RailHold.skipFavoritesMenu());

        RailHold.resetForTests();
        Settings.RAIL_HOLD_COMMENT.save(false);
        RailHold.commentStopsHold(true);
        assertFalse("Comment's switch is off, so its row opens", RailHold.skipCommentMenu());
    }

    @Test public void sharesMenuOpensUnlessTheHoldCheckLetItsPressThrough() {
        View share = button();
        RailHold.setShareLongClick(share, menu(true));
        assertTrue(share.performLongClick());
        assertEquals("TikTok's menu with the switch off", 1, menus[0]);

        Settings.RAIL_HOLD_SHARE.save(true);
        assertTrue(share.performLongClick());
        assertEquals("switched on, but the check turned the press down before asking the buttons", 2, menus[0]);

        RailHold.shareStopsHold(true);
        assertTrue("the long press is taken, so letting go is no tap", share.performLongClick());
        assertEquals("and the menu stays shut", 2, menus[0]);

        View other = button();
        RailHold.setShareLongClick(other, menu(false));
        Settings.RAIL_HOLD_SHARE.save(false);
        RailHold.shareStopsHold(true);
        assertFalse("TikTok's own answer comes back", other.performLongClick());
        assertEquals("Share's switch is off, so its menu opens", 3, menus[0]);
        RailHold.resetForTests();
        Settings.RAIL_HOLD_SHARE.save(true);
        RailHold.commentStopsHold(true);
        assertFalse("a pass on another button leaves Share's menu alone", other.performLongClick());
        assertEquals(4, menus[0]);
    }

    @Test public void aPassBelongsOnlyToThePressItWasFor() {
        Settings.RAIL_HOLD_COMMENT.save(true);
        assertEquals("the long-press timeout the window counts from", 500, ViewConfiguration.getLongPressTimeout());
        RailHold.commentStopsHold(true);
        // Favorites' offer, Comment's row, Share's long press behind a slow frame, the window's end.
        long elapsed = 0;
        for (long at : new long[]{200, 300, 900, 1500}) {
            ShadowSystemClock.advanceBy(at - elapsed, TimeUnit.MILLISECONDS);
            elapsed = at;
            assertTrue("still the same press " + at + " ms in", RailHold.skipCommentMenu());
        }
        ShadowSystemClock.advanceBy(1, TimeUnit.MILLISECONDS);
        assertFalse("a pass older than that was another press's", RailHold.skipCommentMenu());

        RailHold.commentStopsHold(true);
        Settings.RAIL_HOLD_COMMENT.save(false);
        assertFalse("switched off since, so the row opens", RailHold.skipCommentMenu());
    }

    @Test public void aDroppedHoldForgetsEveryPassAndStillDropsTikToksPost() {
        Settings.RAIL_HOLD_COMMENT.save(true);
        Settings.RAIL_HOLD_SHARE.save(true);
        Settings.RAIL_HOLD_FAVORITES.save(true);
        Handler handler = new Handler(Looper.getMainLooper());
        int[] started = {0};
        Runnable start = () -> started[0]++;
        handler.postDelayed(start, 300);
        RailHold.commentStopsHold(true);
        RailHold.shareStopsHold(true);
        RailHold.favoritesStopsHold(true);

        RailHold.holdDropped(handler, start);

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals("TikTok's hold never starts", 0, started[0]);
        assertFalse("Comment's row opens", RailHold.skipCommentMenu());
        assertFalse("Favorites offers a collection", RailHold.skipFavoritesMenu());
        View share = button();
        RailHold.setShareLongClick(share, menu(true));
        share.performLongClick();
        assertEquals("Share's menu opens", 1, menus[0]);
    }

    @Test public void noListenerStaysNoListener() {
        View share = button();
        RailHold.setShareLongClick(share, null);
        Settings.RAIL_HOLD_SHARE.save(true);
        RailHold.shareStopsHold(true);
        assertFalse("nothing handles the long press", share.performLongClick());
    }

    @Test public void theSwitchesGreyOutWhereTikToksHoldCantStart() {
        assertTrue(Settings.RAIL_HOLD_COMMENT.isAvailable());
        Settings.LONG_PRESS_ACTION.save("comments");
        assertFalse("the long press is Metra's", Settings.RAIL_HOLD_COMMENT.isAvailable());
        assertFalse(Settings.RAIL_HOLD_SHARE.isAvailable());
        assertFalse(Settings.RAIL_HOLD_FAVORITES.isAvailable());
        Settings.LONG_PRESS_ACTION.save("default");
        Settings.EDGE_SEEK.save(true);
        assertFalse("the right third seeks", Settings.RAIL_HOLD_SHARE.isAvailable());
        Settings.EDGE_SEEK_SECONDS.save(0);
        assertTrue("a seek of nothing leaves the hold be", Settings.RAIL_HOLD_SHARE.isAvailable());
        Settings.EDGE_SEEK.save(false);
        Settings.LONG_PRESS_ACTION.save("somewhere_new");
        assertTrue("a choice this build doesn't know is TikTok's, as the gesture treats it",
                Settings.RAIL_HOLD_FAVORITES.isAvailable());
    }

    @Test public void theGreyingMatchesThePressGate() {
        for (String action : new String[]{"default", "nothing", "like", "comments", "original_sound", "copy_link",
                "copy_sound_link", "youtube_music", "sleep_timer", "save_frame", "save_cover", "somewhere_new"}) {
            Settings.LONG_PRESS_ACTION.save(action);
            assertEquals(action, GestureActions.allowNativeEdgeSpeedup(0), Settings.RAIL_HOLD_COMMENT.isAvailable());
        }
    }

    @Test public void theExportCountsTheAsksAndTheHolds() {
        int[] found = new int[1];
        HookStatus.setLineWriter((family, count, missing, truncated, firstMiss) -> {
            if (RailHold.FAMILY.equals(family)) found[0] = count;
            return family;
        });
        try {
            RailHold.commentStopsHold(true);
            RailHold.shareStopsHold(true);
            RailHold.favoritesStopsHold(true);
            RailHold.skipCommentMenu();
            RailHold.skipFavoritesMenu();
            RailHold.setShareLongClick(button(), menu(true));
            HookStatus.report();
            assertEquals("three asks, two menu methods and the listener", 6, found[0]);
            Settings.RAIL_HOLD_COMMENT.save(true);
            Settings.RAIL_HOLD_SHARE.save(true);
            Settings.RAIL_HOLD_FAVORITES.save(true);
            RailHold.commentStopsHold(true);
            RailHold.shareStopsHold(true);
            RailHold.favoritesStopsHold(true);
            RailHold.skipCommentMenu();
            RailHold.skipFavoritesMenu();
            HookStatus.report();
            assertEquals("three holds and two menus skipped", 11, found[0]);
            View share = button();
            RailHold.setShareLongClick(share, menu(true));
            share.performLongClick();
            HookStatus.report();
            assertEquals("and Share's long press, its menu skipped", 13, found[0]);
            Handler handler = new Handler(Looper.getMainLooper());
            RailHold.holdDropped(handler, () -> { });
            HookStatus.report();
            assertEquals("and a dropped hold", 14, found[0]);
        } finally {
            HookStatus.setLineWriter(null);
        }
    }
}

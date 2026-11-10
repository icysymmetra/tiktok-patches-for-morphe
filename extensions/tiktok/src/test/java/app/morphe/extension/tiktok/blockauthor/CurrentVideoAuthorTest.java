package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.Rule;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import org.robolectric.util.ReflectionHelpers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The feed binds the items either side of the current one before the user reaches them, so
 * "most recently bound" is not "on screen". These cover the difference.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CurrentVideoAuthorTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    public static final class TestActivity extends PreferenceActivity {}

    @Before public void setUp() {
        CurrentVideoAuthor.resetForTests();
    }

    @After public void tearDown() {
        CurrentVideoAuthor.resetForTests();
    }

    /** Stands in for VideoItemParams. */
    public static final class Params {
        public final Clip aweme;

        Params(String awemeId, String uid) {
            aweme = new Clip(awemeId, uid);
        }
    }

    public static final class Clip {
        public final String aid;
        public final Author author;

        Clip(String aid, String uid) {
            this.aid = aid;
            this.author = new Author(uid);
        }
    }

    public static final class Author {
        public final String uid;
        public final String uniqueId;

        Author(String uid) {
            this.uid = uid;
            this.uniqueId = uid;
        }
    }

    /**
     * One long video playing past the time budget starts the hold without a swipe. The notice
     * and the hold used to be claimed only when a different video came on screen.
     */
    @Test
    public void theHoldStartsWhenTheBudgetRunsOutMidVideo() throws Exception {
        withBudgetClock(now -> {
            Settings.SESSION_BUDGET_MINUTES.save(1);
            Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
            play(now, "long_clip", 70);
            assertTrue("a minute of one video ran past the budget with no hold", SessionBudget.isLocked());
        });
    }

    /** Lowering the budget below what is already watched starts the hold on the same video. */
    @Test
    public void aBudgetLoweredBelowTodaysWatchingHoldsTheSameVideo() throws Exception {
        withBudgetClock(now -> {
            Settings.SESSION_BUDGET_MINUTES.save(10);
            Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
            play(now, "long_clip", 120);
            assertFalse(SessionBudget.isLocked());
            Settings.SESSION_BUDGET_MINUTES.save(1);
            play(now, "long_clip", 3);
            assertTrue("the lowered budget waited for a swipe", SessionBudget.isLocked());
        });
    }

    /** After a hold, a raised budget runs out again on the same video and holds it again. */
    @Test
    public void aBudgetRaisedAfterAHoldHoldsAgainAtTheNewLimit() throws Exception {
        withBudgetClock(now -> {
            Settings.SESSION_BUDGET_MINUTES.save(1);
            Settings.SESSION_BUDGET_LOCK_MINUTES.save(1);
            play(now, "long_clip", 70);
            assertTrue(SessionBudget.isLocked());
            now.addAndGet(61_000L);
            assertFalse("the one-minute hold did not end", SessionBudget.isLocked());

            Settings.SESSION_BUDGET_MINUTES.save(3);
            play(now, "long_clip", 60);
            assertFalse("the raised budget held too early", SessionBudget.isLocked());
            play(now, "long_clip", 70);
            assertTrue("the raised budget ran out with no hold", SessionBudget.isLocked());
        });
    }

    private interface BudgetBody {
        void run(java.util.concurrent.atomic.AtomicLong now) throws Exception;
    }

    /** Runs {@code body} with SessionBudget reading a clock the test moves. */
    private static void withBudgetClock(BudgetBody body) throws Exception {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_790_000_000_000L);
        Class<?> clockType = Class.forName("app.morphe.extension.tiktok.wellbeing.SessionBudget$Clock");
        Object clock = java.lang.reflect.Proxy.newProxyInstance(clockType.getClassLoader(),
                new Class<?>[]{clockType}, (proxy, method, args) ->
                        "now".equals(method.getName()) ? now.get() : null);
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            budgetForTests("awaitWritesForTests");
            ReflectionHelpers.callStaticMethod(SessionBudget.class, "setClockForTests",
                    ReflectionHelpers.ClassParameter.from(clockType, clock));
            budgetForTests("resetForTests");
            CurrentVideoAuthor.update(new Params("long_clip", "creator_one"));
            body.run(now);
        } finally {
            ReflectionHelpers.callStaticMethod(SessionBudget.class, "setClockForTests",
                    ReflectionHelpers.ClassParameter.from(clockType, null));
            budgetForTests("awaitWritesForTests");
            budgetForTests("resetForTests");
            Settings.SESSION_BUDGET_MINUTES.resetToDefault();
            Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
            Settings.SESSION_BUDGET_STATE.resetToDefault();
        }
    }

    /** The player naming one video once a second for {@code seconds} seconds. */
    private static void play(java.util.concurrent.atomic.AtomicLong now, String id, int seconds) {
        CurrentVideoAuthor.onPlaying(id);
        for (int second = 0; second < seconds; second++) {
            now.addAndGet(1_000L);
            CurrentVideoAuthor.onPlaying(id);
        }
    }

    private static void budgetForTests(String method) {
        ReflectionHelpers.callStaticMethod(SessionBudget.class, method);
    }

    @Test
    public void bindingTheNextVideoDoesNotChangeWhoIsTargeted() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());

            // The video on screen, bound and then confirmed by the player.
            CurrentVideoAuthor.update(new Params("aweme_one", "creator_one"));
            CurrentVideoAuthor.onPlaying("aweme_one");
            assertEquals("creator_one", CurrentVideoAuthor.get().uid);

            // The feed prefetches the next item while the first is still playing. This is
            // the bug: taking the latest bind as current armed the button on creator_two.
            CurrentVideoAuthor.update(new Params("aweme_two", "creator_two"));
            assertEquals("creator_one", CurrentVideoAuthor.get().uid);

            // Only the player moving on changes the target.
            CurrentVideoAuthor.onPlaying("aweme_two");
            assertEquals("creator_two", CurrentVideoAuthor.get().uid);
        }
    }

    @Test
    public void aBindThatLandsWhileThePlayerIsChoosingIsKept() throws Exception {
        // The player names the next video on its own thread and finds it not bound yet. The bind
        // landing on another thread right then selected it, and the player's "nothing" was
        // written after, so the video played out with no creator to block and no sound to save.
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            CurrentVideoAuthor.update(new Params("aweme_one", "creator_one"));
            CurrentVideoAuthor.onPlaying("aweme_one");

            Thread[] binder = new Thread[1];
            CurrentVideoAuthor.setBetweenLookupAndSelectForTests(() -> {
                binder[0] = new Thread(() -> CurrentVideoAuthor.update(new Params("aweme_two", "creator_two")));
                binder[0].start();
                try {
                    // Long enough for an unguarded bind to finish; a guarded one waits its turn.
                    binder[0].join(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                CurrentVideoAuthor.onPlaying("aweme_two");
            } finally {
                CurrentVideoAuthor.setBetweenLookupAndSelectForTests(null);
            }
            binder[0].join(5_000);
            assertEquals("a bind that landed mid-choice was overwritten",
                    "creator_two", CurrentVideoAuthor.get() == null ? null : CurrentVideoAuthor.get().uid);
        }
    }

    /**
     * Back on the feed after a creator's grid or a story, the player names the feed video again,
     * but those screens bound more videos than the bind record keeps and it had forgotten the one
     * the reader came back to. The controls went until the next swipe (S22, 47.0.3).
     */
    @Test
    public void theFeedVideoIsFoundAgainAfterAScreenThatBoundManyOthers() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            CurrentVideoAuthor.update(new Params("feed_video", "creator_feed"));
            CurrentVideoAuthor.onPlaying("feed_video");
            assertEquals("creator_feed", CurrentVideoAuthor.get().uid);

            for (int index = 0; index < 40; index++) {
                CurrentVideoAuthor.update(new Params("grid_" + index, "creator_grid"));
            }
            CurrentVideoAuthor.onPlaying("grid_39");
            assertEquals("creator_grid", CurrentVideoAuthor.get().uid);

            CurrentVideoAuthor.onPlaying("feed_video");
            assertEquals("the feed video came back with nobody to target", "creator_feed",
                    CurrentVideoAuthor.get() == null ? null : CurrentVideoAuthor.get().uid);
        }
    }

    @Test
    public void aVideoThePlayerNamesBeforeItIsBoundTargetsNobody() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());

            CurrentVideoAuthor.update(new Params("aweme_one", "creator_one"));
            CurrentVideoAuthor.onPlaying("aweme_one");
            assertEquals("creator_one", CurrentVideoAuthor.get().uid);

            // Nothing is known about this one yet, so the button must hide rather than
            // stay pointed at the previous creator.
            CurrentVideoAuthor.onPlaying("aweme_unbound");
            assertNull(CurrentVideoAuthor.get());

            // It arms once the bind arrives.
            CurrentVideoAuthor.update(new Params("aweme_unbound", "creator_three"));
            assertEquals("creator_three", CurrentVideoAuthor.get().uid);
        }
    }

    @Test
    public void trackingSurvivesTheBlockButtonBeingTurnedOff() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            Settings.BLOCK_AUTHOR_BUTTON.save(false);

            // Not interested, the follow and like confirmations, double tap to open
            // comments and subtitle tracking all read this and depend on this patch for
            // it, so the block button's own switch must not decide whether it runs.
            BlockAuthorPatch.setCurrentVideoParams(new Params("aweme_gated", "creator_gated"));
            BlockAuthorPatch.setPlayingAweme("aweme_gated");

            assertEquals("aweme_gated",
                    Reflect.string(CurrentVideoAuthor.getAweme(), "getAid", "aid"));
            assertEquals("creator_gated", CurrentVideoAuthor.get().uid);
        } finally {
            Settings.BLOCK_AUTHOR_BUTTON.save(false);
        }
    }

    @Test
    public void authorEqualityAndHashingUseTheSameCanonicalIdentifier() {
        VideoAuthor first = new VideoAuthor("uid-1", "sec-1", "First", "video-1");
        VideoAuthor rebound = new VideoAuthor("uid-1", "sec-2", "Renamed", "video-2");
        VideoAuthor secureOnly = new VideoAuthor(null, "sec-1", "First", "video-3");
        VideoAuthor emptyUid = new VideoAuthor("", "sec-1", "First", "video-4");

        assertEquals(first, rebound);
        assertEquals(first.hashCode(), rebound.hashCode());
        assertNotEquals(first, secureOnly);
        assertEquals(secureOnly, emptyUid);
        assertEquals(secureOnly.hashCode(), emptyUid.hashCode());

        java.util.Set<VideoAuthor> authors = new java.util.HashSet<>();
        authors.add(first);
        authors.add(rebound);
        authors.add(secureOnly);
        authors.add(emptyUid);
        assertEquals(2, authors.size());
    }
}

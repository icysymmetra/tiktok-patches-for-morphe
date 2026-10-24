package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorPatch;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
import com.ss.android.ugc.aweme.feed.assem.ability.IVideoCommentAbility;
import com.ss.android.ugc.feed.platform.cell.ability.VideoDiggAssemAbility;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * How a double tap set to comments presses the comment button.
 *
 * <p>On the S22 with 46.2.3 the registered view answered {@code performClick()} with true and
 * opened nothing: TikTok leaves a click listener with no body on the button and handles the
 * real press as a touch. The assem that owns the view implements {@code IVideoCommentAbility},
 * whose one no-argument method is the icon press with TikTok's own gating in front of it. These
 * pin that the press goes through that method when it is there, that it is chosen by shape
 * rather than by name, and that a build without it falls back to the click and says so.
 *
 * <p>Like the video (#136) presses the heart in the cell of the button the comments would press,
 * through the heart's ability in the same way, and the tests at the end pin that too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class DoubleTapCommentsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The comment assem as the extension sees it: the ability plus whatever else it does. */
    public static final class CommentAssem implements IVideoCommentAbility {
        int presses;
        int jumps;

        @Override public void Id0() { presses++; }
        @Override public void XZ1(String enterMethod) { }
        @Override public boolean c00(float x, float y) { return false; }
        @Override public void jo2(int jumpType) { jumps++; }
        @Override public Rect nw2() { return new Rect(); }
        @Override public void qg1(CharSequence text, String source) { }
    }

    /** The same assem on a build that dropped the ability. */
    public static final class BareAssem { }

    /** The heart's assem: TikTok's long press like, and the click that toggles. */
    public static final class HeartAssem implements VideoDiggAssemAbility {
        int likes;
        int clicks;

        @Override public Long CI0() { return 0L; }
        @Override public void Dw0(String enterMethod) { clicks++; }
        @Override public void MG0() { likes++; }
        @Override public Rect Oq1() { return new Rect(); }
        @Override public boolean nY2() { return false; }
        @Override public boolean vp0(float x, float y) { return false; }
    }

    public static final class Params {
        public final GestureActionsTest.Clip aweme;
        Params(String id) { aweme = new GestureActionsTest.Clip(id); }
        Params(GestureActionsTest.Clip clip) { aweme = clip; }
    }

    private android.app.Activity activity;
    private FrameLayout root;

    @Before public void setUp() {
        var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible();
        activity = controller.get();
        Utils.setContext(activity);
        // The window in front decides whose feed a gesture belongs to. Resumed again now that
        // Utils follows this test's application, so no earlier test's window counts as in front.
        controller.pause().resume();
        root = new FrameLayout(activity);
        activity.setContentView(root);
        HookStatus.clear();
    }

    @After public void tearDown() {
        GestureActions.forgetDoubleTaps();
        HookStatus.clear();
    }

    private View registeredView(Object owner, String videoId, int[] clicks) {
        View view = new View(activity);
        view.setOnClickListener(v -> clicks[0]++);
        root.addView(view);
        GestureActions.registerCommentView(owner, view);
        GestureActions.bindCommentView(owner, new Params(videoId));
        return view;
    }

    @Test public void thePressGoesThroughTheAbilityAndNotThePlaceholderClick() {
        CommentAssem assem = new CommentAssem();
        int[] clicks = {0};
        registeredView(assem, "one", clicks);

        assertTrue(GestureActions.openComments("one"));

        assertEquals("The ability's press must be what opens the sheet", 1, assem.presses);
        assertEquals("The panel opener with a jump type is not the press", 0, assem.jumps);
        assertEquals("performClick fires a listener that does nothing on the real app", 0, clicks[0]);
        List<String> report = HookStatus.report();
        assertEquals("Expected the family to report as bound, got " + report, 1, report.size());
        assertTrue(report.get(0), report.get(0).startsWith(GestureActions.FAMILY + ": 1 found, 0 missing"));
    }

    @Test public void thePressIsChosenByShapeNotByName() {
        java.lang.reflect.Method press = GestureActions.commentPress(new CommentAssem());

        assertNotNull(press);
        // Six methods on the ability, one of them takes nothing and returns nothing. Its name is
        // an accident of this build and the next build renames it.
        assertEquals("Id0", press.getName());
        assertEquals(0, press.getParameterTypes().length);
        assertEquals(void.class, press.getReturnType());
    }

    @Test public void aBuildWithoutTheAbilityFallsBackToTheClickAndSaysSo() {
        BareAssem assem = new BareAssem();
        int[] clicks = {0};
        registeredView(assem, "two", clicks);

        assertTrue(GestureActions.openComments("two"));

        assertEquals("With no ability the click is all there is", 1, clicks[0]);
        assertNull(GestureActions.commentPress(assem));
        List<String> missing = HookStatus.missing(GestureActions.FAMILY);
        assertEquals("Expected one miss naming the ability, got " + missing, 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains("IVideoCommentAbility"));
    }

    @Test public void aHiddenControlIsStillPressedThroughTheAbility() {
        // Clear display hides the action rail; the assem behind it still works.
        CommentAssem assem = new CommentAssem();
        int[] clicks = {0};
        View view = registeredView(assem, "three", clicks);
        view.setVisibility(View.INVISIBLE);

        assertTrue(GestureActions.openComments("three"));
        assertEquals(1, assem.presses);
        assertEquals(0, clicks[0]);
        assertFalse(HookStatus.anyMissing());
    }

    private static final int PAGE_WIDTH = 200;
    private static final int PAGE_HEIGHT = 300;

    /**
     * TikTok's pager with a cell a page for each video, laid out the way the feed and a detail
     * page lay them out: the cell at the pager's scroll on screen, the others a page away and
     * clipped. Each cell has its rail with the comment button at the bottom right.
     */
    private VerticalViewPager feed(ViewGroup parent, String... ids) {
        VerticalViewPager pager = new VerticalViewPager(parent.getContext());
        parent.addView(pager, new FrameLayout.LayoutParams(PAGE_WIDTH, PAGE_HEIGHT));
        for (int i = 0; i < ids.length; i++) {
            FrameLayout cell = new FrameLayout(parent.getContext());
            FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(PAGE_WIDTH, PAGE_HEIGHT);
            place.topMargin = i * PAGE_HEIGHT;
            pager.addView(cell, place);
            FrameLayout rail = new FrameLayout(parent.getContext());
            cell.addView(rail, new FrameLayout.LayoutParams(40, 120, Gravity.END | Gravity.BOTTOM));
            View button = new View(parent.getContext());
            rail.addView(button, new FrameLayout.LayoutParams(40, 40));
        }
        return pager;
    }

    /** Registers the comment button of the pager's cell at this index, as TikTok's assem does. */
    private static CommentAssem assem(VerticalViewPager pager, int index, String id) {
        return assem(pager, index, new GestureActionsTest.Clip(id));
    }

    private static CommentAssem assem(VerticalViewPager pager, int index, GestureActionsTest.Clip clip) {
        CommentAssem assem = new CommentAssem();
        View button = rail(pager, index).getChildAt(0);
        GestureActions.registerCommentView(assem, button);
        GestureActions.bindCommentView(assem, new Params(clip));
        return assem;
    }

    /** What the player names: the post it last reported progress for. */
    private static void playing(GestureActionsTest.Clip clip) {
        BlockAuthorPatch.setCurrentVideoParams(new Params(clip));
        BlockAuthorPatch.setPlayingAweme(clip.aid);
    }

    private float middle() {
        return activity.getResources().getDisplayMetrics().widthPixels / 2f;
    }

    private static GestureActionsTest.Clip linked(String id) {
        return new GestureActionsTest.Clip(id, "https://www.tiktok.com/@someone/video/" + id, null);
    }

    private String clipboardText() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        return clip == null ? null : String.valueOf(clip.getItemAt(0).getText());
    }

    private static ViewGroup rail(VerticalViewPager pager, int index) {
        return (ViewGroup) ((ViewGroup) pager.getChildAt(index)).getChildAt(0);
    }

    private static void layOut(View anyView) {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = anyView.getRootView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 400, 800);
    }

    @Test public void theVideoOnScreenIsPressedNotTheOneThePlayerStillNames() {
        // #63: right after a swipe the player names the video before until the new one plays,
        // and the press went to that video's button, off screen a page up.
        VerticalViewPager pager = feed(root, "one", "two");
        CommentAssem before = assem(pager, 0, "one");
        CommentAssem onScreen = assem(pager, 1, "two");
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);

        assertTrue(GestureActions.openVisibleComments("one"));
        assertEquals(0, before.presses);
        assertEquals(1, onScreen.presses);

        // And back up, with the player now a video behind the other way.
        pager.scrollTo(0, 0);
        assertTrue(GestureActions.openVisibleComments("two"));
        assertEquals(1, before.presses);
        assertEquals(1, onScreen.presses);
    }

    @Test public void aDoubleTapSetToCommentsGoesByTheScreen() {
        VerticalViewPager pager = feed(root, "one", "two");
        CommentAssem before = assem(pager, 0, "one");
        CommentAssem onScreen = assem(pager, 1, "two");
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);
        Settings.DOUBLE_TAP_ACTION.save("comments");

        assertTrue(GestureActions.onDoubleTap());

        assertEquals(0, before.presses);
        assertEquals(1, onScreen.presses);
    }

    @Test public void aTapThatReachesASecondGuardRunsItsActionOnce() {
        VerticalViewPager pager = feed(root, "one", "two");
        assem(pager, 0, "one");
        CommentAssem onScreen = assem(pager, 1, "two");
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);
        Settings.DOUBLE_TAP_ACTION.save("comments");
        MotionEvent tap = MotionEvent.obtain(1_000L, 1_180L, MotionEvent.ACTION_DOWN, 10f, 10f, 0);

        assertTrue(GestureActions.onDoubleTap(tap));
        // The second guard gets a copy of the same tap and swallows it without a second press.
        assertTrue(GestureActions.onDoubleTap(MotionEvent.obtain(tap)));
        assertEquals(1, onScreen.presses);

        assertTrue(GestureActions.onDoubleTap(MotionEvent.obtain(2_000L, 2_180L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)));
        assertEquals("the next double tap is its own", 2, onScreen.presses);

        // TikTok's own like keeps its answer at every guard.
        Settings.DOUBLE_TAP_ACTION.save("default");
        MotionEvent liked = MotionEvent.obtain(3_000L, 3_180L, MotionEvent.ACTION_DOWN, 10f, 10f, 0);
        assertFalse(GestureActions.onDoubleTap(liked));
        assertFalse(GestureActions.onDoubleTap(MotionEvent.obtain(liked)));
        assertEquals(2, onScreen.presses);
    }

    @Test public void aClearedRailStillCountsByTheCellUnderIt() {
        // Clear display hides the rail of the video being watched. This one was hidden before it
        // was ever laid out, so the button has no size at all; the rail a page up is showing.
        VerticalViewPager pager = feed(root, "one", "two");
        CommentAssem before = assem(pager, 0, "one");
        CommentAssem onScreen = assem(pager, 1, "two");
        rail(pager, 1).setVisibility(View.GONE);
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);

        assertTrue(GestureActions.openVisibleComments("one"));
        assertEquals(0, before.presses);
        assertEquals(1, onScreen.presses);
    }

    @Test public void midSwipeTheCellShowingMoreWins() {
        VerticalViewPager pager = feed(root, "one", "two");
        CommentAssem leaving = assem(pager, 0, "one");
        CommentAssem arriving = assem(pager, 1, "two");
        layOut(root);

        pager.scrollTo(0, PAGE_HEIGHT * 2 / 3);
        assertTrue(GestureActions.openVisibleComments("one"));
        assertEquals(0, leaving.presses);
        assertEquals(1, arriving.presses);

        pager.scrollTo(0, PAGE_HEIGHT / 3);
        assertTrue(GestureActions.openVisibleComments("two"));
        assertEquals(1, leaving.presses);
        assertEquals(1, arriving.presses);
    }

    @Test public void aFeedPutAwayBehindAnotherPageDoesNotCount() {
        // Another tab's page over the feed: the feed's cells keep their last layout.
        FrameLayout page = new FrameLayout(activity);
        root.addView(page, new FrameLayout.LayoutParams(PAGE_WIDTH, PAGE_HEIGHT));
        CommentAssem away = assem(feed(page, "one"), 0, "one");
        CommentAssem here = assem(feed(root, "two"), 0, "two");
        layOut(root);
        page.setVisibility(View.GONE);

        assertTrue(GestureActions.openVisibleComments("one"));
        assertEquals(0, away.presses);
        assertEquals(1, here.presses);
    }

    @Test public void twoCellsEquallyOnScreenGoToThePlayingVideo() {
        CommentAssem one = assem(feed(root, "one"), 0, "one");
        CommentAssem two = assem(feed(root, "two"), 0, "two");
        layOut(root);

        assertTrue(GestureActions.openVisibleComments("two"));
        assertEquals(0, one.presses);
        assertEquals(1, two.presses);
        assertTrue(GestureActions.openVisibleComments("one"));
        assertEquals(1, one.presses);
        assertEquals(1, two.presses);
    }

    @Test public void theWindowInFrontWinsOverAFeedLeftShowingBehindIt() {
        // A video opened from a profile is an activity of its own over the feed. If the feed's
        // window stays up behind it, its cell is as much on screen as the one being watched.
        Utils.setActivity(activity);
        CommentAssem behind = assem(feed(root, "one"), 0, "one");
        layOut(root);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity detail = controller.get();
            FrameLayout detailRoot = new FrameLayout(detail);
            detail.setContentView(detailRoot);
            CommentAssem watched = assem(feed(detailRoot, "two"), 0, "two");
            layOut(detailRoot);
            assertSame("The test needs the detail page in front", detail, Utils.getVisibleActivity());

            assertTrue(GestureActions.openVisibleComments("one"));
            assertEquals(0, behind.presses);
            assertEquals(1, watched.presses);
        }
    }

    @Test public void withNothingOnScreenThePlayingVideosButtonIsStillPressed() {
        // No pager above it and hidden: nothing to judge by, so the id decides as it always did.
        CommentAssem assem = new CommentAssem();
        View view = registeredView(assem, "three", new int[1]);
        view.setVisibility(View.INVISIBLE);
        layOut(root);

        assertTrue(GestureActions.openVisibleComments("three"));
        assertEquals(1, assem.presses);
        assertFalse(GestureActions.openVisibleComments("four"));
        assertEquals(1, assem.presses);
    }

    @Test public void aCellWithoutAButtonOnScreenDoesNotFallBackToTheVideoBefore() {
        // A LIVE or an ad on screen has no comment button, and the player still names the video
        // before it. That video's button is a page up, and pressing it was #63 all over again.
        VerticalViewPager pager = feed(root, "one", "live");
        CommentAssem before = assem(pager, 0, "one");
        // Nor to a button off the pager that's bound to it, which only counts when there's no
        // feed on screen to go by.
        CommentAssem offThePager = new CommentAssem();
        registeredView(offThePager, "one", new int[1]).setVisibility(View.INVISIBLE);
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);

        assertFalse(GestureActions.openVisibleComments("one"));
        assertEquals(0, before.presses);
        assertEquals(0, offThePager.presses);
    }

    @Test public void aFeedInFrontKeepsThePressFromAFeedLeftShowingBehindIt() {
        // A LIVE or an ad in a video opened from a profile has no comment button. The main
        // feed's cell behind it has one, and as far as its own window knows it's on screen; its
        // comments opened out of sight.
        CommentAssem behind = assem(feed(root, "one"), 0, "one");
        layOut(root);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity detail = controller.get();
            FrameLayout detailRoot = new FrameLayout(detail);
            detail.setContentView(detailRoot);
            feed(detailRoot, "live");
            layOut(detailRoot);
            assertSame("The test needs the detail page in front", detail, Utils.getVisibleActivity());

            assertFalse(GestureActions.openVisibleComments("one"));
            assertEquals(0, behind.presses);
        }
    }

    @Test public void aLongPressSetToCommentsGoesByTheScreen() {
        VerticalViewPager pager = feed(root, "one", "two");
        CommentAssem before = assem(pager, 0, "one");
        CommentAssem onScreen = assem(pager, 1, "two");
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);
        playing(new GestureActionsTest.Clip("one"));
        Settings.LONG_PRESS_ACTION.save("comments");
        try {
            assertTrue(GestureActions.onLongPress(middle()));
        } finally {
            Settings.LONG_PRESS_ACTION.resetToDefault();
        }
        assertEquals(0, before.presses);
        assertEquals(1, onScreen.presses);
    }

    @Test public void aSwipeSetToCommentsGoesByTheScreen() {
        // The left swipe's own tests stand in their own opener; this is the one it ships with.
        VerticalViewPager pager = feed(root, "one", "two");
        CommentAssem before = assem(pager, 0, "one");
        CommentAssem onScreen = assem(pager, 1, "two");
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);
        playing(new GestureActionsTest.Clip("one"));

        GestureActions.swipeCommentsOpener.run();

        assertEquals(0, before.presses);
        assertEquals(1, onScreen.presses);
    }

    @Test public void aLinkCopiedRightAfterASwipeIsTheVideoOnScreens() {
        GestureActionsTest.Clip one = new GestureActionsTest.Clip("one",
                "https://www.tiktok.com/@someone/video/one", new GestureActionsTest.SoundStub("1111"));
        GestureActionsTest.Clip two = new GestureActionsTest.Clip("two",
                "https://www.tiktok.com/@someone/video/two", new GestureActionsTest.SoundStub("2222"));
        VerticalViewPager pager = feed(root, "one", "two", "live");
        assem(pager, 0, one);
        assem(pager, 1, two);
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);
        playing(one);
        Settings.LONG_PRESS_ACTION.save("copy_link");
        Settings.CUSTOM_SHARE_DOMAIN.save("");
        try {
            assertTrue(GestureActions.onLongPress(middle()));
            assertEquals("https://www.tiktok.com/@someone/video/two", clipboardText());
            Settings.LONG_PRESS_ACTION.save("copy_sound_link");
            assertTrue(GestureActions.onLongPress(middle()));
            assertEquals("https://www.tiktok.com/music/x-2222", clipboardText());

            // A LIVE has no post here, and the player still names the video before it.
            Settings.LONG_PRESS_ACTION.save("copy_link");
            pager.scrollTo(0, PAGE_HEIGHT * 2);
            playing(two);
            ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("before", "before"));
            ShadowToast.reset();
            assertTrue(GestureActions.onLongPress(middle()));
            assertEquals("before", clipboardText());
            assertEquals(L10n.t("This video has no link to copy"), ShadowToast.getTextOfLatestToast());
        } finally {
            Settings.LONG_PRESS_ACTION.resetToDefault();
            Settings.CUSTOM_SHARE_DOMAIN.resetToDefault();
        }
    }

    @Test public void offTheFeedOrWithNoButtonRegisteredTheLinkIsThePlayingPosts() {
        // With no feed in front there's nothing on screen to go by, and a feed with no button
        // registered at all is a build whose button moved. Either way it's the playing post.
        CommentAssem elsewhere = new CommentAssem();
        View button = registeredView(elsewhere, "shown", new int[1]);
        GestureActions.bindCommentView(elsewhere, new Params(linked("shown")));
        layOut(root);
        playing(linked("playing"));
        Settings.LONG_PRESS_ACTION.save("copy_link");
        Settings.CUSTOM_SHARE_DOMAIN.save("");
        try {
            assertTrue(button.isShown());
            assertTrue(GestureActions.onLongPress(middle()));
            assertEquals("https://www.tiktok.com/@someone/video/playing", clipboardText());

            root.removeView(button);
            feed(root, "unregistered");
            layOut(root);
            ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("before", "before"));
            assertTrue(GestureActions.onLongPress(middle()));
            assertEquals("https://www.tiktok.com/@someone/video/playing", clipboardText());
        } finally {
            Settings.LONG_PRESS_ACTION.resetToDefault();
            Settings.CUSTOM_SHARE_DOMAIN.resetToDefault();
        }
    }

    @Test public void anEdgePressRightAfterASwipeLeavesTheVideoBeforeAlone() {
        // The new video hasn't reported progress yet; the one before has, a page up. Moving it
        // would be a seek nobody sees.
        VerticalViewPager pager = feed(root, "one", "two");
        assem(pager, 0, "one");
        assem(pager, 1, "two");
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);
        GestureActionsTest.FakeController before = new GestureActionsTest.FakeController();
        FeedSeek.recordProgress(before, "one", 10_000L, before.player.durationMs);
        playing(new GestureActionsTest.Clip("one"));
        Settings.EDGE_SEEK.save(true);
        Settings.EDGE_SEEK_SECONDS.save(5);
        try {
            ShadowToast.reset();
            assertTrue(GestureActions.onLongPress(activity.getResources().getDisplayMetrics().widthPixels * 0.9f));
            assertTrue("the video before was moved", Float.isNaN(before.player.sought));
            assertEquals(L10n.t("Nothing is playing to seek"), ShadowToast.getTextOfLatestToast());
        } finally {
            Settings.EDGE_SEEK.resetToDefault();
            Settings.EDGE_SEEK_SECONDS.resetToDefault();
        }
    }

    /**
     * Puts a heart in the rail of the pager's cell at this index, in a slot of its own as TikTok
     * lays it out, and registers it for this owner as the heart's assem does.
     */
    private static void heart(VerticalViewPager pager, int index, Object owner) {
        ViewGroup rail = rail(pager, index);
        FrameLayout slot = new FrameLayout(rail.getContext());
        rail.addView(slot, new FrameLayout.LayoutParams(40, 40, Gravity.BOTTOM));
        View view = new View(rail.getContext());
        slot.addView(view, new FrameLayout.LayoutParams(40, 40));
        GestureActions.registerLikeView(owner, view);
    }

    private static HeartAssem heart(VerticalViewPager pager, int index) {
        HeartAssem heart = new HeartAssem();
        heart(pager, index, heart);
        return heart;
    }

    private static boolean likeBound() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(GestureActions.LIKE_FAMILY + ": 1 found, 0 missing")) return true;
        }
        return false;
    }

    @Test public void aLongPressSetToLikeLikesTheVideoOnScreenThroughItsHeart() {
        // #136: the like goes to the video on screen, as the comments do (#63), and not to the
        // one the player still names a page up. It's TikTok's own long press like, never the
        // heart's click, which would take the like back off a video that's already liked.
        VerticalViewPager pager = feed(root, "one", "two");
        assem(pager, 0, "one");
        assem(pager, 1, "two");
        HeartAssem before = heart(pager, 0);
        HeartAssem onScreen = heart(pager, 1);
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);
        playing(new GestureActionsTest.Clip("one"));
        Settings.LONG_PRESS_ACTION.save("like");
        ShadowToast.reset();
        try {
            assertTrue("the press is the like's, not TikTok's", GestureActions.onLongPress(middle()));
        } finally {
            Settings.LONG_PRESS_ACTION.resetToDefault();
        }
        assertEquals(0, before.likes);
        assertEquals(1, onScreen.likes);
        assertEquals("the heart's click is a toggle", 0, onScreen.clicks);
        assertEquals(0, ShadowToast.shownToastCount());
        assertTrue("Expected the like to report as bound, got " + HookStatus.report(), likeBound());
    }

    @Test public void theLikeIsChosenByShapeAndIsNotTheHeartsClick() {
        java.lang.reflect.Method like = GestureActions.likePress(new HeartAssem());

        assertNotNull(like);
        // Six methods on the ability. The click takes a String and the like takes nothing; the
        // names are this build's and the next build renames them.
        assertEquals("MG0", like.getName());
        assertEquals(0, like.getParameterTypes().length);
        assertEquals(void.class, like.getReturnType());
    }

    @Test public void aBuildWithoutTheHeartsAbilityLikesNothingAndSaysSo() {
        VerticalViewPager pager = feed(root, "one");
        assem(pager, 0, "one");
        BareAssem bare = new BareAssem();
        heart(pager, 0, bare);
        layOut(root);
        playing(new GestureActionsTest.Clip("one"));
        Settings.LONG_PRESS_ACTION.save("like");
        ShadowToast.reset();
        try {
            assertTrue(GestureActions.onLongPress(middle()));
        } finally {
            Settings.LONG_PRESS_ACTION.resetToDefault();
        }
        assertEquals(L10n.t("Likes aren't available for this video"), ShadowToast.getTextOfLatestToast());
        assertNull(GestureActions.likePress(bare));
        List<String> missing = HookStatus.missing(GestureActions.LIKE_FAMILY);
        assertEquals("Expected one miss naming the ability, got " + missing, 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains("VideoDiggAssemAbility"));
    }

    @Test public void aCellWithoutAHeartDoesNotBorrowTheNextCells() {
        VerticalViewPager pager = feed(root, "one", "two");
        assem(pager, 0, "one");
        assem(pager, 1, "two");
        HeartAssem next = heart(pager, 1);
        layOut(root);

        assertFalse(GestureActions.likeVisibleVideo("one"));
        assertEquals(0, next.likes);
    }

    /** A list of TikTok's that isn't its feed pager, the way a feed on another surface might lay out its cells. */
    public static final class RecyclerView extends FrameLayout {
        public RecyclerView(Context context) { super(context); }
    }

    @Test public void aCellWithoutAHeartInAnotherListDoesNotBorrowItsNeighboursEither() {
        RecyclerView list = new RecyclerView(activity);
        root.addView(list);
        FrameLayout bare = new FrameLayout(activity);
        FrameLayout neighbour = new FrameLayout(activity);
        list.addView(bare);
        list.addView(neighbour);
        View button = new View(activity);
        bare.addView(button);
        View heartView = new View(activity);
        neighbour.addView(heartView);
        HeartAssem heart = new HeartAssem();
        GestureActions.registerLikeView(heart, heartView);

        assertNull(GestureActions.heartBeside(button));
        View besideTheHeart = new View(activity);
        neighbour.addView(besideTheHeart);
        assertSame(heart, GestureActions.heartBeside(besideTheHeart));
    }

    @Test public void aLiveOrAnAdOnScreenLikesNothing() {
        // No comment button, so no cell to find a heart in, and the video before it, which the
        // player still names, isn't the one being watched.
        VerticalViewPager pager = feed(root, "one", "live");
        assem(pager, 0, "one");
        HeartAssem before = heart(pager, 0);
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);
        playing(new GestureActionsTest.Clip("one"));
        Settings.LONG_PRESS_ACTION.save("like");
        ShadowToast.reset();
        try {
            assertTrue(GestureActions.onLongPress(middle()));
        } finally {
            Settings.LONG_PRESS_ACTION.resetToDefault();
        }
        assertEquals(0, before.likes);
        assertEquals(L10n.t("Likes aren't available for this video"), ShadowToast.getTextOfLatestToast());
    }

    @Test public void aClearedRailsHeartStillLikes() {
        // Clear display hides the rail, heart and all. Its assem still likes.
        VerticalViewPager pager = feed(root, "one", "two");
        assem(pager, 0, "one");
        assem(pager, 1, "two");
        HeartAssem before = heart(pager, 0);
        HeartAssem onScreen = heart(pager, 1);
        rail(pager, 1).setVisibility(View.GONE);
        layOut(root);
        pager.scrollTo(0, PAGE_HEIGHT);

        assertTrue(GestureActions.likeVisibleVideo("one"));
        assertEquals(0, before.likes);
        assertEquals(1, onScreen.likes);
    }

    @Test public void offThePagerTheHeartBesideThePlayingVideosButtonLikes() {
        // Nothing on screen to judge by, so the button bound to the playing id stands in, as it
        // does for the comments, and the heart is the one beside it.
        FrameLayout panel = new FrameLayout(activity);
        root.addView(panel);
        CommentAssem comments = new CommentAssem();
        View button = new View(activity);
        panel.addView(button);
        GestureActions.registerCommentView(comments, button);
        GestureActions.bindCommentView(comments, new Params("three"));
        button.setVisibility(View.INVISIBLE);
        HeartAssem heart = new HeartAssem();
        View heartView = new View(activity);
        panel.addView(heartView);
        GestureActions.registerLikeView(heart, heartView);
        layOut(root);

        assertTrue(GestureActions.likeVisibleVideo("three"));
        assertEquals(1, heart.likes);
        assertFalse(GestureActions.likeVisibleVideo("four"));
        assertEquals(1, heart.likes);
        assertEquals("the like isn't the comments", 0, comments.presses);
    }
}

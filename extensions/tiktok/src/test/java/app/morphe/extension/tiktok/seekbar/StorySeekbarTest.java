/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import com.bytedance.tux.widget.RadiusLayout;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.VideoItemParams;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Seek bar on video stories (#72): the line goes into the story cell's rounded frame on the first
 * tick for that cell's story, follows the ticks, and leaves on off, on pause, on a new story and
 * when the player moves on. A drag seeks through the player manager only while the cell, the
 * controller's last tick and its current id all name the same story, and a tap or a swipe up or
 * down still reaches TikTok's own touch view.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class StorySeekbarTest {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;

    private Context context;
    private final Object component = new Object();
    private Root root;
    private RadiusLayout frame;
    private TouchView touch;
    private Controller controller;
    private VideoItemParams params;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        reset();
        controller = new Controller();
    }

    @After
    public void tearDown() {
        reset();
    }

    private static void reset() {
        Settings.STORY_SEEKBAR.save(false);
        PausedProcess.set(false);
        StorySeekbar.reset();
        HookStatus.clear();
    }

    /** A story as the cell's params carry it: a video, or a photo story with images. */
    static final class Story extends Aweme {
        private final String aid;
        private final List<Object> images;

        Story(String aid, List<Object> images) {
            this.aid = aid;
            this.images = images;
        }

        static Story video(String aid) {
            return new Story(aid, Collections.emptyList());
        }

        static Story photo(String aid) {
            return new Story(aid, Collections.singletonList(new Object()));
        }

        @Override public String getAid() { return aid; }
        @SuppressWarnings("rawtypes")
        @Override public List getImageInfos() { return images; }
    }

    /** PlayerController as the seek reads it: its current id and its player manager. */
    public static final class Controller {
        public String mCurrentAid;
        final Manager manager = new Manager();

        public Manager getPlayerManager() {
            return manager;
        }
    }

    /** The player manager: seek takes a percent from 0 to 100. */
    public static final class Manager {
        float seeked = -1f;
        int seeks;

        public void seek(float percent) {
            seeked = percent;
            seeks++;
        }
    }

    /** TikTok's story touch view, which takes every touch that reaches it. */
    static final class TouchView extends View {
        final List<Integer> actions = new ArrayList<>();
        float lastY;

        TouchView(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            actions.add(event.getActionMasked());
            lastY = event.getY();
            return true;
        }
    }

    /** The cell's root, which keeps the last answer about letting its parents take the touch. */
    static final class Root extends FrameLayout {
        Boolean disallowed;

        Root(Context context) {
            super(context);
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallow) {
            disallowed = disallow;
            super.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    /** The story layout as 47.1.4 builds it: a root holding the rounded frame, TikTok's touch view inside. */
    private void buildCell() {
        root = new Root(context);
        frame = new RadiusLayout(context);
        touch = new TouchView(context);
        frame.addView(touch, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(new View(context), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        StorySeekbar.onCellView(component, root);
    }

    private void bind(Aweme story) {
        // The cell keeps its params, as TikTok's does; the hook only holds them weakly.
        params = new VideoItemParams(story);
        StorySeekbar.onCellBind(component, params);
    }

    private void tick(Object player, String aid, long positionMs, long durationMs) {
        StorySeekbar.onProgress(player, aid, positionMs, durationMs);
    }

    private StorySeekbarView line() {
        StorySeekbarView found = null;
        for (int i = 0; i < frame.getChildCount(); i++) {
            if (frame.getChildAt(i) instanceof StorySeekbarView) {
                assertNull("two lines in one frame", found);
                found = (StorySeekbarView) frame.getChildAt(i);
            }
        }
        return found;
    }

    private void layOut() {
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, HEIGHT);
    }

    /** A video story on screen with its line, played four seconds into twenty. */
    private StorySeekbarView playing() {
        Settings.STORY_SEEKBAR.save(true);
        buildCell();
        bind(Story.video("s1"));
        controller.mCurrentAid = "s1";
        tick(controller, "s1", 4_000L, 20_000L);
        layOut();
        StorySeekbarView line = line();
        assertNotNull(line);
        return line;
    }

    private boolean touch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            return root.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** The x on screen that is this fraction along the line. */
    private float along(StorySeekbarView line, float fraction) {
        float inset = StorySeekbarView.INSET_DP * line.getResources().getDisplayMetrics().density;
        return inset + (WIDTH - 2f * inset) * fraction;
    }

    private float onTheLine(StorySeekbarView line) {
        return line.getTop() + line.getHeight() / 2f;
    }

    @Test
    public void offTheHooksLeaveTheStoryAsTikTokBuiltIt() {
        buildCell();
        bind(Story.video("s1"));
        tick(controller, "s1", 1_000L, 10_000L);

        assertEquals(1, frame.getChildCount());
        assertTrue(HookStatus.missing(StorySeekbar.FAMILY).isEmpty());
    }

    @Test
    public void theLineGoesOnTopAtTheBottomOfTheStorysFrameAndFollowsTheTicks() {
        Settings.STORY_SEEKBAR.save(true);
        buildCell();
        bind(Story.video("s1"));
        tick(controller, "s1", 2_500L, 10_000L);

        StorySeekbarView line = line();
        assertNotNull(line);
        assertSame(frame, line.getParent());
        assertEquals(frame.getChildCount() - 1, frame.indexOfChild(line));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) line.getLayoutParams();
        assertEquals(Gravity.BOTTOM, params.gravity);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width);
        assertEquals(0.25f, line.fraction(), 0.0001f);

        tick(controller, "s1", 5_000L, 10_000L);
        assertSame(line, line());
        assertEquals(0.5f, line.fraction(), 0.0001f);
        assertTrue(HookStatus.missing(StorySeekbar.FAMILY).isEmpty());
    }

    @Test
    public void pauseAndOffTakeTheLineOutAndOnPutsItBack() {
        StorySeekbarView line = playing();

        PausedProcess.set(true);
        tick(controller, "s1", 5_000L, 20_000L);
        assertNull(line());
        assertEquals(1, frame.getChildCount());

        PausedProcess.set(false);
        tick(controller, "s1", 6_000L, 20_000L);
        assertNotNull(line());

        Settings.STORY_SEEKBAR.save(false);
        tick(controller, "s1", 7_000L, 20_000L);
        assertNull(line());
        assertFalse(line.seekTo(50f));
        assertEquals(0, controller.manager.seeks);
    }

    @Test
    public void turnedOffWithNoTickTheLineLeavesOnItsNextDrawAndTikTokKeepsTheTouch() {
        StorySeekbarView line = playing();
        Settings.STORY_SEEKBAR.save(false);

        touch(MotionEvent.ACTION_DOWN, along(line, 0.5f), onTheLine(line));
        assertEquals(Collections.singletonList(MotionEvent.ACTION_DOWN), touch.actions);

        line.draw(new Canvas(Bitmap.createBitmap(WIDTH, 200, Bitmap.Config.ARGB_8888)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(line());
    }

    @Test
    public void aPhotoStoryOrAStoryWithNoLengthGetsNoLine() {
        Settings.STORY_SEEKBAR.save(true);
        buildCell();
        bind(Story.photo("p1"));
        tick(controller, "p1", 1_000L, 5_000L);
        assertNull(line());

        bind(Story.video("v1"));
        tick(controller, "v1", 0L, 0L);
        assertNull(line());
        tick(controller, "v1", 1_000L, 5_000L);
        assertNotNull(line());
    }

    @Test
    public void aNewStoryOrAPlayerMovingOnTakesTheLineOutAndAnotherPlayerLeavesIt() {
        Settings.STORY_SEEKBAR.save(true);
        buildCell();
        bind(Story.video("s1"));
        tick(controller, "s1", 1_000L, 10_000L);
        StorySeekbarView first = line();
        assertNotNull(first);

        // A feed video behind the story, on its own player, is no reason to take it out.
        tick(new Controller(), "feed", 1_000L, 30_000L);
        assertSame(first, line());

        // The story's own player moved to a story this cell doesn't show.
        tick(controller, "s2", 0L, 10_000L);
        assertNull(line());
        tick(controller, "s1", 2_000L, 10_000L);
        assertNotNull(line());

        // The same story bound again keeps its line; another story takes it out until its own tick.
        StorySeekbarView again = line();
        StorySeekbar.onCellBind(component, new VideoItemParams(Story.video("s1")));
        assertSame(again, line());
        bind(Story.video("s3"));
        assertNull(line());
        tick(controller, "s3", 500L, 8_000L);
        assertEquals("s3", line().aid);
    }

    @Test
    public void aSidewaysDragSeeksThroughThePlayerManagerAndCancelsTikToksTouch() {
        StorySeekbarView line = playing();
        float y = onTheLine(line);

        assertTrue(touch(MotionEvent.ACTION_DOWN, along(line, 0.5f), y));
        assertEquals(Boolean.TRUE, root.disallowed);
        touch(MotionEvent.ACTION_MOVE, along(line, 0.6f), y);
        assertTrue(line.isDragging());
        line.draw(new Canvas(Bitmap.createBitmap(WIDTH, line.getHeight(), Bitmap.Config.ARGB_8888)));
        touch(MotionEvent.ACTION_MOVE, along(line, 0.7f), y);
        touch(MotionEvent.ACTION_UP, along(line, 0.75f), y);

        assertEquals(1, controller.manager.seeks);
        assertEquals(75f, controller.manager.seeked, 0.01f);
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL),
                touch.actions);
        assertFalse(line.isDragging());
        // The line shows where the seek went until the player reports from there.
        assertEquals(0.75f, line.fraction(), 0.001f);
    }

    @Test
    public void aTapReachesTikToksTouchViewAndSeeksNothing() {
        StorySeekbarView line = playing();
        float y = onTheLine(line);

        touch(MotionEvent.ACTION_DOWN, 300f, y);
        touch(MotionEvent.ACTION_UP, 300f, y);

        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), touch.actions);
        // In the touch view's own coordinates, as the frame would have handed it over.
        assertEquals(y, touch.lastY, 0.01f);
        assertEquals(0, controller.manager.seeks);
    }

    @Test
    public void aSwipeUpOrDownIsHandedBackToTheScreensAroundTheStory() {
        StorySeekbarView line = playing();
        float y = onTheLine(line);

        touch(MotionEvent.ACTION_DOWN, 500f, y);
        assertEquals(Boolean.TRUE, root.disallowed);
        touch(MotionEvent.ACTION_MOVE, 502f, y - 100f);
        assertEquals(Boolean.FALSE, root.disallowed);
        touch(MotionEvent.ACTION_MOVE, 520f, y - 200f);
        touch(MotionEvent.ACTION_UP, 520f, y - 200f);

        assertFalse(line.isDragging());
        assertEquals(0, controller.manager.seeks);
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), touch.actions);
    }

    @Test
    public void aSeekIsRefusedOnceTheCellOrThePlayerIsOnAnotherStory() {
        StorySeekbarView line = playing();

        controller.mCurrentAid = "s9";
        assertFalse(line.seekTo(50f));
        controller.mCurrentAid = "s1";
        assertTrue(line.seekTo(50f));
        assertEquals(50f, controller.manager.seeked, 0.001f);

        // A controller that has no current id yet is held to its last tick alone.
        controller.mCurrentAid = null;
        assertTrue(line.seekTo(10f));

        // The player's last tick was another story.
        tick(controller, "s2", 0L, 10_000L);
        assertFalse(line.seekTo(30f));
        tick(controller, "s1", 1_000L, 20_000L);
        StorySeekbarView back = line();
        assertTrue(back.seekTo(30f));

        // The cell shows another story now.
        bind(Story.video("s3"));
        assertFalse(back.seekTo(40f));
        assertEquals(3, controller.manager.seeks);
        assertEquals(30f, controller.manager.seeked, 0.001f);
    }

    @Test
    public void theSeekStaysInsideTheStoryAndShortOfItsEnd() {
        assertEquals(StorySeekbar.LAST_PERCENT, StorySeekbar.seekPercent(150f), 0f);
        assertEquals(StorySeekbar.LAST_PERCENT, StorySeekbar.seekPercent(100f), 0f);
        assertEquals(0f, StorySeekbar.seekPercent(-3f), 0f);
        assertEquals(0f, StorySeekbar.seekPercent(Float.NaN), 0f);
        assertEquals(42.5f, StorySeekbar.seekPercent(42.5f), 0f);

        StorySeekbarView line = playing();
        assertTrue(line.seekTo(140f));
        assertEquals(StorySeekbar.LAST_PERCENT, controller.manager.seeked, 0f);
    }

    @Test
    public void aScreenReaderHearsASeekBarAndCanMoveIt() {
        StorySeekbarView line = playing();
        // A view outside a window gets an empty node, so this one goes in a window as on the phone.
        Robolectric.buildActivity(Activity.class).setup().get().setContentView(root);

        AccessibilityNodeInfo node = line.createAccessibilityNodeInfo();
        assertEquals(SeekBar.class.getName(), node.getClassName().toString());
        assertEquals("Story position", node.getContentDescription().toString());
        assertEquals(20f, node.getRangeInfo().getMax(), 0f);
        assertEquals(4f, node.getRangeInfo().getCurrent(), 0f);
        List<Integer> actions = new ArrayList<>();
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) actions.add(action.getId());
        assertTrue(actions.contains(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
        assertTrue(actions.contains(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD));
        assertTrue(actions.contains(android.R.id.accessibilityActionSetProgress));
        node.recycle();

        // Five seconds on from four seconds into twenty.
        assertTrue(line.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
        assertEquals(45f, controller.manager.seeked, 0.01f);

        Bundle arguments = new Bundle();
        arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 10f);
        assertTrue(line.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, arguments));
        assertEquals(50f, controller.manager.seeked, 0.01f);
    }

    @Test
    public void aCellWithoutTheVideoFrameIsReportedAndLeftAlone() {
        Settings.STORY_SEEKBAR.save(true);
        root = new Root(context);
        FrameLayout plain = new FrameLayout(context);
        root.addView(plain);
        StorySeekbar.onCellView(component, root);
        bind(Story.video("s1"));
        tick(controller, "s1", 1_000L, 10_000L);
        tick(controller, "s1", 2_000L, 10_000L);

        assertEquals(0, plain.getChildCount());
        List<String> missing = HookStatus.missing(StorySeekbar.FAMILY);
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("RadiusLayout"));
    }

    @Test
    public void twoFramesAtTheSameDepthIsALayoutThisWasNotWrittenFor() {
        FrameLayout twin = new FrameLayout(context);
        twin.addView(new RadiusLayout(context));
        twin.addView(new RadiusLayout(context));
        assertNull(StorySeekbar.videoFrame(twin));

        FrameLayout nested = new FrameLayout(context);
        RadiusLayout near = new RadiusLayout(context);
        FrameLayout deeper = new FrameLayout(context);
        deeper.addView(new RadiusLayout(context));
        nested.addView(near);
        nested.addView(deeper);
        assertSame(near, StorySeekbar.videoFrame(nested));
    }

    /**
     * A story viewer closed while its line is on. TikTok's views lead back to their component
     * (a tag stands in for that here), and the line sits in those views, so a line the cell held
     * strongly kept the component that keys it, and with it the views and their Activity.
     */
    @Test
    public void aClosedStoryViewerIsLetGoWithItsLine() throws InterruptedException {
        Settings.STORY_SEEKBAR.save(true);
        WeakReference<Object> closed = openAViewerWithALine();

        for (int attempt = 0; attempt < 20 && closed.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull("the closed viewer is still held through its line", closed.get());
    }

    private WeakReference<Object> openAViewerWithALine() {
        Object viewer = new Object();
        Root viewerRoot = new Root(context);
        RadiusLayout viewerFrame = new RadiusLayout(context);
        viewerRoot.addView(viewerFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        viewerRoot.setTag(viewer);
        VideoItemParams shown = new VideoItemParams(Story.video("s9"));
        StorySeekbar.onCellView(viewer, viewerRoot);
        StorySeekbar.onCellBind(viewer, shown);
        tick(controller, "s9", 1_000L, 10_000L);
        assertEquals("the line went in", 1, viewerFrame.getChildCount());
        assertTrue(viewerFrame.getChildAt(0) instanceof StorySeekbarView);
        return new WeakReference<>(viewer);
    }
}

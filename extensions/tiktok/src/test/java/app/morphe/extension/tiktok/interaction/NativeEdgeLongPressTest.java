package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorPatch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
import org.robolectric.shadows.ShadowToast;

/** The native 300 ms edge timer competes with the feed's Android long-press detector. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29, qualifiers = "en")
public class NativeEdgeLongPressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final List<NativeCell> cells = new ArrayList<>();
    private ActivityController<Activity> activityController;
    private Activity activity;
    private boolean previousEdge, previousPatch;
    private int previousSeconds;
    private String previousAction;
    private final PlayerController player = new PlayerController();

    @Before public void setUp() {
        previousEdge = Settings.EDGE_SEEK.get();
        previousSeconds = Settings.EDGE_SEEK_SECONDS.get();
        previousAction = Settings.LONG_PRESS_ACTION.get();
        previousPatch = SettingsStatus.longPressEnabled;
        Settings.EDGE_SEEK.save(false);
        Settings.EDGE_SEEK_SECONDS.save(5);
        Settings.LONG_PRESS_ACTION.save("default");
        SettingsStatus.longPressEnabled = true;
        activityController = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = activityController.get();
        Utils.setContext(activity);
        BlockAuthorPatch.setCurrentVideoParams(new VideoItemParams());
        BlockAuthorPatch.setPlayingAweme("native-edge-current");
        FeedSeek.recordProgress(player, "native-edge-current", 10_000, player.manager.durationMs);
    }

    @After public void tearDown() {
        for (NativeCell cell : cells) {
            cell.handler.removeCallbacks(cell.nativeTimer);
            cell.dispatch(MotionEvent.ACTION_CANCEL);
        }
        activityController.close();
        Settings.EDGE_SEEK.save(previousEdge);
        Settings.EDGE_SEEK_SECONDS.save(previousSeconds);
        Settings.LONG_PRESS_ACTION.save(previousAction);
        SettingsStatus.longPressEnabled = previousPatch;
    }

    @Test public void leftAndRightOwnedEdgesReachTheLegacySeekWithoutStartingNativeSpeedup() {
        Settings.EDGE_SEEK.save(true);
        for (float fraction : new float[]{0.1f, 0.9f}) {
            FeedSeek.recordProgress(player, "native-edge-current", 10_000, player.manager.durationMs);
            player.manager.sought = Float.NaN;
            NativeCell cell = cell(fraction);
            cell.begin();
            atNativeDeadline(cell, false);
            assertTrue("seek ran before the legacy long-press deadline", Float.isNaN(player.manager.sought));
            atLegacyDeadline(cell);
            assertEquals(1, cell.handled);
            assertEquals(fraction < 0.5f ? 5_000f : 15_000f, player.manager.sought, 1f);
            assertEquals(0, cell.nativeLongPresses);
            cell.dispatch(MotionEvent.ACTION_UP);
            assertEquals("UP must not restore a speed that never changed", 0, cell.nativeReleases);
        }
    }

    @Test public void recognizedRemapsReachTheirActualActionsFromBothEdges() {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        String[] actions = {"nothing", "comments", "original_sound", "copy_link", "copy_sound_link", "youtube_music"};
        for (String action : actions) for (float fraction : new float[]{0.1f, 0.9f}) {
            Settings.LONG_PRESS_ACTION.save(action);
            NativeCell cell = cell(fraction);
            clipboard.setPrimaryClip(ClipData.newPlainText("before", "before"));
            ShadowToast.reset();
            cell.begin();
            atNativeDeadline(cell, false);
            atLegacyDeadline(cell);
            assertEquals(action, 1, cell.handled);
            assertEquals(action, 0, cell.nativeLongPresses);
            if ("comments".equals(action)) assertEquals(1, cell.commentClicks);
            if ("original_sound".equals(action)) {
                assertEquals("This video has no original sound to save", ShadowToast.getTextOfLatestToast());
            }
            if ("copy_link".equals(action)) {
                assertEquals("https://www.tiktok.com/@nasa/video/native-edge-current",
                        String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
            }
            if ("copy_sound_link".equals(action)) {
                assertEquals("https://www.tiktok.com/music/x-73123456789",
                        String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
            }
            if ("youtube_music".equals(action)) {
                assertEquals("This video's sound has no title to look for", ShadowToast.getTextOfLatestToast());
            }
            cell.dispatch(MotionEvent.ACTION_UP);
            assertEquals(action, 0, cell.nativeReleases);
        }
    }

    @Test public void disabledInvalidOrUnrecognizedChoicesRetainNativeEdgeSpeedup() {
        for (int mode = 0; mode < 4; mode++) {
            Settings.EDGE_SEEK.save(mode != 0);
            Settings.EDGE_SEEK_SECONDS.save(mode == 2 ? -5 : mode == 0 ? 5 : 0);
            Settings.LONG_PRESS_ACTION.save(mode == 3 ? "unrecognized" : "default");
            for (float fraction : new float[]{0.1f, 0.9f}) {
                NativeCell cell = cell(fraction);
                cell.begin();
                atNativeDeadline(cell, true);
                atLegacyDeadline(cell);
                assertEquals(0, cell.handled);
                assertEquals(1, cell.nativeEligibilityReads);
                cell.dispatch(MotionEvent.ACTION_UP);
                assertEquals(1, cell.nativeReleases);
                assertFalse(cell.speeding);
            }
        }
    }

    @Test public void centerPressStillRunsTheOriginalEligibilityAndLegacyAction() {
        Settings.EDGE_SEEK.save(true);
        NativeCell cell = cell(0.5f);
        cell.begin();
        atNativeDeadline(cell, false);
        assertEquals("unowned coordinates skipped the original eligibility body", 1, cell.nativeEligibilityReads);
        atLegacyDeadline(cell);
        assertEquals(0, cell.handled);
        assertEquals(1, cell.nativeLongPresses);
        cell.dispatch(MotionEvent.ACTION_UP);
        assertEquals(0, cell.nativeReleases);
    }

    private NativeCell cell(float fraction) {
        NativeCell cell = new NativeCell(activity, fraction);
        cells.add(cell);
        activity.setContentView(cell);
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        cell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        cell.layout(0, 0, width, 600);
        GestureActions.registerCommentView(cell, cell.comments);
        GestureActions.bindCommentView(cell, new VideoItemParams());
        return cell;
    }

    private static void atNativeDeadline(NativeCell cell, boolean starts) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(299));
        assertEquals(0, cell.nativeStarts);
        assertEquals(0, cell.legacyCallbacks);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertEquals("the native 300 ms speedup claimed the wrong gesture", starts ? 1 : 0, cell.nativeStarts);
        cell.dispatch(MotionEvent.ACTION_MOVE);
        assertEquals(starts ? 1 : 0, cell.nativeConsumedMoves);
    }

    private static void atLegacyDeadline(NativeCell cell) {
        assertEquals(500, ViewConfiguration.getLongPressTimeout());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(201));
        assertEquals("the actual Android detector did not deliver its long press", 1, cell.legacyCallbacks);
    }

    /** Mirrors VideoViewCellRootView -> 0Rgs and VideoViewCell's 0QOQ -> GestureDetector. */
    private static final class NativeCell extends FrameLayout {
        final Handler handler = new Handler(Looper.getMainLooper());
        final GestureDetector detector;
        final View touchLayer, comments;
        final float fraction;
        long downTime;
        boolean speeding;
        int nativeStarts, nativeReleases, nativeConsumedMoves, nativeEligibilityReads;
        int legacyCallbacks, nativeLongPresses, handled, commentClicks;
        // LX/0Rgt.run calls the owning ability's Xs(savedDownX,savedDownY).
        final Runnable nativeTimer = () -> { nativeStarts++; speeding = true; };

        NativeCell(Context context, float fraction) {
            super(context);
            this.fraction = fraction;
            detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public void onLongPress(MotionEvent event) {
                    legacyCallbacks++;
                    if (GestureActions.onLongPress(event)) handled++;
                    else nativeLongPresses++;
                }
            });
            touchLayer = new View(context);
            touchLayer.setOnTouchListener((view, event) -> {
                detector.onTouchEvent(event);
                return true; // LX/0QOQ always returns true after forwarding the event.
            });
            addView(touchLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            comments = new View(context);
            comments.setOnClickListener(view -> commentClicks++);
            addView(comments, new LayoutParams(20, 20));
        }

        void begin() {
            downTime = SystemClock.uptimeMillis();
            dispatch(MotionEvent.ACTION_DOWN);
        }

        void dispatch(int action) {
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    action, getWidth() * fraction, 300, 0);
            try {
                boolean consumed = dispatchTouchEvent(event);
                if (action != MotionEvent.ACTION_CANCEL) assertTrue(consumed);
                else detector.onTouchEvent(event);
            }
            finally { event.recycle(); }
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            // The native root calls its edge delegator before dispatching to its children.
            if (edgeOnTouch(event)) {
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) nativeConsumedMoves++;
                requestDisallowInterceptTouchEvent(true);
                return true;
            }
            return super.dispatchTouchEvent(event);
        }

        private boolean edgeOnTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (NU1(event.getX(), event.getY())) handler.postDelayed(nativeTimer, 300);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(nativeTimer);
                    if (speeding) { nativeReleases++; speeding = false; }
                    break;
                default:
                    break;
            }
            return speeding; // LX/0Rgs returns EdgeSpeedupAbility.JY1().
        }

        private boolean NU1(float x, float y) {
            if (!GestureActions.allowNativeEdgeSpeedup(x)) return false;
            nativeEligibilityReads++;
            // Native 0ROJ's default hot-zone ratio is 0.25, with inclusive outer bounds.
            return x >= 0 && x <= getWidth() / 4f || x >= getWidth() * 0.75f && x <= getWidth();
        }
    }

    public static final class PlayerController {
        final PlayerManager manager = new PlayerManager();
        public PlayerManager getPlayerManager() { return manager; }
    }

    public static final class PlayerManager {
        final int durationMs = 30_000;
        float sought = Float.NaN;
        // Native 0pTR truncates the percentage-derived engine position to integer milliseconds.
        public void seek(float percentage) { sought = (int) (percentage * 0.01d * durationMs); }
    }

    public static final class VideoItemParams {
        public final Clip aweme = new Clip();
    }

    public static final class Clip {
        public String getAid() { return "native-edge-current"; }
        public Author getAuthor() { return new Author(); }
        public Sound getMusic() { return new Sound(); }
    }

    public static final class Author {
        public String getUniqueId() { return "nasa"; }
    }

    public static final class Sound {
        // Native Music.getMid() is a String; getId() returns long on 46.2.3.
        public String getMid() { return "73123456789"; }
    }
}

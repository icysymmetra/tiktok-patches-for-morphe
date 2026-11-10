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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

/** Ordinary 46.2.3 cells use 0R9T -> 0Qet -> 0QPc/0QPd, independently of 0QTs. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29, qualifiers = "en-w360dp-h712dp-xxhdpi")
public class NativeCoordinateLongPressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final List<NativeCell> cells = new ArrayList<>();
    private final CoordinatePlayerController player = new CoordinatePlayerController();
    private ActivityController<Activity> activityController;
    private Activity activity;
    private boolean previousEdge;
    private int previousSeconds;
    private String previousAction, previousDoubleTap;

    @Before public void setUp() {
        previousEdge = Settings.EDGE_SEEK.get();
        previousSeconds = Settings.EDGE_SEEK_SECONDS.get();
        previousAction = Settings.LONG_PRESS_ACTION.get();
        previousDoubleTap = Settings.DOUBLE_TAP_ACTION.get();
        Settings.EDGE_SEEK.save(false);
        Settings.EDGE_SEEK_SECONDS.save(5);
        Settings.LONG_PRESS_ACTION.save("default");
        Settings.DOUBLE_TAP_ACTION.save("default");
        activityController = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = activityController.get();
        Utils.setContext(activity);
        BlockAuthorPatch.setCurrentVideoParams(new CoordinateParams());
        BlockAuthorPatch.setPlayingAweme("coordinate-current");
        resetProgress();
    }

    @After public void tearDown() {
        for (NativeCell cell : cells) {
            cell.coordinate.handler.removeCallbacks(cell.coordinate.timer);
            cell.dispatch(MotionEvent.ACTION_CANCEL, cell.x, cell.y);
        }
        activityController.close();
        Settings.EDGE_SEEK.save(previousEdge);
        Settings.EDGE_SEEK_SECONDS.save(previousSeconds);
        Settings.LONG_PRESS_ACTION.save(previousAction);
        Settings.DOUBLE_TAP_ACTION.save(previousDoubleTap);
    }

    @Test public void coordinateTimerSeeksBothEdgesOnceAtTheNativeDeadline() {
        Settings.EDGE_SEEK.save(true);
        // The observed normal-feed override is 350; the native constructor's default is 500.
        for (int interval : new int[]{350, 500}) for (int page : new int[]{1, 2}) {
            for (float fraction : new float[]{0.1f, 0.9f}) {
                resetProgress();
                NativeCell cell = cell(interval, page, false);
                cell.begin(fraction, 1_000);
                idle(interval - 1);
                assertTrue("the coordinate timer fired early", player.manager.targets.isEmpty());
                assertEquals(0, cell.coordinate.callbacks);
                idle(1);
                assertEquals("the native coordinate timer must seek exactly once", 1,
                        player.manager.targets.size());
                assertEquals("the native coordinate timer did not seek the current video",
                        fraction < 0.5f ? 5_000f : 15_000f, player.manager.targets.get(0), 1f);
                assertEquals(1, cell.coordinate.handled);
                assertTrue(cell.menus.isEmpty());
                idle(700);
                cell.dispatch(MotionEvent.ACTION_UP, cell.x, cell.y);
                assertEquals("one native press must not acquire a second legacy action", 1,
                        player.manager.targets.size());
                assertEquals(1, cell.coordinate.callbacks);
                assertEquals(0, cell.legacyCallbacks);
            }
        }
    }

    @Test public void coordinateRemapsRunTheirActionsWithoutTheNativeMenu() {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        for (String action : new String[]{"copy_link", "copy_sound_link", "comments", "original_sound", "nothing", "youtube_music"}) {
            for (float fraction : new float[]{0.1f, 0.9f}) {
                Settings.LONG_PRESS_ACTION.save(action);
                NativeCell cell = cell(350, 1, false);
                clipboard.setPrimaryClip(ClipData.newPlainText("before", "before"));
                ShadowToast.reset();
                cell.begin(fraction, 1_000);
                idle(350);
                if ("copy_link".equals(action)) {
                    assertEquals("https://www.tiktok.com/@nasa/video/coordinate-current",
                            String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
                } else if ("copy_sound_link".equals(action)) {
                    assertEquals("https://www.tiktok.com/music/x-73123456789",
                            String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
                } else if ("comments".equals(action)) {
                    assertEquals(1, cell.commentClicks);
                } else if ("original_sound".equals(action)) {
                    assertEquals("This video has no original sound to save", ShadowToast.getTextOfLatestToast());
                } else if ("youtube_music".equals(action)) {
                    assertEquals("This video's sound has no title to look for", ShadowToast.getTextOfLatestToast());
                }
                assertEquals(action, 1, cell.coordinate.handled);
                assertTrue(action, cell.menus.isEmpty());
                assertFalse("long-press remaps changed the default double tap", GestureActions.onDoubleTap());
                idle(700);
                cell.dispatch(MotionEvent.ACTION_UP, cell.x, cell.y);
                assertEquals(action, 1, cell.coordinate.callbacks);
                assertEquals(action, 0, cell.legacyCallbacks);
            }
        }
    }

    @Test public void defaultAndDisabledChoicesKeepTheNativeMenuCoordinatesAndCancellation() {
        for (int mode = 0; mode < 5; mode++) for (int page : new int[]{1, 2}) {
            Settings.EDGE_SEEK.save(mode >= 1 && mode <= 3);
            Settings.EDGE_SEEK_SECONDS.save(mode == 2 ? 0 : mode == 3 ? -5 : 5);
            Settings.LONG_PRESS_ACTION.save(mode == 4 ? "unrecognized" : "default");
            NativeCell cell = cell(350, page, false);
            cell.begin(mode == 1 ? 0.5f : 0.1f, 1_000);
            idle(350);
            assertEquals("native default menu was swallowed", 1, cell.menus.size());
            NativeMenu menu = cell.menus.get(0);
            assertEquals(page, menu.page);
            assertEquals(activity.hashCode(), menu.activityIdentity);
            assertEquals(cell.x, menu.x, 0);
            assertEquals(1_000f, menu.y, 0);
            assertEquals(0, cell.coordinate.handled);
            assertTrue(player.manager.targets.isEmpty());
            cell.dispatch(MotionEvent.ACTION_UP, cell.x, cell.y);

            int callbacks = cell.coordinate.callbacks;
            cell.begin(0.1f, 1_000);
            idle(100);
            cell.dispatch(MotionEvent.ACTION_MOVE, cell.x + 2 * ViewConfiguration.get(activity).getScaledTouchSlop(), cell.y);
            idle(600);
            cell.dispatch(MotionEvent.ACTION_UP, cell.x, cell.y);
            assertEquals("moving out of the native slop must cancel the timer", callbacks, cell.coordinate.callbacks);
            cell.begin(0.1f, 1_000);
            cell.dispatch(MotionEvent.ACTION_CANCEL, cell.x, cell.y);
            idle(600);
            assertEquals("CANCEL must keep the coordinate timer cancelled", callbacks, cell.coordinate.callbacks);
        }
    }

    @Test public void aConsumingGradualMaskKeepsTheLegacyCallbackAsTheOnlyTouchOwner() {
        Settings.EDGE_SEEK.save(true);
        NativeCell cell = cell(350, 1, true);
        cell.begin(0.1f, 1_000);
        idle(350);
        assertEquals(0, cell.coordinate.downs);
        assertEquals(0, cell.coordinate.callbacks);
        assertEquals(0, cell.legacyCallbacks);
        assertTrue(player.manager.targets.isEmpty());
        assertEquals(500, ViewConfiguration.getLongPressTimeout());
        idle(151);
        assertEquals(1, player.manager.targets.size());
        assertEquals(5_000f, player.manager.targets.get(0), 1f);
        assertEquals(1, cell.legacyCallbacks);
        assertEquals(1, cell.legacyHandled);
        assertEquals(0, cell.coordinate.callbacks);
        assertTrue(cell.menus.isEmpty());
        idle(700);
        cell.dispatch(MotionEvent.ACTION_UP, cell.x, cell.y);
        assertEquals(1, player.manager.targets.size());
    }

    private void resetProgress() {
        player.manager.targets.clear();
        FeedSeek.recordProgress(player, "coordinate-current", 10_000, player.manager.durationMs);
    }

    private NativeCell cell(int interval, int page, boolean maskOwns) {
        NativeCell cell = new NativeCell(activity, interval, page, maskOwns);
        cells.add(cell);
        activity.setContentView(cell);
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        cell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2_136, View.MeasureSpec.EXACTLY));
        cell.layout(0, 0, width, 2_136);
        GestureActions.registerCommentView(cell, cell.comments);
        GestureActions.bindCommentView(cell, new CoordinateParams());
        return cell;
    }

    private static void idle(long milliseconds) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds));
    }

    /** Missing production hook is the unchanged native handback, not a fixture error. */
    private static boolean coordinateHook(float x) {
        final Method hook;
        try {
            hook = GestureActions.class.getMethod("onLongPress", float.class);
        } catch (NoSuchMethodException unchangedProduction) {
            return false;
        }
        try {
            return (Boolean) hook.invoke(null, x);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("coordinate hook threw", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class NativeMenu {
        final int page, activityIdentity;
        final float x, y;
        NativeMenu(int page, int activityIdentity, float x, float y) {
            this.page = page;
            this.activityIdentity = activityIdentity;
            this.x = x;
            this.y = y;
        }
    }

    private static final class NativeCell extends FrameLayout {
        final CoordinateTouchLayer coordinate;
        final GestureDetector detector;
        final View comments;
        final List<NativeMenu> menus = new ArrayList<>();
        int legacyCallbacks, legacyHandled, commentClicks;
        long downTime;
        float x, y;

        NativeCell(Context context, int interval, int page, boolean maskOwns) {
            super(context);
            coordinate = new CoordinateTouchLayer(context, interval, page);
            addView(coordinate, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public void onLongPress(MotionEvent event) {
                    legacyCallbacks++;
                    if (GestureActions.onLongPress(event)) legacyHandled++;
                    else menus.add(new NativeMenu(page, context.hashCode(), event.getX(), event.getY()));
                }
            });
            // 0QOQ/0Qaf consume on their own gradual-mask view; they are not 0R9T's timer.
            View mask = new View(context);
            mask.setOnTouchListener((view, event) -> { detector.onTouchEvent(event); return true; });
            mask.setVisibility(maskOwns ? VISIBLE : GONE);
            addView(mask, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            comments = new View(context);
            comments.setOnClickListener(view -> commentClicks++);
            addView(comments, new LayoutParams(20, 20));
        }

        void begin(float fraction, float y) {
            this.x = getWidth() * fraction;
            this.y = y;
            downTime = SystemClock.uptimeMillis();
            dispatch(MotionEvent.ACTION_DOWN, x, y);
        }

        void dispatch(int action, float x, float y) {
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
            try { dispatchTouchEvent(event); }
            finally { event.recycle(); }
        }

        /** Clean 0R9T has a null monitor on the observed ordinary cell. */
        private final class CoordinateTouchLayer extends View {
            final Handler handler = new Handler(Looper.getMainLooper());
            final int interval, page;
            float downX, downY;
            int downs, callbacks, handled;
            final Runnable timer = () -> LIZ(downX, downY);

            CoordinateTouchLayer(Context context, int interval, int page) {
                super(context);
                this.interval = interval;
                this.page = page;
            }

            @Override public boolean onTouchEvent(MotionEvent event) {
                if (!isEnabled()) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downs++;
                        downX = event.getX();
                        downY = event.getY();
                        handler.postDelayed(timer, interval);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                        if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) {
                            handler.removeCallbacks(timer);
                        }
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(timer);
                        break;
                    default:
                        break;
                }
                super.onTouchEvent(event);
                return true;
            }

            void LIZ(float x, float y) {
                callbacks++;
                if (coordinateHook(x)) { handled++; return; }
                // 0QPc/0QPd post 0SLd(FIFI) with these original x/page/y/activity fields.
                menus.add(new NativeMenu(page, getContext().hashCode(), x, y));
            }
        }
    }

    public static final class CoordinatePlayerController {
        final CoordinatePlayerManager manager = new CoordinatePlayerManager();
        public CoordinatePlayerManager getPlayerManager() { return manager; }
    }
    public static final class CoordinatePlayerManager {
        final int durationMs = 30_000;
        final List<Float> targets = new ArrayList<>();
        // 0MI0.seek -> 0pTR.LJJLIIIJLJLI converts percent to an integer engine position.
        // The float percentage can put that truncated position one millisecond below target.
        public void seek(float percentage) {
            targets.add((float) (int) (percentage * 0.01d * durationMs));
        }
    }
    public static final class CoordinateParams { public final CoordinateClip aweme = new CoordinateClip(); }
    public static final class CoordinateClip {
        public String getAid() { return "coordinate-current"; }
        public CoordinateAuthor getAuthor() { return new CoordinateAuthor(); }
        public CoordinateSound getMusic() { return new CoordinateSound(); }
    }
    public static final class CoordinateAuthor { public String getUniqueId() { return "nasa"; } }
    public static final class CoordinateSound { public String getMid() { return "73123456789"; } }
}

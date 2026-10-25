package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.feed.model.VideoItemParams;
import com.ss.android.ugc.aweme.story.fake.StoryHoldFakes.Holder;
import com.ss.android.ugc.aweme.story.fake.StoryHoldFakes.Monitor;
import com.ss.android.ugc.aweme.story.fake.StoryHoldFakes.State;

import java.lang.ref.Reference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class StoryNativeLongPressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final List<PlayArea> areas = new ArrayList<>();
    private ActivityController<Activity> controller;
    private Activity activity;
    private boolean previousPatch, previousSetting;
    private String previousDownloader;

    @Before public void setUp() {
        previousPatch = SettingsStatus.advancedDownloadsEnabled;
        previousSetting = Settings.SAVE_STORY.get();
        previousDownloader = Settings.EXTERNAL_DOWNLOADER_PACKAGE.get();
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.SAVE_STORY.save(true);
        Settings.EXTERNAL_DOWNLOADER_PACKAGE.save("com.dv.adm");
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.WRITE_EXTERNAL_STORAGE);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        Utils.setContext(activity);
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After public void tearDown() {
        Settings.SAVE_STORY.save(false);
        for (PlayArea area : areas) {
            area.child.handler.removeCallbacks(area.child.timer);
            StoryDownloads.attachPlayArea(area, area.root);
        }
        controller.close();
        SettingsStatus.advancedDownloadsEnabled = previousPatch;
        Settings.SAVE_STORY.save(previousSetting);
        Settings.EXTERNAL_DOWNLOADER_PACKAGE.save(previousDownloader);
        HookStatus.clear();
        assertEquals("a story test queued network work", 0, MediaJobScheduler.queuedJobs());
        assertEquals("a story test started network work", 0, MediaJobScheduler.runningJobs());
    }

    @Test public void consumedChildHoldSavesItsCurrentSubStoryAfterNeighborBindAndReuse() {
        PlayArea showing = area(true, "7101", "7000");
        PlayArea neighbor = area(true, "7201", "7200");
        assertTrue("the original outer listener was not installed", showing.root.isLongClickable());

        press(showing);
        assertSent("7101");
        assertEquals(1, showing.child.handled);
        assertEquals("a handled save still entered the native pause", 0, showing.child.pauses);
        assertEquals("UP resumed a story the native timer never paused", 0, showing.child.resumes);
        assertEquals(0, neighbor.child.handled);

        // The collection stays bound while its current sub-cell changes.
        ((State) showing.child.LLJJJJLIIL.LLJJJJLIIL).LL = new VideoItemParams(new Story("7102"));
        StoryDownloads.recordStory(neighbor, 1, new Story("7202"));
        press(showing);
        assertSent("7102");
        assertEquals(2, showing.child.handled);
        assertEquals(0, showing.child.pauses);
        assertEquals(0, showing.child.resumes);
    }

    @Test public void disabledSaveAndAbsentPatchKeepNativePauseAndRelease() {
        PlayArea showing = area(true, "7101", "7000");
        Settings.SAVE_STORY.save(false);
        press(showing);
        assertEquals(1, showing.child.pauses);
        assertEquals(1, showing.child.resumes);
        Settings.SAVE_STORY.save(true);
        SettingsStatus.advancedDownloadsEnabled = false;
        press(showing);
        assertEquals(2, showing.child.pauses);
        assertEquals(2, showing.child.resumes);
        assertEquals(0, showing.child.handled);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test public void unownedAndReleasedStoryViewsKeepTheirNativeHold() {
        PlayArea unrelated = area(false, "8101", "8000");
        press(unrelated);
        assertEquals(1, unrelated.child.pauses);
        assertEquals(1, unrelated.child.resumes);

        PlayArea released = area(true, "7101", "7000");
        Map<View, ?> owners = ReflectionHelpers.getStaticField(StoryDownloads.class, "OWNERS");
        ((Reference<?>) owners.get(released.root)).clear();
        press(released);
        assertEquals(1, released.child.pauses);
        assertEquals(1, released.child.resumes);
        assertEquals(0, unrelated.child.handled + released.child.handled);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    /** Each step the saver can't take is named: no monitor on the view, no bound params under it. */
    @Test public void renamedCurrentStoryMembersAreVisibleInHookStatus() {
        assertMissingStoryMember(new View(activity), "a LongPressMonitorAbility");
        assertMissingStoryMember(new FieldView(activity, new Holder(new Monitor(null))),
                "a LongPressMonitorAbility");
        assertMissingStoryMember(new FieldView(activity, new Monitor(new Holder(null))),
                "one VideoItemParams");
        assertMissingStoryMember(new FieldView(activity, new Monitor(new State(new Object()))),
                "one VideoItemParams");
    }

    private void assertMissingStoryMember(View child, String expectedMember) {
        HookStatus.clear();
        Object owner = new Object();
        FrameLayout root = new FrameLayout(activity);
        root.addView(child, new FrameLayout.LayoutParams(-1, -1));
        StoryDownloads.recordStory(owner, 0, new Story("7101"));
        StoryDownloads.attachPlayArea(owner, root);

        assertTrue(StoryDownloads.onNativeLongPress(child));

        List<String> missing = HookStatus.missing("story saves");
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains(expectedMember));
    }

    private PlayArea area(boolean registered, String current, String collection) {
        PlayArea area = new PlayArea(activity, new Story(current));
        areas.add(area);
        if (registered) {
            StoryDownloads.recordStory(area, areas.size() - 1, new Story(collection));
            StoryDownloads.attachPlayArea(area, area.root);
        }
        return area;
    }

    private void press(PlayArea area) {
        activity.setContentView(area.root);
        area.root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        area.root.layout(0, 0, 400, 600);
        long down = SystemClock.uptimeMillis();
        dispatch(area, down, MotionEvent.ACTION_DOWN);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 20L));
        dispatch(area, down, MotionEvent.ACTION_UP);
    }

    private void dispatch(PlayArea area, long down, int action) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 100, 100, 0);
        try {
            assertTrue("the native child did not consume touch", area.root.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private void assertSent(String aid) {
        Intent sent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("the consuming child's long hold never reached the save", sent);
        assertEquals(Intent.ACTION_SEND, sent.getAction());
        assertEquals("com.dv.adm", sent.getPackage());
        assertEquals("https://www.tiktok.com/@nasa/video/" + aid,
                sent.getStringExtra(Intent.EXTRA_TEXT));
        assertNull("one hold submitted two saves", Shadows.shadowOf(activity).getNextStartedActivity());
    }

    private static final class PlayArea {
        final FrameLayout root;
        final NativeLongPressView child;
        PlayArea(Activity activity, Story story) {
            root = new FrameLayout(activity);
            FrameLayout page = new FrameLayout(activity);
            root.addView(page, new FrameLayout.LayoutParams(-1, -1));
            child = new NativeLongPressView(activity, story);
            page.addView(child, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    /**
     * TikTok's native story child consumes touch; its timer calls the monitor's pause and UP its
     * resume. The monitor sits in one of its fields (46.2.3 LLJIJIL, 47.0.3 LLJJJJLIIL), and the
     * item its cell is bound to two Assem fields under that.
     */
    public static final class NativeLongPressView extends View {
        public final Monitor LLJJJJLIIL;
        final Handler handler = new Handler(Looper.getMainLooper());
        int handled, pauses, resumes;
        boolean nativePressed;
        final Runnable timer = () -> {
            if (StoryDownloads.onNativeLongPress(this)) { handled++; return; }
            nativePressed = true;
            pauses++;
        };
        NativeLongPressView(Activity activity, Story story) {
            super(activity);
            LLJJJJLIIL = new Monitor(new State(new VideoItemParams(story)));
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                handler.postDelayed(timer, ViewConfiguration.getLongPressTimeout());
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(timer);
                if (nativePressed) resumes++;
                nativePressed = false;
            }
            super.onTouchEvent(event);
            return true;
        }
    }

    public static final class FieldView extends View {
        public final Object LLJJJJLIIL;
        FieldView(Activity activity, Object value) {
            super(activity);
            LLJJJJLIIL = value;
        }
    }
    public static final class Story {
        private final String aid;
        Story(String value) { aid = value; }
        public String getAid() { return aid; }
        public String getShareUrl() { return "https://www.tiktok.com/@nasa/video/" + aid; }
    }
}

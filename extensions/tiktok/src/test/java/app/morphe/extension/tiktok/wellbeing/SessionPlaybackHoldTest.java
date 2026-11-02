package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorPatch;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Enters the controller hook with the native manager contract used by the feed play button. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SessionPlaybackHoldTest {
    private final AtomicLong now = new AtomicLong(1_788_800_400_000L);

    public static final class HostActivity extends Activity { }

    public static final class Clip {
        private final String aid;
        Clip(String aid) { this.aid = aid; }
        public String getAid() { return aid; }
    }

    public static final class Params {
        public final Clip aweme;
        Params(Clip aweme) { this.aweme = aweme; }
    }

    public static final class NativeManager {
        boolean playing = true;
        boolean paused;
        int pauses;
        int resumes;
        long position;
        boolean deferNativeCommands;
        final java.util.ArrayDeque<Runnable> nativeCommands = new java.util.ArrayDeque<>();

        public boolean isPlaying() { return playing; }
        public boolean isPaused() { return paused; }
        public void pause() {
            assertEquals("pause left the native player thread", Looper.getMainLooper(),
                    Looper.myLooper());
            pauses++;
            // SimplifyAsyncPlayer posts pause/resume to the same playback dispatcher.
            Runnable pause = () -> { playing = false; paused = true; };
            if (deferNativeCommands) nativeCommands.add(pause);
            else new Handler(Looper.getMainLooper()).post(pause);
        }
        public void resume() {
            assertEquals("resume left the native player thread", Looper.getMainLooper(),
                    Looper.myLooper());
            resumes++;
            if (deferNativeCommands) nativeCommands.add(this::nativeResume);
            else nativeResume();
        }
        void nativeResume() { playing = true; paused = false; }
        void advance(long millis) { if (playing) position += millis; }
        void drainNativeCommands() {
            while (!nativeCommands.isEmpty()) nativeCommands.remove().run();
        }
    }

    public static final class NativeController {
        Clip current = new Clip("first");
        NativeManager manager = new NativeManager();
        public Clip currentClip() { return current; }
        public NativeManager getPlayerManager() { return manager; }
        void reportProgress() {
            BlockAuthorPatch.setPlayingAweme(this, current.getAid());
        }
        void bind(String aid) {
            current = new Clip(aid);
            BlockAuthorPatch.setCurrentVideoParams(new Params(current));
        }
    }

    /**
     * The patch writes TikTok's own current-video getter, pause and resume into the hold's three
     * bridges; unpatched, the bridges reach these fakes through this seam instead.
     */
    static void installNativeControls() {
        SessionPlaybackHold.nativeForTests = new SessionPlaybackHold.NativeControls() {
            @Override public Object currentAweme(Object controller) {
                return ((NativeController) controller).currentClip();
            }
            @Override public void pause(Object manager) { ((NativeManager) manager).pause(); }
            @Override public void resume(Object manager) { ((NativeManager) manager).resume(); }
        };
    }

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // A suite whose activities stopped last leaves the overlay believing the app is away.
        SessionLockOverlay.resetForTests();
        installNativeControls();
        SessionBudget.awaitWritesForTests();
        resetSettings();
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "resetForTests");
        SessionLockOverlay.sync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        Settings.SESSION_BUDGET_VIDEOS.save(2);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(2);
    }

    @After public void tearDown() throws Exception {
        Utils.setActivity(null);
        SessionBudget.awaitWritesForTests();
        resetSettings();
        SessionBudget.resetForTests();
        SessionLockOverlay.sync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        SessionBudget.setClockForTests(null);
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "resetForTests");
        ReflectionHelpers.setStaticField(FeedVisibility.class, "homeTabReference",
                new WeakReference<View>(null));
        SessionPlaybackHold.nativeForTests = null;
    }

    private static void resetSettings() {
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_PASSES_PER_DAY.resetToDefault();
        Settings.SESSION_BUDGET_NOTICE_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
    }

    private static View feedTab(Activity activity) {
        Utils.setActivity(activity);
        View home = new View(activity);
        ((ViewGroup) activity.findViewById(android.R.id.content)).addView(home);
        home.setSelected(true);
        ReflectionHelpers.setStaticField(FeedVisibility.class, "homeTabReference",
                new WeakReference<>(home));
        return home;
    }

    private static void startHold(NativeController player) {
        player.bind("first");
        player.reportProgress();
        player.bind("held");
        player.reportProgress();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the real item/progress path did not start the hold", SessionBudget.isLocked());
    }

    private static void releaseFromPanel(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
        assertTrue("the hold release control did not run", panel.getChildAt(3).performClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(SessionBudget.isLocked());
    }

    @Test public void returningToFeedKeepsTheHeldPlayerPausedUntilRelease() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            owner.windowFocusChanged(true);
            View home = feedTab(owner.get());
            NativeController player = new NativeController();
            startHold(player);
            assertEquals("the hold only requested focus", 1, player.manager.pauses);
            assertFalse(player.manager.isPlaying());

            // Leave the feed, then reproduce the native resume on returning from settings.
            home.setSelected(false);
            owner.windowFocusChanged(false);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            player.manager.nativeResume();
            home.setSelected(true);
            owner.windowFocusChanged(true);
            player.reportProgress();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("a live hold ignored the returning native player", 2, player.manager.pauses);
            long stoppedAt = player.manager.position;
            player.manager.advance(6_000);
            assertEquals("the covered video kept advancing", stoppedAt, player.manager.position);

            releaseFromPanel(owner.get());
            assertEquals("the hold did not give its paused player back", 1, player.manager.resumes);
            player.manager.advance(1_000);
            assertEquals(stoppedAt + 1_000, player.manager.position);
        }
    }

    @Test @Config(shadows = FocusOwnerShadow.class)
    public void aRecreatedActivityReleasesFocusThroughTheOriginalManager() {
        assertRecreatedActivityReleasesOriginalRequest(false);
    }

    @Test @Config(shadows = FocusOwnerShadow.class)
    public void aRecreatedActivityAbandonsItsSuspendedOriginalFocusRequest() {
        assertRecreatedActivityReleasesOriginalRequest(true);
    }

    private void assertRecreatedActivityReleasesOriginalRequest(boolean transientLoss) {
        String previousContexts = System.getProperty("robolectric.createActivityContexts");
        System.setProperty("robolectric.createActivityContexts", "true");
        FocusOwnerShadow.requester = null;
        FocusOwnerShadow.abandoner = null;
        try (var first = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            first.windowFocusChanged(true);
            Utils.setContext(first.get());
            feedTab(first.get());
            NativeController player = new NativeController();
            startHold(player);
            AudioManager original = (AudioManager) first.get().getSystemService(Activity.AUDIO_SERVICE);
            assertNotNull("the hold never requested focus", FocusOwnerShadow.requester);
            var request = Shadows.shadowOf(FocusOwnerShadow.requester).getLastAudioFocusRequest();
            assertNotNull("the original activity never requested focus", request);
            if (transientLoss) request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            first.windowFocusChanged(false);
            first.pause().stop();
            try (var replacement = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
                replacement.windowFocusChanged(true);
                Utils.setContext(replacement.get());
                feedTab(replacement.get());
                AudioManager next = (AudioManager) replacement.get().getSystemService(Activity.AUDIO_SERVICE);
                org.junit.Assert.assertNotSame("fixture must replace the activity's audio manager", original, next);
                SessionLockOverlay.sync();
                releaseFromPanel(replacement.get());
                assertSame("the request was abandoned through the replacement context's manager",
                        FocusOwnerShadow.requester, FocusOwnerShadow.abandoner);
                assertEquals(transientLoss ? 0 : 1, player.manager.resumes);
            }
        } finally {
            Utils.setContext(RuntimeEnvironment.getApplication());
            if (previousContexts == null) System.clearProperty("robolectric.createActivityContexts");
            else System.setProperty("robolectric.createActivityContexts", previousContexts);
            FocusOwnerShadow.requester = null;
            FocusOwnerShadow.abandoner = null;
        }
    }

    // The standard audio shadow shares call history across all managers. Record the actual
    // receiver as well, since Android includes that manager in the audio-focus client ID.
    @org.robolectric.annotation.Implements(AudioManager.class)
    public static class FocusOwnerShadow extends org.robolectric.shadows.ShadowAudioManager {
        @org.robolectric.annotation.RealObject private AudioManager manager;
        static AudioManager requester;
        static AudioManager abandoner;

        @org.robolectric.annotation.Implementation
        @Override protected int requestAudioFocus(AudioManager.OnAudioFocusChangeListener listener,
                                                  int stream, int duration) {
            requester = manager;
            return super.requestAudioFocus(listener, stream, duration);
        }

        @org.robolectric.annotation.Implementation
        @Override protected int abandonAudioFocus(AudioManager.OnAudioFocusChangeListener listener) {
            abandoner = manager;
            return super.abandonAudioFocus(listener);
        }
    }

    @Test public void releasingBeforeTheNativePauseRunsQueuesAResumeAfterIt() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            owner.windowFocusChanged(true);
            feedTab(owner.get());
            NativeController player = new NativeController();
            player.manager.deferNativeCommands = true;
            startHold(player);
            assertEquals("the hold did not queue its native pause", 1, player.manager.pauses);
            assertTrue("the native dispatcher ran before release", player.manager.isPlaying());

            releaseFromPanel(owner.get());
            player.manager.drainNativeCommands();
            assertTrue("the queued pause landed after release without a matching resume",
                    player.manager.isPlaying());
            assertEquals(1, player.manager.resumes);
            player.manager.advance(1_000);
            assertEquals(1_000, player.manager.position);
        }
    }

    @Test public void aQueuedReportDoesNotClaimAVideoTheReaderAlreadyPaused() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            owner.windowFocusChanged(true);
            feedTab(owner.get());
            NativeController player = new NativeController();
            // A final progress callback may already be queued when the reader pauses.
            player.manager.playing = false;
            player.manager.paused = true;
            startHold(player);
            releaseFromPanel(owner.get());
            assertEquals("the hold claimed an existing manual pause", 0, player.manager.pauses);
            assertEquals("release undid the reader's pause", 0, player.manager.resumes);
            assertTrue(player.manager.isPaused());
        }
    }

    @Test public void releaseDoesNotResumeAControllerReboundBeforeItsNextReport() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            owner.windowFocusChanged(true);
            feedTab(owner.get());
            NativeController player = new NativeController();
            startHold(player);
            assertFalse("the original player was never paused", player.manager.isPlaying());
            // The cell changes before another progress report can replace the captured ID.
            player.current = new Clip("replacement");
            releaseFromPanel(owner.get());
            assertEquals("release started a different video", 0, player.manager.resumes);
        }
    }

    @Test public void anExpiredHoldDoesNotStartPlaybackBehindAnotherActivity() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            owner.windowFocusChanged(true);
            feedTab(owner.get());
            NativeController player = new NativeController();
            startHold(player);
            assertFalse("the original player was never paused", player.manager.isPlaying());
            owner.windowFocusChanged(false);
            owner.pause().stop();
            now.addAndGet(121_000);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse(SessionBudget.isLocked());
            assertEquals("hold expiry started a background video", 0, player.manager.resumes);
        }
    }

    @Test public void anExpiredHoldDoesNotResumeDuringATransientFocusLoss() {
        assertNoResumeAfterFocusLoss(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    }

    @Test public void anExpiredHoldDoesNotResumeWhileAnotherOwnerCanDuckIt() {
        assertNoResumeAfterFocusLoss(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    }

    private void assertNoResumeAfterFocusLoss(int loss) {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            owner.windowFocusChanged(true);
            feedTab(owner.get());
            NativeController player = new NativeController();
            startHold(player);
            AudioManager audio = (AudioManager) RuntimeEnvironment.getApplication()
                    .getSystemService(Activity.AUDIO_SERVICE);
            var shadow = Shadows.shadowOf(audio);
            var request = shadow.getLastAudioFocusRequest();
            assertNotNull("the hold has no registered focus listener", request);

            request.listener.onAudioFocusChange(loss);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertSame("transient loss caused another focus request", request,
                    shadow.getLastAudioFocusRequest());
            now.addAndGet(121_000);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse(SessionBudget.isLocked());
            assertEquals("hold expiry resumed while another app still had transient focus", 0,
                    player.manager.resumes);
            assertSame("the suspended focus request was left on the stack", request.listener,
                    shadow.getLastAbandonedAudioFocusListener());
        }
    }

    @Test public void focusGainRestoresResumeWithoutRepeatingTheFocusRequest() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            owner.windowFocusChanged(true);
            feedTab(owner.get());
            NativeController player = new NativeController();
            startHold(player);
            AudioManager audio = (AudioManager) RuntimeEnvironment.getApplication()
                    .getSystemService(Activity.AUDIO_SERVICE);
            var shadow = Shadows.shadowOf(audio);
            var request = shadow.getLastAudioFocusRequest();
            assertNotNull("the hold has no registered focus listener", request);

            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
            releaseFromPanel(owner.get());
            assertSame("regaining focus requested it again", request,
                    shadow.getLastAudioFocusRequest());
            assertEquals("regained focus did not restore the hold's resume", 1,
                    player.manager.resumes);
            assertTrue(player.manager.isPlaying());
        }
    }

    @Test public void anExpiredHoldDoesNotTakePlaybackBackFromAnAudioFocusOwner() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            owner.windowFocusChanged(true);
            feedTab(owner.get());
            NativeController player = new NativeController();
            startHold(player);
            assertFalse("the original player was never paused", player.manager.isPlaying());
            AudioManager.OnAudioFocusChangeListener focus = ReflectionHelpers.getStaticField(
                    SessionLockOverlay.class, "QUIET");
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
            now.addAndGet(121_000);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse(SessionBudget.isLocked());
            assertEquals("hold expiry stole playback from the new focus owner", 0,
                    player.manager.resumes);
        }
    }

    /** The picture-in-picture button plays and pauses the reported video with TikTok's own controls. */
    @Test public void thePlayButtonTogglesTheReportedVideo() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            owner.windowFocusChanged(true);
            feedTab(owner.get());
            NativeController player = new NativeController();
            player.bind("first");
            player.reportProgress();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(SessionBudget.isLocked());
            assertSame(player.current, SessionPlaybackHold.currentPlayingAweme());
            assertEquals(Boolean.TRUE, SessionPlaybackHold.currentPlaying());

            assertTrue(SessionPlaybackHold.setCurrentPlaying(false));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, player.manager.pauses);
            assertEquals(Boolean.FALSE, SessionPlaybackHold.currentPlaying());

            assertTrue(SessionPlaybackHold.setCurrentPlaying(true));
            assertEquals(1, player.manager.resumes);
            assertEquals(Boolean.TRUE, SessionPlaybackHold.currentPlaying());
        }
    }

    /** A held video stays held: the button can't play what the daily panel paused. */
    @Test public void thePlayButtonCantPlayAHeldVideo() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            owner.windowFocusChanged(true);
            feedTab(owner.get());
            NativeController player = new NativeController();
            startHold(player);
            int resumes = player.manager.resumes;
            assertFalse(SessionPlaybackHold.setCurrentPlaying(true));
            assertEquals(resumes, player.manager.resumes);
        }
    }

    /** Once the cell moved on to a video the player hasn't reported, there's nothing to play or pause. */
    @Test public void thePlayButtonDoesNothingOnceTheCellMovedOn() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            owner.windowFocusChanged(true);
            feedTab(owner.get());
            NativeController player = new NativeController();
            player.bind("first");
            player.reportProgress();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            player.bind("second");
            assertEquals(null, SessionPlaybackHold.currentPlaying());
            assertFalse(SessionPlaybackHold.setCurrentPlaying(false));
            assertEquals(0, player.manager.pauses);
        }
    }
}

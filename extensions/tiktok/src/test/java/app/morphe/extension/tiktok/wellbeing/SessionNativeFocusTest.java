package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.wellbeing.SessionPlaybackHoldTest.Clip;
import app.morphe.extension.tiktok.wellbeing.SessionPlaybackHoldTest.HostActivity;
import app.morphe.extension.tiktok.wellbeing.SessionPlaybackHoldTest.NativeController;
import app.morphe.extension.tiktok.wellbeing.SessionPlaybackHoldTest.NativeManager;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Exercises the native activity focus calls around the real daily-hold and expiry callers. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SessionNativeFocusTest {
    private final AtomicLong now = new AtomicLong(1_788_800_400_000L);

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // A suite whose activities stopped last leaves the overlay believing the app is away.
        SessionLockOverlay.resetForTests();
        SessionBudget.setClockForTests(now::get);
        SessionPlaybackHoldTest.installNativeControls();
        clearHold();
    }

    @After public void tearDown() throws Exception {
        clearHold();
        SessionBudget.setClockForTests(null);
        SessionPlaybackHold.nativeForTests = null;
    }

    @Test public void nativeGrantAfterActivityReplacementLetsTheHeldVideoResume() throws Exception {
        for (int result : new int[] {AudioManager.AUDIOFOCUS_REQUEST_FAILED,
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED}) {
            try (Hold hold = new Hold()) {
                hold.replaceActivity();
                // A denied native request must not undo an external owner's existing loss.
                hold.quiet.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
                hold.client().request(result);
                hold.expire();
                assertEquals("release ignored the actual native request result", result ==
                        AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? 1 : 0, hold.player.manager.resumes);
            }
        }
    }

    @Test public void newerNativeGrantSurvivesAnOlderListenerLoss() throws Exception {
        try (Hold hold = new Hold()) {
            NativeFocusClient older = hold.client();
            older.request(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            hold.client().request(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            // Android can deliver the previous client's loss after the new request returns.
            older.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
            hold.expire();
            assertEquals("an older listener's loss cleared the current native grant", 1,
                    hold.player.manager.resumes);
        }
    }

    @Test public void nativeGainRestoresAnOlderListenerAfterNewerAbandon() throws Exception {
        try (Hold hold = new Hold()) {
            NativeFocusClient older = hold.client();
            older.request(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            NativeFocusClient newer = hold.client();
            newer.request(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            newer.abandon();
            older.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
            hold.expire();
            assertEquals("the restored native client could not hand its paused video back", 1,
                    hold.player.manager.resumes);
        }
    }

    @Test public void externalLossRevokesTheObservedNativeGrant() throws Exception {
        for (int loss : new int[] {AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}) {
            try (Hold hold = new Hold()) {
                NativeFocusClient nativeOwner = hold.client();
                nativeOwner.request(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                nativeOwner.listener.onAudioFocusChange(loss);
                hold.expire();
                assertEquals("release stole playback after the native client's loss " + loss,
                        0, hold.player.manager.resumes);
            }
        }
    }

    @Test public void abandonRevokesTheObservedNativeGrantAndRejectsALateGain() throws Exception {
        try (Hold hold = new Hold()) {
            NativeFocusClient nativeOwner = hold.client();
            nativeOwner.request(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            nativeOwner.abandon();
            nativeOwner.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
            hold.expire();
            assertEquals("an abandoned native client revived playback permission", 0,
                    hold.player.manager.resumes);
        }
    }

    @Test public void nativeGrantKeepsTheCurrentPlayerAndForegroundGuards() throws Exception {
        for (int changed = 0; changed < 5; changed++) {
            try (Hold hold = new Hold()) {
                hold.client().request(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                NativeManager paused = hold.player.manager;
                if (changed == 0) hold.player.current = new Clip("different-cell");
                else if (changed == 1) hold.player.manager = new NativeManager();
                else if (changed == 2) {
                    NativeController replacement = new NativeController();
                    replacement.current = new Clip("held");
                    replacement.manager.playing = false;
                    replacement.manager.paused = true;
                    replacement.reportProgress();
                } else if (changed == 3) hold.home.setSelected(false);
                else hold.activity.windowFocusChanged(false);
                hold.expire();
                assertEquals("native focus bypassed the owned player/feed check " + changed,
                        0, paused.resumes);
            }
        }
    }

    @Test public void aNativeGainAfterExpiryResumesTheHeldVideoOnlyOnce() throws Exception {
        try (Hold hold = new Hold(); ExternalFocus external = new ExternalFocus(hold)) {
            external.request();
            long stoppedAt = hold.player.manager.position;
            hold.expire();
            assertEquals("expiry resumed while another app still owned focus", 0,
                    hold.player.manager.resumes);
            hold.player.manager.advance(2_000);
            assertEquals(stoppedAt, hold.player.manager.position);
            SessionLockOverlay.sync();
            SessionLockOverlay.sync();

            external.releaseNormally();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("normal native focus return lost the expired hold's paused video", 1,
                    hold.player.manager.resumes);
            hold.player.manager.advance(1_000);
            assertEquals("the returned native player did not advance", stoppedAt + 1_000,
                    hold.player.manager.position);

            external.deliverNativeGain();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("a duplicate native gain resumed an already released hold", 1,
                    hold.player.manager.resumes);
        }
    }

    @Test public void aGainAfterExpiryStillChecksTheCurrentPlayerAndForeground() throws Exception {
        for (int changed = 0; changed < 5; changed++) {
            try (Hold hold = new Hold(); ExternalFocus external = new ExternalFocus(hold)) {
                external.request();
                hold.expire();
                NativeManager paused = hold.player.manager;
                NativeController replacement = null;
                // These changes happen after expiry, while a resume is waiting for focus.
                if (changed == 0) hold.player.current = new Clip("different-cell");
                else if (changed == 1) hold.player.manager = new NativeManager();
                else if (changed == 2) {
                    replacement = new NativeController();
                    replacement.current = new Clip("held");
                    replacement.manager.playing = false;
                    replacement.manager.paused = true;
                    replacement.reportProgress();
                } else if (changed == 3) hold.home.setSelected(false);
                else hold.activity.windowFocusChanged(false);

                external.releaseNormally();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("late focus bypassed the owned player/feed check " + changed,
                        0, paused.resumes);
                assertEquals("late focus resumed a replacement manager " + changed,
                        0, hold.player.manager.resumes);
                if (replacement != null) assertEquals("late focus resumed another controller",
                        0, replacement.manager.resumes);
            }
        }
    }

    @Test public void aGainAfterExpiryLeavesAnIndependentlyPausedPlayerAlone() throws Exception {
        try (Hold hold = new Hold(true); ExternalFocus external = new ExternalFocus(hold)) {
            external.request();
            hold.expire();
            external.releaseNormally();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("late focus resumed a pause the daily hold never made", 0,
                    hold.player.manager.resumes);
            long stoppedAt = hold.player.manager.position;
            hold.player.manager.advance(2_000);
            assertEquals("the independently paused player started advancing", stoppedAt,
                    hold.player.manager.position);
        }
    }

    @Test public void lateOrRevokedNativeCallbacksCannotResumeAnExpiredHold() throws Exception {
        for (int invalid = 0; invalid < 4; invalid++) {
            try (Hold hold = new Hold(); ExternalFocus external = new ExternalFocus(hold)) {
                external.request();
                hold.expire();
                if (invalid == 0) {
                    new NativeFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
                } else {
                    if (invalid == 1) external.nativeOwner.abandon();
                    else if (invalid == 2) external.nativeOwner.listener.onAudioFocusChange(
                            AudioManager.AUDIOFOCUS_LOSS);
                    external.releaseNormally();
                    // A posted gain must recheck a loss received before its main-thread work.
                    if (invalid == 3) external.nativeOwner.listener.onAudioFocusChange(
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("late or revoked focus resumed playback " + invalid, 0,
                        hold.player.manager.resumes);
            }
        }
    }

    @Test public void aLateNativeGainCannotReleaseANewActiveHold() throws Exception {
        try (Hold hold = new Hold(); ExternalFocus external = new ExternalFocus(hold)) {
            external.request();
            hold.expire();
            var reset = new app.morphe.extension.tiktok.settings.preference.StartTodayOverPreference(
                    hold.activity.get());
            assertTrue("the actual reset row did not run",
                    reset.getOnPreferenceClickListener().onPreferenceClick(reset));
            assertEquals("the actual reset row did not clear the count", 0, SessionBudget.videosSeen());
            hold.player.manager.nativeResume();
            hold.player.bind("new-first");
            hold.player.reportProgress();
            hold.player.bind("new-held");
            hold.player.reportProgress();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the selected new videos did not start another hold", SessionBudget.isLocked());
            assertTrue(hold.player.manager.isPaused());

            external.releaseNormally();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("late focus removed the new hold", SessionBudget.isLocked());
            assertEquals("a callback for the expired hold resumed the new active hold", 0,
                    hold.player.manager.resumes);
            assertTrue(hold.player.manager.isPaused());
        }
    }

    @Test public void progressAfterExpiryDistinguishesAnIndependentResumeFromAPausedReport() throws Exception {
        int[] resumes = new int[3];
        for (int independentResume = 0; independentResume < 3; independentResume++) {
            try (Hold hold = new Hold(); ExternalFocus external = new ExternalFocus(hold)) {
                external.request();
                hold.expire();
                if (independentResume == 1) hold.player.manager.nativeResume();
                // The real progress bridge runs even when the current source ID is unchanged.
                hold.player.reportProgress();
                if (independentResume == 1) hold.player.manager.pause();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue("the native player was not paused before focus returned",
                        hold.player.manager.isPaused());

                if (independentResume == 2) {
                    // A fresh native request can also receive permission after the old hold expired.
                    hold.client().request(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                } else {
                    external.releaseNormally();
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                resumes[independentResume] = hold.player.manager.resumes;
            }
        }
        assertEquals("a paused native progress report discarded the hold's pending resume", 1, resumes[0]);
        assertEquals("late focus claimed a new pause after an independently reported resume", 0, resumes[1]);
        assertEquals("a fresh granted native request did not restore the pending video", 1, resumes[2]);
    }

    @Test public void progressBeforeTheQueuedPauseAppliesKeepsTheResumeForNormalFocusReturn() throws Exception {
        try (Hold hold = new Hold(false, true); ExternalFocus external = new ExternalFocus(hold)) {
            external.request();
            ViewGroup root = hold.activity.get().findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            assertTrue("the actual hold release control did not run", panel.getChildAt(3).performClick());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("the panel did not release the hold", SessionBudget.isLocked());
            assertEquals("release resumed while external focus was still held", 0,
                    hold.player.manager.resumes);

            // SimplifyAsyncPlayer can still report PLAYING while our LIZ command is queued.
            // This is the old state before our pause, not an independent resume after it.
            assertTrue("the native pause ran before the final progress report",
                    hold.player.manager.isPlaying());
            hold.player.reportProgress();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            hold.player.manager.drainNativeCommands();
            assertTrue("the queued native pause did not apply", hold.player.manager.isPaused());

            external.releaseNormally();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("pre-pause progress discarded the matching resume on normal focus return",
                    1, hold.player.manager.resumes);
            hold.player.manager.drainNativeCommands();
            assertTrue("the native dispatcher did not apply the matching resume",
                    hold.player.manager.isPlaying());
            hold.player.manager.advance(1_000);
            assertEquals("the returned native player did not advance", 1_000,
                    hold.player.manager.position);
        }
    }

    /** Real request/normal-abandon calls; Android's resulting callbacks need replay in Robolectric. */
    private static final class ExternalFocus implements AutoCloseable {
        final AudioManager audio;
        final NativeFocusClient nativeOwner;
        final AudioManager.OnAudioFocusChangeListener listener = change -> { };
        boolean requested;

        ExternalFocus(Hold hold) {
            audio = hold.audio;
            nativeOwner = hold.client();
            nativeOwner.request(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        }

        @SuppressWarnings("deprecation")
        void request() {
            int result = audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            assertEquals("the competing app did not receive focus",
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED, result);
            requested = true;
            nativeOwner.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        }

        @SuppressWarnings("deprecation")
        void releaseNormally() throws Exception {
            assertTrue("no external focus request was active", requested);
            audio.abandonAudioFocus(listener);
            requested = false;
            deliverNativeGain();
        }

        void deliverNativeGain() throws Exception {
            Throwable[] failure = new Throwable[1];
            Thread callback = new Thread(() -> {
                try {
                    nativeOwner.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
                } catch (Throwable error) {
                    failure[0] = error;
                }
            }, "native-focus-callback");
            callback.start();
            callback.join(5_000);
            if (callback.isAlive()) {
                callback.interrupt();
                throw new AssertionError("native focus callback did not finish");
            }
            if (failure[0] != null) throw new AssertionError("native focus callback failed", failure[0]);
        }

        @Override @SuppressWarnings("deprecation") public void close() {
            if (requested) audio.abandonAudioFocus(listener);
        }
    }

    /** Mirrors 0q3r's actual request/result and abandon calls, including its listener identity. */
    private static final class NativeFocusClient {
        final AudioManager audio;
        final NativeFocusListener listener = new NativeFocusListener();

        NativeFocusClient(AudioManager audio) { this.audio = audio; }

        @SuppressWarnings("deprecation")
        void request(int response) {
            var shadow = Shadows.shadowOf(audio);
            var previous = shadow.getLastAudioFocusRequest();
            shadow.setNextFocusRequestResponse(response);
            int result = audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            SessionPlaybackHold.onNativeFocusRequestResult(listener, result);
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED && previous != null
                    && previous.listener != listener) {
                previous.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            }
        }

        @SuppressWarnings("deprecation")
        void abandon() {
            SessionPlaybackHold.onNativeFocusAbandon(listener);
            audio.abandonAudioFocus(listener);
        }
    }

    private static final class NativeFocusListener implements AudioManager.OnAudioFocusChangeListener {
        @Override public void onAudioFocusChange(int change) {
            SessionPlaybackHold.onNativeFocusChange(this, change);
        }
    }

    private final class Hold implements AutoCloseable {
        final List<ActivityController<HostActivity>> activities = new ArrayList<>();
        final List<NativeFocusClient> clients = new ArrayList<>();
        final NativeController player = new NativeController();
        final AudioManager audio;
        final AudioManager.OnAudioFocusChangeListener quiet;
        ActivityController<HostActivity> activity;
        View home;

        Hold() throws Exception {
            this(false);
        }

        Hold(boolean pausedBeforeHold) throws Exception {
            this(pausedBeforeHold, false);
        }

        Hold(boolean pausedBeforeHold, boolean deferNativeCommands) throws Exception {
            clearHold();
            Settings.SESSION_BUDGET_VIDEOS.save(2);
            Settings.SESSION_BUDGET_LOCK_MINUTES.save(2);
            audio = (AudioManager) RuntimeEnvironment.getApplication()
                    .getSystemService(Activity.AUDIO_SERVICE);
            Shadows.shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            openActivity();
            player.manager.deferNativeCommands = deferNativeCommands;
            if (pausedBeforeHold) {
                player.manager.pause();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue("the native pause did not precede the daily hold", player.manager.isPaused());
            }
            player.bind("first");
            player.reportProgress();
            player.bind("held");
            player.reportProgress();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the real item/progress path did not start the hold", SessionBudget.isLocked());
            if (pausedBeforeHold) assertEquals("the daily hold claimed an already paused player",
                    1, player.manager.pauses);
            else assertEquals("the hold did not own a native pause", 1, player.manager.pauses);
            if (deferNativeCommands) assertTrue("the native command queue drained early",
                    player.manager.isPlaying());
            else assertTrue(player.manager.isPaused());
            var request = Shadows.shadowOf(audio).getLastAudioFocusRequest();
            assertNotNull("the hold never requested audio focus", request);
            quiet = request.listener;
        }

        private void openActivity() {
            activity = Robolectric.buildActivity(HostActivity.class).setup().visible();
            activities.add(activity);
            activity.windowFocusChanged(true);
            Utils.setActivity(activity.get());
            home = new View(activity.get());
            ((ViewGroup) activity.get().findViewById(android.R.id.content)).addView(home);
            home.setSelected(true);
            ReflectionHelpers.setStaticField(FeedVisibility.class, "homeTabReference",
                    new WeakReference<>(home));
        }

        void replaceActivity() {
            activity.windowFocusChanged(false);
            activity.pause().stop();
            openActivity();
            Utils.setContext(activity.get());
            SessionLockOverlay.sync();
        }

        NativeFocusClient client() {
            NativeFocusClient client = new NativeFocusClient(audio);
            clients.add(client);
            return client;
        }

        void expire() {
            assertFalse("fixture failed to suspend the hold's own focus request",
                    ReflectionHelpers.<Boolean>getStaticField(SessionLockOverlay.class, "focusGranted"));
            now.addAndGet(121_000);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse("the hold did not expire through its timer", SessionBudget.isLocked());
        }

        @Override public void close() throws Exception {
            for (NativeFocusClient client : clients) client.abandon();
            clearHold();
            for (int i = activities.size() - 1; i >= 0; i--) activities.get(i).close();
        }
    }

    private static void clearHold() throws Exception {
        Utils.setActivity(null);
        Utils.setContext(RuntimeEnvironment.getApplication());
        SessionBudget.awaitWritesForTests();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_PASSES_PER_DAY.resetToDefault();
        Settings.SESSION_BUDGET_NOTICE_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        SessionBudget.resetForTests();
        // Robolectric can clear queued messages while preserving the static timer flag.
        // Let the real callback observe the cleared budget and finish its own lifecycle.
        Runnable tick = ReflectionHelpers.getStaticField(SessionLockOverlay.class, "TICK");
        android.os.Handler handler = ReflectionHelpers.getStaticField(SessionLockOverlay.class, "MAIN");
        handler.removeCallbacks(tick);
        tick.run();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "resetForTests");
        ReflectionHelpers.setStaticField(FeedVisibility.class, "homeTabReference",
                new WeakReference<View>(null));
    }
}

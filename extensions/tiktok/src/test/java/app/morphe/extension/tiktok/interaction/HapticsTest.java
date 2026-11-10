/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The stand-ins Turn off haptics puts in place of TikTok's own haptic and vibrator calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HapticsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int NO_FLAGS = -1;

    /** A view that records the haptics it was asked for instead of playing them. */
    private static final class CountingView extends View {
        int haptics;
        int lastConstant = -1;
        int lastFlags = NO_FLAGS;

        CountingView(Context context) {
            super(context);
        }

        @Override public boolean performHapticFeedback(int feedbackConstant) {
            haptics++;
            lastConstant = feedbackConstant;
            lastFlags = NO_FLAGS;
            return true;
        }

        @Override public boolean performHapticFeedback(int feedbackConstant, int flags) {
            haptics++;
            lastConstant = feedbackConstant;
            lastFlags = flags;
            return true;
        }
    }

    private CountingView view;
    private Vibrator vibrator;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        view = new CountingView(context);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        // The switch starts off; these tests are about what it does once the reader turns it on.
        Settings.TURN_OFF_HAPTICS.save(true);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.TURN_OFF_HAPTICS.resetToDefault();
    }

    private static VibrationEffect shortEffect(long millis) {
        return VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE);
    }

    private static AudioAttributes touchAttributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build();
    }

    @Test public void theSwitchStartsOffAndOnTikToksHapticsDontPlay() {
        assertEquals("the patch is in the default selection, so its switch starts off",
                Boolean.FALSE, Settings.TURN_OFF_HAPTICS.defaultValue);
        assertTrue(Settings.TURN_OFF_HAPTICS.get());

        assertFalse("the view call answers as a view with haptics off does",
                Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS));
        assertFalse(Haptics.performHapticFeedback(view, HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING));
        assertEquals("the view was never asked", 0, view.haptics);

        Haptics.vibrate(vibrator, 30L);
        assertFalse("a timed vibration played", shadowOf(vibrator).isVibrating());
        Haptics.vibrate(vibrator, shortEffect(30L));
        assertFalse("an effect played", shadowOf(vibrator).isVibrating());
        Haptics.vibrate(vibrator, shortEffect(30L), touchAttributes());
        assertFalse("an effect with attributes played", shadowOf(vibrator).isVibrating());
    }

    @Test public void switchedOffTheyPlayAsTikTokAsked() {
        Settings.TURN_OFF_HAPTICS.save(false);

        assertTrue(Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS));
        assertEquals(1, view.haptics);
        assertEquals(HapticFeedbackConstants.LONG_PRESS, view.lastConstant);
        assertEquals("the one-argument call stays one", NO_FLAGS, view.lastFlags);

        assertTrue(Haptics.performHapticFeedback(view, HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING));
        assertEquals(2, view.haptics);
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, view.lastConstant);
        assertEquals(HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING, view.lastFlags);

        Haptics.vibrate(vibrator, 30L);
        assertTrue(shadowOf(vibrator).isVibrating());
        assertEquals(30L, shadowOf(vibrator).getMilliseconds());
    }

    @Test public void switchedOffAnEffectPlays() {
        Settings.TURN_OFF_HAPTICS.save(false);

        Haptics.vibrate(vibrator, shortEffect(40L));
        assertTrue(shadowOf(vibrator).isVibrating());
    }

    @Test public void switchedOffAnEffectWithAttributesPlays() {
        Settings.TURN_OFF_HAPTICS.save(false);

        Haptics.vibrate(vibrator, shortEffect(40L), touchAttributes());
        assertTrue(shadowOf(vibrator).isVibrating());
    }

    @Test public void pausedTheyPlayWithTheSwitchOn() {
        PausedProcess.set(true);

        assertTrue(Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS));
        assertEquals(1, view.haptics);
        Haptics.vibrate(vibrator, 25L);
        assertTrue(shadowOf(vibrator).isVibrating());
        assertEquals(25L, shadowOf(vibrator).getMilliseconds());
    }

    /**
     * A view that notes whether its haptics were on when Android asked it for the long press buzz.
     * Robolectric's ShadowView answers performHapticFeedback(int) itself, records the constant and
     * ignores the view's setting, so what it records can't show a buzz was skipped. What this
     * shows is the setting Android's own check reads at that moment, on the call API 28's
     * performLongClickInternal makes right after the listener answers.
     */
    private static final class WitnessView extends View {
        int longPressAsks;
        boolean enabledWhenAsked;

        WitnessView(Context context) {
            super(context);
        }

        @Override public boolean performHapticFeedback(int feedbackConstant) {
            if (feedbackConstant == HapticFeedbackConstants.LONG_PRESS) {
                longPressAsks++;
                enabledWhenAsked = isHapticFeedbackEnabled();
            }
            return super.performHapticFeedback(feedbackConstant);
        }
    }

    /** A view that keeps the long click listener it was handed. */
    private static final class RecordingView extends View {
        View.OnLongClickListener listener;

        RecordingView(Context context) {
            super(context);
        }

        @Override public void setOnLongClickListener(View.OnLongClickListener listener) {
            this.listener = listener;
            super.setOnLongClickListener(listener);
        }
    }

    /** A view in a real window, so a post reaches the main looper. */
    private interface WithView {
        void run(WitnessView target);
    }

    private static void inAWindow(WithView body) {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            WitnessView target = new WitnessView(controller.get());
            controller.get().setContentView(target);
            body.run(target);
        }
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void aLongPressIsQuietAndTheViewsHapticsComeBackAfter() {
        inAWindow(target -> {
            int[] presses = {0};
            Haptics.setOnLongClickListener(target, v -> {
                presses[0]++;
                return true;
            });
            assertTrue(target.performLongClick());
            assertEquals("TikTok's own listener still runs", 1, presses[0]);
            assertEquals("Android asked for its long press buzz", 1, target.longPressAsks);
            assertFalse("the view's haptics were on when Android asked", target.enabledWhenAsked);
            assertFalse("off for the buzz Android asks for right after", target.isHapticFeedbackEnabled());
            idle();
            assertTrue("back on with the next message", target.isHapticFeedbackEnabled());
        });
    }

    @Test public void hapticsTikTokTurnsOffFromItsOwnListenerStayOff() {
        inAWindow(target -> {
            Haptics.setOnLongClickListener(target, v -> {
                v.post(() -> v.setHapticFeedbackEnabled(false));
                return true;
            });
            assertTrue(target.performLongClick());
            idle();
            assertFalse("the restore ran after TikTok's message and turned them back on",
                    target.isHapticFeedbackEnabled());
        });
    }

    @Test public void aListenerAlreadyWrappedIsHandedOnAsItIs() {
        // A TikTok view that takes the listener into a setter of its own and hands it to
        // another view's, where both setters are patched.
        Context context = RuntimeEnvironment.getApplication();
        RecordingView first = new RecordingView(context);
        RecordingView second = new RecordingView(context);
        Haptics.setOnLongClickListener(first, v -> true);
        assertTrue(first.listener instanceof Haptics.QuietLongClick);
        Haptics.setOnLongClickListener(second, first.listener);
        assertSame("wrapped a second time", first.listener, second.listener);
    }

    @Test public void aLongPressNobodyHandledLeavesTheView() {
        inAWindow(target -> {
            Haptics.setOnLongClickListener(target, v -> false);
            target.performLongClick();
            assertTrue(target.isHapticFeedbackEnabled());
        });
    }

    @Test public void switchedOffOrPausedALongPressKeepsItsBuzz() {
        Settings.TURN_OFF_HAPTICS.save(false);
        inAWindow(target -> {
            Haptics.setOnLongClickListener(target, v -> true);
            assertTrue(target.performLongClick());
            assertEquals(1, target.longPressAsks);
            assertTrue("the buzz was asked for with haptics on", target.enabledWhenAsked);
            assertTrue(target.isHapticFeedbackEnabled());
        });
        Settings.TURN_OFF_HAPTICS.save(true);
        PausedProcess.set(true);
        inAWindow(target -> {
            Haptics.setOnLongClickListener(target, v -> true);
            assertTrue(target.performLongClick());
            assertTrue(target.isHapticFeedbackEnabled());
        });
    }

    @Test public void aViewWithHapticsOffStaysOff() {
        inAWindow(target -> {
            target.setHapticFeedbackEnabled(false);
            Haptics.setOnLongClickListener(target, v -> true);
            assertTrue(target.performLongClick());
            idle();
            assertFalse(target.isHapticFeedbackEnabled());
        });
    }

    @Test public void clearingTheListenerStillClearsIt() {
        inAWindow(target -> {
            // View.hasOnLongClickListeners is API 30 and this runs on 28, so count the calls instead.
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            Haptics.setOnLongClickListener(target, v -> {
                calls.incrementAndGet();
                return true;
            });
            assertTrue(target.performLongClick());
            assertEquals(1, calls.get());
            Haptics.setOnLongClickListener(target, null);
            target.performLongClick();
            assertEquals("a cleared listener isn't called again", 1, calls.get());
        });
    }
}

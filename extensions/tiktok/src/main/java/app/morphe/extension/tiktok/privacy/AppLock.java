/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.wellbeing.SessionLockOverlay;
import app.morphe.extension.tiktok.wellbeing.SessionPlaybackHold;

/**
 * Keeps TikTok behind the phone's own unlock.
 *
 * <p>It covers rather than redirects. Each of TikTok's screens that starts while the lock is up
 * gets an opaque panel on its decor view before its first frame, so the screen a shared link
 * opened is still the one underneath, intent and all, and is simply uncovered once the reader
 * unlocks. The lock comes up when the process starts and when TikTok comes back after longer
 * in the background than the chosen delay. The ask is BiometricPrompt from Android 9, with the
 * screen lock offered in the same prompt from Android 10, and the system's confirm-credential
 * screen before that. A phone with no screen lock is let in and told why.
 *
 * <p>The callbacks are registered from the host application's attachBaseContext, before any
 * activity exists, so the trampoline a link opens through is covered as well as the feed.
 */
@SuppressWarnings("deprecation")
public final class AppLock {
    static final String COVER_TAG = "hushfeed_app_lock";
    static final String CREDENTIAL_TAG = "hushfeed_app_lock_credential";
    /** Long enough for a link's trampoline to hand over and finish before anything is asked. */
    static final long ASK_DELAY_MS = 250;
    private static final long NEVER = Long.MIN_VALUE;
    private static final String USE_BIOMETRIC = "android.permission.USE_BIOMETRIC";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private enum Asking { NONE, PROMPT, CREDENTIAL }

    private static boolean installed;
    private static Boolean mainProcess;
    private static final Set<Activity> STARTED = weakSet();
    private static final Set<Activity> COVERED = weakSet();
    /** Windows given FLAG_SECURE on the way out, below Android 13, to take off on return. */
    private static final Set<Activity> SECURED = weakSet();
    /** Screens told to leave no recents screenshot, Android 13 and up. */
    private static final Set<Activity> UNSNAPPED = weakSet();
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    /** A new process has been away, which is how a cold start counts. */
    private static boolean away = true;
    private static long leftAt = NEVER;
    private static boolean locked;
    private static Asking asking = Asking.NONE;
    /** The reader closed the prompt. The panel's button asks again; nothing else does. */
    private static boolean declined;
    private static int systemCancels;
    private static boolean toldNoScreenLock;

    /** Stands in for the system prompt in tests, which Robolectric can't show. */
    interface Asker {
        void ask(Activity activity);
    }

    static Asker askerForTests;

    private static final Runnable ASK = () -> guard(() -> {
        Activity activity = resumed.get();
        if (activity != null && !declined) ask(activity);
    });

    private AppLock() {
    }

    private static Set<Activity> weakSet() {
        return Collections.newSetFromMap(new WeakHashMap<>());
    }

    /**
     * From the host application's attachBaseContext, right after its call to the framework's.
     * Nothing here reads a setting: the extension's context is set just after this.
     */
    public static void install(Context context) {
        try {
            if (installed || !(context instanceof Application)) return;
            installed = true;
            ((Application) context).registerActivityLifecycleCallbacks(CALLBACKS);
        } catch (Throwable error) {
            Logger.printInfo(() -> "App lock could not follow TikTok's screens: " + error);
        }
    }

    private static final Application.ActivityLifecycleCallbacks CALLBACKS =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityStarted(Activity activity) {
                    guard(() -> onStarted(activity));
                }

                @Override public void onActivityResumed(Activity activity) {
                    guard(() -> onResumed(activity));
                }

                // Before TikTok's own pause, so the flag is in as early as an app can put it.
                @Override public void onActivityPrePaused(Activity activity) {
                    guard(() -> hideFromRecents(activity));
                }

                @Override public void onActivityPaused(Activity activity) {
                    guard(() -> onPaused(activity));
                }

                @Override public void onActivityStopped(Activity activity) {
                    guard(() -> onStopped(activity));
                }

                @Override public void onActivityCreated(Activity activity, Bundle state) {
                }

                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
                }

                @Override public void onActivityDestroyed(Activity activity) {
                }
            };

    /** Every callback runs inside TikTok's own lifecycle, where nothing may escape. */
    private static void guard(Runnable step) {
        try {
            step.run();
        } catch (Throwable error) {
            Logger.printException(() -> "App lock failed", error);
        }
    }

    /** The safe mode screens run in a process of their own and stay out of this. */
    private static boolean inMainProcess() {
        Boolean known = mainProcess;
        if (known == null) {
            known = Utils.isMainProcess();
            mainProcess = known;
        }
        return known;
    }

    /** Kept while Hushfeed is paused, so pausing is no way past the lock. */
    private static boolean enabled() {
        return Settings.APP_LOCK.get();
    }

    static long timeoutMillis() {
        try {
            return Math.max(0L, Long.parseLong(Settings.APP_LOCK_TIMEOUT.get().trim())) * 60_000L;
        } catch (NumberFormatException unreadable) {
            return 0L;
        }
    }

    private static void onStarted(Activity activity) {
        if (!inMainProcess()) return;
        STARTED.add(activity);
        boolean enabled = enabled();
        if (away) {
            away = false;
            if (enabled && !locked && (leftAt == NEVER
                    || SystemClock.elapsedRealtime() - leftAt >= timeoutMillis())) {
                lock(activity);
            }
        }
        if (locked && !enabled) unlock(false);
        // Before the screen's first frame, which comes after its resume.
        if (locked) cover(activity);
        syncRecentsScreenshot(activity, enabled);
    }

    private static void onResumed(Activity activity) {
        if (!inMainProcess()) return;
        resumed = new WeakReference<>(activity);
        if (locked) keepCovered(activity);
        showInRecentsAgain(activity);
        syncRecentsScreenshot(activity, enabled());
    }

    private static void keepCovered(Activity activity) {
        if (!cover(activity)) {
            // A screen with no content of its own gets its decor only after this resume.
            MAIN.post(() -> guard(() -> {
                if (locked && !activity.isFinishing()) cover(activity);
            }));
        }
        // A screen of TikTok's in front means the confirm screen isn't. Its answer comes before
        // the resume of the screen that asked, so one still outstanding was left behind, as when
        // a link's trampoline asked and then finished.
        if (asking == Asking.CREDENTIAL) asking = Asking.NONE;
        if (!declined && asking == Asking.NONE) {
            MAIN.removeCallbacks(ASK);
            MAIN.postDelayed(ASK, ASK_DELAY_MS);
        }
    }

    private static void onPaused(Activity activity) {
        if (!inMainProcess()) return;
        if (resumed.get() == activity) resumed = new WeakReference<>(null);
        hideFromRecents(activity);
    }

    private static void onStopped(Activity activity) {
        if (!inMainProcess()) return;
        STARTED.remove(activity);
        // A rotation stops the screen and starts its replacement, and the confirm screen stops
        // TikTok while it asks. Neither is TikTok being left.
        if (!STARTED.isEmpty() || activity.isChangingConfigurations() || asking != Asking.NONE) return;
        away = true;
        leftAt = SystemClock.elapsedRealtime();
        declined = false;
        systemCancels = 0;
    }

    private static void lock(Activity activity) {
        if (!deviceSecure(activity)) {
            tellNoScreenLock();
            return;
        }
        locked = true;
        declined = false;
        systemCancels = 0;
        // The cover hides the feed but not its sound, and TikTok plays on under a prompt.
        SessionPlaybackHold.pauseForLock();
        Logger.printDebug(() -> "App lock is up");
    }

    private static void unlock(boolean resume) {
        locked = false;
        asking = Asking.NONE;
        declined = false;
        systemCancels = 0;
        MAIN.removeCallbacks(ASK);
        for (Activity activity : new ArrayList<>(COVERED)) uncover(activity);
        COVERED.clear();
        SessionPlaybackHold.releaseForLock(resume);
    }

    static boolean isLocked() {
        return locked;
    }

    // ------------------------------------------------------------------ asking

    static void ask(Activity activity) {
        MAIN.removeCallbacks(ASK);
        if (!locked || asking != Asking.NONE || activity == null || activity.isFinishing()) return;
        if (!deviceSecure(activity)) {
            letInWithoutScreenLock();
            return;
        }
        Asker asker = askerForTests;
        if (asker != null) {
            asking = Asking.PROMPT;
            asker.ask(activity);
            return;
        }
        // TikTok declares the permission itself. Without it the confirm screen still works.
        if (Build.VERSION.SDK_INT >= 28
                && activity.checkSelfPermission(USE_BIOMETRIC) == PackageManager.PERMISSION_GRANTED) {
            asking = Asking.PROMPT;
            try {
                Prompt.show(activity);
                return;
            } catch (RuntimeException refused) {
                asking = Asking.NONE;
                Logger.printInfo(() -> "App lock's prompt was refused, so it asks with the confirm screen: " + refused);
            }
        }
        askForCredential(activity);
    }

    private static void askForCredential(Activity activity) {
        KeyguardManager keyguard = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
        String title = L10n.t(activity, "Unlock TikTok");
        Intent intent = keyguard == null ? null : keyguard.createConfirmDeviceCredentialIntent(title, null);
        if (intent == null) {
            letInWithoutScreenLock();
            return;
        }
        FragmentManager fragments = activity.getFragmentManager();
        if (fragments == null || fragments.isDestroyed()) return;
        asking = Asking.CREDENTIAL;
        CredentialRequest request = new CredentialRequest();
        Bundle arguments = new Bundle();
        arguments.putParcelable(CredentialRequest.INTENT, intent);
        request.setArguments(arguments);
        // The answer comes back to this headless fragment, not to TikTok's own activity.
        fragments.beginTransaction().add(request, CREDENTIAL_TAG).commitAllowingStateLoss();
    }

    static void succeeded() {
        asking = Asking.NONE;
        unlock(true);
    }

    static void credentialAnswered(boolean confirmed) {
        asking = Asking.NONE;
        if (confirmed) {
            unlock(true);
        } else {
            declined = true;
        }
    }

    /** The prompt ended without an unlock. {@code activity} is the screen it was shown on. */
    static void onPromptError(int code, Activity activity) {
        asking = Asking.NONE;
        switch (code) {
            case BiometricPrompt.BIOMETRIC_ERROR_CANCELED:
                // The system took it down: TikTok left the screen, or the screen it was asked
                // on went away. Ask again on the screen in front, but not in a loop.
                if (++systemCancels > 2) {
                    declined = true;
                } else if (resumed.get() != null) {
                    MAIN.removeCallbacks(ASK);
                    MAIN.postDelayed(ASK, ASK_DELAY_MS);
                }
                return;
            case BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL:
                letInWithoutScreenLock();
                return;
            case BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE:
            case BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT:
            case BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT:
            case BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS:
            case BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT:
                // Android 9's prompt can't offer the screen lock, so the confirm screen does.
                // From Android 10 the prompt offers it itself.
                if (Build.VERSION.SDK_INT < 29 && activity != null && !activity.isFinishing()) {
                    askForCredential(activity);
                    return;
                }
                declined = true;
                return;
            default:
                // Closed by the reader, or timed out. The panel's button asks again.
                declined = true;
        }
    }

    private static void letInWithoutScreenLock() {
        unlock(true);
        tellNoScreenLock();
    }

    private static void tellNoScreenLock() {
        if (toldNoScreenLock) return;
        toldNoScreenLock = true;
        Utils.showToastLong(L10n.t("TikTok isn't locked because this phone has no screen lock"));
    }

    private static boolean deviceSecure(Context context) {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    /** Kept apart so no API 28 type is loaded on an older phone. */
    @RequiresApi(28)
    private static final class Prompt {
        static void show(Activity activity) {
            Executor main = activity.getMainExecutor();
            WeakReference<Activity> owner = new WeakReference<>(activity);
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                    .setTitle(L10n.t(activity, "Unlock TikTok"));
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else if (Build.VERSION.SDK_INT == 29) {
                builder.setDeviceCredentialAllowed(true);
            } else {
                builder.setNegativeButton(L10n.t(activity, "Use screen lock"), main,
                        (dialog, which) -> guard(() -> {
                            asking = Asking.NONE;
                            Activity shown = owner.get();
                            if (shown != null && !shown.isFinishing()) askForCredential(shown);
                        }));
            }
            builder.build().authenticate(new CancellationSignal(), main,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            guard(AppLock::succeeded);
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence message) {
                            guard(() -> onPromptError(code, owner.get()));
                        }
                    });
        }
    }

    /**
     * Starts the system's confirm-credential screen and takes its answer. A framework fragment,
     * because the answer to startActivityForResult goes to whoever asked, and TikTok's
     * activities aren't ours to hook for it.
     */
    public static final class CredentialRequest extends Fragment {
        static final String INTENT = "hushfeed_confirm_intent";
        static final int REQUEST = 0x4C4B;

        public CredentialRequest() {
        }

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            // Restored with its screen already up, or already answered: nothing to start.
            if (state != null) return;
            Bundle arguments = getArguments();
            Intent intent = arguments == null ? null : arguments.getParcelable(INTENT);
            try {
                if (intent == null) throw new IllegalStateException("no confirm intent");
                startActivityForResult(intent, REQUEST);
            } catch (RuntimeException unavailable) {
                leave();
                Logger.printInfo(() -> "App lock has no confirm screen to ask with: " + unavailable);
                guard(AppLock::letInWithoutScreenLock);
            }
        }

        @Override
        public void onActivityResult(int request, int result, Intent data) {
            if (request != REQUEST) return;
            leave();
            guard(() -> credentialAnswered(result == Activity.RESULT_OK));
        }

        private void leave() {
            FragmentManager fragments = getFragmentManager();
            if (fragments != null) fragments.beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }

    // ------------------------------------------------------------------ the cover

    /** Covers the screen, or answers false when it has no decor to cover yet. */
    private static boolean cover(Activity activity) {
        ViewGroup decor = decorOf(activity);
        if (decor == null) return false;
        COVERED.add(activity);
        if (coverIn(decor) != null) return true;

        FrameLayout cover = new FrameLayout(activity);
        cover.setTag(COVER_TAG);
        // Opaque: the scrim the daily hold uses lets a little of the feed through.
        cover.setBackgroundColor(Color.BLACK);
        // Swallows every touch, so nothing under it can be pressed.
        cover.setClickable(true);
        cover.setFocusable(true);
        // Above anything TikTok raised on the decor, with no shadow to draw for it.
        cover.setOutlineProvider(null);
        cover.setTranslationZ(SettingsUi.dp(activity, 1000));

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int side = SettingsUi.dp(activity, 24);
        column.setPadding(side, side, side, side);

        String titleText = L10n.t(activity, "TikTok is locked");
        if (Build.VERSION.SDK_INT >= 28) cover.setAccessibilityPaneTitle(titleText);
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(SettingsUi.OVERLAY_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_HEADLINE);
        title.setGravity(Gravity.CENTER);
        column.addView(title);

        // The way back to the prompt once it was closed.
        TextView unlock = new TextView(activity);
        String unlockText = L10n.t(activity, "Unlock");
        unlock.setText(unlockText);
        unlock.setContentDescription(unlockText);
        SettingsUi.markAsButton(unlock);
        unlock.setTextColor(SettingsUi.OVERLAY_TEXT);
        unlock.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY);
        unlock.setGravity(Gravity.CENTER);
        unlock.setBackground(SettingsUi.overlayControl(activity, SettingsUi.RADIUS_OVERLAY));
        unlock.setFocusable(true);
        int padding = SettingsUi.dp(activity, 20);
        unlock.setPadding(padding, SettingsUi.dp(activity, 14), padding, SettingsUi.dp(activity, 14));
        unlock.setMinimumHeight(SettingsUi.dp(activity, 48));
        LinearLayout.LayoutParams unlockParams = new LinearLayout.LayoutParams(-2, -2);
        unlockParams.topMargin = SettingsUi.dp(activity, 28);
        unlock.setLayoutParams(unlockParams);
        unlock.setOnClickListener(view -> guard(() -> {
            declined = false;
            systemCancels = 0;
            ask(activity);
        }));
        column.addView(unlock);

        cover.addView(column, new FrameLayout.LayoutParams(-1, -1));
        decor.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        // A screen reader can't reach what is under it either.
        SessionLockOverlay.hideBehind(decor, cover, true);
        return true;
    }

    private static void uncover(Activity activity) {
        ViewGroup decor = decorOf(activity);
        View cover = decor == null ? null : coverIn(decor);
        if (cover == null) return;
        SessionLockOverlay.hideBehind(decor, cover, false);
        decor.removeView(cover);
    }

    /** The decor if the screen has one already. Making it early would refuse a later window feature. */
    private static ViewGroup decorOf(Activity activity) {
        Window window = activity.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        return decor instanceof ViewGroup ? (ViewGroup) decor : null;
    }

    static View coverIn(ViewGroup decor) {
        for (int index = 0; index < decor.getChildCount(); index++) {
            View child = decor.getChildAt(index);
            if (COVER_TAG.equals(child.getTag())) return child;
        }
        return null;
    }

    // ------------------------------------------------------------------ recent apps

    /**
     * Android 13 and up: the screen leaves no screenshot in recent apps while the switch is on,
     * and gets it back once the switch is off. A screen the switch never touched is left alone.
     */
    private static void syncRecentsScreenshot(Activity activity, boolean enabled) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (enabled) {
            if (UNSNAPPED.add(activity)) activity.setRecentsScreenshotEnabled(false);
        } else if (UNSNAPPED.remove(activity)) {
            activity.setRecentsScreenshotEnabled(true);
        }
    }

    /**
     * Below Android 13 there is no such switch, so a screen on its way out carries FLAG_SECURE,
     * which the recents screenshot leaves blank, and loses it again on return. Screenshots inside
     * TikTok keep working. A window TikTok made secure itself is left as it is.
     */
    private static void hideFromRecents(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33 || !inMainProcess() || SECURED.contains(activity) || !enabled()) return;
        Window window = activity.getWindow();
        if (window == null) return;
        if ((window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        SECURED.add(activity);
    }

    private static void showInRecentsAgain(Activity activity) {
        if (!SECURED.remove(activity)) return;
        Window window = activity.getWindow();
        if (window != null) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    // ------------------------------------------------------------------ tests

    static void resetForTests(Application application) {
        if (installed && application != null) application.unregisterActivityLifecycleCallbacks(CALLBACKS);
        installed = false;
        mainProcess = null;
        STARTED.clear();
        COVERED.clear();
        SECURED.clear();
        UNSNAPPED.clear();
        resumed = new WeakReference<>(null);
        away = true;
        leftAt = NEVER;
        locked = false;
        asking = Asking.NONE;
        declined = false;
        systemCancels = 0;
        toldNoScreenLock = false;
        askerForTests = null;
        MAIN.removeCallbacks(ASK);
        SessionPlaybackHold.releaseForLock(false);
    }
}

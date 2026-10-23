/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * A dot in the top corner while TikTok holds the camera or records sound.
 *
 * <p>A green square for the camera, an orange diamond for the microphone, both when both. Two
 * sources decide it. On Android 11 and up, the system's own record of this app's active camera
 * and recording ops, which is what the status bar's privacy dots read, so a recording TikTok
 * makes natively counts too. And on every version, the patched call sites: a camera counts from
 * the moment it opens until it is released or closed, a recorder from start until stop or
 * release. The dot sits on the decor view of whichever activity is on top, follows the top
 * activity while an access is live, and takes no touches. With the switch off nothing is drawn;
 * both sources are still kept so turning it on mid-access shows the truth.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class CameraMicIndicator {
    private static final AtomicInteger CAMERAS = new AtomicInteger();
    private static final AtomicInteger MICROPHONES = new AtomicInteger();
    /**
     * What the system says this app is doing right now, on API 30 and up. TikTok records a story's
     * sound natively, never through the Java recorder calls the patch counts, so on the S25
     * (2026-09-26) the diamond flashed for 120 ms at the start of a four-second recording.
     */
    private static volatile boolean systemCamera;
    private static volatile boolean systemMicrophone;
    /** The package the system's reports are held to; null until the watch starts. */
    private static volatile String ownPackage;
    private static boolean watching;
    private static WeakReference<Activity> top = new WeakReference<>(null);
    private static WeakReference<Activity> shownOn = new WeakReference<>(null);
    private static DotView dot;
    private static WeakReference<Application> followed = new WeakReference<>(null);

    /**
     * From the main activity's onCreate, so the screen in front is known before any camera opens.
     * TikTok's story and record cameras are scenes inside one activity that has already resumed
     * when the camera opens; tracking that only began with the first access never saw it resume,
     * and the mark went onto the main activity underneath it (the S25, 2026-09-26).
     */
    public static void install(Activity activity) {
        if (activity == null) return;
        follow(activity.getApplication());
        if (!watching && Build.VERSION.SDK_INT >= 30) {
            watching = true;
            SystemOps.watch(activity.getApplicationContext());
        }
    }

    /**
     * The system's report that one of this app's ops started or stopped. Only this uid and package
     * count: without the WATCH_APPOPS permission the system reports nothing else, and the check
     * keeps it that way whatever a ROM does.
     */
    static void onOpActiveChanged(String op, int uid, String packageName, boolean active) {
        String own = ownPackage;
        if (uid != Process.myUid() || own == null || !own.equals(packageName)) return;
        if (AppOpsManager.OPSTR_CAMERA.equals(op)) {
            systemCamera = active;
        } else if (AppOpsManager.OPSTR_RECORD_AUDIO.equals(op)) {
            systemMicrophone = active;
        } else {
            return;
        }
        Logger.printInfo(() -> "Camera/mic indicator: system reports " + op + (active ? " active" : " inactive"));
        Utils.runOnMainThreadNowOrLater(CameraMicIndicator::refresh);
    }

    /** Kept apart so no API 30 type is loaded on an older phone. */
    @RequiresApi(30)
    private static final class SystemOps {
        static void watch(Context context) {
            if (context == null) return;
            try {
                AppOpsManager ops = context.getSystemService(AppOpsManager.class);
                if (ops == null) return;
                String own = context.getPackageName();
                ownPackage = own;
                String[] watched = {AppOpsManager.OPSTR_CAMERA, AppOpsManager.OPSTR_RECORD_AUDIO};
                ops.startWatchingActive(watched, context.getMainExecutor(), CameraMicIndicator::onOpActiveChanged);
                // After the watch starts, so an access that ends in between is reported, not lost.
                int uid = Process.myUid();
                for (String op : watched) {
                    if (ops.isOpActive(op, uid, own)) onOpActiveChanged(op, uid, own, true);
                }
            } catch (Throwable error) {
                // Inside TikTok's onCreate, so nothing may escape. The call sites still count, so
                // this is a weaker mark, not a broken one.
                Logger.printInfo(() -> "Camera/mic indicator could not watch the system's camera and microphone state: " + error);
            }
        }
    }

    /** The camera opened and came back non-null; a failed open shows nothing. */
    public static void onCameraOpened(Camera camera) {
        if (camera != null) onCameraStart();
    }

    public static void onCameraStart() {
        change(CAMERAS, 1, "camera opened");
    }

    public static void onCameraStop() {
        change(CAMERAS, -1, "camera released");
    }

    public static void onMicStart() {
        change(MICROPHONES, 1, "microphone recording");
    }

    public static void onMicStop() {
        change(MICROPHONES, -1, "microphone released");
    }

    private static void change(AtomicInteger counter, int delta, String what) {
        // Clamped at zero: a recorder that is stopped and then released reports its end twice.
        int now;
        do {
            int current = counter.get();
            now = Math.max(0, current + delta);
            if (counter.compareAndSet(current, now)) break;
        } while (true);
        Logger.printInfo(() -> "Camera/mic indicator: " + what
                + " (camera " + CAMERAS.get() + ", microphone " + MICROPHONES.get() + ")");
        Utils.runOnMainThreadNowOrLater(CameraMicIndicator::refresh);
    }

    private static boolean switchOn() {
        return Utils.getContext() != null && Settings.CAMERA_MIC_INDICATOR.get();
    }

    private static void refresh() {
        try {
            boolean camera = CAMERAS.get() > 0 || systemCamera;
            boolean microphone = MICROPHONES.get() > 0 || systemMicrophone;
            boolean wanted = (camera || microphone) && switchOn();
            Activity activity = wanted ? currentActivity() : null;
            Activity previous = shownOn.get();
            if (previous != null && previous != activity) remove(previous);
            if (activity == null) {
                shownOn = new WeakReference<>(null);
                return;
            }
            follow(activity.getApplication());
            DotView view = attach(activity);
            view.show(camera, microphone);
            shownOn = new WeakReference<>(activity);
        } catch (Throwable error) {
            Logger.printException(() -> "Camera/mic indicator could not draw", error);
        }
    }

    private static Activity currentActivity() {
        Activity current = top.get();
        if (current != null && !current.isFinishing()) return current;
        return Utils.getActivity();
    }

    /** Follows the top activity for as long as the process lives, registered once per application. */
    private static void follow(Application application) {
        if (application == null || followed.get() == application) return;
        followed = new WeakReference<>(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity resumed) {
                top = new WeakReference<>(resumed);
                refresh();
            }

            @Override public void onActivityDestroyed(Activity destroyed) {
                if (shownOn.get() == destroyed) {
                    dot = null;
                    shownOn = new WeakReference<>(null);
                }
                if (top.get() == destroyed) top = new WeakReference<>(null);
            }

            @Override public void onActivityCreated(Activity created, Bundle state) {}
            @Override public void onActivityStarted(Activity started) {}
            @Override public void onActivityPaused(Activity paused) {}
            @Override public void onActivityStopped(Activity stopped) {}
            @Override public void onActivitySaveInstanceState(Activity saved, Bundle state) {}
        });
    }

    private static DotView attach(Activity activity) {
        if (dot != null && dot.getParent() != null && shownOn.get() == activity) return dot;
        remove(shownOn.get());
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        DotView view = new DotView(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        int edge = dp(activity, 10);
        params.topMargin = statusBarHeight(decor) + edge;
        params.setMarginEnd(edge);
        decor.addView(view, params);
        dot = view;
        return view;
    }

    private static void remove(Activity activity) {
        if (dot == null) return;
        if (dot.getParent() instanceof ViewGroup) ((ViewGroup) dot.getParent()).removeView(dot);
        dot = null;
    }

    /**
     * Where the status bar ends. TikTok's camera screen draws under a status bar it still shows and
     * reports no top inset, so the mark sat on the battery icon (the S25, 2026-09-26): the bar's
     * own height is used whenever the window reports none.
     */
    static int statusBarHeight(View decor) {
        WindowInsets insets = decor.getRootWindowInsets();
        int top = insets == null ? 0 : insets.getSystemWindowInsetTop();
        if (insets != null && Build.VERSION.SDK_INT >= 30) {
            top = Math.max(top, insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top);
        }
        if (top <= 0) {
            int id = decor.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id != 0) top = decor.getResources().getDimensionPixelSize(id);
        }
        return top;
    }

    private static int dp(Context context, int value) {
        return SettingsUi.dp(context, value);
    }

    /**
     * One or two filled marks with a hairline dark ring, so they read on any video. The camera
     * is a rounded square and the microphone a diamond: they were two dots told apart by colour
     * alone, which a reader who can't separate green from orange couldn't do.
     */
    static final class DotView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path diamond = new android.graphics.Path();
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean camera;
        private boolean microphone;

        DotView(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Math.max(1f, dp(context, 1)));
            ring.setColor(SettingsUi.INDICATOR_RING);
            setClickable(false);
            setFocusable(false);
            setElevation(dp(context, 24));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void show(boolean cameraOn, boolean microphoneOn) {
            boolean changed = camera != cameraOn || microphone != microphoneOn;
            camera = cameraOn;
            microphone = microphoneOn;
            Context context = getContext();
            String description = camera && microphone
                    ? L10n.t(context, "Camera and microphone in use")
                    : camera ? L10n.t(context, "Camera in use") : L10n.t(context, "Microphone in use");
            setContentDescription(description);
            if (changed) {
                requestLayout();
                invalidate();
                announceForAccessibility(description);
            }
        }

        boolean camera() { return camera; }

        boolean microphone() { return microphone; }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int size = dp(getContext(), 12);
            int gap = dp(getContext(), 6);
            int count = (camera ? 1 : 0) + (microphone ? 1 : 0);
            int width = count == 0 ? 0 : count * size + (count - 1) * gap;
            setMeasuredDimension(width, count == 0 ? 0 : size);
        }

        @Override protected void onDraw(Canvas canvas) {
            int size = dp(getContext(), 12);
            int gap = dp(getContext(), 6);
            float half = size / 2f;
            float x = half;
            // The ring is stroked on the outline, so half of it lies outside each shape. The shapes
            // sit that far inside the view, and the diamond's right-angled points further, since a
            // mitred point reaches about 1.4 times as far: at 0.5px the outline was cut off there.
            float edge = ring.getStrokeWidth() / 2f;
            float point = ring.getStrokeWidth() * 0.75f;
            if (camera) {
                fill.setColor(SettingsUi.INDICATOR_CAMERA);
                // Square cornered: the scale's 0, and the plainest contrast with the diamond.
                android.graphics.RectF box = new android.graphics.RectF(
                        x - half + edge, edge, x + half - edge, size - edge);
                canvas.drawRect(box, fill);
                canvas.drawRect(box, ring);
                x += size + gap;
            }
            if (microphone) {
                fill.setColor(SettingsUi.INDICATOR_MICROPHONE);
                diamond.reset();
                diamond.moveTo(x, point);
                diamond.lineTo(x + half - point, half);
                diamond.lineTo(x, size - point);
                diamond.lineTo(x - half + point, half);
                diamond.close();
                canvas.drawPath(diamond, fill);
                canvas.drawPath(diamond, ring);
            }
        }
    }

    /** What is live right now, for tests and the diagnostic report. */
    static int cameras() { return CAMERAS.get(); }

    static int microphones() { return MICROPHONES.get(); }

    static DotView shownDot() { return dot; }

    static void resetForTests() {
        CAMERAS.set(0);
        MICROPHONES.set(0);
        systemCamera = false;
        systemMicrophone = false;
        ownPackage = null;
        watching = false;
        remove(shownOn.get());
        shownOn = new WeakReference<>(null);
        top = new WeakReference<>(null);
        followed = new WeakReference<>(null);
    }

    private CameraMicIndicator() {}
}

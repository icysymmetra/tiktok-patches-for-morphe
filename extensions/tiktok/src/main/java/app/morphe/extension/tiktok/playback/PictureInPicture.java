/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.playback;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.SystemClock;
import android.util.Rational;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.RequiresApi;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.wellbeing.SessionPlaybackHold;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A video playing in the feed, or in a video opened from a profile, search or a sound, keeps going
 * in a small window when the reader leaves TikTok. TikTok has picture-in-picture for LIVE only. Its
 * feed activity already declares support for it (for LIVE), and the patch declares it for the
 * detail pager too, which takes size changes itself like the feed does.
 *
 * <p>The patch hands over two moments: leaving (onUserLeaveHint, in the base activity both windows
 * share) and the window opening or closing (ComponentActivity's onPictureInPictureModeChanged).
 * Leaving opens the window only while the player reports the video on screen playing, so a paused
 * video, a profile page or the inbox leave TikTok as they always did, and TikTok's own LIVE window
 * is left to TikTok. In the window, everything but the video is set aside and comes back as it was
 * when the window closes or grows back into TikTok. TikTok may stop its player as the activity
 * pauses, so the video is started again once the window is up, unless the reader paused it there.
 * Play and pause go through TikTok's own player controls, the session hold's bridge. Closing the
 * window stops the video, unless Keep playing in the background is on.
 *
 * <p>Read when the reader leaves, so the switch needs no restart. Paused, it answers off.
 */
public final class PictureInPicture {
    static final String ACTION_TOGGLE = "app.morphe.extension.tiktok.PICTURE_IN_PICTURE_TOGGLE";

    /**
     * Carried by the window's own button. Before Android 13 a receiver registered at run time
     * can't be kept from other apps, so any of them could send the action and pause or play
     * the video. They can't read this, since only the button's PendingIntent holds it.
     */
    static final String EXTRA_TOKEN = "app.morphe.extension.tiktok.PICTURE_IN_PICTURE_TOKEN";
    private static final String TOKEN = java.util.UUID.randomUUID().toString();

    /** Android's limits on a window's shape: no narrower than 1:2.39 and no wider than 2.39:1. */
    static final Rational NARROWEST = new Rational(100, 239);
    static final Rational WIDEST = new Rational(239, 100);

    /** A second leave hint for the same activity this soon, from a second hooked declaration, is the same leave. */
    private static final long REPEAT_MS = 1000;

    /** When TikTok's own pause has landed after the window opens, and a second look in case it was late. */
    private static final long[] RESUME_CHECKS_MS = {600, 1500};

    /** How long after the window closes TikTok has to be back in front, or the reader closed the window. */
    private static final long CLOSED_CHECK_MS = 500;

    // Main thread only, like every entry point here.
    private static WeakReference<Activity> opened = new WeakReference<>(null);
    private static long openedAt;
    private static boolean readerPaused;
    private static final List<SetAside> setAside = new ArrayList<>();
    private static WeakReference<View> asideVideo = new WeakReference<>(null);
    private static WeakReference<View> asideRoot = new WeakReference<>(null);
    private static WeakReference<View> keptOn = new WeakReference<>(null);
    private static BroadcastReceiver toggle;

    private PictureInPicture() {
    }

    private static final class SetAside {
        final WeakReference<View> view;
        final int visibility;

        SetAside(View view) {
            this.view = new WeakReference<>(view);
            this.visibility = view.getVisibility();
        }
    }

    static boolean enabled() {
        return SettingsStatus.pictureInPictureEnabled && Settings.PICTURE_IN_PICTURE.get();
    }

    /** Called first in the feed windows' shared onUserLeaveHint. */
    public static void onUserLeaveHint(Activity activity) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            open(activity);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Picture-in-picture couldn't open", failure);
        }
    }

    @RequiresApi(26)
    private static void open(Activity activity) {
        if (activity == null || !enabled() || !FeedVisibility.isFeedWindow(activity)) return;
        if (activity.isInPictureInPictureMode()) return;
        long now = SystemClock.uptimeMillis();
        if (opened.get() == activity && now - openedAt < REPEAT_MS) return;
        if (!Boolean.TRUE.equals(SessionPlaybackHold.currentPlaying())) return;
        View video = videoView(activity.getWindow().getDecorView());
        if (video == null) return;
        readerPaused = false;
        if (activity.enterPictureInPictureMode(params(activity, video, true))) {
            opened = new WeakReference<>(activity);
            openedAt = now;
        }
    }

    /** Called first in ComponentActivity's onPictureInPictureModeChanged(boolean, Configuration). */
    public static void onModeChanged(Activity activity, boolean inWindow) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            follow(activity, inWindow);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Picture-in-picture couldn't follow the window", failure);
        }
    }

    @RequiresApi(26)
    private static void follow(Activity activity, boolean inWindow) {
        // Only the window this class opened: TikTok's own LIVE window is TikTok's.
        if (activity == null || opened.get() != activity) return;
        if (inWindow) {
            keepAsideWhileUp(activity.getWindow().getDecorView());
            listen(activity);
            WeakReference<Activity> owner = new WeakReference<>(activity);
            for (long delay : RESUME_CHECKS_MS) {
                Utils.runOnMainThreadDelayed(() -> keepPlaying(owner.get()), delay);
            }
        } else {
            bringBack();
            stopListening(activity);
            opened.clear();
            WeakReference<Activity> owner = new WeakReference<>(activity);
            Utils.runOnMainThreadDelayed(() -> stopIfClosed(owner.get()), CLOSED_CHECK_MS);
        }
    }

    /**
     * Closing the window, rather than growing it back into TikTok, stops the video the way leaving
     * TikTok does, since the window started it again after TikTok's own pause. Keep playing in the
     * background asks for the opposite, so with it on the video plays on.
     */
    private static void stopIfClosed(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.hasWindowFocus()) return;
        if (SettingsStatus.backgroundPlayEnabled && Settings.BACKGROUND_PLAY.get()) return;
        if (Boolean.TRUE.equals(SessionPlaybackHold.currentPlaying())) {
            SessionPlaybackHold.setCurrentPlaying(false);
        }
    }

    /** Starts the video again when TikTok stopped it as the window opened, unless the reader paused it. */
    @RequiresApi(26)
    private static void keepPlaying(Activity activity) {
        if (activity == null || opened.get() != activity || readerPaused) return;
        if (!activity.isInPictureInPictureMode()) return;
        if (Boolean.FALSE.equals(SessionPlaybackHold.currentPlaying())) {
            SessionPlaybackHold.setCurrentPlaying(true);
        }
    }

    /** The window's play or pause button. */
    static void onToggle() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            flip();
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Picture-in-picture couldn't update its button", failure);
        }
    }

    @RequiresApi(26)
    private static void flip() {
        Activity activity = opened.get();
        if (activity == null) return;
        boolean play = !Boolean.TRUE.equals(SessionPlaybackHold.currentPlaying());
        if (!SessionPlaybackHold.setCurrentPlaying(play)) return;
        readerPaused = !play;
        activity.setPictureInPictureParams(params(activity, videoView(activity.getWindow().getDecorView()), play));
    }

    /**
     * The video on screen: the largest TextureView or SurfaceView showing, which is what TikTok's
     * player draws into. Neighbouring cells sit off screen and show nothing. The frame a Long press
     * saves is read from the same view.
     */
    public static View videoView(View root) {
        List<View> found = new ArrayList<>();
        collect(root, found);
        View best = null;
        long bestArea = 0;
        Rect bounds = new Rect();
        for (View view : found) {
            if (!view.isShown() || !view.getGlobalVisibleRect(bounds)) continue;
            long area = (long) bounds.width() * bounds.height();
            if (area > bestArea) {
                best = view;
                bestArea = area;
            }
        }
        return best;
    }

    private static void collect(View view, List<View> found) {
        if (view == null) return;
        if (view instanceof TextureView || view instanceof SurfaceView) found.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), found);
        }
    }

    /**
     * Sets aside every view showing beside the video and its parents, up to the window: the
     * captions, the right column, the tabs and the neighbouring cells. Each keeps what it was, and
     * {@link #bringBack} restores only those TikTok left alone meanwhile.
     */
    static void setAside(View root) {
        View video = videoView(root);
        if (video == null) return;
        asideVideo = new WeakReference<>(video);
        asideRoot = new WeakReference<>(root);
        hideBeside(video, root);
    }

    /**
     * Keeps the video alone until {@link #bringBack}. TikTok shows the cell's caption, right
     * column and search bar again after the window opens, so on 47.1.4 they sat in the small
     * window over the video (S22, 2026-10-08). Every frame sets aside again whatever TikTok has
     * shown beside the video since. The frame still draws: a skipped one could hold a
     * TextureView's video still if TikTok shows a view on every layout.
     */
    static void keepAsideWhileUp(View root) {
        stopKeeping();
        setAside(root);
        if (asideVideo.get() == null) return;
        root.getViewTreeObserver().addOnPreDrawListener(KEEP_ASIDE);
        keptOn = new WeakReference<>(root);
    }

    private static final ViewTreeObserver.OnPreDrawListener KEEP_ASIDE = () -> {
        View video = asideVideo.get();
        View root = asideRoot.get();
        if (video != null && root != null && video.isAttachedToWindow()) hideBeside(video, root);
        return true;
    };

    private static void hideBeside(View video, View root) {
        View child = video;
        ViewParent parent = child.getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View sibling = group.getChildAt(i);
                if (sibling == child || sibling.getVisibility() != View.VISIBLE) continue;
                if (!isSetAside(sibling)) setAside.add(new SetAside(sibling));
                sibling.setVisibility(View.INVISIBLE);
            }
            if (group == root) break;
            child = group;
            parent = group.getParent();
        }
    }

    private static boolean isSetAside(View view) {
        for (SetAside entry : setAside) {
            if (entry.view.get() == view) return true;
        }
        return false;
    }

    private static void stopKeeping() {
        View root = keptOn.get();
        if (root != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnPreDrawListener(KEEP_ASIDE);
        }
        keptOn = new WeakReference<>(null);
    }

    static void bringBack() {
        stopKeeping();
        asideVideo = new WeakReference<>(null);
        asideRoot = new WeakReference<>(null);
        for (SetAside entry : setAside) {
            View view = entry.view.get();
            if (view != null && view.getVisibility() == View.INVISIBLE) view.setVisibility(entry.visibility);
        }
        setAside.clear();
    }

    /** The window's shape: the video's own, held to what Android takes, or null when unknown. */
    static Rational shape(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        Rational shape = new Rational(width, height);
        if (shape.compareTo(NARROWEST) < 0) return NARROWEST;
        if (shape.compareTo(WIDEST) > 0) return WIDEST;
        return shape;
    }

    /** The post's own video size, read off the player's current post, else the view the video draws into. */
    private static Rational videoShape(View video) {
        Object post = SessionPlaybackHold.currentPlayingAweme();
        Object model = post == null ? null : Reflect.invoke(post, "getVideo");
        Object width = model == null ? null : Reflect.invoke(model, "getWidth");
        Object height = model == null ? null : Reflect.invoke(model, "getHeight");
        if (width instanceof Integer && height instanceof Integer) {
            Rational shape = shape((Integer) width, (Integer) height);
            if (shape != null) return shape;
        }
        return video == null ? null : shape(video.getWidth(), video.getHeight());
    }

    @RequiresApi(26)
    private static PictureInPictureParams params(Activity activity, View video, boolean playing) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        Rational shape = videoShape(video);
        if (shape != null) builder.setAspectRatio(shape);
        builder.setActions(actions(activity, playing));
        return builder.build();
    }

    @RequiresApi(26)
    static List<RemoteAction> actions(Activity activity, boolean playing) {
        Intent intent = new Intent(ACTION_TOGGLE).setPackage(activity.getPackageName())
                .putExtra(EXTRA_TOKEN, TOKEN);
        PendingIntent pending = PendingIntent.getBroadcast(activity, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String label = playing ? L10n.t("Pause") : L10n.t("Play");
        Icon icon = Icon.createWithResource("android",
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        return Collections.singletonList(new RemoteAction(icon, label, label, pending));
    }

    private static void listen(Activity activity) {
        if (toggle != null) return;
        toggle = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (fromTheButton(intent)) onToggle();
            }
        };
        Context app = activity.getApplicationContext();
        IntentFilter filter = new IntentFilter(ACTION_TOGGLE);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(toggle, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(toggle, filter);
        }
    }

    static boolean fromTheButton(Intent intent) {
        try {
            return intent != null && TOKEN.equals(intent.getStringExtra(EXTRA_TOKEN));
        } catch (RuntimeException unreadable) {
            // Another app's extras can name a class this process can't unparcel.
            return false;
        }
    }

    private static void stopListening(Activity activity) {
        if (toggle == null) return;
        try {
            activity.getApplicationContext().unregisterReceiver(toggle);
        } catch (IllegalArgumentException alreadyGone) {
            // Nothing left to unregister.
        }
        toggle = null;
    }
}

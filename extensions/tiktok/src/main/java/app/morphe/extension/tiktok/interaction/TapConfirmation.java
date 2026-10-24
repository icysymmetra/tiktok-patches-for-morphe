/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.interaction;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.L10n;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TapConfirmation {
    /**
     * How long a first tap stays armed. The settings rows say this number, formatted in from
     * here, so a change to it never leaves five languages describing the old window.
     */
    public static final int CONFIRM_WINDOW_SECONDS = 4;
    private static final long CONFIRM_WINDOW_MS = CONFIRM_WINDOW_SECONDS * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String COMMENT_MODEL = "com.ss.android.ugc.aweme.comment.model.Comment";
    private static final Object NO_FIELD = new Object();
    private static final Map<Class<?>, Object> COMMENT_FIELDS = new ConcurrentHashMap<>();
    private static WeakReference<Object> armed = new WeakReference<>(null);
    private static WeakReference<View> ringed = new WeakReference<>(null);
    private static String armedKey;
    private static long armedAt;
    private static Drawable previousForeground;
    private static Drawable ring;
    private static int generation;
    private TapConfirmation() {}

    public static void onVideoChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) clear();
        else MAIN.post(TapConfirmation::clear);
    }

    public static boolean follow(View view) {
        Object video = CurrentVideoAuthor.getAweme();
        Object user = Reflect.property(video, "getAuthor", "author");
        Object status = Reflect.property(user, "getFollowStatus", "followStatus");
        String id = Reflect.string(video, "getAid", "aid");
        // The same native control can turn into a message button after following. With no
        // video in hand there is nothing for a second tap to match, and the follow went dead.
        boolean enabled = Settings.CONFIRM_FOLLOW.get() && id != null
                && (!(status instanceof Number) || ((Number) status).intValue() == 0);
        return allow(view, "follow", id, enabled);
    }

    public static boolean like(View view) {
        Object video = CurrentVideoAuthor.getAweme();
        String id = Reflect.string(video, "getAid", "aid");
        boolean enabled = Settings.CONFIRM_LIKE.get() && id != null
                && !Boolean.TRUE.equals(Reflect.invoke(video, "isLike"));
        return allow(view, "like", id, enabled);
    }

    /**
     * A story's like. {@code story} is the story's own video, which TikTok's click reads just
     * before this: the story viewer isn't the feed, so the playing video can't stand in for it.
     */
    public static boolean storyLike(View view, Object story) {
        String id = Reflect.string(story, "getAid", "aid");
        boolean enabled = Settings.CONFIRM_STORY_LIKE.get() && id != null
                && !Boolean.TRUE.equals(Reflect.invoke(story, "isLike"));
        return allow(view, view, "like", id, enabled);
    }

    /**
     * A comment's like, from the comment list's like view or a photo post's comment like, by a
     * tap on the heart or a double tap on the comment. Removing a like stays immediate. The
     * list's like view is a view itself and carries the ring; a photo post's like hands over no
     * view, so its toast alone says a second tap is needed.
     */
    public static boolean commentLike(Object owner) {
        return commentLike(owner, commentOf(owner));
    }

    /** A comment's like whose control hands over the comment it has just read. */
    public static boolean commentLike(Object owner, Object comment) {
        String id = Reflect.string(comment, "getCid", "cid");
        boolean enabled = Settings.CONFIRM_COMMENT_LIKE.get() && id != null
                && !Boolean.TRUE.equals(Reflect.invoke(comment, "isUserDigged"));
        return allow(owner, owner instanceof View ? (View) owner : null, "like", id, enabled);
    }

    /**
     * TikTok's one-tap repost from the bar under a feed video. {@code owner} is that bar's
     * component; a repost sent with a note from the repost panel never comes here.
     */
    public static boolean quickRepost(Object owner) {
        String id = Reflect.string(CurrentVideoAuthor.getAweme(), "getAid", "aid");
        return allow(owner, null, "repost", id, Settings.CONFIRM_QUICK_REPOST.get() && id != null);
    }

    static boolean allow(View view, String action, String id, boolean enabled) {
        return allow(view, view, action, id, enabled);
    }

    /**
     * {@code target} is what the second tap has to come from, and {@code ringView} shows the
     * armed state, or is null where no view is in hand and the toast alone says so.
     */
    static boolean allow(Object target, View ringView, String action, String id, boolean enabled) {
        if (!enabled || target == null) {
            clear();
            return true;
        }
        String key = action + ":" + id;
        long now = SystemClock.uptimeMillis();
        if (id != null && armed.get() == target && key.equals(armedKey)
                && now - armedAt < CONFIRM_WINDOW_MS) {
            clear();
            return true;
        }
        clear();
        armed = new WeakReference<>(target);
        armedKey = key;
        armedAt = now;
        if (ringView != null) {
            ringed = new WeakReference<>(ringView);
            previousForeground = ringView.getForeground();
            ring = armedRing(ringView);
            ringView.setForeground(ring);
            ringView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
        int token = generation;
        MAIN.postDelayed(() -> { if (token == generation) clear(); }, CONFIRM_WINDOW_MS);
        // Whole sentences rather than a verb spliced into one. The verb was an English
        // literal, so a German phone read "Noch einmal tippen zum follow".
        // Chosen before the call, because the translation gate reads every literal inside a
        // toast's brackets and "follow" here is a branch, not something anybody sees.
        String message = "follow".equals(action)
                ? L10n.t("Tap again to follow")
                : "repost".equals(action)
                ? L10n.t("Tap again to repost")
                : L10n.t("Tap again to like");
        Utils.showToastShort(message);
        return false;
    }

    /** The one field of TikTok's comment model {@code owner} keeps, read, or null. */
    static Object commentOf(Object owner) {
        if (owner == null) return null;
        // Not computeIfAbsent: Map's default methods arrived in API 24 and the floor is 23.
        Class<?> type = owner.getClass();
        Object field = COMMENT_FIELDS.get(type);
        if (field == null) {
            field = commentField(type);
            COMMENT_FIELDS.put(type, field);
        }
        if (field == NO_FIELD) return null;
        try {
            return ((Field) field).get(owner);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Object commentField(Class<?> type) {
        Field found = null;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!COMMENT_MODEL.equals(field.getType().getName())) continue;
                // Two comments on one control would leave the like's own comment a guess.
                if (found != null) return NO_FIELD;
                found = field;
            }
        }
        if (found == null) return NO_FIELD;
        try {
            found.setAccessible(true);
            return found;
        } catch (RuntimeException error) {
            return NO_FIELD;
        }
    }

    public static Drawable armedRing(View anchor) {
        float density = anchor.getResources().getDisplayMetrics().density;
        int radius = app.morphe.extension.tiktok.settings.preference.SettingsUi.dp(
                anchor.getContext(),
                app.morphe.extension.tiktok.settings.preference.SettingsUi.RADIUS_OVERLAY);
        GradientDrawable inner = new GradientDrawable();
        inner.setColor(Color.TRANSPARENT);
        inner.setCornerRadius(radius);
        inner.setStroke(Math.max(1, Math.round(1 * density)),
                app.morphe.extension.tiktok.settings.preference.SettingsUi.OVERLAY_SCRIM);
        GradientDrawable outer = new GradientDrawable();
        outer.setColor(Color.TRANSPARENT);
        outer.setCornerRadius(radius);
        outer.setStroke(Math.max(2, Math.round(2 * density)),
                app.morphe.extension.tiktok.settings.preference.SettingsUi.OVERLAY_TEXT);
        LayerDrawable result = new LayerDrawable(new Drawable[]{outer, inner});
        int inset = Math.max(2, Math.round(2 * density));
        result.setLayerInset(1, inset, inset, inset, inset);
        return result;
    }

    private static void clear() {
        View view = ringed.get();
        if (view != null && view.getForeground() == ring) view.setForeground(previousForeground);
        ringed.clear();
        armed.clear();
        armedKey = null;
        previousForeground = null;
        ring = null;
        generation++;
    }
}

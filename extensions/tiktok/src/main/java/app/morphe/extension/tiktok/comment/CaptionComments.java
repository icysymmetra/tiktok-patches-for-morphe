/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.interaction.GestureActions;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;

/**
 * A video's caption above its comments (upstream #156).
 *
 * <p>Tapping "more" under a video spreads its caption over the video. TikTok has a page that
 * puts a post's caption above its comments, but only for photo posts. For a video, TikTok's own
 * comment list can start with the caption as its first row, and does when whoever opened the
 * list asks for it, which the comment button never does. So one switch makes "more", and a tap
 * on the caption text, which expands it the same way, open the video's comments with the caption
 * as that first row. The other puts the caption at the top of every video's comments. Photo posts
 * and ads keep TikTok's own handling.
 */
public final class CaptionComments {
    static final String FAMILY = "caption comments";

    /**
     * How long after "more" opens the comments their list may still ask whether to show the
     * caption. The list binds as the sheet opens, well inside this.
     */
    static final long PENDING_MS = 5_000;

    /** TikTok's names for the two ways a caption is expanded: the "more" button and its text. */
    static final String SEE_MORE = "SEE_MORE_BTN";
    static final String TAP_TEXT = "TAP_TEXT";

    private static final String DESC_VM = "com.ss.android.ugc.aweme.feed.assem.desc.VideoDescVM";

    /** The video whose comments "more" opened, and when, until its list takes the caption row. */
    private static String pendingAid;
    private static long pendingAt = -1;

    /** The video of the list about to ask, handed over just before the ask. */
    private static WeakReference<Object> askingAweme = new WeakReference<>(null);

    /** Each caption handler class's VideoDescVM field, found by type once. */
    private static final Map<Class<?>, Field> DESC_VM_FIELDS = new WeakHashMap<>();

    private CaptionComments() {
    }

    /**
     * The caption handler of a feed video, about to expand or collapse the caption. True means
     * the comments opened instead and TikTok should do nothing more.
     */
    public static boolean opensComments(Object caption, boolean expanding, String how, Object mechanism) {
        HookStatus.bound(FAMILY, "caption tap");
        if (!expanding || !Settings.CAPTION_OPENS_COMMENTS.get()) return false;
        if (!(mechanism instanceof Enum)) return false;
        String kind = ((Enum<?>) mechanism).name();
        if (!SEE_MORE.equals(kind) && !TAP_TEXT.equals(kind)) return false;
        Object aweme = awemeOf(caption);
        if (aweme == null) aweme = CurrentVideoAuthor.getAweme();
        if (!isPlainVideo(aweme)) return false;
        String aid = ((Aweme) aweme).getAid();
        if (aid == null || aid.isEmpty()) return false;
        pendingAid = aid;
        pendingAt = SystemClock.uptimeMillis();
        if (!GestureActions.openComments(aid)) {
            pendingAid = null;
            return false;
        }
        HookStatus.bound(FAMILY, "comments opened");
        return true;
    }

    /** The video of the comment list that asks {@link #showCaption} next. */
    public static void listFor(Object aweme) {
        askingAweme = new WeakReference<>(aweme);
    }

    /**
     * TikTok's comment list asks whether to start with the video's caption, having read the
     * opener's answer into {@code tikTok}. A list "more" opened gets it, and with the second switch
     * on so does every video's.
     */
    public static boolean showCaption(boolean tikTok) {
        HookStatus.bound(FAMILY, "caption row check");
        Object aweme = askingAweme.get();
        askingAweme = new WeakReference<>(null);
        if (tikTok) return true;
        boolean fromMore = takePending(aweme);
        if (!fromMore && !(Settings.CAPTION_ABOVE_COMMENTS.get() && isPlainVideo(aweme))) return false;
        HookStatus.bound(FAMILY, "caption row shown");
        return true;
    }

    /** Whether "more" opened this video's comments within the window, forgetting it if so. */
    private static boolean takePending(Object aweme) {
        String aid = pendingAid;
        if (aid == null || !(aweme instanceof Aweme)) return false;
        if (SystemClock.uptimeMillis() - pendingAt > PENDING_MS) {
            pendingAid = null;
            return false;
        }
        if (!aid.equals(((Aweme) aweme).getAid())) return false;
        pendingAid = null;
        return true;
    }

    /** A post whose caption TikTok spreads over the video: not a photo post and not an ad. */
    static boolean isPlainVideo(Object aweme) {
        if (!(aweme instanceof Aweme)) return false;
        Aweme video = (Aweme) aweme;
        return !video.isAd() && video.getPhotoModeImageInfo() == null && video.getPhotoModeTextInfo() == null;
    }

    /** The video of the caption handler, read through its VideoDescVM, or null. */
    static Object awemeOf(Object caption) {
        if (caption == null) return null;
        try {
            Field field;
            synchronized (DESC_VM_FIELDS) {
                Class<?> type = caption.getClass();
                if (DESC_VM_FIELDS.containsKey(type)) {
                    field = DESC_VM_FIELDS.get(type);
                } else {
                    field = fieldOfType(type, DESC_VM);
                    DESC_VM_FIELDS.put(type, field);
                }
            }
            if (field == null) return null;
            Object vm = field.get(caption);
            Object item = vm == null ? null : vm.getClass().getMethod("getItem").invoke(vm);
            return Reflect.property(item, "getAweme", "aweme");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logger.printDebug(() -> "Caption comments could not read the caption's video: " + failure);
            return null;
        }
    }

    private static Field fieldOfType(Class<?> type, String name) {
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (name.equals(field.getType().getName())) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    static void resetForTests() {
        pendingAid = null;
        pendingAt = -1;
        askingAweme = new WeakReference<>(null);
        synchronized (DESC_VM_FIELDS) {
            DESC_VM_FIELDS.clear();
        }
    }
}

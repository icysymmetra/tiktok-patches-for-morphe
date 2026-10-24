/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.app.Activity;
import android.content.Context;
import android.widget.ImageView;

/**
 * The share sheet actions Hushfeed builds on TikTok's native Report carrier: Block this creator
 * (Share sheet tools) and Save cover (Advanced downloads). The carrier's key, label, click and
 * icon hooks land here once and each action answers only for the carriers it built, so a carrier
 * neither owns keeps every original Report instruction.
 */
public final class OwnedShareActions {
    private OwnedShareActions() {}

    public static boolean owns(Object action) {
        return ShareBlockAuthor.owns(action) || ShareSaveCover.owns(action);
    }

    public static String key(Object action) {
        String key = ShareBlockAuthor.key(action);
        return key != null ? key : ShareSaveCover.key(action);
    }

    public static String label(Object action) {
        String label = ShareBlockAuthor.label(action);
        return label != null ? label : ShareSaveCover.label(action);
    }

    public static boolean click(Object action, Context context, Object sharePackage) {
        return ShareBlockAuthor.click(action, context, sharePackage)
                || ShareSaveCover.click(action, context, sharePackage);
    }

    /**
     * The key a native action binder looks up in its framed-icon list, read for that test alone.
     * TikTok draws its themed circle and icon only for a key on the list and a bare drawable for
     * any other, so an owned carrier answers Report's here: it gets Report's frame, and
     * {@link #bindIcon} puts its own glyph inside it. Every other read keeps the carrier's key.
     */
    public static String framedKey(Object action, String key) {
        return owns(action) ? FRAMED_KEY : key;
    }

    /** The native carrier's own key, which TikTok's framed-icon list holds. */
    static final String FRAMED_KEY = "report";

    public static void bindIcon(Object action, ImageView image) {
        ShareBlockAuthor.bindIcon(action, image);
        ShareSaveCover.bindIcon(action, image);
    }

    /** Replaced with the reviewed native Report carrier constructor by the patch. */
    static Object newAction(Activity activity, Object aweme) {
        return null;
    }
}
